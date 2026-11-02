import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import DocumentPanel from "./DocumentPanel";
import i18n from "./i18n";

beforeEach(async () => { vi.restoreAllMocks(); await i18n.changeLanguage("zh"); });
function api() {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => ({
    ok: true, status: init?.method === "DELETE" ? 204 : 200,
    text: async () => "# 解析后的正文\n<script>不可执行</script>",
    json: async () => url.endsWith("/csrf") ? { token: "csrf" } : url.includes("/documents?") ? {
      items: [{ id: "d1", sourceName: "资料.md", revisionId: "r1", status: "indexing", chunkCount: 3, sizeBytes: 123 }],
      total: 51, page: 0, size: 20,
    } : {},
  }));
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

it("shows a paged document table with stage controls and parsed text preview", async () => {
  const fetch = api();
  render(<DocumentPanel kb="kb1" canWrite onSessionExpired={() => {}} />);
  const table = await screen.findByRole("table");
  await within(table).findByText("资料.md");
  expect(within(table).getByRole("columnheader", { name: "状态" })).toBeTruthy();
  expect(screen.getByLabelText("正在处理").className).toContain("animate-spin");
  fireEvent.click(screen.getByRole("button", { name: "暂停" }));
  await waitFor(() => expect(fetch.mock.calls.some(([url, init]) => url.endsWith("/d1/pause") && init?.method === "POST")).toBe(true));
  fireEvent.click(screen.getByRole("button", { name: "查看" }));
  await screen.findByText(/解析后的正文/);
  expect(document.querySelector("script")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "关闭" }));
  fireEvent.change(screen.getByLabelText("每页条数"), { target: { value: "50" } });
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes("page=0&size=50"))).toBe(true));
  fireEvent.click(screen.getByRole("button", { name: "下一页" }));
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes("page=1&size=50"))).toBe(true));
});

it("opens text and batch upload dialogs and preserves multipart CSRF protection", async () => {
  const fetch = api();
  render(<DocumentPanel kb="kb1" canWrite onSessionExpired={() => {}} />);
  expect(screen.queryByRole("dialog")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "提交文本" }));
  const dialog = screen.getByRole("dialog");
  fireEvent.change(within(dialog).getByLabelText("文档名称"), { target: { value: "文本.md" } });
  fireEvent.change(within(dialog).getByLabelText("文本内容"), { target: { value: "知识库正文" } });
  fireEvent.click(within(dialog).getByRole("button", { name: "提交文档" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(fetch.mock.calls.some(([url, init]) => url.endsWith("/text") && String(init?.body).includes("知识库正文"))).toBe(true);
  fireEvent.click(screen.getByRole("button", { name: "上传文档" }));
  const upload = screen.getByRole("dialog");
  expect(within(upload).getByLabelText("选择目录").hasAttribute("webkitdirectory")).toBe(true);
  fireEvent.change(within(upload).getByLabelText("选择文件"), { target: { files: [new File(["a"], "a.txt"), new File(["b"], "b.md")] } });
  fireEvent.click(within(upload).getByRole("button", { name: "开始上传" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  const uploads = fetch.mock.calls.filter(([url, init]) => url.endsWith("/upload") && init?.method === "POST");
  expect(uploads).toHaveLength(2);
  for (const [, init] of uploads) {
    expect(init?.body).toBeInstanceOf(FormData);
    expect((init?.headers as Record<string, string>)["X-CSRF-TOKEN"]).toBe("csrf");
    expect((init?.headers as Record<string, string>)["Content-Type"]).toBeUndefined();
  }
});

it("keeps viewing available to readers while disabling mutations", async () => {
  api();
  render(<DocumentPanel kb="kb1" canWrite={false} onSessionExpired={() => {}} />);
  await screen.findByText("资料.md");
  for (const name of ["上传文档", "提交文本", "重建", "删除", "暂停"]) {
    expect((screen.getByRole("button", { name }) as HTMLButtonElement).disabled).toBe(true);
  }
  expect((screen.getByRole("button", { name: "查看" }) as HTMLButtonElement).disabled).toBe(false);
});

it("retries only failed uploads and asks for confirmation before deleting", async () => {
  let failures = true;
  const uploads: string[] = [];
  const baseApi = api();
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/upload")) {
      const name = ((init?.body as FormData).get("file") as File).name;
      uploads.push(name);
      return { ok: !(failures && name === "bad.md"), status: failures && name === "bad.md" ? 500 : 200, json: async () => ({}) };
    }
    return baseApi(url, init);
  });
  vi.stubGlobal("fetch", fetch);
  render(<DocumentPanel kb="kb1" canWrite onSessionExpired={() => {}} />);
  await screen.findByText("资料.md");
  fireEvent.click(screen.getByRole("button", { name: "删除" }));
  expect(fetch.mock.calls.some(([, init]) => init?.method === "DELETE")).toBe(false);
  fireEvent.click(screen.getByRole("button", { name: "关闭" }));
  expect(fetch.mock.calls.some(([, init]) => init?.method === "DELETE")).toBe(false);
  fireEvent.click(screen.getByRole("button", { name: "上传文档" }));
  fireEvent.change(screen.getByLabelText("选择文件"), { target: { files: [new File(["a"], "good.md"), new File(["b"], "bad.md")] } });
  fireEvent.click(screen.getByRole("button", { name: "开始上传" }));
  await screen.findByText("上传失败");
  expect(screen.getByRole("dialog")).toBeTruthy();
  failures = false;
  fireEvent.click(screen.getByRole("button", { name: "开始上传" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(uploads).toEqual(["good.md", "bad.md", "bad.md"]);
});
