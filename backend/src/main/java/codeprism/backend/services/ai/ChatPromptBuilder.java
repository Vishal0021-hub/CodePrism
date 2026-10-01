package codeprism.backend.services.ai;

import org.springframework.stereotype.Component;

/**
 * Builds the prompts sent to OpenAI.
 *
 * <p>We use two messages:
 * <ul>
 *   <li><b>System</b> — rules for how the assistant should behave</li>
 *   <li><b>User</b> — retrieved code context + the actual question</li>
 * </ul>
 */
@Component
public class ChatPromptBuilder {

    public String systemPrompt(String repositoryFullName) {
        return """
                You are CodePrism, an expert assistant for the %s codebase.
                Answer using ONLY the provided code context.
                If the context is insufficient, say you are unsure.
                Cite file paths and line ranges when relevant.
                Be concise and technical.
                """.formatted(repositoryFullName);
    }

    public String userPrompt(String semanticContext, String structuralContext, String question) {
        return """
                Directly relevant code:
                %s

                Related code (calls / called by):
                %s

                User question:
                %s
                """.formatted(
                    (semanticContext == null || semanticContext.isBlank()) ? "(none)" : semanticContext,
                    (structuralContext == null || structuralContext.isBlank()) ? "(none)" : structuralContext,
                    question
                );
    }

    public String userPrompt(String codeContext, String question) {
        if (codeContext != null && codeContext.contains("Directly relevant code:")) {
            return """
                    Code context:
                    %s

                    User question:
                    %s
                    """.formatted(codeContext, question);
        }
        return userPrompt(codeContext, "(none)", question);
    }
}