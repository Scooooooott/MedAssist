package com.medassist.retrieval.versioning;

public record ChunkDifference(int ordinal, String sectionPath, String changeType, String summary) {}
