import { expect, it } from "vitest";
import { readQueryEvents } from "./queryStream";
import { ApiError } from "./api";
function body(parts: Uint8Array[]) {
  return new ReadableStream<Uint8Array>({
    start(c) {
      parts.forEach((p) => c.enqueue(p));
      c.close();
    },
  });
}
it("decodes split UTF-8, CRLF, comments and multiple data lines", async () => {
  const bytes = new TextEncoder().encode(
    ': heartbeat\r\nevent: delta\r\ndata: {"text":\r\ndata: "中文🙂"}\r\n\r\nevent: done\ndata: {"status":"success"}\n\n',
  );
  const events: unknown[] = [];
  await readQueryEvents(
    body(Array.from(bytes, (b) => new Uint8Array([b]))),
    (e, d) => events.push([e, d]),
  );
  expect(events).toEqual([
    ["delta", { text: "中文🙂" }],
    ["done", { status: "success" }],
  ]);
});
it("rejects EOF without done and visible error events even after a partial answer", async () => {
  await expect(
    readQueryEvents(
      body([
        new TextEncoder().encode('event: delta\ndata: {"text":"partial"}\n\n'),
      ]),
      () => {},
    ),
  ).rejects.toThrow();
  await expect(
    readQueryEvents(
      body([
        new TextEncoder().encode(
          'event: error\ndata: {"error":"model_timeout"}\n\n',
        ),
      ]),
      () => {},
    ),
  ).rejects.toThrow();
});
it("rejects oversized events and malformed JSON", async () => {
  for (const raw of [
    "event: delta\ndata: nope\n\n",
    "event: delta\ndata: " + "x".repeat(4 * 1024 * 1024 + 1),
  ])
    await expect(
      readQueryEvents(body([new TextEncoder().encode(raw)]), () => {}),
    ).rejects.toThrow();
});
it("preserves safe provider error codes for visible query failures", async () => {
  await expect(readQueryEvents(body([new TextEncoder().encode(
    'event: error\ndata: {"error":"query_budget_too_small"}\n\n',
  )]), () => {})).rejects.toMatchObject({ status: 502, code: "query_budget_too_small" });
  await expect(readQueryEvents(body([new TextEncoder().encode(
    'event: error\ndata: {"error":"private response body"}\n\n',
  )]), () => {})).rejects.toEqual(new ApiError(502, "query_stream_failed"));
});
