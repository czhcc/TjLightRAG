import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import DocumentPanel from "./DocumentPanel";
import i18n from "./i18n";

beforeEach(async () => { vi.restoreAllMocks(); await i18n.changeLanguage("zh"); });
function api(errorCode?: string) {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => ({
    ok: true, status: init?.method === "DELETE" ? 204 : 200,
    text: async () => "# 解析后的正文\n<script>不可执行</script>",
    json: async () => url.endsWith("/csrf") ? { token: "csrf" } : url.includes("/documents?") ? {
      items: [{ id: "d1", sourceName: "资料.md", revisionId: "r1", status: errorCode ? "failed" : "indexing", errorCode, chunkCount: 3, sizeBytes: 123 }],
      total: 51, page: 0, size: 20,
    } : {},
  }));
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

it("uploads PDF and Office documents through the existing queue", async () => {
  const fetch = api();
  render(<DocumentPanel kb="kb1" canWrite onSessionExpired={() => {}} />);
  fireEvent.click(screen.getByRole("button", { name: "上传文档" }));
  const picker = screen.getByLabelText("选择文件");
  expect(picker.getAttribute("accept")).toContain(".pdf");
  expect(picker.getAttribute("accept")).toContain(".docx");
  fireEvent.change(picker, { target: { files: [
    new File(["pdf bytes"], "report.pdf"), new File(["docx bytes"], "report.docx"),
    new File(["xlsx bytes"], "report.xlsx"), new File(["pptx bytes"], "slides.pptx"),
  ] } });
  fireEvent.click(screen.getByRole("button", { name: "开始上传" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(fetch.mock.calls.filter(([url]) => url.endsWith("/upload"))).toHaveLength(4);
});

it.each([
  ["document_parse_failed", "文档解析失败，请检查文件是否损坏或另存为受支持的格式后重新上传。"],
  ["document_format_mismatch", "文件内容与扩展名不一致，请确认文件格式后重新上传。"],
  ["document_encrypted", "文档已加密，请解除密码保护后重新上传。"],
  ["model_timeout", "模型调用超时，请增大所用模型的超时秒数，保存配置后重新构建索引。"],
  ["model_truncated_or_invalid", "模型输出被截断或返回结果不完整，请检查最大输出 token、上下文限制及模型服务后点击运行重试。"],
  ["model_http_error", "模型服务返回 HTTP 错误，请检查服务地址、访问权限及服务端日志后点击运行重试。"],
  ["extraction_invalid_json", "模型返回的抽取 JSON 不符合实体或关系格式，请检查文本模型后点击运行重试。"],
  ["extraction_invalid_entity_type", "抽取实体的类型为空、超长或包含禁用名称，请点击运行重试。"],
  ["extraction_invalid_attributes", "抽取实体的 attributes 必须是属性数组（兼容旧对象格式），且最多包含 32 项。请点击运行重试。"],
  ["extraction_invalid_attribute_value", "抽取属性的名称、类型或值不合法，或值与声明类型不符，请点击运行重试。"],
  ["extraction_self_relation", "模型返回了起点和终点相同的自身关系，请点击运行重试。"],
  ["extraction_invalid_field_name", "抽取实体的 name 缺失、为空、类型错误或超长，请点击运行重试。"],
  ["extraction_invalid_field_type", "抽取记录的 type 缺失、为空、类型错误或超长，请点击运行重试。"],
  ["extraction_invalid_field_description", "抽取记录的 description 缺失、为空、类型错误或超长，请点击运行重试。"],
  ["extraction_invalid_field_source", "抽取关系的 source 缺失、为空、类型错误或超长，请点击运行重试。"],
  ["extraction_invalid_field_target", "抽取关系的 target 缺失、为空、类型错误或超长，请点击运行重试。"],
  ["extraction_invalid_field_keywords", "抽取关系的 keywords 缺失、为空、类型错误或超长，请点击运行重试。"],
  ["extraction_incomplete", "模型未返回完整的抽取 JSON，请检查输出限制后点击运行重试。"],
  ["model_stream_incomplete", "模型输出流提前结束，请检查模型服务后点击运行重试。"],
  ["extraction_record_limit", "抽取结果超过对象数量或大小限制，请减小文档分块后重建。"],
  ["index_version_unsupported", "抽取协议已更新，请点击运行重新构建索引。"],
  ["index_document_scope_incompatible", "其他已索引文档使用不同的模型或抽取协议，无法复用。请先恢复兼容的模型配置；单文档运行不会自动重建其他文档。"],
  ["unknown_failure", "处理失败，请检查模型配置与连接后点击运行重试。"],
])("shows the actual indexing failure for %s", async (code, message) => {
  api(code);
  render(<DocumentPanel kb="kb1" canWrite onSessionExpired={() => {}} />);
  const row = (await screen.findByText("资料.md")).closest("tr")!;
  expect(within(row).getByText(message)).toBeTruthy();
  expect((within(row).getByRole("button", { name: "运行" }) as HTMLButtonElement).disabled).toBe(false);
});

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

it.each(["选择文件", "选择目录"])("retains live file selections after clearing %s", async label => {
  const fetch = api();
  render(<DocumentPanel kb="kb1" canWrite onSessionExpired={() => {}} />);
  await screen.findByText("资料.md");
  fireEvent.click(screen.getByRole("button", { name: "上传文档" }));
  const input = screen.getByLabelText(label) as HTMLInputElement;
  const files: File[] = [];
  // Native FileList contents disappear when the input's value is reset.
  Object.defineProperty(input, "files", { configurable: true, get: () => files });
  Object.defineProperty(input, "value", { configurable: true, get: () => "", set: () => { files.length = 0; } });
  act(() => {
    files.push(new File(["first"], "first.txt"));
    fireEvent.change(input);
    files.push(new File(["second"], "second.txt"));
    fireEvent.change(input);
  });
  const dialog = screen.getByRole("dialog");
  expect(within(dialog).getByText("first.txt")).toBeTruthy();
  expect(within(dialog).getByText("second.txt")).toBeTruthy();
  expect((within(dialog).getByRole("button", { name: "开始上传" }) as HTMLButtonElement).disabled).toBe(false);
  fireEvent.click(within(dialog).getByRole("button", { name: "开始上传" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  const uploads = fetch.mock.calls.filter(([url]) => url.endsWith("/upload"));
  expect(uploads.map(([, init]) => ((init?.body as FormData).get("file") as File).name))
    .toEqual(["first.txt", "second.txt"]);
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
