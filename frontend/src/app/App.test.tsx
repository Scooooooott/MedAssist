import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { App } from "./App";
import { makeAnswer } from "../features/qa/testData";

function streamResponse(payloads: unknown[], ok = true, event = "final"): Response {
  return streamEvents(
    payloads.map((payload) => ({ event, payload })),
    ok
  );
}

function streamEvents(events: Array<{ event: string; payload: unknown }>, ok = true): Response {
  const encoder = new TextEncoder();
  return {
    ok,
    status: ok ? 200 : 500,
    body: new ReadableStream<Uint8Array>({
      start(controller) {
        for (const item of events) {
          controller.enqueue(
            encoder.encode(`event: ${item.event}\ndata: ${JSON.stringify(item.payload)}\n\n`)
          );
        }
        controller.close();
      }
    })
  } as Response;
}

describe("App", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("streams markdown and renders the final answer with citations", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      streamEvents([
        { event: "delta", payload: { delta: "**Aspir" } },
        { event: "final", payload: makeAnswer() }
      ]) as unknown as Response
    );
    render(<App />);

    await userEvent.type(
      screen.getByPlaceholderText("Ask a corpus-grounded question"),
      "Should eligible adults take aspirin?"
    );
    await userEvent.type(screen.getByLabelText("Document types"), "GUIDELINE");
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));

    expect(await screen.findByText("Aspirin")).toBeInTheDocument();
    expect(screen.getByText(/Filters: doc_type=GUIDELINE/)).toBeInTheDocument();
    expect(screen.getByText("Retrieved evidence")).toBeInTheDocument();
  });

  it("shows retrieval retry progress while the answer stream is active", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      delayedRetryStreamResponse() as unknown as Response
    );
    render(<App />);

    await userEvent.type(
      screen.getByPlaceholderText("Ask a corpus-grounded question"),
      "Find more evidence"
    );
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));

    expect(await screen.findByText("Finding more evidence")).toBeInTheDocument();
    expect(screen.getByText(/Retrieval attempt 1 of 2/)).toBeInTheDocument();
    expect(await screen.findByText("Retrieved evidence")).toBeInTheDocument();
  });

  it("shows refusal states", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      streamResponse([
        makeAnswer({
          abstained: true,
          sufficientEvidence: false,
          abstainReason: "The corpus does not contain enough evidence."
        })
      ]) as unknown as Response
    );
    render(<App />);

    await userEvent.type(
      screen.getByPlaceholderText("Ask a corpus-grounded question"),
      "Unsupported question"
    );
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));

    expect(await screen.findByText("Insufficient evidence")).toBeInTheDocument();
    expect(screen.getByText("The corpus does not contain enough evidence.")).toBeInTheDocument();
  });

  it("shows network errors without raw stack traces", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      streamResponse([], false) as unknown as Response
    );
    render(<App />);

    await userEvent.type(
      screen.getByPlaceholderText("Ask a corpus-grounded question"),
      "Will this fail?"
    );
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Request failed"));
    expect(screen.queryByText(/at .*App/)).not.toBeInTheDocument();
  });

  it("surfaces an SSE error event as a friendly message", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      streamResponse(
        [{ message: "Provider failed\nat Provider.call(Provider.java:1)" }],
        true,
        "error"
      ) as unknown as Response
    );
    render(<App />);

    await userEvent.type(
      screen.getByPlaceholderText("Ask a corpus-grounded question"),
      "Will this stream fail?"
    );
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Unable to answer"));
    expect(screen.getByRole("alert")).toHaveTextContent("Please try again");
    expect(screen.queryByText(/Provider\.java/)).not.toBeInTheDocument();
  });

  it("cancels an in-flight request and reports an interrupted stream", async () => {
    let signal: AbortSignal | undefined;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) => {
      signal = init?.signal ?? undefined;
      return {
        ok: true,
        status: 200,
        body: new ReadableStream<Uint8Array>({
          start(controller) {
            controller.enqueue(
              new TextEncoder().encode('event: delta\ndata: {"delta":"Partial"}\n\n')
            );
          }
        })
      } as Response;
    });
    render(<App />);

    await userEvent.type(
      screen.getByPlaceholderText("Ask a corpus-grounded question"),
      "Cancel this stream"
    );
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));
    await userEvent.click(await screen.findByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("interrupted"));
    expect(signal?.aborted).toBe(true);
  });

  it("resets the question, filters, and answer state", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      streamResponse([makeAnswer()], true, "final") as unknown as Response
    );
    render(<App />);

    const query = screen.getByPlaceholderText("Ask a corpus-grounded question");
    await userEvent.type(query, "Reset this answer");
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));
    expect(await screen.findByText("Retrieved evidence")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Reset" }));
    expect(screen.getByPlaceholderText("Ask a corpus-grounded question")).toHaveValue("");
    expect(screen.getByText("No question yet")).toBeInTheDocument();
    expect(screen.queryByText("Retrieved evidence")).not.toBeInTheDocument();
  });

  it("shows a friendly error when an SSE stream ends before final", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      streamResponse([{ delta: "Partial answer" }], true, "delta") as unknown as Response
    );
    render(<App />);

    await userEvent.type(
      screen.getByPlaceholderText("Ask a corpus-grounded question"),
      "Incomplete stream"
    );
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Unable to answer"));
    expect(screen.getByRole("alert")).toHaveTextContent("complete answer");
  });
});

function delayedRetryStreamResponse(): Response {
  const encoder = new TextEncoder();
  return {
    ok: true,
    status: 200,
    body: new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode(
            `event: retry\ndata: ${JSON.stringify({ attempt: 1, maxAttempts: 2, reason: "Evidence coverage was low." })}\n\n`
          )
        );
        window.setTimeout(() => {
          controller.enqueue(
            encoder.encode(`event: final\ndata: ${JSON.stringify(makeAnswer())}\n\n`)
          );
          controller.close();
        }, 700);
      }
    })
  } as unknown as Response;
}
