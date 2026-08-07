import { parseSseStream } from "../../lib/sse";
import type { AnswerRequest, AnswerResponse } from "./types";

interface StreamOptions extends AnswerRequest {
  onPartialAnswer: (markdown: string) => void;
}

function isAnswerResponse(value: unknown): value is AnswerResponse {
  return Boolean(
    value &&
    typeof value === "object" &&
    "answer" in value &&
    "citations" in value &&
    "retrieval" in value
  );
}

export async function askAnswerStream(options: StreamOptions): Promise<AnswerResponse> {
  const response = await fetch("/api/answer/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
    body: JSON.stringify({ query: options.query, filters: options.filters })
  });

  if (!response.ok) {
    throw new Error(`Request failed with status ${response.status}.`);
  }

  if (!response.body) {
    return askAnswerFallback(options);
  }

  let finalAnswer: AnswerResponse | null = null;
  let partial = "";

  await parseSseStream(response.body, (event) => {
    if (!event.data) return;
    const parsed = JSON.parse(event.data) as unknown;
    if (isAnswerResponse(parsed)) {
      finalAnswer = parsed;
      options.onPartialAnswer(parsed.answer);
      return;
    }
    if (typeof parsed === "object" && parsed && "delta" in parsed) {
      partial += String((parsed as { delta: unknown }).delta ?? "");
      options.onPartialAnswer(partial);
    }
  });

  if (!finalAnswer) {
    throw new Error("The stream ended before an answer was received.");
  }
  return finalAnswer;
}

async function askAnswerFallback(request: AnswerRequest): Promise<AnswerResponse> {
  const response = await fetch("/api/answer", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request)
  });
  if (!response.ok) {
    throw new Error(`Request failed with status ${response.status}.`);
  }
  return (await response.json()) as AnswerResponse;
}
