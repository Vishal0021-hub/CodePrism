package codeprism.backend.dto;

public record FindResultDto(
    String filePath,
    String symbolName,
    Integer lineNumber,
    String snippet,
    boolean exactMatch
) {}
