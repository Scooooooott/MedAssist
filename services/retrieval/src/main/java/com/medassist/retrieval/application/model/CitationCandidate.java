package com.medassist.retrieval.application.model;

import java.util.UUID;

public record CitationCandidate(
    UUID chunkId, UUID documentVersionId, String quotedSpan, String relevance) {}
