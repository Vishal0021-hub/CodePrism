package codeprism.backend.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import codeprism.backend.dto.FindResultDto;
import codeprism.backend.entity.CodeRelationship;
import codeprism.backend.repository.CodeRelationshipRepository;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class FindService {

    private final CodeRelationshipRepository codeRelationshipRepository;
    private final JsonMapper jsonMapper;

    @Autowired(required = false)
    private NamedParameterJdbcTemplate jdbcTemplate;

    public FindService(
            CodeRelationshipRepository codeRelationshipRepository,
            JsonMapper jsonMapper) {
        this.codeRelationshipRepository = codeRelationshipRepository;
        this.jsonMapper = jsonMapper;
    }

    public List<FindResultDto> findSymbols(UUID repoId, String symbol) {
        if (symbol == null || symbol.trim().isBlank()) {
            return List.of();
        }

        String query = symbol.trim();
        String queryLower = query.toLowerCase();
        String likePattern = "%" + queryLower + "%";

        Map<String, FindResultDto> resultsByKey = new LinkedHashMap<>();

        // 1. Query CodeRelationship for sourceSymbol or targetSymbol containing the symbol
        List<CodeRelationship> relationships = List.of();
        try {
            relationships = codeRelationshipRepository.findBySymbolContaining(repoId, query);
        } catch (Exception ex) {
            log.warn("Querying CodeRelationship for symbol '{}' failed: {}", query, ex.getMessage());
        }

        Set<String> relSymbols = new LinkedHashSet<>();
        for (CodeRelationship rel : relationships) {
            if (rel.getSourceSymbol() != null) {
                relSymbols.add(rel.getSourceSymbol());
            }
            if (rel.getTargetSymbol() != null) {
                relSymbols.add(rel.getTargetSymbol());
            }
        }

        // 2. Direct query against vector_store chunk store
        if (jdbcTemplate != null) {
            try {
                String sql = """
                    SELECT id, content, metadata
                    FROM vector_store
                    WHERE (metadata->>'repoId' = :repoId OR metadata->>'repo_id' = :repoId)
                      AND (
                        LOWER(metadata->>'symbol') LIKE :likePattern
                        OR LOWER(metadata->>'methodName') LIKE :likePattern
                        OR LOWER(metadata->>'className') LIKE :likePattern
                        OR metadata->>'symbol' IN (:relSymbols)
                        OR metadata->>'className' IN (:relSymbols)
                        OR metadata->>'methodName' IN (:relSymbols)
                      )
                    LIMIT 100
                """;

                MapSqlParameterSource params = new MapSqlParameterSource()
                        .addValue("repoId", repoId.toString())
                        .addValue("likePattern", likePattern)
                        .addValue("relSymbols", relSymbols.isEmpty() ? List.of("__NONE__") : relSymbols);

                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);

                for (Map<String, Object> row : rows) {
                    String rawContent = row.get("content") != null ? String.valueOf(row.get("content")) : "";
                    String metaJson = row.get("metadata") != null ? String.valueOf(row.get("metadata")) : "";
                    Map<String, Object> meta = parseJsonMetadata(metaJson);

                    String filePath = meta.get("filePath") != null ? String.valueOf(meta.get("filePath")) : "";
                    String symbolName = extractSymbolName(meta, query);
                    Integer lineNumber = parseLineNumber(meta.get("startLine"));
                    String snippet = formatSnippet(rawContent);

                    boolean exactMatch = isExactMatch(symbolName, queryLower);

                    String dedupeKey = filePath + ":" + (lineNumber != null ? lineNumber : "") + ":" + symbolName;
                    resultsByKey.put(dedupeKey, new FindResultDto(
                            filePath,
                            symbolName,
                            lineNumber,
                            snippet,
                            exactMatch
                    ));
                }
            } catch (Exception ex) {
                log.warn("Direct SQL chunk fetch for symbol '{}' failed: {}", query, ex.getMessage());
            }
        }

        // 3. Include any relationships whose matched symbol was not already captured with a chunk
        for (CodeRelationship rel : relationships) {
            boolean sourceMatches = rel.getSourceSymbol() != null && rel.getSourceSymbol().toLowerCase().contains(queryLower);
            boolean targetMatches = rel.getTargetSymbol() != null && rel.getTargetSymbol().toLowerCase().contains(queryLower);

            if (sourceMatches) {
                String sym = rel.getSourceSymbol();
                String key = (rel.getFilePath() != null ? rel.getFilePath() : "") + "::" + sym;
                if (!hasMatchingEntry(resultsByKey, rel.getFilePath(), sym)) {
                    String snippet = "// Relationship: " + rel.getRelationType() + " -> " + rel.getTargetSymbol();
                    resultsByKey.put(key, new FindResultDto(
                            rel.getFilePath(),
                            sym,
                            1,
                            snippet,
                            isExactMatch(sym, queryLower)
                    ));
                }
            }

            if (targetMatches) {
                String sym = rel.getTargetSymbol();
                String key = (rel.getFilePath() != null ? rel.getFilePath() : "") + "::" + sym;
                if (!hasMatchingEntry(resultsByKey, rel.getFilePath(), sym)) {
                    String snippet = "// Used in " + rel.getSourceSymbol() + " (" + rel.getRelationType() + ")";
                    resultsByKey.put(key, new FindResultDto(
                            rel.getFilePath(),
                            sym,
                            1,
                            snippet,
                            isExactMatch(sym, queryLower)
                    ));
                }
            }
        }

        // 4. Rank exact-match-first, then prefix-match, then substring match
        List<FindResultDto> sortedList = new ArrayList<>(resultsByKey.values());
        sortedList.sort(Comparator
                .comparingInt((FindResultDto dto) -> matchRank(dto.symbolName(), queryLower))
                .thenComparing(FindResultDto::filePath, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(dto -> dto.lineNumber() != null ? dto.lineNumber() : 0));

        return sortedList;
    }

    private boolean hasMatchingEntry(Map<String, FindResultDto> results, String filePath, String symbol) {
        if (symbol == null) return true;
        for (FindResultDto dto : results.values()) {
            if (symbol.equalsIgnoreCase(dto.symbolName())) {
                if (filePath == null || filePath.equalsIgnoreCase(dto.filePath())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String extractSymbolName(Map<String, Object> meta, String query) {
        String symbol = meta.get("symbol") != null ? String.valueOf(meta.get("symbol")) : null;
        if (symbol != null && !symbol.isBlank()) {
            return symbol;
        }

        String className = meta.get("className") != null ? String.valueOf(meta.get("className")) : null;
        String methodName = meta.get("methodName") != null ? String.valueOf(meta.get("methodName")) : null;

        if (className != null && methodName != null) {
            return className + "." + methodName;
        } else if (methodName != null) {
            return methodName;
        } else if (className != null) {
            return className;
        }

        String filePath = meta.get("filePath") != null ? String.valueOf(meta.get("filePath")) : "";
        if (!filePath.isBlank()) {
            int lastSlash = filePath.lastIndexOf('/');
            return lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
        }

        return query;
    }

    private Integer parseLineNumber(Object val) {
        if (val instanceof Number n) {
            return n.intValue();
        }
        if (val != null) {
            try {
                return Integer.parseInt(String.valueOf(val).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private String formatSnippet(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return "";
        }

        String content = rawContent;
        if (content.startsWith("// File:")) {
            int firstNewline = content.indexOf('\n');
            if (firstNewline >= 0) {
                content = content.substring(firstNewline + 1).stripLeading();
            }
        }

        String[] lines = content.split("\r?\n");
        int maxLines = 25;
        if (lines.length <= maxLines) {
            return content.stripTrailing();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append("// ... (truncated)");
        return sb.toString();
    }

    private boolean isExactMatch(String symbolName, String queryLower) {
        return matchRank(symbolName, queryLower) <= 1;
    }

    private int matchRank(String symbolName, String queryLower) {
        if (symbolName == null || symbolName.isBlank()) {
            return 99;
        }

        String symLower = symbolName.toLowerCase();
        int lastDot = symLower.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? symLower.substring(lastDot + 1) : symLower;

        // Rank 0: Exact match on simple symbol name
        if (simpleName.equals(queryLower)) {
            return 0;
        }
        // Rank 1: Exact match on full symbol name
        if (symLower.equals(queryLower)) {
            return 1;
        }
        // Rank 2: Prefix match on simple name
        if (simpleName.startsWith(queryLower)) {
            return 2;
        }
        // Rank 3: Prefix match on full symbol name
        if (symLower.startsWith(queryLower)) {
            return 3;
        }
        // Rank 4: Contains anywhere
        if (symLower.contains(queryLower)) {
            return 4;
        }

        return 5;
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
