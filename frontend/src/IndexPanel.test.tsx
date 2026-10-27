import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import IndexPanel from "./IndexPanel";
import i18n from "./i18n";
beforeEach(async () => {
  vi.restoreAllMocks();
  await i18n.changeLanguage("zh");
});
it("activates a ready generation using the current generation as precondition", async () => {
  const calls: { url: string; options?: RequestInit }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, options?: RequestInit) => {
      calls.push({ url, options });
      return {
        ok: true,
        status: options?.method === "POST" ? 204 : 200,
        json: async () =>
          url.endsWith("/csrf")
            ? { token: "csrf" }
            : [
                {
                  id: "old",
                  state: "ready",
                  active: true,
                  dimensions: 3,
                  totalChunks: 1,
                  completedChunks: 1,
                },
                {
                  id: "new",
                  state: "ready",
                  active: false,
                  dimensions: 4,
                  totalChunks: 1,
                  completedChunks: 1,
                },
              ],
      };
    }),
  );
  render(<IndexPanel kb="kb" owner onSessionExpired={() => {}} />);
  const activate = await screen.findByRole("button", { name: "激活此索引" });
  fireEvent.click(activate);
  await waitFor(() =>
    expect(
      calls.some(
        (c) =>
          c.url.endsWith("/new/activate") &&
          String(c.options?.body).includes(
            '"expectedActiveGenerationId":"old"',
          ),
      ),
    ).toBe(true),
  );
});
it("read-only members cannot build or switch generations", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })),
  );
  render(<IndexPanel kb="kb" owner={false} onSessionExpired={() => {}} />);
  await screen.findByText("索引构建与切换");
  expect(
    (screen.getByRole("button", { name: "构建新索引" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
});
