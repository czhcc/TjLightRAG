import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { beforeEach, it, expect, vi } from "vitest";
import QueryPanel from "./QueryPanel";
import i18n from "./i18n";
function stubGlobal(_name: string, handler: (...args: any[]) => any) {
  vi.stubGlobal("fetch", vi.fn(async (url: string, options?: RequestInit) => {
    if (/\/conversations$/.test(url)) return { ok: true, status: 200, json: async () => options?.method === "POST" ? { id: "conversation", title: "" } : [] };
    if (/\/conversations\/[^/]+\/turns/.test(url)) return { ok: true, status: 200, json: async () => [] };
    return handler(url, options);
  }));
}
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
it("explains empty recalled chunks in both streamed replies and saved history", async () => {
  const emptyEvidence = { ...evidence, chunks: [], references: [] };
  vi.stubGlobal("fetch", vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith("/csrf")) return { ok: true, status: 200, json: async () => ({ token: "t" }) };
    if (url.endsWith("/conversations")) return { ok: true, status: 200, json: async () => options?.method === "POST" ? { id: "new", title: "" } : [{ id: "old", title: "旧对话" }] };
    if (url.endsWith("/turns")) return { ok: true, status: 200, json: async () => [{ id: "turn", sequence: 1, question: "旧问题", answer: "旧回答", status: "success", result: { retrieval: emptyEvidence } }] };
    return { ok: true, status: 200, headers: new Headers({ "Content-Type": "text/event-stream" }), body: stream([
      ["references", emptyEvidence], ["delta", { text: "回答" }], ["done", { status: "success", retrieval: emptyEvidence }],
    ]) };
  }));
  render(<QueryPanel kb="kb1" onSessionExpired={() => {}} />);
  await screen.findByRole("button", { name: "旧对话" });
  fireEvent.change(screen.getByLabelText("问答问题"), { target: { value: "问题" } });
  fireEvent.click(screen.getByRole("button", { name: "发送" }));
  await screen.findByText("回答已完成");
  const message = "本轮没有可展示的召回文本块，候选文本可能因 token 预算或重排过滤被移除。";
  expect(screen.getByText(message)).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "旧对话" }));
  await screen.findByText("旧回答");
  expect(screen.getByText(message)).toBeTruthy();
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
it("aligns composer actions to the right", () => {
  stubGlobal("fetch", async () => ({ ok: true, status: 200, json: async () => [] }));
  render(<QueryPanel kb="kb1" onSessionExpired={() => {}} />);
  const actions = screen.getByRole("button", { name: "发送" }).parentElement!;
  expect(actions.classList.contains("justify-end")).toBe(true);
  expect(within(actions).getByRole("button", { name: "预览上下文" })).toBeTruthy();
  expect(within(actions).getByRole("button", { name: "预览提示词" })).toBeTruthy();
});
it("places mode and rerank controls in the conversation header outside the composer", async () => {
  stubGlobal("fetch", async () => ({ ok: true, status: 200, json: async () => [] }));
  render(<QueryPanel kb="kb1" onSessionExpired={() => {}} />);
  for (const label of ["问答模式", "启用重排"]) {
    const control = screen.getByLabelText(label);
    expect(control.closest("header")?.classList.contains("conversation-header")).toBe(true);
    expect(control.closest("form")).toBeNull();
  }
});
it("uses POST with CSRF and renders streamed Markdown plus exact Unicode source", async () => {
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
        ["delta", { text: "## 实时结论\n\n答案[1]<script>bad()</script>" }],
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
  stubGlobal("fetch", fetch);
  render(<QueryPanel kb="kb1" onSessionExpired={() => {}} />);
  await waitFor(() => expect((screen.getByRole("button", { name: "发送" }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.change(screen.getByLabelText("问答问题"), {
    target: { value: "问题" },
  });
  fireEvent.click(screen.getByRole("button", { name: "发送" }));
  await screen.findByText("回答已完成");
  expect(within(screen.getByRole("log")).getByText("问题")).toBeTruthy();
  for (const className of ["ml-auto", "w-fit", "max-w-1/2", "text-right", "break-words"]) {
    expect(within(screen.getByRole("log")).getByText("问题").classList.contains(className)).toBe(true);
  }
  expect(screen.queryByText("你", { exact: true })).toBeNull();
  expect(screen.getByRole("heading", { name: "实时结论" })).toBeTruthy();
  expect(screen.getByText("答案[1]<script>bad()</script>")).toBeTruthy();
  expect(document.querySelector("script")).toBeNull();
  fireEvent.click(
    screen.getByRole("button", { name: "查看 原文.md · 第 1 块来源" }),
  );
  await waitFor(() =>
    expect(document.querySelector("mark")?.textContent).toBe("🙂知识"),
  );
});
it.each(["done", "error"])("clears the sent question immediately and preserves the next draft after %s", async (terminal) => {
  let controller: ReadableStreamDefaultController<Uint8Array> | undefined;
  let sentQuestion = "";
  stubGlobal("fetch", async (url: string, options?: RequestInit) => {
    if (url.endsWith("/csrf")) return { ok: true, status: 200, json: async () => ({ token: "t" }) };
    sentQuestion = JSON.parse(options!.body as string).query;
    return { ok: true, status: 200, headers: new Headers({ "Content-Type": "text/event-stream" }),
      body: new ReadableStream<Uint8Array>({ start(value) { controller = value; } }) };
  });
  render(<QueryPanel kb="kb1" onSessionExpired={() => {}} />);
  await waitFor(() => expect((screen.getByRole("button", { name: "发送" }) as HTMLButtonElement).disabled).toBe(false));
  const input = screen.getByLabelText("问答问题") as HTMLTextAreaElement;
  fireEvent.change(input, { target: { value: "原问题" } });
  fireEvent.click(screen.getByRole("button", { name: "发送" }));
  expect(input.value).toBe("");
  expect(within(screen.getByRole("log")).getByText("原问题")).toBeTruthy();
  await waitFor(() => expect(controller).toBeDefined());
  expect(sentQuestion).toBe("原问题");
  // Even an identical new draft belongs to the user and must survive completion.
  fireEvent.change(input, { target: { value: "原问题" } });
  const payload = terminal === "done" ? { status: "success" } : { error: "model_http_error" };
  controller!.enqueue(new TextEncoder().encode(`event: ${terminal}\ndata: ${JSON.stringify(payload)}\n\n`));
  controller!.close();
  await waitFor(() => expect((screen.getByRole("button", { name: "发送" }) as HTMLButtonElement).disabled).toBe(false));
  expect(input.value).toBe("原问题");
});

it("keeps partial output but shows an interrupted-stream error without success", async () => {
  stubGlobal(
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
  await waitFor(() => expect((screen.getByRole("button", { name: "发送" }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.change(screen.getByLabelText("问答问题"), {
    target: { value: "问题" },
  });
  fireEvent.click(screen.getByRole("button", { name: "发送" }));
  await screen.findByRole("alert");
  expect(screen.getByText("部分内容")).toBeTruthy();
  expect(screen.queryByText("回答已完成")).toBeNull();
});
it.each(["answer", "context", "http-answer"])("shows the unavailable-index reason for %s", async kind => {
  stubGlobal("fetch", vi.fn(async (url: string) => url.endsWith("/csrf")
    ? { ok: true, status: 200, json: async () => ({ token: "t" }) }
    : kind === "context" || kind === "http-answer"
      ? { ok: false, status: 409, json: async () => ({ error: "retrieval_no_active_index" }) }
      : { ok: true, status: 200, headers: new Headers({ "Content-Type": "text/event-stream" }),
          body: stream([["error", { error: "retrieval_no_active_index" }]]) }));
  render(<QueryPanel kb="kb" onSessionExpired={() => {}} />);
  await waitFor(() => expect((screen.getByRole("button", { name: "发送" }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.change(screen.getByLabelText("问答问题"), { target: { value: "问题" } });
  fireEvent.click(screen.getByRole("button", { name: kind === "context" ? "预览上下文" : "发送" }));
  expect((await screen.findByRole("alert")).textContent).toBe("文档索引尚未就绪，请在文档管理中查看处理状态");
  expect(screen.queryByText("回答已完成")).toBeNull();
});
it.each(["answer", "context"])("shows a specific token budget error for %s", async (kind) => {
  stubGlobal("fetch", vi.fn(async (url: string) => url.endsWith("/csrf")
    ? { ok: true, status: 200, json: async () => ({ token: "t" }) }
    : kind === "context"
      ? { ok: false, status: 400, json: async () => ({ error: "query_budget_too_small" }) }
      : { ok: true, status: 200, headers: new Headers({ "Content-Type": "text/event-stream" }),
          body: stream([["error", { error: "query_budget_too_small" }]]) }));
  render(<QueryPanel kb="kb" onSessionExpired={() => {}} />);
  await waitFor(() => expect((screen.getByRole("button", { name: "发送" }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.change(screen.getByLabelText("问答问题"), { target: { value: "问题" } });
  if (kind === "context") {
    fireEvent.click(screen.getByRole("button", { name: "预览上下文" }));
  } else fireEvent.click(screen.getByRole("button", { name: "发送" }));
  expect((await screen.findByRole("alert")).textContent).toContain("问答 token 预算不足");
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
  stubGlobal("fetch", fetch);
  const { rerender } = render(
    <QueryPanel kb="kb1" onSessionExpired={() => {}} />,
  );
  await waitFor(() => expect((screen.getByRole("button", { name: "发送" }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.change(screen.getByLabelText("问答问题"), {
    target: { value: "私密问题" },
  });
  fireEvent.click(screen.getByRole("button", { name: "发送" }));
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
  stubGlobal(
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
  await waitFor(() => expect((screen.getByRole("button", { name: "Send" }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.change(screen.getByLabelText("Question"), {
    target: { value: "q" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Send" }));
  await waitFor(() => expect(expired).toBe(true));
});
it("keeps repeated questions in the selected conversation and preserves mode and reranking", async () => {
  const bodies: any[] = [];
  stubGlobal("fetch", async (url: string, options?: RequestInit) => {
    if (url.endsWith("/csrf")) return { ok: true, status: 200, json: async () => ({ token: "t" }) };
    bodies.push(JSON.parse(options!.body as string));
    return { ok: true, status: 200, headers: new Headers({ "Content-Type": "text/event-stream" }),
      body: stream([["delta", { text: `回答${bodies.length}` }], ["done", { status: "success", retrieval: evidence, unknownReferenceIds: [] }]]) };
  });
  render(<QueryPanel kb="kb1" onSessionExpired={() => {}} />);
  await screen.findByRole("navigation", { name: "对话历史" });
  fireEvent.change(screen.getByLabelText("问答模式"), { target: { value: "local" } });
  fireEvent.click(screen.getByLabelText("启用重排"));
  for (const question of ["介绍张三", "他的成果呢？"]) {
    await waitFor(() => expect((screen.getByRole("button", { name: "发送" }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.change(screen.getByLabelText("问答问题"), { target: { value: question } });
    fireEvent.click(screen.getByRole("button", { name: "发送" }));
    await screen.findByText(`回答${bodies.length || 1}`);
    await waitFor(() => expect((screen.getByRole("button", { name: "发送" }) as HTMLButtonElement).disabled).toBe(false));
  }
  expect(within(screen.getByRole("log")).getByText("介绍张三")).toBeTruthy(); expect(screen.getByText("他的成果呢？")).toBeTruthy();
  expect(screen.getByText("回答1")).toBeTruthy(); expect(screen.getByText("回答2")).toBeTruthy();
  expect(bodies).toHaveLength(2);
  expect(bodies.every(body => body.conversationId === "conversation" && body.mode === "local" && body.enableRerank)).toBe(true);
  expect(bodies[1].history).toBeUndefined();
  fireEvent.click(screen.getByRole("button", { name: "新建对话" }));
  await waitFor(() => expect(screen.queryByText("回答1")).toBeNull());
  expect(screen.getByRole("form", { name: "发送问句" }).getAttribute("data-position")).toBe("center");
});
it("loads persisted private history and can select a different conversation", async () => {
  vi.stubGlobal("fetch", vi.fn(async (url: string) => ({ ok: true, status: 200,
    json: async () => url.endsWith("/conversations") ? [{ id: "old", title: "旧对话" }, { id: "other", title: "另一对话" }]
      : url.includes("/old/turns") ? [{ id: "turn", sequence: 1, question: "旧问题", answer: "**旧回答**\n\n| 列 |\n| --- |\n| 内容 |", status: "success", result: { retrieval: evidence } }] : [] })));
  render(<QueryPanel kb="kb1" onSessionExpired={() => {}} />);
  const history = within(await screen.findByRole("navigation", { name: "对话历史" }));
  await history.findByRole("button", { name: "旧对话" });
  expect(screen.queryByText("旧回答")).toBeNull();
  expect(screen.getByRole("form", { name: "发送问句" }).getAttribute("data-position")).toBe("center");
  fireEvent.click(history.getByRole("button", { name: "旧对话" }));
  await screen.findByText("旧回答");
  expect(history.getByRole("button", { name: "旧对话" }).getAttribute("aria-current")).toBe("page");
  expect(screen.getByRole("form", { name: "发送问句" }).getAttribute("data-position")).toBe("bottom");
  expect(screen.getByText("旧问题")).toBeTruthy();
  for (const className of ["ml-auto", "w-fit", "max-w-1/2", "text-right", "break-words"]) {
    expect(screen.getByText("旧问题").classList.contains(className)).toBe(true);
  }
  expect(within(screen.getByRole("log")).queryByText("你", { exact: true })).toBeNull();
  expect(within(screen.getByRole("log")).getByText("旧回答")).toBeTruthy();
  expect(screen.getByRole("table")).toBeTruthy();
  fireEvent.click(history.getByRole("button", { name: "另一对话" }));
  await waitFor(() => expect(screen.queryByText("旧回答")).toBeNull());
});

it("moves the centered composer below the conversation on send and honors Enter and IME input", async () => {
  let sends = 0;
  stubGlobal("fetch", async (url: string) => url.endsWith("/csrf")
    ? { ok: true, status: 200, json: async () => ({ token: "t" }) }
    : { ok: true, status: 200, headers: new Headers({ "Content-Type": "text/event-stream" }),
        body: stream([["delta", { text: `回复${++sends}` }], ["done", { status: "success", unknownReferenceIds: [] }]]) });
  render(<QueryPanel kb="kb" onSessionExpired={() => {}} />);
  await waitFor(() => expect((screen.getByRole("button", { name: "发送" }) as HTMLButtonElement).disabled).toBe(false));
  const form = screen.getByRole("form", { name: "发送问句" });
  expect(form.getAttribute("data-position")).toBe("center");
  const input = screen.getByLabelText("问答问题");
  fireEvent.change(input, { target: { value: "一个问题" } });
  fireEvent.keyDown(input, { key: "Enter", shiftKey: true });
  fireEvent.keyDown(input, { key: "Enter", isComposing: true });
  expect(sends).toBe(0);
  fireEvent.keyDown(input, { key: "Enter" });
  expect(form.getAttribute("data-position")).toBe("bottom");
  await screen.findByText("回复1");
  expect(screen.getByLabelText("问答问题")).toBe(input);
});
