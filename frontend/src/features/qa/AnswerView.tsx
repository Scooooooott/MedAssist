import ReactMarkdown from "react-markdown";

import { Alert } from "../../components/Alert";
import { stabilizePartialMarkdown } from "../../lib/markdown";
import { VirtualEvidenceList } from "./VirtualEvidenceList";
import type { AnswerResponse } from "./types";

interface AnswerViewProps {
  answer: AnswerResponse | null;
  partialMarkdown: string;
}

export function AnswerView({ answer, partialMarkdown }: AnswerViewProps) {
  const markdown = stabilizePartialMarkdown(answer?.answer ?? partialMarkdown);

  return (
    <div className="answer-layout">
      {answer?.abstained ? (
        <Alert tone="warning" title="Insufficient evidence">
          {answer.abstainReason || "The corpus does not contain sufficient evidence."}
        </Alert>
      ) : null}
      <div className="markdown-answer" data-testid="markdown-answer">
        <ReactMarkdown>{markdown}</ReactMarkdown>
      </div>
      {answer ? (
        <>
          <dl className="timing-grid" aria-label="Timing breakdown">
            <div>
              <dt>Embedding</dt>
              <dd>{answer.timing.embeddingMs} ms</dd>
            </div>
            <div>
              <dt>Retrieval</dt>
              <dd>{answer.timing.retrievalMs} ms</dd>
            </div>
            <div>
              <dt>Generation</dt>
              <dd>{answer.timing.generationMs} ms</dd>
            </div>
            <div>
              <dt>Total</dt>
              <dd>{answer.timing.totalMs} ms</dd>
            </div>
          </dl>
          <p className="filter-summary">
            Filters: doc_type={answer.retrieval.appliedFilters.docTypes.join(",") || "*"};
            publisher={answer.retrieval.appliedFilters.publishers.join(",") || "*"}
          </p>
          <VirtualEvidenceList
            citations={answer.citations}
            results={answer.retrieval.results}
            viewportHeight={420}
            rowHeight={174}
          />
        </>
      ) : null}
    </div>
  );
}
