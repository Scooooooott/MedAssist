export interface SseEvent {
  event: string;
  data: string;
}

export async function parseSseStream(
  body: ReadableStream<Uint8Array>,
  onEvent: (event: SseEvent) => void,
  signal?: AbortSignal
) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  const abort = () => {
    void reader.cancel();
  };
  signal?.addEventListener("abort", abort, { once: true });

  try {
    while (true) {
      if (signal?.aborted)
        throw new DOMException("The answer stream was interrupted.", "AbortError");
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() ?? "";
      for (const frame of frames) {
        const event = parseFrame(frame);
        if (event) onEvent(event);
      }
    }

    buffer += decoder.decode();
    const finalEvent = parseFrame(buffer);
    if (finalEvent) onEvent(finalEvent);
  } finally {
    signal?.removeEventListener("abort", abort);
    reader.releaseLock();
  }
}

function parseFrame(frame: string): SseEvent | null {
  if (!frame.trim()) return null;
  const event = { event: "message", data: "" };
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith("event:")) event.event = line.slice(6).trim();
    if (line.startsWith("data:")) event.data += line.slice(5).trimStart();
  }
  return event;
}
