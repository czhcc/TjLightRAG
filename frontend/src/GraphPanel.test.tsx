import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import GraphPanel from "./GraphPanel";
import i18n from "./i18n";

const alice = { id: "a", name: "张三", type: "人", description: "研究人员", attributes: { 年龄: [{ type: "数字", value: 30 }] } };
const org = { id: "b", name: "研究院", type: "网", description: "研究机构", attributes: {} };
const link = { id: "ab", source: "a", target: "b", types: ["任职"], description: "在研究院工作", weight: 1 };
const slice = { generationId: "g1", nodes: [alice], links: [], hasMore: false, nextOffset: 0 };
beforeEach(async () => { vi.restoreAllMocks(); await i18n.changeLanguage("zh"); });

it("queries by type and literal name, expands and deduplicates a node in the captured generation", async () => {
  let expansions = 0;
  const fetch = vi.fn(async (url: string) => ({ ok: true, status: 200, json: async () =>
    url.endsWith("/types") ? { generationId: "g1", types: ["人", "网"] } : url.includes("/neighbors?")
      ? { ...slice, nodes: [alice, org], links: [link], hasMore: ++expansions === 1, nextOffset: 100 } : slice }));
  vi.stubGlobal("fetch", fetch);
  render(<GraphPanel kb="kb1" onSessionExpired={() => {}} />);
  await screen.findByRole("option", { name: "人" });
  fireEvent.change(screen.getByLabelText("实体类型"), { target: { value: "人" } });
  fireEvent.change(screen.getByLabelText("实体名称"), { target: { value: "张三%" } });
  fireEvent.click(screen.getByRole("button", { name: "查询" }));
  const canvas = within(screen.getByRole("region", { name: "关联图" }));
  const node = await canvas.findByRole("button", { name: "展开 张三（人）" });
  const query = new URL(fetch.mock.calls.find(([url]) => url.includes("/graph?"))![0], "http://localhost");
  expect(query.searchParams.get("type")).toBe("人");
  expect(query.searchParams.get("name")).toBe("张三%");
  fireEvent.doubleClick(node);
  await canvas.findByRole("button", { name: "展开 研究院（网）" });
  expect(fetch.mock.calls.some(([url]) => url.includes("/graph/entities/a/neighbors?") && url.includes("generationId=g1"))).toBe(true);
  expect(canvas.getAllByRole("button", { name: /^展开 / })).toHaveLength(2);
  expect(canvas.getByText("任职")).toBeTruthy();
  await screen.findByText("该节点还有更多关联，再次双击可继续展开。");
  fireEvent.doubleClick(canvas.getByRole("button", { name: "展开 张三（人）" }));
  await waitFor(() => expect(expansions).toBe(2));
  expect(fetch.mock.calls.some(([url]) => url.includes("offset=100"))).toBe(true);
  await waitFor(() => expect((screen.getByRole("button", { name: "查询" }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.doubleClick(canvas.getByRole("button", { name: "展开 张三（人）" }));
  await waitFor(() => expect(screen.getByText("该节点的关联节点已全部展开。")).toBeTruthy());
  expect(canvas.getAllByRole("button", { name: /^展开 / })).toHaveLength(2);
  expect(expansions).toBe(2);
});

it("shows empty results and index errors and expires unauthorized sessions", async () => {
  const expired = vi.fn();
  let status = 200;
  vi.stubGlobal("fetch", vi.fn(async (url: string) => ({ ok: status === 200, status, json: async () => status === 409
    ? { error: "retrieval_no_active_index" } : url.endsWith("/types") ? { types: [] } : { ...slice, nodes: [] } })));
  render(<GraphPanel kb="kb" onSessionExpired={expired} />);
  await waitFor(() => expect((screen.getByRole("button", { name: "查询" }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(screen.getByRole("button", { name: "查询" }));
  await screen.findByText("没有找到匹配的实体，请调整类型或名称。");
  status = 409;
  fireEvent.click(screen.getByRole("button", { name: "查询" }));
  await screen.findByRole("alert");
  expect(screen.getByRole("alert").textContent).toContain("索引");
  status = 401;
  fireEvent.click(screen.getByRole("button", { name: "查询" }));
  await waitFor(() => expect(expired).toHaveBeenCalled());
});

it("discards a delayed query after switching knowledge bases", async () => {
  let finish: ((value: unknown) => void) | undefined;
  vi.stubGlobal("fetch", vi.fn(async (url: string) => ({ ok: true, status: 200, json: async () => url.endsWith("/types")
    ? { types: ["人"] } : new Promise(resolve => { finish = resolve; }) })));
  const { rerender } = render(<GraphPanel kb="kb1" onSessionExpired={() => {}} />);
  await screen.findByRole("option", { name: "人" });
  fireEvent.click(screen.getByRole("button", { name: "查询" }));
  await waitFor(() => expect(finish).toBeDefined());
  rerender(<GraphPanel kb="kb2" onSessionExpired={() => {}} />);
  finish!(slice);
  await waitFor(() => expect((screen.getByRole("button", { name: "查询" }) as HTMLButtonElement).disabled).toBe(false));
  expect(screen.queryByRole("button", { name: "展开 张三（人）" })).toBeNull();
});
