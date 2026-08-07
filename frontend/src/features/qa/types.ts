export interface RetrievalFilters {
  docTypes: string[];
  publishers: string[];
}

export interface AnswerRequest {
  query: string;
  filters: RetrievalFilters;
}

export interface TimingBreakdown {
  embeddingMs: number;
  retrievalMs: number;
  generationMs: number;
  totalMs: number;
}

export interface Citation {
  chunkId: string;
  documentVersionId: string;
  quotedSpan: string;
  relevance: string;
  valid: boolean;
  validationMessage: string;
}

export interface RetrievalResult {
  chunkId: string;
  documentVersionId: string;
  ordinal: number;
  sectionPath: string;
  text: string;
  tokenCount: number;
  sourceCharStart: number;
  sourceCharEnd: number;
  score: number;
  retrievalMethod: string;
  distanceMetric: string;
  docType: string;
  publisher: string;
  sourceTitle: string;
  version: string;
  effectiveDate: string | null;
  metadata: Record<string, string>;
}

export interface SearchResponse {
  query: string;
  results: RetrievalResult[];
  appliedFilters: RetrievalFilters;
  modelName: string;
  modelVersion: string;
  distanceMetric: string;
  retrievedAt: string;
}

export interface AnswerResponse {
  query: string;
  answer: string;
  citations: Citation[];
  sufficientEvidence: boolean;
  abstained: boolean;
  abstainReason: string;
  retrieval: SearchResponse;
  timing: TimingBreakdown;
  generatedAt: string;
}
