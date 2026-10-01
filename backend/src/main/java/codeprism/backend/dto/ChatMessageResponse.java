package codeprism.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import codeprism.backend.entity.MessageRole;

public record ChatMessageResponse(
        UUID id,
        MessageRole role,
        String content,
        List<CitationDto> citations,
        Integer sourcesCount,
        Instant createdAt) {

    public ChatMessageResponse(
            UUID id,
            MessageRole role,
            String content,
            List<CitationDto> citations,
            Instant createdAt) {
        this(id, role, content, citations, citations != null ? citations.size() : 0, createdAt);
    }
}