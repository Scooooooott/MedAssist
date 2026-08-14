export type GenerationStatus =
  "CREATED" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED" | "EXPIRED";

export type GenerationEventType =
  "accepted" | "delta" | "citation" | "degradation" | "final" | "error" | "cancelled";

export interface GenerationSession {
  generation_id: string;
  status: GenerationStatus;
  expires_at: string;
  events_url: string;
  terminal_event_id?: string;
}

export interface GenerationEvent {
  event_id: string;
  generation_id: string;
  type: GenerationEventType;
  schema_version: "1";
  created_at: string;
  payload: Record<string, unknown>;
}

export interface GenerationRetrievalFilters {
  docTypes?: ReadonlyArray<string>;
  publishers?: ReadonlyArray<string>;
  effectiveDateFrom?: string;
  effectiveDateTo?: string;
  sectionTypes?: ReadonlyArray<string>;
}

export interface GenerationClientState {
  generationId: string;
  status: GenerationStatus;
  text: string;
  citations: ReadonlyArray<Record<string, unknown>>;
  degradations: ReadonlyArray<Record<string, unknown>>;
  lastEventId?: string;
}

export interface GenerationClientOptions {
  getAccessToken: () => string | Promise<string>;
  fetch?: typeof fetch;
  maxReconnects?: number;
  reconnectDelayMs?: number;
}

export interface StreamGenerationOptions {
  signal?: AbortSignal;
  initialState?: GenerationClientState;
  onState?: (state: GenerationClientState) => void;
}

export class GenerationSessionError extends Error {
  readonly status?: number;
  readonly code?: string;

  constructor(message: string, status?: number, code?: string) {
    super(message);
    this.name = "GenerationSessionError";
    this.status = status;
    this.code = code;
  }
}

export class GenerationSessionClient {
  private readonly fetchImpl: typeof fetch;
  private readonly maxReconnects: number;
  private readonly reconnectDelayMs: number;

  constructor(private readonly options: GenerationClientOptions) {
    this.fetchImpl = options.fetch ?? fetch;
    this.maxReconnects = options.maxReconnects ?? 3;
    this.reconnectDelayMs = options.reconnectDelayMs ?? 250;
  }

  async create(
    query: string,
    filters: GenerationRetrievalFilters,
    idempotencyKey: string,
    signal?: AbortSignal
  ): Promise<GenerationSession> {
    const response = await this.fetchImpl("/api/generations", {
      method: "POST",
      headers: await this.headers({ "Content-Type": "application/json" }),
      body: JSON.stringify({ query, filters, idempotency_key: idempotencyKey }),
      credentials: "same-origin",
      signal
    });
    return this.readJson<GenerationSession>(response);
  }

  async status(generationId: string, signal?: AbortSignal): Promise<GenerationSession> {
    const response = await this.fetchImpl(this.sessionUrl(generationId), {
      headers: await this.headers(),
      credentials: "same-origin",
      signal
    });
    return this.readJson<GenerationSession>(response);
  }

  async cancel(generationId: string, signal?: AbortSignal): Promise<GenerationSession> {
    const response = await this.fetchImpl(this.sessionUrl(generationId), {
      method: "DELETE",
      headers: await this.headers(),
      credentials: "same-origin",
      signal
    });
    return this.readJson<GenerationSession>(response);
  }

  async stream(
    session: GenerationSession,
    options: StreamGenerationOptions = {}
  ): Promise<GenerationClientState> {
    this.requireSameOriginPath(session.events_url);
    let state =
      options.initialState ??
      ({
        generationId: session.generation_id,
        status: session.status,
        text: "",
        citations: [],
        degradations: []
      } satisfies GenerationClientState);
    if (state.generationId !== session.generation_id) {
      throw new GenerationSessionError("The recovery state belongs to another generation.");
    }
    const seen = new Set<string>();
    let reconnects = 0;

    while (!isTerminal(state.status)) {
      options.signal?.throwIfAborted();
      const cursorBefore = state.lastEventId;
      try {
        const headers: Record<string, string> = { Accept: "text/event-stream" };
        if (state.lastEventId) headers["Last-Event-ID"] = state.lastEventId;
        const response = await this.fetchImpl(session.events_url, {
          headers: await this.headers(headers),
          credentials: "same-origin",
          signal: options.signal
        });
        if (!response.ok) await this.throwProblem(response);
        if (!response.body) {
          throw new GenerationSessionError("The generation event stream has no body.");
        }
        await parseGenerationEvents(
          response.body,
          (event) => {
            if (event.generation_id !== session.generation_id) {
              throw new GenerationSessionError("The event belongs to another generation.");
            }
            if (
              seen.has(event.event_id) ||
              compareEventIds(event.event_id, state.lastEventId) <= 0
            ) {
              return;
            }
            seen.add(event.event_id);
            state = reduceEvent(state, event);
            options.onState?.(state);
          },
          options.signal
        );
        if (!isTerminal(state.status)) {
          reconnects = state.lastEventId === cursorBefore ? reconnects + 1 : 0;
          if (reconnects > this.maxReconnects) {
            throw new GenerationSessionError("The generation stream could not be resumed.");
          }
          await delay(this.reconnectDelayMs, options.signal);
        }
      } catch (error) {
        if (options.signal?.aborted || isAbortError(error)) throw error;
        if (error instanceof GenerationSessionError && error.status !== undefined) throw error;
        reconnects = state.lastEventId === cursorBefore ? reconnects + 1 : 0;
        if (reconnects > this.maxReconnects) {
          throw new GenerationSessionError("The generation stream could not be resumed.");
        }
        await delay(this.reconnectDelayMs, options.signal);
      }
    }
    return state;
  }

