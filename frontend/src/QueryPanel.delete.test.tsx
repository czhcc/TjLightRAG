import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import QueryPanel from "./QueryPanel";
import i18n from "./i18n";

beforeEach(async () => {
  vi.restoreAllMocks();
  await i18n.changeLanguage("zh");
});

function server(failure = false) {
  let items = [{ id: "a", title: "对话甲" }, { id: "b", title: "对话乙" }];
  const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith("/csrf")) return { ok: true, status: 200, json: async () => ({ token: "csrf" }) };
    if (options?.method === "DELETE") {
      if (failure) return { ok: false, status: 403, json: async () => ({}) };
      items = items.filter(item => !url.endsWith(`/${item.id}`));
      return { ok: true, status: 204 };
    }
    if (url.endsWith("/turns")) return { ok: true, status: 200, json: async () => [{ id: "t", sequence: 1, question: "问题甲", answer: "回答甲", status: "success" }] };
    return { ok: true, status: 200, json: async () => items };
  });
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}

function confirmDelete() {
  const dialog = screen.getByRole("dialog", { name: "确认删除" });
  fireEvent.click(within(dialog).getByRole("button", { name: "确认删除" }));
}

it("deletes another conversation with CSRF and preserves the selected conversation", async () => {
  const fetcher = server();
  render(<QueryPanel kb="kb" onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "对话甲" }));
  await screen.findByText("回答甲");
  const deleteButton = screen.getByRole("button", { name: "删除对话：对话乙" });
  expect(deleteButton.textContent).toBe("×");
  fireEvent.click(deleteButton);
  expect(screen.getByRole("dialog", { name: "确认删除" }).textContent).toContain("对话乙");
  expect(fetcher.mock.calls.some(([, options]) => options?.method === "DELETE")).toBe(false);
  confirmDelete();
  await waitFor(() => expect(screen.queryByRole("button", { name: "对话乙" })).toBeNull());
  expect(screen.getByText("回答甲")).toBeTruthy();
  expect(fetcher).toHaveBeenCalledWith("/api/knowledge-bases/kb/conversations/b", expect.objectContaining({ method: "DELETE", headers: expect.objectContaining({ "X-CSRF-TOKEN": "csrf" }) }));
  expect(fetcher.mock.calls.filter(([url, options]) => url.endsWith("/conversations") && !options?.method)).toHaveLength(2);
});

it("deleting the selected conversation clears messages and restores the centered composer", async () => {
  server();
  render(<QueryPanel kb="kb" onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "对话甲" }));
  await screen.findByText("回答甲");
  fireEvent.click(screen.getByRole("button", { name: "删除对话：对话甲" }));
  confirmDelete();
  await waitFor(() => expect(screen.queryByText("回答甲")).toBeNull());
  expect(document.querySelector('form[data-position="center"]')).toBeTruthy();
  expect(screen.getByRole("button", { name: "对话乙" })).toBeTruthy();
});

it("cancelled confirmation does not delete; failed deletion retains history and messages", async () => {
  const fetcher = server(true);
  render(<QueryPanel kb="kb" onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "对话甲" }));
  await screen.findByText("回答甲");
  const trigger = screen.getByRole("button", { name: "删除对话：对话甲" });
  trigger.focus();
  fireEvent.click(trigger);
  const cancel = within(screen.getByRole("dialog")).getByRole("button", { name: "取消" });
  expect(document.activeElement).toBe(cancel);
  fireEvent.keyDown(cancel, { key: "Escape" });
  expect(screen.queryByRole("dialog")).toBeNull();
  expect(document.activeElement).toBe(trigger);
  expect(fetcher.mock.calls.some(([, options]) => options?.method === "DELETE")).toBe(false);
  fireEvent.click(trigger);
  fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "取消" }));
  expect(screen.queryByRole("dialog")).toBeNull();
  expect(fetcher.mock.calls.some(([, options]) => options?.method === "DELETE")).toBe(false);
  fireEvent.click(trigger);
  confirmDelete();
  expect(await screen.findByRole("alert")).toHaveProperty("textContent", "删除对话失败，请检查权限与连接后重试。");
  expect(screen.getByRole("button", { name: "对话甲" })).toBeTruthy();
  expect(screen.getByText("回答甲")).toBeTruthy();
});

