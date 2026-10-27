import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { beforeEach, it, expect, vi } from "vitest";
import QueryPanel from "./QueryPanel";
import i18n from "./i18n";
const evidence = {
  generationId: "g",
  chunks: [
    {
      chunk_id: "c",
      content: "依据🙂",
      reference_id: "1",
      sources: [
        {
          documentId: "d",
          revisionId: "r",
          chunkOrder: 0,
          sourceName: "原文.md",
        },
      ],
    },
  ],
  references: [{ reference_id: "1", file_path: "原文.md" }],
  context: "context",
};
beforeEach(async () => {
  vi.restoreAllMocks();
  await i18n.changeLanguage("zh");
});
function stream(events: [string, unknown][]) {
  return new ReadableStream<Uint8Array>({
    start(c) {
      events.forEach(([name, data]) =>
        c.enqueue(
          new TextEncoder().encode(
            `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`,
          ),
        ),
      );
      c.close();
    },
  });
}
it("uses POST with CSRF and renders plain answer plus exact Unicode source", async () => {
  const fetch = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith("/csrf"))
      return { ok: true, status: 200, json: async () => ({ token: "csrf" }) };
    if (url.includes("/source?"))
      return {
        ok: true,
        status: 200,
        json: async () => ({
          sourceName: "原文.md",
          excerpt: "前🙂知识后",
          excerptStart: 10,
          sourceStart: 11,
          sourceEnd: 14,
        }),
      };
    expect(options?.method).toBe("POST");
    expect((options?.headers as Record<string, string>)["X-CSRF-TOKEN"]).toBe(
      "csrf",
    );
    return {
      ok: true,
      status: 200,
      headers: new Headers({ "Content-Type": "text/event-stream" }),
      body: stream([
        ["metadata", { rerankStatus: "not_configured" }],
        ["references", evidence],
        ["delta", { text: "答案[1]<script>bad()</script>" }],
        [
          "done",
          {
            status: "success",
            unknownReferenceIds: [],
            citedReferenceIds: ["1"],
          },
        ],
      ]),
    };
  });
  vi.stubGlobal("fetch", fetch);
  render(<QueryPanel kb="kb1" onSessionExpired={() => {}} />);
  fireEvent.change(screen.getByLabelText("问答问题"), {
    target: { value: "问题" },
  });
  fireEvent.click(screen.getByRole("button", { name: "生成回答" }));
  await screen.findByText("回答已完成");
  expect(screen.getByText("答案[1]<script>bad()</script>")).toBeTruthy();
  expect(document.querySelector("script")).toBeNull();
  fireEvent.click(
    screen.getByRole("button", { name: "查看 原文.md · 第 1 块来源" }),
  );
  await waitFor(() =>
    expect(document.querySelector("mark")?.textContent).toBe("🙂知识"),
  );
});
it("keeps partial output but shows an interrupted-stream error without success", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) =>
      url.endsWith("/csrf")
        ? { ok: true, status: 200, json: async () => ({ token: "t" }) }
        : {
            ok: true,
            status: 200,
            headers: new Headers({ "Content-Type": "text/event-stream" }),
            body: stream([["delta", { text: "部分内容" }]]),
          },
    ),
  );
  render(<QueryPanel kb="kb" onSessionExpired={() => {}} />);
  fireEvent.change(screen.getByLabelText("问答问题"), {
    target: { value: "问题" },
  });
  fireEvent.click(screen.getByRole("button", { name: "生成回答" }));
  await screen.findByRole("alert");
  expect(screen.getByText("部分内容")).toBeTruthy();
  expect(screen.queryByText("回答已完成")).toBeNull();
});
it("explicitly cancels and aborts the fetch, and clears drafts on knowledge-base switch", async () => {
  let signal: AbortSignal | undefined;
  const fetch = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith("/csrf"))
      return { ok: true, status: 200, json: async () => ({ token: "t" }) };
    if (url.endsWith("/cancel")) return { ok: true, status: 204 };
    signal = options?.signal as AbortSignal;
    return {
      ok: true,
      status: 200,
      headers: new Headers({ "Content-Type": "text/event-stream" }),
      body: new ReadableStream({ start() {} }),
    };
  });
  vi.stubGlobal("fetch", fetch);
  const { rerender } = render(
    <QueryPanel kb="kb1" onSessionExpired={() => {}} />,
  );
  fireEvent.change(screen.getByLabelText("问答问题"), {
    target: { value: "私密问题" },
  });
  fireEvent.click(screen.getByRole("button", { name: "生成回答" }));
  await waitFor(() => expect(signal).toBeTruthy());
  fireEvent.click(screen.getByRole("button", { name: "停止生成" }));
  await waitFor(() =>
    expect(fetch.mock.calls.some(([url]) => url.includes("/cancel"))).toBe(
      true,
    ),
  );
  expect(signal?.aborted).toBe(true);
  rerender(<QueryPanel kb="kb2" onSessionExpired={() => {}} />);
  expect((screen.getByLabelText("问答问题") as HTMLTextAreaElement).value).toBe(
    "",
  );
});
it("has an English query interface and handles expired sessions", async () => {
  await i18n.changeLanguage("en");
  let expired = false;
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => ({ ok: false, status: 401, json: async () => ({}) })),
  );
  render(
    <QueryPanel
      kb="kb"
      onSessionExpired={() => {
        expired = true;
      }}
    />,
  );
  fireEvent.change(screen.getByLabelText("Question"), {
    target: { value: "q" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Generate answer" }));
  await waitFor(() => expect(expired).toBe(true));
});
