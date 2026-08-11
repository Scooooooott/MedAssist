import { parseSseStream } from "../../lib/sse";
import type { AnswerRequest, AnswerResponse, RetryStatus } from "./types";

interface StreamOptions extends AnswerRequest {
  onPartialAnswer: (markdown: string) => void;
  onRetryStatus?: (status: RetryStatus) => void;
  signal?: AbortSignal;
}

export class AnswerStreamError extends Error {
  constructor(message = "The answer stream was interrupted. Please try again.") {
    super(message);
    this.name = "AnswerStreamError";
  }
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
    body: JSON.stringify({ query: options.query, filters: options.filters }),
    signal: options.signal
  });

  if (!response.ok) {
    throw new Error(`Request failed with status ${response.status}.`);
  }

  if (!response.body) {
    return askAnswerFallback(options);
  }

  let finalAnswer: AnswerResponse | null = null;
  let partial = "";

  await parseSseStream(
    response.body,
    (event) => {
      if (!event.data) return;
      const parsed = parseEventData(event.data);

      if (event.event === "error") {
        throw new AnswerStreamError(readErrorMessage(parsed));
      }
      if (event.event === "delta") {
        const delta = readDelta(parsed);
        if (delta) {
          partial += delta;
          options.onPartialAnswer(partial);
        }
        return;
      }
      if (event.event === "retry") {
        const retry = readRetryStatus(parsed);
        if (retry) options.onRetryStatus?.(retry);
        return;
      }
      if (event.event === "final") {
        if (isAnswerResponse(parsed)) {
          finalAnswer = parsed;
          options.onPartialAnswer(parsed.answer);
        }
        return;
      }

      // Keep the original single-event contract working during the backend rollout.
      if (event.event === "answer") {
        if (isAnswerResponse(parsed)) {
          finalAnswer = parsed;
          options.onPartialAnswer(parsed.answer);
        } else {
          const delta = readDelta(parsed);
          if (delta) {
            partial += delta;
            options.onPartialAnswer(partial);
          }
        }
      }
    },
    options.signal
  );

  if (!finalAnswer) {
    throw new AnswerStreamError("The answer stream ended before a complete answer was received.");
  }
  return finalAnswer;
}

async function askAnswerFallback(request: StreamOptions): Promise<AnswerResponse> {
  const response = await fetch("/api/answer", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query: request.query, filters: request.filters }),
    signal: request.signal
  });
  if (!response.ok) {
    throw new Error(`Request failed with status ${response.status}.`);
  }
  return (await response.json()) as AnswerResponse;
}

function parseEventData(data: string): unknown {
  try {
    return JSON.parse(data) as unknown;
  } catch {
    throw new AnswerStreamError("The answer stream returned an invalid event.");
  }
}

function readDelta(value: unknown): string {
  if (typeof value === "string") return value;
  if (typeof value === "object" && value !== null && "delta" in value) {
    const delta = (value as { delta?: unknown }).delta;
    return typeof delta === "string" ? delta : String(delta ?? "");
  }
  return "";
}

function readRetryStatus(value: unknown): RetryStatus | null {
  if (typeof value !== "object" || value === null) return null;
  const candidate = value as { attempt?: unknown; maxAttempts?: unknown; reason?: unknown };
  if (
    !Number.isInteger(candidate.attempt) ||
    !Number.isInteger(candidate.maxAttempts) ||
    typeof candidate.reason !== "string"
  ) {
    return null;
  }
  return {
    attempt: candidate.attempt as number,
    maxAttempts: candidate.maxAttempts as number,
    reason: candidate.reason
  };
}

function readErrorMessage(value: unknown): string {
  if (typeof value === "string" && value.trim() && !looksLikeStackTrace(value)) return value;
  if (typeof value === "object" && value !== null && "message" in value) {
    const message = (value as { message?: unknown }).message;
    if (typeof message === "string" && message.trim() && !looksLikeStackTrace(message)) {
      return message;
    }
  }
  return "The answer stream reported an error. Please try again.";
}

function looksLikeStackTrace(message: string): boolean {
  return message.includes("\n") || /\bat\s+\S+\(/.test(message);
}