  private async headers(additional: Record<string, string> = {}): Promise<Record<string, string>> {
    const token = await this.options.getAccessToken();
    if (!token) throw new GenerationSessionError("Authentication is required.");
    return { ...additional, Authorization: `Bearer ${token}` };
  }

  private sessionUrl(generationId: string): string {
    if (!/^[A-Za-z0-9_-]{22,128}$/.test(generationId)) {
      throw new GenerationSessionError("The generation identifier is invalid.");
    }
    return `/api/generations/${generationId}`;
  }

  private requireSameOriginPath(url: string): void {
    if (!url.startsWith("/") || url.startsWith("//") || url.includes("://")) {
      throw new GenerationSessionError("The generation event URL is invalid.");
    }
  }

  private async readJson<T>(response: Response): Promise<T> {
    if (!response.ok) await this.throwProblem(response);
    return (await response.json()) as T;
  }

  private async throwProblem(response: Response): Promise<never> {
    let code: string | undefined;
    let detail = `Generation request failed with status ${response.status}.`;
    try {
      const problem = (await response.json()) as { code?: unknown; detail?: unknown };
      if (typeof problem.code === "string") code = problem.code;
      if (typeof problem.detail === "string" && problem.detail.trim()) detail = problem.detail;
    } catch {
      // Keep the bounded generic message for non-problem responses.
    }
    throw new GenerationSessionError(detail, response.status, code);
  }
}

function reduceEvent(state: GenerationClientState, event: GenerationEvent): GenerationClientState {
  const nextStatus = statusFor(event.type);
  const status = advanceStatus(state.status, nextStatus);
  const text =
    event.type === "delta" && typeof event.payload.text === "string"
      ? state.text + event.payload.text
      : state.text;
  const citations =
    event.type === "citation" ? [...state.citations, event.payload] : state.citations;
  const degradations =
    event.type === "degradation" || event.type === "error"
      ? [...state.degradations, event.payload]
      : state.degradations;
  return {
    ...state,
    status,
    text,
    citations,
    degradations,
    lastEventId: event.event_id
  };
}

function statusFor(type: GenerationEventType): GenerationStatus | undefined {
  if (type === "accepted") return "RUNNING";
  if (type === "final") return "COMPLETED";
  if (type === "error") return "FAILED";
  if (type === "cancelled") return "CANCELLED";
  return undefined;
}

function advanceStatus(
  current: GenerationStatus,
  candidate: GenerationStatus | undefined
): GenerationStatus {
  if (!candidate || isTerminal(current)) return current;
  const rank: Record<GenerationStatus, number> = {
    CREATED: 0,
    RUNNING: 1,
    COMPLETED: 2,
    FAILED: 2,
    CANCELLED: 2,
    EXPIRED: 2
  };
  return rank[candidate] >= rank[current] ? candidate : current;
}

function isTerminal(status: GenerationStatus): boolean {
  return ["COMPLETED", "FAILED", "CANCELLED", "EXPIRED"].includes(status);
}

async function parseGenerationEvents(
  body: ReadableStream<Uint8Array>,
  onEvent: (event: GenerationEvent) => void,
  signal?: AbortSignal
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      signal?.throwIfAborted();
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() ?? "";
      for (const frame of frames) parseFrame(frame, onEvent);
    }
    buffer += decoder.decode();
    parseFrame(buffer, onEvent);
  } finally {
    reader.releaseLock();
  }
}

function parseFrame(frame: string, onEvent: (event: GenerationEvent) => void): void {
  if (!frame.trim()) return;
  let id = "";
  let type = "message";
  const data: string[] = [];
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith("id:")) id = line.slice(3).trim();
    if (line.startsWith("event:")) type = line.slice(6).trim();
    if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
  }
  const parsed = JSON.parse(data.join("\n")) as Partial<GenerationEvent> | null;
  if (
    !parsed ||
    parsed.event_id !== id ||
    parsed.type !== type ||
    parsed.schema_version !== "1" ||
    !generationEventTypes.includes(parsed.type) ||
    typeof parsed.generation_id !== "string" ||
    typeof parsed.created_at !== "string" ||
    typeof parsed.payload !== "object" ||
    parsed.payload === null
  ) {
    throw new GenerationSessionError("The generation stream returned an invalid event.");
  }
  onEvent(parsed as GenerationEvent);
}

const generationEventTypes: ReadonlyArray<GenerationEventType> = [
  "accepted",
  "delta",
  "citation",
  "degradation",
  "final",
  "error",
  "cancelled"
];

function compareEventIds(left: string, right?: string): number {
  if (!right) return 1;
  const [leftTime, leftSequence] = parseEventId(left);
  const [rightTime, rightSequence] = parseEventId(right);
  if (leftTime !== rightTime) return leftTime > rightTime ? 1 : -1;
  if (leftSequence === rightSequence) return 0;
  return leftSequence > rightSequence ? 1 : -1;
}

function parseEventId(id: string): [bigint, bigint] {
  const match = /^(\d+)-(\d+)$/.exec(id);
  if (!match) throw new GenerationSessionError("The generation event identifier is invalid.");
  return [BigInt(match[1]), BigInt(match[2])];
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

function delay(milliseconds: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const complete = () => {
      signal?.removeEventListener("abort", abort);
      resolve();
    };
    const timeout = setTimeout(complete, milliseconds);
    const abort = () => {
      clearTimeout(timeout);
      signal?.removeEventListener("abort", abort);
      reject(new DOMException("The generation stream was interrupted.", "AbortError"));
    };
    if (signal?.aborted) abort();
    else signal?.addEventListener("abort", abort, { once: true });
  });
}
