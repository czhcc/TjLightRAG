import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import Workspace from "./Workspace";
import i18n from "./i18n";
beforeEach(async () => {
  vi.restoreAllMocks();
  await i18n.changeLanguage("zh");
});
const base = { id: "kb1", name: "知识库一", description: "", role: "OWNER" };
function mockApi(role = "OWNER") {
  let accepted = false;
  const fetch = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith("/csrf"))
      return { ok: true, status: 200, json: async () => ({ token: "csrf" }) };
    if (url === "/api/knowledge-bases")
      return { ok: true, status: 200, json: async () => [{ ...base, role }] };
    if (url.includes("/jobs/job1"))
      return {
        ok: true,
        status: 200,
        json: async () => ({
          id: "job1",
          documentId: "doc1",
          revisionId: "rev1",
          status: "failed",
          attempts: 1,
          errorCode: "invalid_encoding",
          events: [],
        }),
      };
    if (options?.method === "POST") {
      accepted = true;
      return {
        ok: true,
        status: 202,
        json: async () => ({
          documentId: "doc1",
          revisionId: "rev1",
          jobId: "job1",
          duplicate: true,
        }),
      };
    }
    return {
      ok: true,
      status: 200,
      json: async () => ({
        items: accepted
          ? [
              {
                id: "doc1",
                sourceName: "note.md",
                revisionId: "rev1",
                jobId: "job1",
                status: "failed",
                chunkCount: 0,
              },
            ]
          : [],
        total: accepted ? 1 : 0,
        page: 0,
        size: 20,
      }),
    };
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}
it("submits pasted text with CSRF and exposes task failure/retry controls", async () => {
  const fetch = mockApi();
  render(<Workspace onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "知识库一" }));
  fireEvent.change(screen.getByLabelText("文档名称"), {
    target: { value: "note.md" },
  });
  fireEvent.change(screen.getByLabelText("文本内容"), {
    target: { value: "# Title\n知识🙂" },
  });
  fireEvent.click(screen.getByRole("button", { name: "提交文档" }));
  await screen.findByText("该文档已存在，已返回原任务");
  await screen.findByText("编码无效，请上传 UTF-8 文本");
  expect(screen.getByRole("button", { name: "重试任务" })).toBeTruthy();
  const request = fetch.mock.calls.find(
    ([url, options]) =>
      url.endsWith("/documents/text") && options?.method === "POST",
  );
  expect(
    (request?.[1]?.headers as Record<string, string>)["X-CSRF-TOKEN"],
  ).toBe("csrf");
});
it("readers cannot submit or retry, and language changes update workspace labels", async () => {
  mockApi("READER");
  render(<Workspace onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "知识库一" }));
  expect(
    (screen.getByRole("button", { name: "提交文档" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  expect(
    (screen.getByRole("button", { name: "上传文件" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  await i18n.changeLanguage("en");
  await screen.findByRole("heading", { name: "Documents" });
});
it("expires the session rather than showing another knowledge base on 401", async () => {
  const expired = vi.fn();
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => ({ ok: false, status: 401 })),
  );
  render(<Workspace onSessionExpired={expired} />);
  await waitFor(() => expect(expired).toHaveBeenCalled());
});

it("uploads a file without forcing a multipart Content-Type", async () => {
  const fetch = mockApi();
  render(<Workspace onSessionExpired={() => {}} />);
  await screen.findByRole("button", { name: "知识库一" });
  const file = new File(["# Heading\n知识🙂"], "upload.md", {
    type: "text/markdown",
  });
  fireEvent.change(screen.getByLabelText("选择文件"), {
    target: { files: [file] },
  });
  fireEvent.click(screen.getByRole("button", { name: "上传文件" }));
  await screen.findByText("该文档已存在，已返回原任务");
  const call = fetch.mock.calls.find(
    ([url, options]) =>
      url.includes("/documents/upload") && options?.method === "POST",
  );
  expect(call?.[1]?.body).toBeInstanceOf(FormData);
  expect(
    (call?.[1]?.headers as Record<string, string>)["Content-Type"],
  ).toBeUndefined();
});

it("persists cancellation and explicit retry through protected task requests", async () => {
  let status = "pending";
  const job = () => ({
    id: "job1",
    documentId: "doc1",
    revisionId: "rev1",
    sourceName: "note.md",
    status,
    attempts: 1,
    maxAttempts: 3,
    cancelRequested: false,
    events: [],
  });
  const fetch = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith("/csrf"))
      return { ok: true, status: 200, json: async () => ({ token: "csrf" }) };
    if (url === "/api/knowledge-bases")
      return { ok: true, status: 200, json: async () => [base] };
    if (url.endsWith("/cancel")) status = "cancelled";
    if (url.endsWith("/retry")) status = "pending";
    if (url.includes("/jobs/job1"))
      return { ok: true, status: 200, json: async () => job() };
    return {
      ok: true,
      status: 200,
      json: async () => ({
        items: url.includes("/documents")
          ? [
              {
                id: "doc1",
                sourceName: "note.md",
                jobId: "job1",
                status,
                chunkCount: 0,
              },
            ]
          : [job()],
        total: 1,
        page: 0,
        size: 20,
      }),
    };
  });
  vi.stubGlobal("fetch", fetch);
  render(<Workspace onSessionExpired={() => {}} />);
  fireEvent.click(
    (await screen.findAllByRole("button", { name: "note.md" }))[0],
  );
  fireEvent.click(await screen.findByRole("button", { name: "取消任务" }));
  fireEvent.click(await screen.findByRole("button", { name: "重试任务" }));
  await screen.findByRole("button", { name: "取消任务" });
  expect(
    fetch.mock.calls.filter(
      ([url, options]) => url.endsWith("/retry") && options?.method === "POST",
    ),
  ).toHaveLength(1);
});

it("renders Markdown and chunk source literally rather than executing embedded HTML", async () => {
  const body = "<script>unsafe()</script>\n知识🙂";
  const job = {
    id: "job1",
    documentId: "doc1",
    revisionId: "rev1",
    sourceName: "safe.md",
    status: "processed",
    attempts: 1,
    maxAttempts: 3,
    cancelRequested: false,
    events: [],
  };
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      if (url === "/api/knowledge-bases")
        return { ok: true, status: 200, json: async () => [base] };
      if (url.includes("/jobs/job1"))
        return { ok: true, status: 200, json: async () => job };
      if (url.includes("/content"))
        return { ok: true, status: 200, text: async () => body };
      if (url.includes("/chunks"))
        return {
          ok: true,
          status: 200,
          json: async () => ({
            items: [
              {
                id: "c1",
                order: 0,
                content: body,
                tokens: 12,
                sourceStart: 0,
                sourceEnd: Array.from(body).length,
                headings: ["标题"],
              },
            ],
            total: 1,
            page: 0,
            size: 20,
          }),
        };
      return {
        ok: true,
        status: 200,
        json: async () => ({
          items: url.includes("/documents")
            ? [
                {
                  id: "doc1",
                  sourceName: "safe.md",
                  jobId: "job1",
                  status: "processed",
                  chunkCount: 1,
                },
              ]
            : [job],
          total: 1,
          page: 0,
          size: 20,
        }),
      };
    }),
  );
  render(<Workspace onSessionExpired={() => {}} />);
  fireEvent.click(
    (await screen.findAllByRole("button", { name: "safe.md" }))[0],
  );
  fireEvent.click(
    await screen.findByRole("button", { name: "查看原文与分块" }),
  );
  await screen.findByRole("heading", { name: "解析原文" });
  expect(document.querySelector("script")).toBeNull();
  expect(screen.getAllByText(/<script>unsafe\(\)<\/script>/).length).toBe(2);
});
