package codeprism.backend.services.ai;

import java.util.List;

import codeprism.backend.dto.CitationDto;

public record RetrievedContext(
        List<CitationDto> citations,
        String contextText) {
}