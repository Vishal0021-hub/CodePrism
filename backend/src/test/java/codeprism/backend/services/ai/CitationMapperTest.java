package codeprism.backend.services.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import tools.jackson.databind.json.JsonMapper;

import codeprism.backend.dto.CitationDto;

class CitationMapperTest {

    private CitationMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new CitationMapper(new JsonMapper());
    }

    @Test
    void testFromDocumentWithSemanticAndStructuralMatchType() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("filePath", "src/Main.java");
        meta1.put("startLine", 10);
        meta1.put("endLine", 25);
        meta1.put("language", "java");
        meta1.put("matchType", "semantic");

        Document doc1 = new Document("content", meta1);
        CitationDto citation1 = mapper.fromDocument(doc1);
        assertEquals("semantic", citation1.matchType());
        assertEquals("src/Main.java", citation1.filePath());
        assertEquals(10, citation1.startLine());

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("filePath", "src/Repo.java");
        meta2.put("startLine", 5);
        meta2.put("endLine", 15);
        meta2.put("language", "java");
        meta2.put("matchType", "structural");

        Document doc2 = new Document("content", meta2);
        CitationDto citation2 = mapper.fromDocument(doc2);
        assertEquals("structural", citation2.matchType());
    }

    @Test
    void testJsonRoundtrip() {
        CitationDto dto = new CitationDto("path/to/File.java", 1, 10, "java", "structural");
        String json = mapper.toJson(List.of(dto));

        assertTrue(json.contains("\"matchType\":\"structural\""));

        List<CitationDto> back = mapper.fromJson(json);
        assertEquals(1, back.size());
        assertEquals("structural", back.get(0).matchType());
        assertEquals("path/to/File.java", back.get(0).filePath());
    }
}
