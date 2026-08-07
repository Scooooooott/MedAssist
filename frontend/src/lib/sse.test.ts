import { describe, expect, it } from "vitest";

import { parseSseStream } from "./sse";

describe("parseSseStream", () => {
  it("parses streamed server-sent events across chunks", async () => {
    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: answer\ndata: {"delta":"Hel"}\n\n'));
        controller.enqueue(encoder.encode('event: answer\ndata: {"delta":"lo"}\n\n'));
        controller.close();
      }
    });
    const data: string[] = [];

    await parseSseStream(stream, (event) => data.push(event.data));

    expect(data).toEqual(['{"delta":"Hel"}', '{"delta":"lo"}']);
  });
});
