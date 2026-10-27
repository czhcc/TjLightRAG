import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { beforeEach, it, expect, vi } from "vitest";
import RetrievalPanel from "./RetrievalPanel";
import i18n from "./i18n";
beforeEach(async () => {
  vi.restoreAllMocks();
  await i18n.changeLanguage("zh");
});
it("posts the selected mode with CSRF and renders evidence instead of a generated answer", async () => {
  const calls: { url: string; options?: RequestInit }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, options?: RequestInit) => {
      calls.push({ url, options });
      return {
        ok: true,
        status: 200,
        json: async () =>
          url.endsWith("/csrf")
            ? { token: "csrf" }
            : {
                status: "success",
                mode: "mix",
                generationId: "gen1",
                entities: [],
                relations: [],
                chunks: [
                  {
                    chunk_id: "c1",
                    content: "知识图谱🙂",
                    reference_id: "1",
                    sources: [],
                  },
                ],
                references: [{ reference_id: "1", file_path: "来源.md" }],
                context: "evidence",
                metadata: {
                  budget: { totalTokens: 100 },
                  branches: ["entities", "relations", "chunks"],
                },
              },
      };
    }),
  );
  render(<RetrievalPanel kb="kb1" onSessionExpired={() => {}} />);
  fireEvent.change(screen.getByLabelText("检索问题"), {
    target: { value: "知识图谱是什么？" },
  });
  fireEvent.change(screen.getByLabelText("检索模式"), {
    target: { value: "mix" },
  });
  fireEvent.click(screen.getByRole("button", { name: "执行检索" }));
  await screen.findByText("知识图谱🙂");
  expect(
    calls.some(
      (c) =>
        c.url === "/api/knowledge-bases/kb1/retrieval" &&
        JSON.parse(String(c.options?.body)).mode === "mix",
    ),
  ).toBe(true);
  expect(screen.getByText("来源.md")).toBeTruthy();
  expect(screen.queryByText("生成回答")).toBeNull();
});
it("does not turn a backend failure into an empty success", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => ({
      ok: url.endsWith("/csrf"),
      status: url.endsWith("/csrf") ? 200 : 503,
      json: async () => ({ token: "csrf" }),
    })),
  );
  render(<RetrievalPanel kb="kb1" onSessionExpired={() => {}} />);
  fireEvent.change(screen.getByLabelText("检索问题"), {
    target: { value: "question" },
  });
  fireEvent.click(screen.getByRole("button", { name: "执行检索" }));
  await screen.findByRole("alert");
  expect(screen.queryByText("没有找到符合条件的内容")).toBeNull();
});
it("highlights exact source codepoints including emoji", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => ({
      ok: true,
      status: 200,
      json: async () =>
        url.endsWith("/csrf")
          ? { token: "csrf" }
          : url.includes("/source?")
            ? {
                sourceName: "原文.md",
                excerpt: "前🙂知识🙂后",
                excerptStart: 99,
                sourceStart: 100,
                sourceEnd: 104,
              }
            : {
                status: "success",
                mode: "naive",
                generationId: "g1",
                entities: [],
                relations: [],
                chunks: [
                  {
                    chunk_id: "c1",
                    content: "🙂知识🙂",
                    reference_id: "1",
                    sources: [
                      {
                        sourceName: "原文.md",
                        documentId: "d1",
                        revisionId: "r1",
                        chunkOrder: 0,
                        sourceStart: 100,
                        sourceEnd: 104,
                        headings: [],
                      },
                    ],
                  },
                ],
                references: [],
                context: "",
                metadata: {
                  branches: ["chunks"],
                  budget: { totalTokens: 100, maxTotalTokens: 16000 },
                },
              },
    })),
  );
  render(<RetrievalPanel kb="kb1" onSessionExpired={() => {}} />);
  fireEvent.change(screen.getByLabelText("检索问题"), {
    target: { value: "来源" },
  });
  fireEvent.click(screen.getByRole("button", { name: "执行检索" }));
  fireEvent.click(
    await screen.findByRole("button", { name: "查看 原文.md · 第 1 块来源" }),
  );
  await waitFor(() =>
    expect(document.querySelector("mark")?.textContent).toBe("🙂知识🙂"),
  );
});
it("clears evidence and drafts when switching knowledge bases", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => ({
      ok: true,
      status: 200,
      json: async () =>
        url.endsWith("/csrf")
          ? { token: "csrf" }
          : {
              status: "success",
              mode: "naive",
              generationId: "g1",
              entities: [],
              relations: [],
              chunks: [
                {
                  chunk_id: "c1",
                  content: "旧知识库内容",
                  reference_id: "1",
                  sources: [],
                },
              ],
              references: [],
              context: "",
              metadata: {
                branches: ["chunks"],
                budget: { totalTokens: 100, maxTotalTokens: 16000 },
              },
            },
    })),
  );
  const { rerender } = render(
    <RetrievalPanel kb="kb1" onSessionExpired={() => {}} />,
  );
  fireEvent.change(screen.getByLabelText("检索问题"), {
    target: { value: "旧问题" },
  });
  fireEvent.click(screen.getByRole("button", { name: "执行检索" }));
  await screen.findByText("旧知识库内容");
  rerender(<RetrievalPanel kb="kb2" onSessionExpired={() => {}} />);
  await waitFor(() => expect(screen.queryByText("旧知识库内容")).toBeNull());
  expect((screen.getByLabelText("检索问题") as HTMLTextAreaElement).value).toBe(
    "",
  );
});
