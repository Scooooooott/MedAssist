import { AlertTriangle, ChevronDown } from "lucide-react";
import { useState } from "react";

import { buildHighlightSegments } from "../lib/highlight";
import type { Citation, RetrievalResult } from "../features/qa/types";

interface CitationItemProps {
  citation?: Citation;
  result: RetrievalResult;
}

export function CitationItem({ citation, result }: CitationItemProps) {
  const [expanded, setExpanded] = useState(false);
  const quote = citation?.quotedSpan ?? "";
  const segments = buildHighlightSegments(result.text, quote);
  const hasHighlight = segments.some((segment) => segment.highlighted);
  const publisher = result.publisher || "Unknown";
  const version = result.version || "Unknown";
  const effectiveDate = result.effectiveDate || "Unknown";
  const score = (value: number | null | undefined) =>
    value === null || value === undefined ? "Unknown" : value.toFixed(3);

  return (
    <article className="citation-card">
      <button
        className="citation-summary"
        type="button"
        aria-expanded={expanded}
        onClick={() => setExpanded((value) => !value)}
      >
        <ChevronDown aria-hidden="true" size={18} />
        <span>{result.sourceTitle || "Unknown"}</span>
        <small>{publisher}</small>
      </button>
      <div className="citation-meta">
        <span>Publisher: {publisher}</span>
        <span>Version: {version}</span>
        <span>Effective date: {effectiveDate}</span>
        <span>Status: {result.documentStatus || "Unknown"}</span>
        <span>Type: {result.docType || "Unknown"}</span>
        <span>Score: {score(result.score)}</span>
      </div>
      {result.stale ? (
        <div className="stale-warning" role="alert">
          <AlertTriangle aria-hidden="true" size={16} />
          <span>Stale document: verify that this source is still current.</span>
        </div>
      ) : null}
      <div className="citation-ranks" aria-label="Retrieval ranks and scores">
        <span>Vector rank: {result.vectorRank ?? "Unknown"}</span>
        <span>Lexical rank: {result.lexicalRank ?? "Unknown"}</span>
        <span>Vector score: {score(result.vectorScore)}</span>
        <span>Lexical score: {score(result.lexicalScore)}</span>
        <span>Fused score: {score(result.fusedScore)}</span>
      </div>
      {expanded ? (
        <div className="chunk-text">
          {!hasHighlight && quote ? (
            <p className="highlight-warning">Quoted span could not be aligned to this chunk.</p>
          ) : null}
          <p>
            {segments.map((segment, index) =>
              segment.highlighted ? (
                <mark key={`${segment.text}-${index}`}>{segment.text}</mark>
              ) : (
                <span key={`${segment.text}-${index}`}>{segment.text}</span>
              )
            )}
          </p>
        </div>
      ) : null}
    </article>
  );
}
