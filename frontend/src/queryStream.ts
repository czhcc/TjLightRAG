import { ApiError, request } from "./api";
export type QueryEventHandler = (event: string, data: unknown) => void;
/** Reads bounded SSE frames with streaming UTF-8 decoding and explicit terminal success. */
export async function readQueryEvents(
  body: ReadableStream<Uint8Array>,
  onEvent: QueryEventHandler,
  signal?: AbortSignal,
): Promise<void> {
  const reader = body.getReader(),
    decoder = new TextDecoder("utf-8", { fatal: true });
  let buffer = "",
    event = "message",
    data: string[] = [],
    size = 0,
    total = 0,
    done = false;
  const abort = () => {
    void reader.cancel().catch(() => {});
  };
  signal?.addEventListener("abort", abort, { once: true });
  function line(value: string) {
    if (value.endsWith("\r")) value = value.slice(0, -1);
    if (!value) {
      if (data.length) {
        const payload: unknown = JSON.parse(data.join("\n"));
        onEvent(event, payload);
        if (event === "error") throw new ApiError(502, "query_stream_failed");
        if (event === "done") {
          const status = (payload as { status?: string })?.status;
          if (!["success", "empty", "preview"].includes(status ?? ""))
            throw new Error("Invalid completion");
          done = true;
        }
      }
      event = "message";
      data = [];
      size = 0;
    } else if (!value.startsWith(":")) {
      const colon = value.indexOf(":"),
        name = colon < 0 ? value : value.slice(0, colon);
      let content = colon < 0 ? "" : value.slice(colon + 1);
      if (content.startsWith(" ")) content = content.slice(1);
      if (name === "event") event = content;
      if (name === "data") {
        data.push(content);
        size += content.length;
      }
      if (size > 4 * 1024 * 1024) throw new Error("Oversized event");
    }
  }
  try {
    while (!done) {
      if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
      const part = await reader.read();
      if (part.done) {
        buffer += decoder.decode();
        break;
      }
      total += part.value.byteLength;
      if (total > 32 * 1024 * 1024) throw new Error("Oversized stream");
      buffer += decoder.decode(part.value, { stream: true });
      let end: number;
      while (!done && (end = buffer.indexOf("\n")) >= 0) {
        line(buffer.slice(0, end));
        buffer = buffer.slice(end + 1);
      }
      if (buffer.length + size > 4 * 1024 * 1024)
        throw new Error("Oversized event");
    }
    if (!done) throw new Error("Incomplete stream");
  } finally {
    signal?.removeEventListener("abort", abort);
    void reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
/** Sends session/CSRF-protected POST and consumes native fetch streams. */
export async function queryStream(
  url: string,
  body: unknown,
  onEvent: QueryEventHandler,
  signal: AbortSignal,
) {
  const csrf = await request<{ token: string }>("/api/auth/csrf", { signal });
  const response = await fetch(url, {
    method: "POST",
    signal,
    credentials: "same-origin",
    cache: "no-store",
    headers: {
      "X-CSRF-TOKEN": csrf.token,
      "Content-Type": "application/json",
      Accept: "text/event-stream",
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new ApiError(response.status);
  if (
    !response.body ||
    !response.headers.get("Content-Type")?.startsWith("text/event-stream")
  )
    throw new Error("Invalid stream");
  await readQueryEvents(response.body, onEvent, signal);
}
