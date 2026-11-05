import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import ObjectExplorer from "./ObjectExplorer";
import i18n from "./i18n";

const entity = { id: "a", name: "张三", type: "人", description: "研究人员", attributes: { 年龄: [{ type: "number", value: 30 }] } };
const page = { items: [entity], total: 21, page: 0, size: 20 };
const listing = { generationId: "g", allCount: 22, types: [{ type: "人", count: 21 }, { type: "网", count: 1 }], objects: page };
const detail = { generationId: "g", entity,
  texts: { ...page, total: 1, items: [{ chunkId: "c", documentId: "d", sourceName: "资料.txt", chunkOrder: 0, content: "张三在研究院工作。" }] },
  related: { ...page, total: 1, items: [{ entity: { ...entity, id: "b", name: "研究院", type: "网" }, types: ["任职"], description: "在研究院工作" }] } };
beforeEach(async () => { vi.restoreAllMocks(); await i18n.changeLanguage("zh"); });

it("shows type counts, filters by exact type and name, and pages within one generation", async () => {
  const fetcher = vi.fn(async () => ({ ok: true, status: 200, json: async () => listing }));
  vi.stubGlobal("fetch", fetcher);
  render(<ObjectExplorer kb="kb" onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "人（21）" }));
  await waitFor(() => expect(fetcher.mock.calls.length).toBe(2));
  fireEvent.change(screen.getByLabelText("对象名称"), { target: { value: "张%" } });
  fireEvent.click(screen.getByRole("button", { name: "查询" }));
  await waitFor(() => expect(fetcher.mock.calls.length).toBe(3));
  const url = new URL(vi.mocked(fetch).mock.calls[2][0] as string, "http://localhost");
  expect(url.searchParams.get("generationId")).toBe("g");
  expect(url.searchParams.get("type")).toBe("人");
  expect(url.searchParams.get("name")).toBe("张%");
  fireEvent.click(screen.getByRole("button", { name: "下一页" }));
  await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).includes("page=1"))).toBe(true));
  expect(screen.getByRole("button", { name: "全部（22）" })).toBeTruthy();
});

it("opens object attributes, source text and related objects in a detail dialog", async () => {
  vi.stubGlobal("fetch", vi.fn(async (url: string) => ({ ok: true, status: 200, json: async () => url.includes("/objects/a?") ? detail : listing })));
  render(<ObjectExplorer kb="kb" onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "详情" }));
  const dialog = within(await screen.findByRole("dialog", { name: "对象详情" }));
  await dialog.findByText("年龄");
  expect(dialog.getByText("number")).toBeTruthy();
  expect(dialog.getByText("30")).toBeTruthy();
  expect(dialog.getByText("张三在研究院工作。")).toBeTruthy();
  expect(dialog.getByText("资料.txt · 文本块 1")).toBeTruthy();
  expect(dialog.getByRole("button", { name: "研究院" })).toBeTruthy();
  expect(dialog.getByText("任职")).toBeTruthy();
  fireEvent.click(dialog.getByRole("button", { name: "关闭" }));
  expect(screen.queryByRole("dialog")).toBeNull();
});

it("reports missing indexes and expired sessions, and ignores late results after a knowledge-base switch", async () => {
  const expired = vi.fn();
  let status = 409;
  let finish!: (value: unknown) => void;
  vi.stubGlobal("fetch", vi.fn(async (url: string) => ({ ok: status === 200, status, json: async () =>
    status === 409 ? { error: "retrieval_no_active_index" } : status !== 200 ? {} : url.includes("/kb/objects") ? new Promise(resolve => { finish = resolve; }) : { ...listing, objects: { ...page, items: [], total: 0 } } })));
  const { rerender } = render(<ObjectExplorer kb="kb" onSessionExpired={expired} />);
  expect((await screen.findByRole("alert")).textContent).toContain("索引");
  status = 401;
  fireEvent.click(screen.getByRole("button", { name: "刷新" }));
  await waitFor(() => expect(expired).toHaveBeenCalled());
  status = 200;
  fireEvent.click(screen.getByRole("button", { name: "刷新" }));
  await waitFor(() => expect(finish).toBeDefined());
  rerender(<ObjectExplorer kb="other" onSessionExpired={expired} />);
  await screen.findByText("没有找到匹配的对象。");
  finish(listing);
  await waitFor(() => expect(screen.queryByText("张三")).toBeNull());
});

it("pages source texts independently and lets a related object open its own details", async () => {
  const fetcher = vi.fn(async (url: string) => ({ ok: true, status: 200, json: async () => {
    if (!url.includes("/objects/")) return listing;
    if (url.includes("/objects/b?")) return { ...detail, entity: { ...entity, name: "研究院", type: "网" } };
    const textPage = Number(new URL(url, "http://localhost").searchParams.get("textPage"));
    return { ...detail, texts: { ...detail.texts, total: 21, page: textPage, items: textPage ? [{ ...detail.texts.items[0], chunkOrder: 1, content: "第二页文本" }] : detail.texts.items } };
  } }));
  vi.stubGlobal("fetch", fetcher);
  render(<ObjectExplorer kb="kb" onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "详情" }));
  const dialog = within(await screen.findByRole("dialog"));
  const sources = within(await dialog.findByRole("region", { name: "关联文本" }));
  fireEvent.click(sources.getByRole("button", { name: "下一页" }));
  await sources.findByText("第二页文本");
  expect(fetcher.mock.calls.some(([url]) => url.includes("textPage=1") && url.includes("relationPage=0") && url.includes("generationId=g"))).toBe(true);
  fireEvent.click(dialog.getByRole("button", { name: "研究院" }));
  await dialog.findByRole("heading", { name: "研究院 · 网" });
  expect(fetcher.mock.calls.some(([url]) => url.includes("/objects/b?") && url.includes("generationId=g"))).toBe(true);
});

it("renders untrusted object content as text and supports the English interface", async () => {
  const unsafe = { ...entity, name: "<img src=x onerror=alert(1)>", attributes: { note: [{ type: "string", value: "<script>alert(1)</script>" }] } };
  vi.stubGlobal("fetch", vi.fn(async (url: string) => ({ ok: true, status: 200, json: async () => url.includes("/objects/a?")
    ? { ...detail, entity: unsafe } : { ...listing, objects: { ...page, items: [unsafe] } } })));
  await i18n.changeLanguage("en");
  render(<ObjectExplorer kb="kb" onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "Details" }));
  const dialog = within(await screen.findByRole("dialog", { name: "Object details" }));
  await dialog.findByText("<script>alert(1)</script>");
  expect(document.querySelector("img,script")).toBeNull();
  expect(screen.getByRole("button", { name: "All (22)" })).toBeTruthy();
});
