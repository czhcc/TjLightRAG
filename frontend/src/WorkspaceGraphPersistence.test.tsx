import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import Workspace from "./Workspace";
import i18n from "./i18n";

vi.mock("./QueryPanel", () => ({ default: () => <h2>问答页面</h2> }));
vi.mock("./Models", () => ({ default: () => <h2>模型页面</h2> }));
vi.mock("./ObjectExplorer", () => ({ default: () => <h2>对象页面</h2> }));
vi.mock("./RetrievalPanel", () => ({ default: () => <h2>检索页面</h2> }));
vi.mock("./DocumentPanel", () => ({ default: () => <h2>文档页面</h2> }));

const alice = { id: "a", name: "张三", type: "人", description: "研究员", attributes: {} };
const org = { ...alice, id: "b", name: "研究院", type: "网" };
const graph = { generationId: "g1", nodes: [alice], links: [], hasMore: false, nextOffset: 0 };
beforeEach(async () => { vi.restoreAllMocks(); await i18n.changeLanguage("zh"); });
function api() {
  const fetcher = vi.fn(async (url: string) => ({ ok: true, status: 200, json: async () => {
    if (url === "/api/knowledge-bases") return ["kb1", "kb2"].map(id => ({ id, name: id, role: "OWNER", description: "" }));
    if (url.endsWith("/types")) return { generationId: "g1", types: ["人", "网"] };
    if (url.includes("/neighbors?")) return { ...graph, nodes: [alice, org], links: [{ id: "ab", source: "a", target: "b", types: ["任职"], description: "工作", weight: 1 }] };
    return graph;
  } }));
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}

it("keeps graph data, canvas, zoom, filters, selection and expansion progress across every menu", async () => {
  const fetcher = api();
  render(<Workspace onSessionExpired={() => {}} />);
  const nav = within(await screen.findByRole("navigation", { name: "功能菜单" }));
  expect(fetcher.mock.calls.some(([url]) => url.includes("/graph"))).toBe(false);
  fireEvent.click(nav.getByRole("button", { name: "图谱查看" }));
  await screen.findByRole("option", { name: "人" });
  fireEvent.change(screen.getByLabelText("实体类型"), { target: { value: "人" } });
  fireEvent.change(screen.getByLabelText("实体名称"), { target: { value: "张三" } });
  fireEvent.click(screen.getByRole("button", { name: "查询" }));
  const node = await screen.findByRole("button", { name: "展开 张三（人）" });
  fireEvent.doubleClick(node);
  await screen.findByRole("button", { name: "展开 研究院（网）" });
  await screen.findByRole("region", { name: "实体详情" });
  fireEvent.click(screen.getByRole("button", { name: "放大" }));
  const svg = document.querySelector("svg")!;
  const viewport = svg.firstElementChild!;
  const zoom = viewport.getAttribute("transform");
  const requests = fetcher.mock.calls.length;
  for (const name of ["知识库问答", "对象探索", "检索证据", "文档管理", "模型配置"]) {
    fireEvent.click(nav.getByRole("button", { name }));
    expect(screen.queryByRole("region", { name: "关联图" })).toBeNull();
    expect(document.querySelector("svg")).toBe(svg);
    const position = svg.querySelector('[aria-label="展开 张三（人）"]')!.getAttribute("transform");
    if (name === "知识库问答") {
      await new Promise(resolve => setTimeout(resolve, 60));
      expect(svg.querySelector('[aria-label="展开 张三（人）"]')!.getAttribute("transform")).toBe(position);
    }
    fireEvent.click(nav.getByRole("button", { name: "图谱查看" }));
    expect(await screen.findByRole("button", { name: "展开 研究院（网）" })).toBeTruthy();
    expect(document.querySelector("svg")).toBe(svg);
    expect(viewport.getAttribute("transform")).toBe(zoom);
    expect(svg.querySelector('[aria-label="展开 张三（人）"]')!.getAttribute("transform")).toBe(position);
    expect((screen.getByLabelText("实体名称") as HTMLInputElement).value).toBe("张三");
    expect((screen.getByLabelText("实体类型") as HTMLSelectElement).value).toBe("人");
    expect(screen.getByRole("region", { name: "实体详情" })).toBeTruthy();
  }
  fireEvent.doubleClick(screen.getByRole("button", { name: "展开 张三（人）" }));
  await screen.findByText("该节点的关联节点已全部展开。");
  expect(fetcher.mock.calls.length).toBe(requests);
});

it("retains a query that completes while the graph menu is hidden", async () => {
  const fetcher = api();
  const normal = fetcher.getMockImplementation()!;
  let finish!: (value: unknown) => void;
  fetcher.mockImplementation(async url => {
    if (url.includes("/graph?")) return { ok: true, status: 200, json: async () => await new Promise(resolve => { finish = resolve; }) } as any;
    return normal(url);
  });
  render(<Workspace onSessionExpired={() => {}} />);
  const nav = within(await screen.findByRole("navigation", { name: "功能菜单" }));
  fireEvent.click(nav.getByRole("button", { name: "图谱查看" }));
  await screen.findByRole("option", { name: "人" });
  fireEvent.click(screen.getByRole("button", { name: "查询" }));
  await waitFor(() => expect(finish).toBeDefined());
  fireEvent.click(nav.getByRole("button", { name: "模型配置" }));
  finish(graph);
  await waitFor(() => expect(document.querySelector('[aria-label="展开 张三（人）"]')).not.toBeNull());
  expect(screen.queryByRole("region", { name: "关联图" })).toBeNull();
  fireEvent.click(nav.getByRole("button", { name: "图谱查看" }));
  await screen.findByRole("button", { name: "展开 张三（人）" });
  expect(fetcher.mock.calls.filter(([url]) => url.endsWith("/types"))).toHaveLength(1);
  expect(fetcher.mock.calls.filter(([url]) => url.includes("/graph?"))).toHaveLength(1);
});

it("destroys the previous graph on knowledge-base changes, including while hidden", async () => {
  const fetcher = api();
  render(<Workspace onSessionExpired={() => {}} />);
  const nav = within(await screen.findByRole("navigation", { name: "功能菜单" }));
  fireEvent.click(nav.getByRole("button", { name: "图谱查看" }));
  await screen.findByRole("option", { name: "人" });
  fireEvent.click(screen.getByRole("button", { name: "查询" }));
  await screen.findByRole("button", { name: "展开 张三（人）" });
  fireEvent.click(nav.getByRole("button", { name: "知识库问答" }));
  fireEvent.change(screen.getByLabelText("当前知识库"), { target: { value: "kb2" } });
  expect(document.querySelector("svg")).toBeNull();
  fireEvent.click(nav.getByRole("button", { name: "图谱查看" }));
  await screen.findByRole("option", { name: "人" });
  expect(screen.queryByRole("button", { name: "展开 张三（人）" })).toBeNull();
  expect((screen.getByLabelText("实体名称") as HTMLInputElement).value).toBe("");
  await waitFor(() => expect(fetcher.mock.calls.some(([url]) => url.includes("/kb2/graph/types"))).toBe(true));
  fireEvent.change(screen.getByLabelText("当前知识库"), { target: { value: "kb1" } });
  await waitFor(() => expect(fetcher.mock.calls.filter(([url]) => url.includes("/kb1/graph/types"))).toHaveLength(2));
  expect(screen.queryByRole("button", { name: "展开 张三（人）" })).toBeNull();
});
