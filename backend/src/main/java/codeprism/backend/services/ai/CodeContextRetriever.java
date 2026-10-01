package codeprism.backend.services.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import codeprism.backend.dto.CitationDto;
import codeprism.backend.entity.CodeRelationship;
import codeprism.backend.repository.CodeRelationshipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class CodeContextRetriever {
    private static final String NO_MATCHES = "(no matching code chunks found)";

    private final VectorStore vectorStore;
    private final CitationMapper citationMapper;
    private final CodeRelationshipRepository codeRelationshipRepository;
    private final JsonMapper jsonMapper;

    @Autowired(required = false)
    private NamedParameterJdbcTemplate jdbcTemplate;

    public CodeContextRetriever(
            VectorStore vectorStore,
            CitationMapper citationMapper,
            CodeRelationshipRepository codeRelationshipRepository,
            JsonMapper jsonMapper) {
        this.vectorStore = vectorStore;
        this.citationMapper = citationMapper;
        this.codeRelationshipRepository = codeRelationshipRepository;
        this.jsonMapper = jsonMapper;
    }

    public RetrievedContext retrieve(UUID repositoryId, String question) {
        // 1. Semantic vector similarity search
        var filter = new FilterExpressionBuilder()
                .eq(RagSettings.METADATA_REPO_ID, repositoryId.toString())
                .build();

        var search = SearchRequest.builder()
                .query(question)
                .topK(RagSettings.TOP_K_CHUNKS)
                .filterExpression(filter)
                .build();

        List<Document> semanticDocs = vectorStore.similaritySearch(search);

        // 2. Lexical re-rank step: boost score of chunks whose filePath or symbol contains query terms
        semanticDocs = rerankSemanticDocuments(semanticDocs, question);

        double maxSemanticScore = semanticDocs.stream()
                .mapToDouble(d -> d.getScore() != null ? d.getScore() : 0.0)
                .max()
                .orElse(0.0);

        // 3. For top 3 hits (after reranking), extract symbol names, class names, and method names
        List<Document> top3Hits = semanticDocs.stream().limit(3).toList();
        Set<String> hitSymbols = new LinkedHashSet<>();
        for (Document hit : top3Hits) {
            Map<String, Object> meta = hit.getMetadata();
            if (meta.get("symbol") != null) {
                hitSymbols.add(String.valueOf(meta.get("symbol")));
            }
            if (meta.get("className") != null) {
                hitSymbols.add(String.valueOf(meta.get("className")));
            }
            if (meta.get("methodName") != null) {
                hitSymbols.add(String.valueOf(meta.get("methodName")));
            }
        }

        // 3. Look up directly connected CodeRelationship rows in both directions
        List<CodeRelationship> relationships = hitSymbols.isEmpty()
                ? List.of()
                : codeRelationshipRepository.findConnectedRelationships(repositoryId, hitSymbols);

        Set<String> connectedSymbols = new LinkedHashSet<>();
        Set<String> connectedFiles = new LinkedHashSet<>();

        for (CodeRelationship rel : relationships) {
            if (hitSymbols.contains(rel.getSourceSymbol())) {
                connectedSymbols.add(rel.getTargetSymbol());
            }
            if (hitSymbols.contains(rel.getTargetSymbol())) {
                connectedSymbols.add(rel.getSourceSymbol());
                connectedFiles.add(rel.getFilePath());
            }

            String target = rel.getTargetSymbol();
            if (target.contains(".")) {
                connectedSymbols.add(target.substring(0, target.indexOf(".")));
            }
            String source = rel.getSourceSymbol();
            if (source.contains(".")) {
                connectedSymbols.add(source.substring(0, source.indexOf(".")));
            }
        }
        connectedSymbols.removeAll(hitSymbols);

        // 4. Fetch corresponding chunks for connected relationships
        Set<String> seenTexts = semanticDocs.stream()
                .map(Document::getText)
                .collect(Collectors.toSet());

        List<Document> structuralDocs = fetchStructuralChunks(repositoryId, connectedSymbols, connectedFiles, seenTexts);

        // 5. Build citations distinguishing semantic vs structural
        List<CitationDto> semanticCitations = semanticDocs.stream()
                .map(d -> citationMapper.fromDocument(d, "semantic"))
                .distinct()
                .toList();

        List<CitationDto> structuralCitations = structuralDocs.stream()
                .map(d -> citationMapper.fromDocument(d, "structural"))
                .distinct()
                .toList();

        List<CitationDto> allCitations = Stream.concat(semanticCitations.stream(), structuralCitations.stream())
                .distinct()
                .toList();

        // 6. Build labeled context text
        String semanticContextText = semanticDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
        if (semanticContextText.isBlank()) {
            semanticContextText = NO_MATCHES;
        }

        String structuralContextText = structuralDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
        if (structuralContextText.isBlank()) {
            structuralContextText = "(none)";
        }

        String fullContextText = """
                Directly relevant code:
                %s

                Related code (calls / called by):
                %s
                """.formatted(semanticContextText, structuralContextText);

        int sourcesCount = semanticDocs.size() + structuralDocs.size();

        return new RetrievedContext(
                allCitations,
                fullContextText,
                semanticCitations,
                structuralCitations,
                semanticContextText,
                structuralContextText,
                maxSemanticScore,
                sourcesCount);
    }

    public List<Document> rerankSemanticDocuments(List<Document> documents, String question) {
        if (documents == null || documents.isEmpty() || question == null || question.isBlank()) {
            return documents != null ? documents : List.of();
        }

        Set<String> stopWords = Set.of(
                "what", "how", "why", "when", "where", "which", "who", "whom", "whose",
                "does", "doesnt", "is", "are", "was", "were", "be", "been", "being",
                "the", "a", "an", "in", "on", "at", "to", "for", "of", "with", "from",
                "and", "or", "not", "this", "that", "these", "those", "can", "could",
                "tell", "show", "give", "explain", "describe", "find", "code", "file"
        );

        List<String> queryTerms = java.util.Arrays.stream(question.toLowerCase().split("[^a-zA-Z0-9_]+"))
                .map(String::trim)
                .filter(t -> t.length() >= 3)
                .filter(t -> !stopWords.contains(t))
                .distinct()
                .toList();

        if (queryTerms.isEmpty()) {
            return documents;
        }

        class ScoredDocument {
            final Document doc;
            final double score;

            ScoredDocument(Document doc, double score) {
                this.doc = doc;
                this.score = score;
            }
        }

        List<ScoredDocument> scored = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            Double docScore = doc.getScore();
            double baseScore = (docScore != null) ? docScore : Math.max(0.1, 0.9 - (i * 0.05));

            Map<String, Object> meta = doc.getMetadata();
            String filePath = meta.get("filePath") != null ? String.valueOf(meta.get("filePath")).toLowerCase() : "";
            String symbol = meta.get("symbol") != null ? String.valueOf(meta.get("symbol")).toLowerCase() : "";
            String className = meta.get("className") != null ? String.valueOf(meta.get("className")).toLowerCase() : "";

            int matches = 0;
            for (String term : queryTerms) {
                if (filePath.contains(term) || symbol.contains(term) || className.contains(term)) {
                    matches++;
                }
            }

            double boost = matches * 0.15;
            double finalScore = baseScore + boost;

            Map<String, Object> updatedMeta = new HashMap<>(meta);
            updatedMeta.put("score", finalScore);
            updatedMeta.put("lexicalMatches", matches);

            Document boostedDoc = doc.mutate()
                    .metadata(updatedMeta)
                    .score(finalScore)
                    .build();

            scored.add(new ScoredDocument(boostedDoc, finalScore));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        return scored.stream().map(s -> s.doc).toList();
    }

    private List<Document> fetchStructuralChunks(
            UUID repositoryId,
            Set<String> connectedSymbols,
            Set<String> connectedFiles,
            Set<String> seenTexts) {

        List<Document> result = new ArrayList<>();
        if (connectedSymbols.isEmpty() && connectedFiles.isEmpty()) {
            return result;
        }

        if (jdbcTemplate != null) {
            try {
                String sql = """
                    SELECT id, content, metadata
                    FROM vector_store
                    WHERE (metadata->>'repoId' = :repoId OR metadata->>'repo_id' = :repoId)
                      AND (
                        metadata->>'symbol' IN (:symbols)
                        OR metadata->>'className' IN (:symbols)
                        OR metadata->>'filePath' IN (:filePaths)
                      )
                    LIMIT 5
                """;

                MapSqlParameterSource params = new MapSqlParameterSource()
                        .addValue("repoId", repositoryId.toString())
                        .addValue("symbols", connectedSymbols.isEmpty() ? List.of("__NONE__") : connectedSymbols)
                        .addValue("filePaths", connectedFiles.isEmpty() ? List.of("__NONE__") : connectedFiles);

                List<Document> fetched = jdbcTemplate.query(sql, params, (rs, rowNum) -> {
                    String id = rs.getString("id");
                    String content = rs.getString("content");
                    String metaJson = rs.getString("metadata");
                    Map<String, Object> meta = parseJsonMetadata(metaJson);
                    meta.put("matchType", "structural");
                    return new Document(id, content, meta);
                });

                for (Document doc : fetched) {
                    if (seenTexts.add(doc.getText())) {
                        result.add(doc);
                    }
                }
            } catch (Exception ex) {
                log.warn("Direct SQL chunk fetch for structural relationships failed: {}", ex.getMessage());
            }
        }

        if (result.isEmpty() && !connectedSymbols.isEmpty()) {
            for (String sym : connectedSymbols) {
                if (result.size() >= 3) {
                    break;
                }
                try {
                    var symFilter = new FilterExpressionBuilder()
                            .eq(RagSettings.METADATA_REPO_ID, repositoryId.toString())
                            .build();
                    var symSearch = SearchRequest.builder()
                            .query(sym)
                            .topK(1)
                            .filterExpression(symFilter)
                            .build();
                    var found = vectorStore.similaritySearch(symSearch);
                    for (Document doc : found) {
                        if (seenTexts.add(doc.getText())) {
                            Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                            meta.put("matchType", "structural");
                            result.add(new Document(doc.getId(), doc.getText(), meta));
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return result;
    }

    private Map<String, Object> parseJsonMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return new HashMap<>();
        }
    }
}