it("ignores a late delete response after changing knowledge bases", async () => {
  const fetcher = server();
  let finish!: (value: unknown) => void;
  let signal: AbortSignal | undefined;
  const normal = fetcher.getMockImplementation()!;
  fetcher.mockImplementation(async (url, options) => {
    if (options?.method === "DELETE") {
      signal = options.signal ?? undefined;
      return await new Promise(resolve => { finish = resolve; }) as any;
    }
    return normal(url, options);
  });
  const { rerender } = render(<QueryPanel kb="kb" onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "删除对话：对话甲" }));
  confirmDelete();
  await waitFor(() => expect(finish).toBeDefined());
  rerender(<QueryPanel kb="other" onSessionExpired={() => {}} />);
  await waitFor(() => expect(signal?.aborted).toBe(true));
  finish({ ok: true, status: 204 });
  expect(await screen.findByRole("button", { name: "对话甲" })).toBeTruthy();
  await waitFor(() => expect((screen.getByRole("button", { name: "删除对话：对话甲" }) as HTMLButtonElement).disabled).toBe(false));
});

it("reloads the first page after deletion so the next page does not skip a conversation", async () => {
  let items = Array.from({ length: 51 }, (_, index) => ({ id: String(index), title: `历史${index}` }));
  vi.stubGlobal("fetch", vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith("/csrf")) return { ok: true, status: 200, json: async () => ({ token: "t" }) };
    if (options?.method === "DELETE") { items = items.slice(1); return { ok: true, status: 204 }; }
    const page = Number(new URL(url, "http://localhost").searchParams.get("page") ?? 0);
    return { ok: true, status: 200, json: async () => items.slice(page * 50, page * 50 + 50) };
  }));
  render(<QueryPanel kb="kb" onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "删除对话：历史0" }));
  confirmDelete();
  await screen.findByRole("button", { name: "历史50" });
  await waitFor(() => expect((screen.getByRole("button", { name: "更多对话" }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(screen.getByRole("button", { name: "更多对话" }));
  await waitFor(() => expect(screen.queryByRole("button", { name: "更多对话" })).toBeNull());
  expect(screen.getByRole("button", { name: "历史50" })).toBeTruthy();
});

it("deleting the running conversation aborts the stream and sends explicit cancellation", async () => {
  const fetcher = server();
  const normal = fetcher.getMockImplementation()!;
  let signal: AbortSignal | undefined;
  fetcher.mockImplementation(async (url, options) => {
    if (url.endsWith("/query/stream")) {
      signal = options?.signal ?? undefined;
      return { ok: true, status: 200, headers: new Headers({ "Content-Type": "text/event-stream" }), body: new ReadableStream({ start() {} }) } as any;
    }
    if (url.endsWith("/cancel")) return { ok: true, status: 204 } as any;
    return normal(url, options);
  });
  render(<QueryPanel kb="kb" onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "对话甲" }));
  await screen.findByText("回答甲");
  fireEvent.change(screen.getByLabelText("问答问题"), { target: { value: "继续" } });
  fireEvent.click(screen.getByRole("button", { name: "发送" }));
  await waitFor(() => expect(signal).toBeDefined());
  fireEvent.click(screen.getByRole("button", { name: "删除对话：对话甲" }));
  confirmDelete();
  await waitFor(() => expect(signal?.aborted).toBe(true));
  await waitFor(() => expect(fetcher.mock.calls.some(([url]) => url.endsWith("/cancel"))).toBe(true));
  expect(screen.queryByText("回答甲")).toBeNull();
});
