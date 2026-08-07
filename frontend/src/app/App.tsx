import { Activity, ShieldCheck } from "lucide-react";
import { useMemo, useState } from "react";

import { Alert } from "../components/Alert";
import { Panel } from "../components/Panel";
import { AnswerView } from "../features/qa/AnswerView";
import { QuestionForm } from "../features/qa/QuestionForm";
import { askAnswerStream } from "../features/qa/api";
import type { AnswerResponse, RetrievalFilters } from "../features/qa/types";

type RequestState = "idle" | "loading" | "ready" | "error";

export function App() {
  const [state, setState] = useState<RequestState>("idle");
  const [answer, setAnswer] = useState<AnswerResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [partialMarkdown, setPartialMarkdown] = useState("");

  const statusText = useMemo(() => {
    if (state === "loading") return "Loading evidence";
    if (state === "error") return "Request failed";
    if (state === "ready") return "Ready";
    return "Idle";
  }, [state]);

  async function handleAsk(query: string, filters: RetrievalFilters) {
    setState("loading");
    setError(null);
    setAnswer(null);
    setPartialMarkdown("");

    try {
      const result = await askAnswerStream({
        query,
        filters,
        onPartialAnswer: setPartialMarkdown
      });
      setAnswer(result);
      setPartialMarkdown("");
      setState("ready");
    } catch (unknownError) {
      setError(
        unknownError instanceof Error ? unknownError.message : "Unable to complete the request."
      );
      setState("error");
    }
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">MedAssist</p>
          <h1>Evidence-grounded clinical search</h1>
        </div>
        <div className="status-pill" aria-live="polite">
          <Activity aria-hidden="true" size={18} />
          <span>{statusText}</span>
        </div>
      </header>

      <section className="workspace" aria-label="Question answering workspace">
        <Panel title="Question">
          <QuestionForm disabled={state === "loading"} onAsk={handleAsk} />
        </Panel>

        <Panel
          title="Answer"
          actions={
            <span className="inline-badge">
              <ShieldCheck aria-hidden="true" size={16} />
              Citations required
            </span>
          }
        >
          {state === "idle" ? (
            <Alert tone="neutral" title="No question yet">
              Ask a corpus-grounded question to inspect the answer, citations, filters, and timing.
            </Alert>
          ) : null}
          {state === "loading" ? (
            <AnswerView
              answer={null}
              partialMarkdown={partialMarkdown || "Preparing the answer..."}
            />
          ) : null}
          {state === "error" ? (
            <Alert tone="danger" title="Unable to answer">
              {error ?? "The request could not be completed."}
            </Alert>
          ) : null}
          {state === "ready" && answer ? <AnswerView answer={answer} partialMarkdown="" /> : null}
        </Panel>
      </section>
    </main>
  );
}
