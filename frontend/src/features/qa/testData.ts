import type { AnswerResponse, RetrievalResult } from "./types";

export function makeResult(index = 0): RetrievalResult {
  return {
    chunkId: `chunk-${index}`,
    documentVersionId: `version-${index}`,
    ordinal: index,
    sectionPath: `section.${index}`,
    text: `The guideline recommends aspirin for eligible adults. Chunk number ${index}.`,
    tokenCount: 18,
    sourceCharStart: index * 100,
    sourceCharEnd: index * 100 + 72,
    score: 0.92 - index * 0.001,
    retrievalMethod: "VECTOR",
    distanceMetric: "cosine",
    docType: "GUIDELINE",
    publisher: "CDC",
    sourceTitle: `Guideline ${index}`,
    version: "v1",
    effectiveDate: "2026-01-01",
    metadata: {}
  };
}

export function makeAnswer(overrides: Partial<AnswerResponse> = {}): AnswerResponse {
  const results = Array.from({ length: 8 }, (_, index) => makeResult(index));
  return {
    query: "Should eligible adults take aspirin?",
    answer: "**Aspirin** is recommended when the cited guideline supports it.",
    citations: [
      {
        chunkId: "chunk-0",
        documentVersionId: "version-0",
        quotedSpan: "guideline recommends aspirin",
        relevance: "dose guidance",
        valid: true,
        validationMessage: ""
      }
    ],
    sufficientEvidence: true,
    abstained: false,
    abstainReason: "",
    retrieval: {
      query: "Should eligible adults take aspirin?",
      results,
      appliedFilters: { docTypes: ["GUIDELINE"], publishers: ["CDC"] },
      modelName: "bge-m3",
      modelVersion: "m1",
      distanceMetric: "cosine",
      retrievedAt: "2026-08-07T00:00:00Z"
    },
    timing: {
      embeddingMs: 10,
      retrievalMs: 20,
      generationMs: 30,
      totalMs: 60
    },
    generatedAt: "2026-08-07T00:00:00Z",
    ...overrides
  };
}
