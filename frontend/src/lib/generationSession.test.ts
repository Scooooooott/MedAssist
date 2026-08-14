import { describe, expect, it, vi } from "vitest";

import {
  GenerationSessionClient,
  GenerationSessionError,
  type GenerationEvent,
  type GenerationSession
} from "./generationSession";

const generationId = "abcdefghijklmnopqrstuvwx";
const session: GenerationSession = {
  generation_id: generationId,
  status: "RUNNING",
  expires_at: "2030-01-01T00:00:00Z",
  events_url: `/api/generations/${generationId}/events`
};

describe("GenerationSessionClient", () => {
  it("creates an idempotent session with authentication outside the URL", async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      expect(init?.headers).toMatchObject({ Authorization: "Bearer secret-token" });
      expect(JSON.parse(String(init?.body))).toEqual({
        query: "question",
        filters: { publishers: ["WHO"] },
        idempotency_key: "idempotency-key-1"
      });
      return Response.json(session, { status: 201 });
    });
    const client = new GenerationSessionClient({
      getAccessToken: () => "secret-token",
      fetch: fetchMock
    });

    await client.create("question", { publishers: ["WHO"] }, "idempotency-key-1");

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/generations");
    expect(String(fetchMock.mock.calls[0]?.[0])).not.toContain("secret-token");
  });

  it("recovers with Last-Event-ID and deduplicates replayed text and citations", async () => {
    const first = stream([
      event("1-0", "accepted", { status: "RUNNING" }),
      event("2-0", "delta", { text: "A" }),
      event("3-0", "delta", { text: "B" })
    ]);
    const second = stream([
      event("3-0", "delta", { text: "B" }),
      event("4-0", "citation", { valid_count: 1 }),
      event("5-0", "final", { status: "COMPLETED" })
    ]);
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(first, { status: 200 }))
      .mockResolvedValueOnce(new Response(second, { status: 200 }));
    const states: string[] = [];
    const client = new GenerationSessionClient({
      getAccessToken: () => "token",
      fetch: fetchMock,
      reconnectDelayMs: 0
    });

    const result = await client.stream(session, { onState: (state) => states.push(state.text) });

    expect(result.text).toBe("AB");
    expect(result.citations).toHaveLength(1);
    expect(result.status).toBe("COMPLETED");
    expect(result.lastEventId).toBe("5-0");
    expect(states.filter((value) => value === "AB")).toHaveLength(3);
    expect(fetchMock.mock.calls[1]?.[1]?.headers).toMatchObject({ "Last-Event-ID": "3-0" });
  });

  it("keeps terminal state monotonic when terminal events are replayed", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        new Response(
          stream([
            event("1-0", "delta", { text: "safe" }),
            event("2-0", "final", { status: "COMPLETED" }),
            event("3-0", "accepted", { status: "RUNNING" }),
            event("3-0", "accepted", { status: "RUNNING" })
          ]),
          { status: 200 }
        )
      );
    const client = new GenerationSessionClient({ getAccessToken: () => "token", fetch: fetchMock });

    const result = await client.stream(session);

    expect(result.status).toBe("COMPLETED");
    expect(result.text).toBe("safe");
    expect(result.lastEventId).toBe("3-0");
  });

  it("collects degradation and error events before a failed terminal state", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        new Response(
          stream([
            event("1-0", "accepted", { status: "RUNNING" }),
            event("2-0", "degradation", { code: "RETRIEVAL_DEGRADED" }),
            event("3-0", "error", { code: "GENERATION_FAILED", retryable: true })
          ]),
          { status: 200 }
        )
      );
    const client = new GenerationSessionClient({ getAccessToken: () => "token", fetch: fetchMock });

    const result = await client.stream(session);

    expect(result.status).toBe("FAILED");
    expect(result.degradations).toEqual([
      { code: "RETRIEVAL_DEGRADED" },
      { code: "GENERATION_FAILED", retryable: true }
    ]);
  });

  it("reads status and preserves RFC9457 code and detail on cancellation failure", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json(session))
      .mockResolvedValueOnce(
        Response.json(
          { code: "TERMINAL_CONFLICT", detail: "Generation is already terminal." },
          { status: 409 }
        )
      );
    const client = new GenerationSessionClient({ getAccessToken: () => "token", fetch: fetchMock });

    await expect(client.status(generationId)).resolves.toEqual(session);
    await expect(client.cancel(generationId)).rejects.toMatchObject({
      status: 409,
      code: "TERMINAL_CONFLICT",
      message: "Generation is already terminal."
    });
  });

  it("bounds clean reconnects that make no event progress", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockImplementation(async () => new Response(stream([]), { status: 200 }));
    const client = new GenerationSessionClient({
      getAccessToken: () => "token",
      fetch: fetchMock,
      maxReconnects: 1,
      reconnectDelayMs: 0
    });

    await expect(client.stream(session)).rejects.toThrow(
      "The generation stream could not be resumed."
    );
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("rejects cross-origin event URLs before exposing the bearer token", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    const client = new GenerationSessionClient({ getAccessToken: () => "token", fetch: fetchMock });

    await expect(
      client.stream({ ...session, events_url: "https://attacker.example/events" })
    ).rejects.toBeInstanceOf(GenerationSessionError);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

function event(
  eventId: string,
  type: GenerationEvent["type"],
  payload: Record<string, unknown>
): GenerationEvent {
  return {
    event_id: eventId,
    generation_id: generationId,
    type,
    schema_version: "1",
    created_at: "2026-08-11T00:00:00Z",
    payload
  };
}

function stream(events: GenerationEvent[], failAfter = false): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const value of events) {
        controller.enqueue(
          encoder.encode(
            `id: ${value.event_id}\nevent: ${value.type}\ndata: ${JSON.stringify(value)}\n\n`
          )
        );
      }
      if (failAfter) controller.error(new Error("network interrupted"));
      else controller.close();
    }
  });
}
