import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import Models from "./Models";
import i18n from "./i18n";
beforeEach(async () => {
  vi.restoreAllMocks();
  await i18n.changeLanguage("zh");
});
it("lists masked model versions and saves a secret without retaining it", async () => {
  const calls: { url: string; init?: RequestInit }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return {
        ok: true,
        status: 200,
        json: async () =>
          url.endsWith("/csrf")
            ? { token: "csrf" }
            : url.endsWith("/bindings")
              ? {}
              : url.endsWith("/models") && !init?.method
                ? [
                    {
                      id: "v1",
                      profileId: "p1",
                      name: "测试模型",
                      kind: "CHAT",
                      model: "test",
                      baseUrl: "https://example.com/v1",
                      keyConfigured: true,
                      version: 1,
                    },
                  ]
                : { id: "v2" },
      };
    }),
  );
  render(<Models onSessionExpired={() => {}} />);
  await screen.findByText("测试模型");
  expect(screen.queryByText("actual-secret")).toBeNull();
  fireEvent.change(screen.getByLabelText("配置名称"), {
    target: { value: "新模型" },
  });
  fireEvent.change(screen.getByLabelText("服务地址"), {
    target: { value: "https://example.com/v1" },
  });
  fireEvent.change(screen.getByLabelText("模型名称"), {
    target: { value: "chat-model" },
  });
  fireEvent.change(screen.getByLabelText("API 密钥"), {
    target: { value: "actual-secret" },
  });
  fireEvent.click(screen.getByRole("button", { name: "保存模型配置" }));
  await waitFor(() =>
    expect(
      calls.some(
        (c) =>
          c.init?.method === "POST" &&
          String(c.init.body).includes("actual-secret"),
      ),
    ).toBe(true),
  );
  await waitFor(() =>
    expect((screen.getByLabelText("API 密钥") as HTMLInputElement).value).toBe(
      "",
    ),
  );
});
it("shows authorization errors without exposing a model management form", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => ({ ok: false, status: 403 })),
  );
  render(<Models onSessionExpired={() => {}} />);
  await screen.findByRole("alert");
  expect(screen.queryByRole("button", { name: "保存模型配置" })).toBeNull();
});
it("handles empty successful binding responses", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => ({
      ok: true,
      status: init?.method === "PUT" ? 204 : 200,
      json: async () =>
        url.endsWith("/csrf")
          ? { token: "csrf" }
          : url.endsWith("/models")
            ? []
            : {},
    })),
  );
  render(<Models onSessionExpired={() => {}} />);
  fireEvent.click(await screen.findByRole("button", { name: "保存用途绑定" }));
  await screen.findByText("用途绑定已保存，已有索引不受影响");
  expect(screen.queryByRole("alert")).toBeNull();
});
