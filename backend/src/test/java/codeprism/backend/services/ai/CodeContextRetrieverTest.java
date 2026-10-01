package codeprism.backend.services.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import tools.jackson.databind.json.JsonMapper;

import codeprism.backend.entity.CodeRelationship;
import codeprism.backend.entity.RelationType;
import codeprism.backend.repository.CodeRelationshipRepository;

@ExtendWith(MockitoExtension.class)
class CodeContextRetrieverTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private CodeRelationshipRepository codeRelationshipRepository;

    private CodeContextRetriever retriever;

    @BeforeEach
    void setUp() {
        CitationMapper citationMapper = new CitationMapper(new JsonMapper());
        retriever = new CodeContextRetriever(
                vectorStore,
                citationMapper,
                codeRelationshipRepository,
                new JsonMapper());
    }

    @Test
    void testRetrieveDistinguishesSemanticAndStructuralMatches() {
        UUID repoId = UUID.randomUUID();

        // 1. Mock vector store semantic match
        Map<String, Object> meta = new HashMap<>();
        meta.put("filePath", "UserService.java");
        meta.put("symbol", "UserService.getUser");
        meta.put("className", "UserService");
        meta.put("methodName", "getUser");
        meta.put("language", "java");
        Document semanticDoc = Document.builder()
                .text("public User getUser() { return repo.findById(1); }")
                .metadata(meta)
                .score(0.85)
                .build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(semanticDoc));

        // 2. Mock connected CodeRelationship
        CodeRelationship rel = CodeRelationship.builder()
                .repositoryId(repoId)
                .sourceSymbol("UserService.getUser")
                .targetSymbol("UserRepository.findById")
                .relationType(RelationType.CALLS)
                .filePath("UserService.java")
                .build();

        when(codeRelationshipRepository.findConnectedRelationships(any(), any()))
                .thenReturn(List.of(rel));

        RetrievedContext result = retriever.retrieve(repoId, "How does getUser work?");

        assertNotNull(result);
        assertEquals(1, result.semanticCitations().size());
        assertEquals("semantic", result.semanticCitations().get(0).matchType());
        assertEquals(1, result.sourcesCount());
        assertTrue(result.maxSemanticScore() >= 0.85);
        assertTrue(result.contextText().contains("Directly relevant code:"));
        assertTrue(result.contextText().contains("Related code (calls / called by):"));
    }

    @Test
    void testRerankReordersChunksLexically() {
        // Chunk 0: GeneralHelper.java, base score 0.75, no lexical match to query
        Map<String, Object> meta0 = new HashMap<>(Map.of(
                "filePath", "src/main/java/codeprism/backend/util/GeneralHelper.java",
                "symbol", "GeneralHelper.formatDate",
                "className", "GeneralHelper"));
        Document doc0 = Document.builder().text("public static String formatDate() {}").metadata(meta0).score(0.75).build();

        // Chunk 1: AppConfig.java, base score 0.70, no lexical match to query
        Map<String, Object> meta1 = new HashMap<>(Map.of(
                "filePath", "src/main/java/codeprism/backend/config/AppConfig.java",
                "symbol", "AppConfig.corsConfig",
                "className", "AppConfig"));
        Document doc1 = Document.builder().text("public CorsConfig corsConfig() {}").metadata(meta1).score(0.70).build();

        // Chunk 2: UserController.java, base score 0.65, strong lexical match to "UserController" & "login"
        Map<String, Object> meta2 = new HashMap<>(Map.of(
                "filePath", "src/main/java/codeprism/backend/controller/UserController.java",
                "symbol", "UserController.handleUserLogin",
                "className", "UserController"));
        Document doc2 = Document.builder().text("public Response handleUserLogin() {}").metadata(meta2).score(0.65).build();

        // Chunk 3: UserService.java, base score 0.65, lexical match to "user"
        Map<String, Object> meta3 = new HashMap<>(Map.of(
                "filePath", "src/main/java/codeprism/backend/services/UserService.java",
                "symbol", "UserService.findUserByEmail",
                "className", "UserService"));
        Document doc3 = Document.builder().text("public User findUserByEmail() {}").metadata(meta3).score(0.65).build();

        List<Document> original = List.of(doc0, doc1, doc2, doc3);
        String question = "Where is user login handled in UserController?";

        List<Document> reranked = retriever.rerankSemanticDocuments(original, question);

        // Before: [GeneralHelper (0.75), AppConfig (0.70), UserController (0.65), UserService (0.60)]
        // After:  [UserController (0.65 + boost), UserService (0.60 + boost), GeneralHelper (0.75), AppConfig (0.70)]
        assertEquals("UserController", reranked.get(0).getMetadata().get("className"));
        assertEquals("UserService", reranked.get(1).getMetadata().get("className"));
        assertEquals("GeneralHelper", reranked.get(2).getMetadata().get("className"));
        assertEquals("AppConfig", reranked.get(3).getMetadata().get("className"));

        int reorderedCount = 0;
        for (int i = 0; i < original.size(); i++) {
            if (!original.get(i).getMetadata().get("className").equals(reranked.get(i).getMetadata().get("className"))) {
                reorderedCount++;
            }
        }

        // All 4 chunks changed position
        assertEquals(4, reorderedCount, "All 4 chunks should be reordered based on lexical boost");
    }
}
