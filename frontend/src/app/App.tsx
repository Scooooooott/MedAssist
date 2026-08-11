import { Activity, LayoutDashboard, MessageSquare, ShieldCheck } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";

import { Alert } from "../components/Alert";
import { Panel } from "../components/Panel";
import { AnswerView } from "../features/qa/AnswerView";
import { QuestionForm } from "../features/qa/QuestionForm";
import { askAnswerStream } from "../features/qa/api";
import type { AnswerResponse, RetryStatus, RetrievalFilters } from "../features/qa/types";
import { GovernanceDashboard } from "../features/governance/GovernanceDashboard";

type RequestState = "idle" | "loading" | "ready" | "error";

export function App() {
  const [view, setView] = useState<"qa" | "governance">("qa");
  const [state, setState] = useState<RequestState>("idle");
  const [answer, setAnswer] = useState<AnswerResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [partialMarkdown, setPartialMarkdown] = useState("");
  const [retryStatus, setRetryStatus] = useState<RetryStatus | null>(null);
  const [formResetKey, setFormResetKey] = useState(0);
  const controllerRef = useRef<AbortController | null>(null);

  const statusText = useMemo(() => {
    if (state === "loading") return "Loading evidence";
    if (state === "error") return "Request failed";
    if (state === "ready") return "Ready";
    return "Idle";
  }, [state]);

  async function handleAsk(query: string, filters: RetrievalFilters) {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setState("loading");
    setError(null);
    setAnswer(null);
    setPartialMarkdown("");
    setRetryStatus(null);

    try {
      const result = await askAnswerStream({
        query,
        filters,
        onPartialAnswer: setPartialMarkdown,
        onRetryStatus: setRetryStatus,
        signal: controller.signal
      });
      if (controller.signal.aborted || controllerRef.current !== controller) return;
      setAnswer(result);
      setPartialMarkdown("");
      setRetryStatus(null);
      setState("ready");
    } catch (unknownError) {
      if (controllerRef.current !== controller) return;
      if (controller.signal.aborted) {
        setError("The answer stream was interrupted. You can try again.");
      } else {
        setError(
          unknownError instanceof Error ? unknownError.message : "Unable to complete the request."
        );
      }
      setState("error");
    } finally {
      if (controllerRef.current === controller) controllerRef.current = null;
    }
  }

  function handleCancel() {
    controllerRef.current?.abort();
  }

  function handleReset() {
    controllerRef.current?.abort();
    controllerRef.current = null;
    setFormResetKey((value) => value + 1);
    setState("idle");
    setAnswer(null);
    setError(null);
    setPartialMarkdown("");
    setRetryStatus(null);
  }

  useEffect(() => () => controllerRef.current?.abort(), []);

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

      <nav className="view-tabs" aria-label="Application views">
        <button
          className={view === "qa" ? "view-tab is-active" : "view-tab"}
          onClick={() => setView("qa")}
          type="button"
        >
          <MessageSquare aria-hidden="true" size={17} />
          Question answering
        </button>
        <button
          className={view === "governance" ? "view-tab is-active" : "view-tab"}
          onClick={() => setView("governance")}
          type="button"
        >
          <LayoutDashboard aria-hidden="true" size={17} />
          Governance dashboards
        </button>
      </nav>

      {view === "governance" ? <GovernanceDashboard role="RESEARCHER" /> : null}

      {view === "qa" ? (
        <section className="workspace" aria-label="Question answering workspace">
          <Panel title="Question">
            <QuestionForm
              key={formResetKey}
              disabled={state === "loading"}
              onAsk={handleAsk}
              onCancel={handleCancel}
              onReset={handleReset}
            />
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
                Ask a corpus-grounded question to inspect the answer, citations, filters, and
                timing.
              </Alert>
            ) : null}
            {state === "loading" ? (
              <>
                {retryStatus ? (
                  <Alert tone="neutral" title="Finding more evidence">
                    Retrieval attempt {retryStatus.attempt} of {retryStatus.maxAttempts}:{" "}
                    {retryStatus.reason}
                  </Alert>
                ) : null}
                <AnswerView
                  answer={null}
                  partialMarkdown={partialMarkdown || "Preparing the answer..."}
                />
              </>
            ) : null}
            {state === "error" ? (
              <Alert tone="danger" title="Unable to answer">
                {error ?? "The request could not be completed."}
              </Alert>
            ) : null}
            {state === "ready" && answer ? <AnswerView answer={answer} partialMarkdown="" /> : null}
          </Panel>
        </section>
      ) : null}
    </main>
  );
}
