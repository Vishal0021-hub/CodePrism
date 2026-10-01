package codeprism.backend.services.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class CodeChunkerTest {

    private CodeChunker codeChunker;

    @BeforeEach
    void setUp() {
        CodeFileFilter filter = new CodeFileFilter();
        codeChunker = new CodeChunker(800, filter);
    }

    @Test
    void testChunkJavaFileByClassAndMethodBoundaries() {
        String javaCode = """
                package com.example.demo;

                import java.util.List;
                import org.springframework.stereotype.Service;

                @Service
                public class UserService extends BaseService {
                    private final UserRepository userRepository;
                    private final String appName;

                    public UserService(UserRepository userRepository) {
                        this.userRepository = userRepository;
                        this.appName = "TestApp";
                    }

                    public User findById(Long id) {
                        return userRepository.findById(id).orElse(null);
                    }

                    public void deleteUser(Long id) {
                        userRepository.deleteById(id);
                    }
                }
                """;

        String filePath = "src/main/java/com/example/demo/UserService.java";
        CodeChunker.ChunkResult result = codeChunker.chunkFileWithAst("repo-123", filePath, javaCode);

        assertNotNull(result.compilationUnit(), "CompilationUnit AST should be present");
        List<Document> chunks = result.documents();
        assertTrue(chunks.size() >= 3, "Expected at least class-level chunk, constructor, and method chunks");

        // Every chunk must start with "// File: ..."
        for (Document chunk : chunks) {
            assertTrue(chunk.getText().startsWith("// File: " + filePath),
                    "Each chunk must start with the file header");
            assertEquals("repo-123", chunk.getMetadata().get(codeprism.backend.services.ai.RagSettings.METADATA_REPO_ID));
            assertEquals("java", chunk.getMetadata().get("language"));
            assertEquals("UserService", chunk.getMetadata().get("className"));
            assertNotNull(chunk.getMetadata().get("symbol"));
        }

        // Verify class-level chunk
        Document classChunk = chunks.get(0);
        assertEquals("UserService", classChunk.getMetadata().get("symbol"));
        assertTrue(classChunk.getText().contains("class UserService"));
        assertTrue(classChunk.getText().contains("userRepository"));

        // Verify method chunks
        boolean hasFindById = chunks.stream()
                .anyMatch(c -> "UserService.findById".equals(c.getMetadata().get("symbol"))
                        && "findById".equals(c.getMetadata().get("methodName")));
        assertTrue(hasFindById, "Should contain UserService.findById chunk");

        boolean hasDeleteUser = chunks.stream()
                .anyMatch(c -> "UserService.deleteUser".equals(c.getMetadata().get("symbol"))
                        && "deleteUser".equals(c.getMetadata().get("methodName")));
        assertTrue(hasDeleteUser, "Should contain UserService.deleteUser chunk");
    }

    @Test
    void testNonJavaFileFallback() {
        String mdContent = "# Overview\nThis is a readme documentation file.";
        String filePath = "README.md";

        CodeChunker.ChunkResult result = codeChunker.chunkFileWithAst("repo-123", filePath, mdContent);

        assertTrue(result.compilationUnit() == null, "Non-Java file should have null compilation unit");
        List<Document> chunks = result.documents();
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getText().startsWith("// File: README.md\n"));
    }

    @Test
    void testLargeMethodSplitsWithinMethod() {
        StringBuilder largeMethod = new StringBuilder();
        largeMethod.append("public class BigClass {\n");
        largeMethod.append("    public void bigMethod() {\n");
        for (int i = 0; i < 200; i++) {
            largeMethod.append("        System.out.println(\"Log line ").append(i).append(" with substantial tokens to force splitting\");\n");
        }
        largeMethod.append("    }\n");
        largeMethod.append("}\n");

        CodeChunker.ChunkResult result = codeChunker.chunkFileWithAst("repo-123", "BigClass.java", largeMethod.toString());
        List<Document> chunks = result.documents();

        // Chunks for bigMethod should be split into multiple parts
        long bigMethodSubchunks = chunks.stream()
                .filter(c -> "BigClass.bigMethod".equals(c.getMetadata().get("symbol")))
                .count();

        assertTrue(bigMethodSubchunks > 1, "Large method should be split into multiple chunks");
        for (Document chunk : chunks) {
            assertTrue(chunk.getText().startsWith("// File: BigClass.java\n"), "All subchunks must have header");
        }
    }
}
