import { useMemo, useState } from "react";

import { CitationItem } from "../../components/CitationItem";
import type { Citation, RetrievalResult } from "./types";

interface VirtualEvidenceListProps {
  citations: Citation[];
  results: RetrievalResult[];
  viewportHeight: number;
  rowHeight: number;
}

export function VirtualEvidenceList({
  citations,
  results,
  viewportHeight,
  rowHeight
}: VirtualEvidenceListProps) {
  const [scrollTop, setScrollTop] = useState(0);
  const citationByChunk = useMemo(
    () => new Map(citations.map((citation) => [citation.chunkId, citation])),
    [citations]
  );
  const overscan = 4;
  const visibleCount = Math.ceil(viewportHeight / rowHeight) + overscan * 2;
  const startIndex = Math.max(0, Math.floor(scrollTop / rowHeight) - overscan);
  const endIndex = Math.min(results.length, startIndex + visibleCount);
  const visible = results.slice(startIndex, endIndex);

  if (results.length === 0) {
    return <p className="empty-state">No evidence chunks were returned.</p>;
  }

  return (
    <section aria-label="Retrieved evidence">
      <div className="section-heading">
        <h3>Retrieved evidence</h3>
        <span>{results.length} chunks</span>
      </div>
      <div
        className="virtual-list"
        style={{ height: viewportHeight }}
        onScroll={(event) => setScrollTop(event.currentTarget.scrollTop)}
        data-testid="virtual-list"
      >
        <div style={{ height: results.length * rowHeight, position: "relative" }}>
          {visible.map((result, offset) => (
            <div
              className="virtual-row"
              key={result.chunkId}
              style={{
                height: rowHeight,
                transform: `translateY(${(startIndex + offset) * rowHeight}px)`
              }}
            >
              <CitationItem citation={citationByChunk.get(result.chunkId)} result={result} />
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
