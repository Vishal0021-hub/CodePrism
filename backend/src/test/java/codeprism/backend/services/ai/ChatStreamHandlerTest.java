package codeprism.backend.services.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tools.jackson.databind.json.JsonMapper;

import codeprism.backend.dto.ChatMessageResponse;
import codeprism.backend.dto.CitationDto;
import codeprism.backend.entity.ChatMessage;
import codeprism.backend.entity.MessageRole;
import codeprism.backend.repository.ChatMessageRepository;

@ExtendWith(MockitoExtension.class)
class ChatStreamHandlerTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    private ChatStreamHandler handler;

    @BeforeEach
    void setUp() {
        CitationMapper citationMapper = new CitationMapper(new JsonMapper());
        handler = new ChatStreamHandler(chatModel, chatMessageRepository, citationMapper, 0.5);
    }

    @Test
    void testLowSimilaritySkipsAiAndStreamsFallbackMessage() {
        UUID sessionId = UUID.randomUUID();
        ChatMessageResponse userMsg = new ChatMessageResponse(
                UUID.randomUUID(), MessageRole.USER, "What is XYZ?", List.of(), 0, Instant.now());

        RetrievedContext lowScoreContext = new RetrievedContext(
                List.of(new CitationDto("Unrelated.java", 1, 10, "java", "semantic")),
                "Some irrelevant code",
                List.of(),
                List.of(),
                "Some irrelevant code",
                "",
                0.35, // Below 0.5 threshold
                1);

        ChatMessage savedAssistantMsg = ChatMessage.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .role(MessageRole.ASSISTANT)
                .content("No relevant code found in this repository for that question.")
                .citations("[]")
                .sourcesCount(0)
                .createdAt(Instant.now())
                .build();

        when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(savedAssistantMsg);

        SseEmitter emitter = handler.stream(
                sessionId,
                userMsg,
                lowScoreContext,
                "system prompt",
                "user prompt");

        assertNotNull(emitter);

        // Verify that chatModel was NEVER called
        org.mockito.Mockito.verifyNoInteractions(chatModel);

        // Verify that chatMessageRepository saved the fixed message with empty citations and 0 sources
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository).save(captor.capture());

        ChatMessage saved = captor.getValue();
        assertEquals("No relevant code found in this repository for that question.", saved.getContent());
        assertEquals("[]", saved.getCitations());
        assertEquals(0, saved.getSourcesCount());
    }
}
