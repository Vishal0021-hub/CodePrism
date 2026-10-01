package codeprism.backend.dto;

public record CitationDto(
        String filePath,
        Integer startLine,
        Integer endLine,
        String language,
        String matchType) {

    public CitationDto(String filePath, Integer startLine, Integer endLine, String language) {
        this(filePath, startLine, endLine, language, "semantic");
    }
}