import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { useState } from "react";
import AgentTrace from "./AgentTrace";
import { request } from "./api";
import "./i18n";
vi.mock("./api", async original => ({ ...await original<typeof import("./api")>(), request: vi.fn() }));
beforeEach(() => vi.clearAllMocks());
it("loads only owned-turn summaries and lazy plain-text payloads", async () => {
  vi.mocked(request).mockResolvedValueOnce({ status: "success", availability: "available", spans: [
    { id: "s", sequence: 1, name: "aggregate_objects", label: "对象统计", status: "success", durationMs: 12 },
  ] }).mockResolvedValueOnce({ content: '<script>bad()</script>', parts: 1 });
  render(<AgentTrace kb="kb" conversation="c" turn="t" close={() => {}} />);
  fireEvent.click(await screen.findByText(/对象统计/));
  await waitFor(() => expect(screen.getByText('<script>bad()</script>')).toBeTruthy());
  expect(document.querySelector("script")).toBeNull();
  expect(vi.mocked(request).mock.calls[0][0]).toContain("/conversations/c/turns/t/trace");
});
it("cancels lazy content requests when closed", async () => {
  vi.mocked(request).mockResolvedValueOnce({ status: "success", availability: "expired", spans: [
    { id: "s", sequence: 1, name: "find_objects", label: "对象查询", status: "success", durationMs: 2 },
  ] }).mockImplementationOnce(() => new Promise(() => {}));
  function Host() {
    const [open, setOpen] = useState(true);
    return open ? <AgentTrace kb="kb" conversation="c" turn="t" close={() => setOpen(false)} /> : null;
  }
  render(<Host />);
  fireEvent.click(await screen.findByText(/对象查询/));
  await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
  const signal = vi.mocked(request).mock.calls[1][1]?.signal;
  fireEvent.click(screen.getByRole("button", { name: "关闭" }));
  expect(screen.queryByRole("dialog")).toBeNull();
  expect(signal?.aborted).toBe(true);
});
it.each(["loading", "empty", "error"])("keeps a visible close button while %s", async state => {
  if (state === "loading") vi.mocked(request).mockImplementationOnce(() => new Promise(() => {}));
  else if (state === "error") vi.mocked(request).mockRejectedValueOnce(new Error("offline"));
  else vi.mocked(request).mockResolvedValueOnce({ status: "not_recorded", availability: "not_recorded", spans: [] });
  const close = vi.fn();
  render(<AgentTrace kb="kb" conversation="c" turn="t" close={close} />);
  fireEvent.click(screen.getByRole("button", { name: "关闭" }));
  expect(close).toHaveBeenCalledOnce();
});
