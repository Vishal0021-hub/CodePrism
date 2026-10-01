package codeprism.backend.services.ai;

import java.util.List;

import codeprism.backend.dto.CitationDto;

public record RetrievedContext(
        List<CitationDto> citations,
        String contextText,
        List<CitationDto> semanticCitations,
        List<CitationDto> structuralCitations,
        String semanticContextText,
        String structuralContextText,
        double maxSemanticScore,
        int sourcesCount) {

    public RetrievedContext(List<CitationDto> citations, String contextText) {
        this(citations, contextText, citations, List.of(), contextText, "", 1.0, citations != null ? citations.size() : 0);
    }

    public RetrievedContext(
            List<CitationDto> citations,
            String contextText,
            List<CitationDto> semanticCitations,
            List<CitationDto> structuralCitations,
            String semanticContextText,
            String structuralContextText) {
        this(citations, contextText, semanticCitations, structuralCitations, semanticContextText, structuralContextText, 1.0, citations != null ? citations.size() : 0);
    }
}