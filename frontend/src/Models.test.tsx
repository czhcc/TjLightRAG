import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import Models from "./Models";
import i18n from "./i18n";
const rows = [
  { id: "chat", name: "文本服务", kind: "CHAT", baseUrl: "https://example.com/v1", model: "chat-model", keyConfigured: true, dimensions: 0, contextTokens: 26000, maxOutputTokens: 20000, timeoutSeconds: 30, extraBody: { chat_template_kwargs: { enable_thinking: false } } },
  { id: "embed", name: "向量服务", kind: "EMBEDDING", baseUrl: "https://example.com/v1", model: "embed-model", keyConfigured: false, dimensions: 3, contextTokens: 16000, maxOutputTokens: 2000, timeoutSeconds: 30 },
  { id: "rerank", name: "重排服务", kind: "RERANK", baseUrl: "https://example.com/v1/rerank", model: "rerank-model", keyConfigured: false, dimensions: 0, contextTokens: 16000, maxOutputTokens: 2000, timeoutSeconds: 30 },
];
function api(conflict = false) {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => ({
    ok: !(init?.method === "DELETE" && conflict), status: init?.method === "DELETE" ? conflict ? 409 : 204 : 200,
    json: async () => url.endsWith("/csrf") ? { token: "csrf" } : url.endsWith("/model-bindings") ? { answer: "chat" } :
      url.endsWith("/models") || url.endsWith("/model-options") ? rows : url.endsWith("/test") ? { elapsedMs: 12 } : { masterKeyConfigured: true },
  }));
  vi.stubGlobal("fetch", fetch); return fetch;
}
beforeEach(async () => { vi.restoreAllMocks(); await i18n.changeLanguage("zh"); });
function view(admin = true) { return render(<Models kb="kb1" kbName="知识库一" admin={admin} canBind onSessionExpired={() => {}} />); }
it("lists models under three tabs and opens type-specific creation dialogs", async () => {
  api(); view();
  await screen.findByText("文本服务");
  expect(screen.queryByLabelText("配置名称")).toBeNull();
  expect(screen.queryByText(/版本历史/)).toBeNull();
  expect(within(screen.getByRole("table")).getByRole("button", { name: "修改" })).toBeTruthy();
  fireEvent.click(screen.getByRole("tab", { name: "向量模型" }));
  expect(screen.getByText("向量服务")).toBeTruthy();
  expect(screen.queryByText("文本服务")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "新建" }));
  const dialog = within(screen.getByRole("dialog"));
  expect(dialog.getByLabelText("向量维度")).toBeTruthy();
  expect(dialog.queryByLabelText("最大输出 token")).toBeNull();
  fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
  expect(screen.queryByRole("dialog")).toBeNull();
  fireEvent.click(screen.getByRole("tab", { name: "重排模型" }));
  expect(screen.getByText("重排服务")).toBeTruthy();
});
it("stacks the extension label above its full-width JSON editor", async () => {
  api(); view(); await screen.findByText("文本服务");
  fireEvent.click(screen.getByRole("button", { name: "修改" }));
  const editor = within(screen.getByRole("dialog")).getByLabelText("扩展参数（JSON）");
  expect(editor.parentElement?.classList.contains("grid")).toBe(true);
  expect(editor.previousElementSibling?.textContent).toBe("扩展参数（JSON）");
  expect(editor.classList.contains("w-full")).toBe(true);
});
it("aligns the save button to the right across the full form row", async () => {
  api(); view(); await screen.findByText("文本服务");
  fireEvent.click(screen.getByRole("button", { name: "修改" }));
  const actions = within(screen.getByRole("dialog")).getByRole("button", { name: "保存模型配置" }).parentElement!;
  expect(actions.classList.contains("md:col-span-2")).toBe(true);
  expect(actions.classList.contains("justify-end")).toBe(true);
});
it("gives the clear-key checkbox its own row with a compact control", async () => {
  api(); view(); await screen.findByText("文本服务");
  fireEvent.click(screen.getByRole("button", { name: "修改" }));
  const checkbox = within(screen.getByRole("dialog")).getByRole("checkbox", { name: "清除已保存的 API 密钥" });
  expect(checkbox.parentElement?.classList.contains("md:col-span-2")).toBe(true);
  expect(checkbox.classList.contains("w-auto")).toBe(true);
  expect(checkbox.classList.contains("mt-0")).toBe(true);
});
it("edits the same model with a blank preserved key and native extension parameters", async () => {
  const fetch = api(); view(); await screen.findByText("文本服务");
  fireEvent.click(screen.getByRole("button", { name: "修改" }));
  const dialog = within(screen.getByRole("dialog"));
  expect((dialog.getByLabelText("API 密钥") as HTMLInputElement).value).toBe("");
  fireEvent.change(dialog.getByLabelText("最大输出 token"), { target: { value: "19000" } });
  fireEvent.click(dialog.getByRole("button", { name: "保存模型配置" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  const call = fetch.mock.calls.find(([url, init]) => url.endsWith("/models/chat") && init?.method === "PUT")!;
  const body = JSON.parse(call[1]!.body as string);
  expect(body.maxOutputTokens).toBe(19000);
  expect(body.extraBody.chat_template_kwargs.enable_thinking).toBe(false);
  expect(body.apiKey).toBe("");
});
it("creates from a modal and rejects invalid extension JSON", async () => {
  const fetch = api(); view(); await screen.findByText("文本服务");
  fireEvent.click(screen.getByRole("button", { name: "新建" }));
  const dialog = within(screen.getByRole("dialog"));
  for (const [label, value] of [["配置名称", "新模型"], ["服务地址", "https://example.com/v1"], ["模型名称", "new-model"], ["API 密钥", "new-secret"]])
    fireEvent.change(dialog.getByLabelText(label), { target: { value } });
  fireEvent.change(dialog.getByLabelText("扩展参数（JSON）"), { target: { value: "[]" } });
  expect((dialog.getByRole("button", { name: "保存模型配置" }) as HTMLButtonElement).disabled).toBe(true);
  fireEvent.change(dialog.getByLabelText("扩展参数（JSON）"), { target: { value: "{}" } });
  fireEvent.click(dialog.getByRole("button", { name: "保存模型配置" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(fetch.mock.calls.some(([url, init]) => url.endsWith("/models") && init?.method === "POST")).toBe(true);
  expect(screen.queryByDisplayValue("new-secret")).toBeNull();
});
it.each([false, true])("confirms model deletion and reports knowledge-base references: %s", async conflict => {
  const fetch = api(conflict); view(); await screen.findByText("文本服务");
  fireEvent.click(screen.getByRole("button", { name: "删除" }));
  expect(fetch.mock.calls.some(([, init]) => init?.method === "DELETE")).toBe(false);
  fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "确认删除" }));
  if (conflict) expect((await within(screen.getByRole("dialog")).findByRole("alert")).textContent).toContain("知识库用途绑定");
  else await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
});
it("opens knowledge-base bindings and saves current model IDs only to that base", async () => {
  const fetch = api(); view(); await screen.findByText("文本服务");
  fireEvent.click(screen.getByRole("button", { name: "模型用途绑定" }));
  const dialog = within(screen.getByRole("dialog"));
  expect(dialog.getByText(/知识库一/)).toBeTruthy();
  fireEvent.change(dialog.getByLabelText("向量生成"), { target: { value: "embed" } });
  fireEvent.click(dialog.getByRole("button", { name: "保存用途绑定" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  const call = fetch.mock.calls.find(([url, init]) => url === "/api/knowledge-bases/kb1/model-bindings" && init?.method === "PUT")!;
  expect(JSON.parse(call[1]!.body as string)).toMatchObject({ answer: "chat", embedding: "embed" });
  expect(fetch.mock.calls.some(([url]) => url.includes("/versions") || url === "/api/admin/models/bindings")).toBe(false);
});
it("lets a knowledge-base owner bind catalog choices without exposing administrator settings", async () => {
  const fetch = api(); view(false); await screen.findByText("文本服务");
  expect(screen.queryByRole("button", { name: "新建" })).toBeNull();
  expect(fetch.mock.calls.some(([url]) => url.startsWith("/api/admin/"))).toBe(false);
  fireEvent.click(screen.getByRole("button", { name: "模型用途绑定" }));
  expect(screen.getByRole("dialog")).toBeTruthy();
});
it("tests the current model configuration", async () => {
  const fetch = api(); view(); await screen.findByText("文本服务");
  fireEvent.click(screen.getByRole("button", { name: "连接测试" }));
  await screen.findByText(/12/);
  expect(fetch.mock.calls.some(([url, init]) => url === "/api/admin/models/chat/test" && init?.method === "POST")).toBe(true);
});
it("discards unsaved binding changes when closing the dialog", async () => {
  api(); view(); await screen.findByText("文本服务");
  fireEvent.click(screen.getByRole("button", { name: "模型用途绑定" }));
  fireEvent.change(within(screen.getByRole("dialog")).getByLabelText("向量生成"), { target: { value: "embed" } });
  fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
  fireEvent.click(screen.getByRole("button", { name: "模型用途绑定" }));
  expect((within(screen.getByRole("dialog")).getByLabelText("向量生成") as HTMLSelectElement).value).toBe("");
});
it("clears modal drafts and loads bindings when switching knowledge bases", async () => {
  const fetch = api(); const page = view(); await screen.findByText("文本服务");
  fireEvent.click(screen.getByRole("button", { name: "修改" }));
  fireEvent.change(within(screen.getByRole("dialog")).getByLabelText("API 密钥"), { target: { value: "draft-secret" } });
  page.rerender(<Models kb="kb2" kbName="知识库二" admin canBind onSessionExpired={() => {}} />);
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  await screen.findByText("文本服务");
  fireEvent.click(screen.getByRole("button", { name: "模型用途绑定" }));
  expect(within(screen.getByRole("dialog")).getByText(/知识库二/)).toBeTruthy();
  expect(screen.queryByDisplayValue("draft-secret")).toBeNull();
  expect(fetch.mock.calls.some(([url]) => url === "/api/knowledge-bases/kb2/model-bindings")).toBe(true);
});
