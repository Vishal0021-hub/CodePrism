package codeprism.backend.services.ai;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import codeprism.backend.dto.ChatMessageResponse;
import codeprism.backend.dto.CitationDto;
import codeprism.backend.entity.ChatMessage;
import codeprism.backend.entity.MessageRole;
import codeprism.backend.repository.ChatMessageRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Generation step: call AI model via Spring AI and stream tokens to the browser over SSE.
 */
@Component
@Slf4j
public class ChatStreamHandler {

    private final ChatModel chatModel;
    private final ChatMessageRepository chatMessageRepository;
    private final CitationMapper citationMapper;
    private final double similarityThreshold;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public ChatStreamHandler(
            ChatModel chatModel,
            ChatMessageRepository chatMessageRepository,
            CitationMapper citationMapper,
            @Value("${rag.similarity-threshold:0.5}") double similarityThreshold) {
        this.chatModel = chatModel;
        this.chatMessageRepository = chatMessageRepository;
        this.citationMapper = citationMapper;
        this.similarityThreshold = similarityThreshold;
    }

    public SseEmitter stream(
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            RetrievedContext retrievedContext,
            String systemPrompt,
            String userPrompt) {

        SseEmitter emitter = new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);
        StringBuilder fullReply = new StringBuilder();

        try {
            emitter.send(SseEmitter.event()
                    .name("user_message")
                    .data(savedUserMessage));

            // Check if any semantic match clears rag.similarity-threshold
            if (retrievedContext.maxSemanticScore() < similarityThreshold) {
                String fixedMessage = "No relevant code found in this repository for that question.";
                ChatMessage assistant = chatMessageRepository.save(ChatMessage.builder()
                        .sessionId(sessionId)
                        .role(MessageRole.ASSISTANT)
                        .content(fixedMessage)
                        .citations(citationMapper.toJson(List.of()))
                        .sourcesCount(0)
                        .build());

                emitter.send(SseEmitter.event()
                        .name("token")
                        .data(fixedMessage, MediaType.APPLICATION_JSON));

                emitter.send(SseEmitter.event()
                        .name("assistant_message")
                        .data(toMessageResponse(assistant, 0)));

                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
                return emitter;
            }

            int sourcesCount = retrievedContext.sourcesCount();

            // Stream metadata with sourcesCount (number of chunks actually used)
            emitter.send(SseEmitter.event()
                    .name("metadata")
                    .data(Map.of("sourcesCount", sourcesCount)));

            AtomicInteger tokenCounter = new AtomicInteger(0);
            Timer.Sample chatSample = meterRegistry != null ? Timer.start(meterRegistry) : null;

            ChatClient.builder(chatModel)
                    .build()
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        tokenCounter.incrementAndGet();
                        appendToken(emitter, fullReply, token);
                    })
                    .doOnError(err -> {
                        log.error("Chat stream error", err);
                        recordChatMetrics(chatSample, tokenCounter.get());
                        emitter.completeWithError(err);
                    })
                    .doOnComplete(() -> {
                        recordChatMetrics(chatSample, tokenCounter.get());
                        completeStream(
                                emitter, sessionId, fullReply, retrievedContext.citations(), sourcesCount);
                    })
                    .subscribe();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }

        return emitter;
    }

    public SseEmitter stream(
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            List<CitationDto> citations,
            String systemPrompt,
            String userPrompt) {
        return stream(
                sessionId,
                savedUserMessage,
                new RetrievedContext(citations, userPrompt, citations, List.of(), userPrompt, "", 1.0, citations != null ? citations.size() : 0),
                systemPrompt,
                userPrompt);
    }

    private void appendToken(SseEmitter emitter, StringBuilder fullReply, String token) {
        fullReply.append(token);
        try {
            emitter.send(SseEmitter.event()
                    .name("token")
                    .data(token, MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void completeStream(
            SseEmitter emitter,
            UUID sessionId,
            StringBuilder fullReply,
            List<CitationDto> citations,
            int sourcesCount) {
        try {
            ChatMessage assistant = chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .role(MessageRole.ASSISTANT)
                    .content(fullReply.toString())
                    .citations(citationMapper.toJson(citations))
                    .sourcesCount(sourcesCount)
                    .build());

            emitter.send(SseEmitter.event()
                    .name("assistant_message")
                    .data(toMessageResponse(assistant, sourcesCount)));
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    private ChatMessageResponse toMessageResponse(ChatMessage message, Integer sourcesCount) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                citationMapper.fromJson(message.getCitations()),
                sourcesCount != null ? sourcesCount : message.getSourcesCount(),
                message.getCreatedAt());
    }

    private void recordChatMetrics(Timer.Sample chatSample, int tokens) {
        if (meterRegistry != null) {
            try {
                DistributionSummary.builder("codeprism.gemini.chat.tokens")
                        .description("Gemini chat completion token usage per request")
                        .register(meterRegistry)
                        .record(tokens);

                if (chatSample != null) {
                    chatSample.stop(Timer.builder("codeprism.gemini.chat.duration")
                            .description("Gemini chat completion latency per request")
                            .register(meterRegistry));
                }
            } catch (Exception ex) {
                log.warn("Failed to record chat completion metrics: {}", ex.getMessage());
            }
        }
    }
}