import { ChevronDown } from "lucide-react";
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

  return (
    <article className="citation-card">
      <button
        className="citation-summary"
        type="button"
        aria-expanded={expanded}
        onClick={() => setExpanded((value) => !value)}
      >
        <ChevronDown aria-hidden="true" size={18} />
        <span>{result.sourceTitle || "Unknown source"}</span>
        <small>{result.publisher || "Unknown publisher"}</small>
      </button>
      <div className="citation-meta">
        <span>{result.docType || "Unknown type"}</span>
        <span>{result.version || "Unknown version"}</span>
        <span>{result.effectiveDate || "Unknown date"}</span>
        <span>Score {result.score.toFixed(3)}</span>
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
