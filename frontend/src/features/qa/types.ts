export interface RetrievalFilters {
  docTypes: string[];
  publishers: string[];
  effectiveDateFrom?: string | null;
  effectiveDateTo?: string | null;
  sectionTypes?: string[];
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
  publisher?: string | null;
  sourceTitle?: string | null;
  version?: string | null;
  effectiveDate?: string | null;
  documentStatus?: string | null;
  stale?: boolean;
  vectorRank?: number | null;
  lexicalRank?: number | null;
  vectorScore?: number | null;
  lexicalScore?: number | null;
  fusedScore?: number | null;
  metadata: Record<string, string>;
}

export interface SearchResponse {
  query: string;
  role?: string;
  results: RetrievalResult[];
  filters?: RetrievalFilters;
  appliedFilters?: RetrievalFilters;
  modelName?: string;
  modelVersion?: string;
  distanceMetric?: string;
  retrievalMode?: string;
  rerankEnabled?: boolean;
  degraded?: boolean;
  degradationReasons?: string[];
  timing?: TimingBreakdown;
  generatedAt?: string;
  retrievedAt?: string;
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

export interface RetryStatus {
  attempt: number;
  maxAttempts: number;
  reason: string;
}

export function getSearchFilters(search: SearchResponse): RetrievalFilters {
  return search.filters ?? search.appliedFilters ?? { docTypes: [], publishers: [] };
}
