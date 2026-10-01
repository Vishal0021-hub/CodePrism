package codeprism.backend.services.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatPromptBuilderTest {

    private final ChatPromptBuilder builder = new ChatPromptBuilder();

    @Test
    void testUserPromptWithLabeledSections() {
        String semantic = "// File: Service.java\npublic void process() {}";
        String structural = "// File: Repo.java\npublic interface Repo {}";
        String question = "How does process work?";

        String prompt = builder.userPrompt(semantic, structural, question);

        assertTrue(prompt.contains("Directly relevant code:"));
        assertTrue(prompt.contains(semantic));
        assertTrue(prompt.contains("Related code (calls / called by):"));
        assertTrue(prompt.contains(structural));
        assertTrue(prompt.contains("User question:"));
        assertTrue(prompt.contains(question));
    }

    @Test
    void testUserPromptWithEmptyStructuralSection() {
        String semantic = "// File: Service.java\npublic void process() {}";
        String question = "What is process?";

        String prompt = builder.userPrompt(semantic, "", question);

        assertTrue(prompt.contains("Directly relevant code:"));
        assertTrue(prompt.contains("Related code (calls / called by):\n(none)"));
    }
}
