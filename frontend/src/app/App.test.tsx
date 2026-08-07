import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { App } from "./App";
import { makeAnswer } from "../features/qa/testData";

function streamResponse(payloads: unknown[], ok = true): Response {
  const encoder = new TextEncoder();
  return {
    ok,
    status: ok ? 200 : 500,
    body: new ReadableStream<Uint8Array>({
      start(controller) {
        for (const payload of payloads) {
          controller.enqueue(encoder.encode(`event: answer\ndata: ${JSON.stringify(payload)}\n\n`));
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
      streamResponse([{ delta: "**Aspir" }, makeAnswer()]) as unknown as Response
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
});
