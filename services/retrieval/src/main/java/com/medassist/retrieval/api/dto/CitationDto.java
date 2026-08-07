package com.medassist.retrieval.api.dto;

import java.util.UUID;

public record CitationDto(
    UUID chunkId,
    UUID documentVersionId,
    String quotedSpan,
    String relevance,
    boolean valid,
    String validationMessage) {}
