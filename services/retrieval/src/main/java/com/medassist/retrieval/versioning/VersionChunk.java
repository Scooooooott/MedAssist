package com.medassist.retrieval.versioning;

import java.util.UUID;

public record VersionChunk(UUID id, int ordinal, String sectionPath, String text) {}
