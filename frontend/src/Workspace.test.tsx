import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import Workspace from "./Workspace";
import i18n from "./i18n";
beforeEach(async () => { vi.restoreAllMocks(); await i18n.changeLanguage("zh"); });
const base = { id: "kb1", name: "知识库一", description: "", role: "OWNER" };
function mockApi(role = "OWNER") {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => ({
    ok: true, status: init?.method === "POST" ? 202 : 200,
    json: async () => url.endsWith("/csrf") ? { token: "csrf" } :
      url === "/api/knowledge-bases" ? [{ ...base, role }] : url.endsWith("/conversations") ? [] : { items: [], total: 0, page: 0, size: 20 },
  }));
  vi.stubGlobal("fetch", fetch); return fetch;
}
it("submits pasted text from a modal and returns to the document list", async () => {
  const fetch = mockApi(); render(<Workspace onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "文档管理" }));
  expect(screen.queryByLabelText("文档名称")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "提交文本" }));
  const dialog = within(screen.getByRole("dialog"));
  fireEvent.change(dialog.getByLabelText("文档名称"), { target: { value: "note.md" } });
  fireEvent.change(dialog.getByLabelText("文本内容"), { target: { value: "# Title\n知识🙂" } });
  fireEvent.click(dialog.getByRole("button", { name: "提交文档" }));
  await screen.findByText("文档已提交，后台将自动分块并构建索引。");
  expect(screen.queryByRole("dialog")).toBeNull();
  const call = fetch.mock.calls.find(([url, init]) => url.endsWith("/documents/text") && init?.method === "POST");
  expect((call?.[1]?.headers as Record<string, string>)["X-CSRF-TOKEN"]).toBe("csrf");
  expect(fetch.mock.calls.some(([url]) => url.includes("/indexes") || url.includes("/jobs"))).toBe(false);
});
it("readers cannot open mutation dialogs and language changes update the list", async () => {
  mockApi("READER"); render(<Workspace onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "文档管理" }));
  for (const name of ["提交文本", "上传文档"]) expect((screen.getByRole("button", { name }) as HTMLButtonElement).disabled).toBe(true);
  await i18n.changeLanguage("en");
  await screen.findByRole("heading", { name: "Documents" });
});
it("expires the session rather than showing another knowledge base on 401", async () => {
  const expired = vi.fn(); vi.stubGlobal("fetch", vi.fn(async () => ({ ok: false, status: 401 })));
  render(<Workspace onSessionExpired={expired} />);
  await waitFor(() => expect(expired).toHaveBeenCalled());
});
it("clears document dialogs and input when changing knowledge bases", async () => {
  mockApi();
  const fetch = vi.mocked(globalThis.fetch);
  fetch.mockImplementation(async (input) => ({ ok: true, status: 200, json: async () => String(input) === "/api/knowledge-bases" ?
    [base, { ...base, id: "kb2", name: "第二知识库" }] : String(input).endsWith("/conversations") ? [] : { items: [], total: 0 } }) as Response);
  render(<Workspace onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "文档管理" }));
  fireEvent.click(screen.getByRole("button", { name: "提交文本" }));
  fireEvent.change(screen.getByLabelText("文本内容"), { target: { value: "private draft" } });
  fireEvent.change(screen.getByLabelText("当前知识库"), { target: { value: "kb2" } });
  expect(screen.queryByRole("dialog")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "提交文本" }));
  expect((screen.getByLabelText("文本内容") as HTMLTextAreaElement).value).toBe("");
});
