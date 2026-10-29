import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import Workspace from "./Workspace";
import i18n from "./i18n";

vi.mock("./QueryPanel", () => ({ default: ({ kb }: { kb: string }) => <h2>问答内容 {kb}</h2> }));
vi.mock("./RetrievalPanel", () => ({ default: () => <h2>检索内容</h2> }));
vi.mock("./IndexPanel", () => ({ default: () => <h2>索引内容</h2> }));
vi.mock("./Models", () => ({ default: () => <h2>模型内容</h2> }));

beforeEach(async () => {
  vi.restoreAllMocks();
  await i18n.changeLanguage("zh");
});

const base = { id: "kb1", name: "项目资料", role: "OWNER", description: "" };
function mockApi(bases = [base], failCreate = false) {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/csrf")) return { ok: true, status: 200, json: async () => ({ token: "csrf" }) };
    if (url === "/api/knowledge-bases") {
      const creating = init?.method === "POST";
      return {
        ok: !creating || !failCreate,
        status: creating && failCreate ? 500 : 200,
        json: async () => creating ? { ...base, name: "新知识库" } : bases,
      };
    }
    return { ok: true, status: 200, json: async () => ({ items: [], total: 0, page: 0, size: 20 }) };
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

it("guides an empty account to create its first base before entering the workspace", async () => {
  const fetch = mockApi([]);
  render(<Workspace admin onSessionExpired={() => {}} />);
  await screen.findByRole("heading", { name: "创建第一个知识库" });
  expect(screen.queryByRole("navigation", { name: "功能菜单" })).toBeNull();
  const input = screen.getByLabelText("知识库名称");
  expect(document.activeElement).toBe(input);
  fireEvent.change(input, { target: { value: "   " } });
  expect((screen.getByRole("button", { name: "创建知识库" }) as HTMLButtonElement).disabled).toBe(true);
  fireEvent.change(input, { target: { value: " 新知识库 " } });
  fireEvent.click(screen.getByRole("button", { name: "创建知识库" }));
  await screen.findByRole("heading", { name: "问答内容 kb1" });
  expect((screen.getByLabelText("当前知识库") as HTMLSelectElement).value).toBe("kb1");
  const creation = fetch.mock.calls.find(([url, init]) => url === "/api/knowledge-bases" && init?.method === "POST");
  expect(JSON.parse(String(creation?.[1]?.body))).toEqual({ name: "新知识库", description: "" });
  expect((creation?.[1]?.headers as Record<string, string>)["X-CSRF-TOKEN"]).toBe("csrf");
});

it("keeps the first-base form and entered name available after a creation failure", async () => {
  mockApi([], true);
  render(<Workspace onSessionExpired={() => {}} />);
  fireEvent.change(await screen.findByLabelText("知识库名称"), { target: { value: "新知识库" } });
  fireEvent.click(screen.getByRole("button", { name: "创建知识库" }));
  await screen.findByRole("alert");
  expect((screen.getByLabelText("知识库名称") as HTMLInputElement).value).toBe("新知识库");
  expect(screen.queryByRole("navigation", { name: "功能菜单" })).toBeNull();
});

it("shows the six ordered sidebar entries and only the selected feature", async () => {
  const fetch = mockApi();
  render(<Workspace admin onSessionExpired={() => {}} />);
  const nav = within(await screen.findByRole("navigation", { name: "功能菜单" }));
  const labels = ["知识库问答", "检索证据", "索引构建与切换", "文档管理", "任务管理", "模型配置"];
  expect(nav.getAllByRole("button").map(button => button.textContent)).toEqual(labels);
  expect(screen.getByRole("heading", { name: "问答内容 kb1" })).toBeTruthy();
  expect(screen.queryByLabelText("文档名称")).toBeNull();
  expect(fetch.mock.calls.some(([url]) => url.includes("/documents") || url.includes("/jobs"))).toBe(false);
  for (const [label, heading] of [["检索证据", "检索内容"], ["索引构建与切换", "索引内容"], ["文档管理", "文档"], ["任务管理", "任务"], ["模型配置", "模型内容"]]) {
    fireEvent.click(nav.getByRole("button", { name: label }));
    await screen.findByRole("heading", { name: heading });
    expect(nav.getByRole("button", { name: label }).getAttribute("aria-current")).toBe("page");
    expect(screen.queryByRole("heading", { name: "问答内容 kb1" })).toBeNull();
  }
  expect(screen.queryByRole("heading", { name: "文档" })).toBeNull();
  expect(screen.queryByRole("heading", { name: "任务" })).toBeNull();
  expect((screen.getByLabelText("当前知识库") as HTMLSelectElement).value).toBe("kb1");
  await i18n.changeLanguage("en");
  await screen.findByRole("navigation", { name: "Features" });
  expect(screen.getByRole("button", { name: "Document management" })).toBeTruthy();
});

it("preserves model administration permissions and switches knowledge base context", async () => {
  mockApi([base, { ...base, id: "kb2", name: "另一个知识库" }]);
  render(<Workspace onSessionExpired={() => {}} />);
  await screen.findByRole("navigation", { name: "功能菜单" });
  expect((screen.getByRole("button", { name: "模型配置" }) as HTMLButtonElement).disabled).toBe(true);
  fireEvent.change(screen.getByLabelText("当前知识库"), { target: { value: "kb2" } });
  await screen.findByRole("heading", { name: "问答内容 kb2" });
  expect(screen.queryByRole("heading", { name: "问答内容 kb1" })).toBeNull();
});

it("shows a load error without mistaking an unavailable base list for an empty account", async () => {
  const fetch = vi.fn(async () => ({ ok: false, status: 500, json: async () => [base] }));
  vi.stubGlobal("fetch", fetch);
  render(<Workspace onSessionExpired={() => {}} />);
  await screen.findByRole("alert");
  expect(screen.queryByRole("heading", { name: "创建第一个知识库" })).toBeNull();
  fetch.mockResolvedValue({ ok: true, status: 200, json: async () => [base] });
  fireEvent.click(screen.getByRole("button", { name: "刷新" }));
  await screen.findByRole("heading", { name: "问答内容 kb1" });
});

it("loads only the active document or task list", async () => {
  const fetch = mockApi();
  render(<Workspace onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "文档管理" }));
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes("/documents?"))).toBe(true));
  expect(fetch.mock.calls.some(([url]) => url.includes("/jobs?"))).toBe(false);
  fetch.mockClear();
  fireEvent.click(screen.getByRole("button", { name: "任务管理" }));
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes("/jobs?"))).toBe(true));
  expect(fetch.mock.calls.some(([url]) => url.includes("/documents?"))).toBe(false);
});
