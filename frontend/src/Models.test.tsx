import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import Models from "./Models";
import i18n from "./i18n";

it.each([false, true])("deletes an old version or explains a reference conflict: %s", async conflict => {
  let removed = false;
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === "DELETE") {
      removed = !conflict;
      return { ok: !conflict, status: conflict ? 409 : 204, json: async () => ({}) };
    }
    return { ok: true, status: 200, json: async () =>
      url.endsWith("/csrf") ? { token: "csrf" } :
      url.endsWith("/models") ? [2, ...(removed ? [] : [1])].map(version => ({
        id: `v${version}`, profileId: "profile", name: `旧配置-v${version}`, kind: "CHAT",
        version, model: "model", baseUrl: "https://example.com/v1", keyConfigured: false,
      })) : {} };
  });
  vi.stubGlobal("fetch", fetch);
  render(<Models onSessionExpired={() => {}} />);
  const old = (await screen.findByText("旧配置-v1")).closest("li")!;
  expect(within((await screen.findByText("旧配置-v2")).closest("li")!)
    .queryByRole("button", { name: "删除旧版本" })).toBeNull();
  fireEvent.click(within(old).getByRole("button", { name: "删除旧版本" }));
  expect(fetch.mock.calls.some(([, init]) => init?.method === "DELETE")).toBe(false);
  fireEvent.click(screen.getByRole("button", { name: "确认删除" }));
  if (conflict) {
    await screen.findByText("无法删除：该版本仍被用途绑定或索引引用，或已成为最新版本。");
    expect(screen.getByText("旧配置-v1")).toBeTruthy();
  } else {
    await waitFor(() => expect(screen.queryByText("旧配置-v1")).toBeNull());
    expect(fetch.mock.calls.some(([url, init]) => url.endsWith("/versions/v1") &&
      init?.method === "DELETE" && (init.headers as Record<string, string>)["X-CSRF-TOKEN"] === "csrf")).toBe(true);
  }
});
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

const modelKinds = [
  ["CHAT", "文本模型"],
  ["EMBEDDING", "向量模型"],
  ["RERANK", "重排模型"],
] as const;
function categoryApi(probeFails = false) {
  const models = modelKinds.map(([kind], index) => ({
    id: `version-${index}`, profileId: `profile-${index}`, kind,
    name: `配置-${kind}`, model: `model-${kind}`, version: 1,
    baseUrl: "https://example.com/v1", keyConfigured: true,
    dimensions: kind === "EMBEDDING" ? 1024 : 0,
    contextTokens: 16000, maxOutputTokens: 2000, timeoutSeconds: 30,
  }));
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/test")) return {
      ok: !probeFails, status: probeFails ? 502 : 200,
      json: async () => ({ success: true, elapsedMs: 42 }),
    };
    return { ok: true, status: 200, json: async () =>
      url.endsWith("/csrf") ? { token: "csrf" } :
      url.endsWith("/status") ? { masterKeyConfigured: true } :
      url.endsWith("/bindings") ? {} :
      !init?.method ? models : { id: "created" },
    };
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

it.each(modelKinds)("separates %s settings, versions and tests", async (kind, label) => {
  const fetch = categoryApi();
  render(<Models onSessionExpired={() => {}} />);
  await screen.findByText("配置-CHAT");
  fireEvent.click(screen.getByRole("button", { name: label }));
  const item = screen.getByRole("heading", { name: `配置-${kind}` }).closest("li")!;
  for (const [other] of modelKinds.filter(([other]) => other !== kind)) {
    expect(screen.queryByRole("heading", { name: `配置-${other}` })).toBeNull();
  }
  expect(!!screen.queryByLabelText("向量维度")).toBe(kind === "EMBEDDING");
  expect(!!screen.queryByLabelText("最大输出 token")).toBe(kind === "CHAT");
  fireEvent.click(within(item).getByRole("button", { name: "连接测试" }));
  await within(item).findByText("连接测试成功（42 ms）");
  expect(within(item).getByRole("status").textContent).toContain("42 ms");
  const index = modelKinds.findIndex(([value]) => value === kind);
  const testCall = fetch.mock.calls.find(([url]) => url.endsWith("/test"));
  expect(testCall?.[0]).toBe(`/api/admin/models/versions/version-${index}/test`);
  expect(testCall?.[1]?.method).toBe("POST");
  expect((testCall?.[1]?.headers as Record<string, string>)["X-CSRF-TOKEN"]).toBe("csrf");
  expect(testCall?.[1]?.body).toBeUndefined();
});

it.each(modelKinds)("creates a %s configuration within its own category", async (kind, label) => {
  const fetch = categoryApi();
  render(<Models onSessionExpired={() => {}} />);
  await screen.findByText("配置-CHAT");
  fireEvent.click(screen.getByRole("button", { name: label }));
  fireEvent.change(screen.getByLabelText("配置名称"), { target: { value: "新配置" } });
  fireEvent.change(screen.getByLabelText("服务地址"), { target: { value: "https://example.com/v1" } });
  fireEvent.change(screen.getByLabelText("模型名称"), { target: { value: "example" } });
  fireEvent.click(screen.getByRole("button", { name: "保存模型配置" }));
  await screen.findByText("模型新版本已保存");
  const call = fetch.mock.calls.find(([url, init]) => url === "/api/admin/models" && init?.method === "POST");
  const body = JSON.parse(String(call?.[1]?.body));
  expect(body.kind).toBe(kind);
  expect(body.dimensions).toBe(kind === "EMBEDDING" ? 1024 : 0);
  expect(screen.getByRole("button", { name: label }).getAttribute("aria-pressed")).toBe("true");
});

it("shows a failed probe beside its model and permits retry", async () => {
  const fetch = categoryApi(true);
  render(<Models onSessionExpired={() => {}} />);
  const item = (await screen.findByRole("heading", { name: "配置-CHAT" })).closest("li")!;
  fireEvent.click(within(item).getByRole("button", { name: "连接测试" }));
  await within(item).findByRole("alert");
  expect(within(item).queryByRole("status")).toBeNull();
  expect((within(item).getByRole("button", { name: "连接测试" }) as HTMLButtonElement).disabled).toBe(false);
  fireEvent.click(within(item).getByRole("button", { name: "连接测试" }));
  await waitFor(() => expect(fetch.mock.calls.filter(([url]) => url.endsWith("/test"))).toHaveLength(2));
});

it("prevents duplicate probes and aborts an in-flight probe when leaving the page", async () => {
  const fetch = categoryApi();
  const normalFetch = fetch.getMockImplementation()!;
  let probeSignal: AbortSignal | null | undefined;
  fetch.mockImplementation(async (url, init) => {
    if (url.endsWith("/test")) {
      probeSignal = init?.signal;
      return new Promise(() => {});
    }
    return normalFetch(url, init);
  });
  const { unmount } = render(<Models onSessionExpired={() => {}} />);
  const item = (await screen.findByRole("heading", { name: "配置-CHAT" })).closest("li")!;
  fireEvent.click(within(item).getByRole("button", { name: "连接测试" }));
  const pending = await within(item).findByRole("button", { name: "测试中…" });
  expect((pending as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(pending);
  await waitFor(() => expect(probeSignal).toBeTruthy());
  expect(fetch.mock.calls.filter(([url]) => url.endsWith("/test"))).toHaveLength(1);
  unmount();
  expect(probeSignal!.aborted).toBe(true);
});

it("edits an embedding version without changing its category and clears secrets on category switches", async () => {
  categoryApi();
  render(<Models onSessionExpired={() => {}} />);
  await screen.findByText("配置-CHAT");
  fireEvent.click(screen.getByRole("button", { name: "向量模型" }));
  fireEvent.click(screen.getByRole("button", { name: "创建新版本" }));
  expect((screen.getByLabelText("配置名称") as HTMLInputElement).value).toBe("配置-EMBEDDING");
  expect((screen.getByLabelText("向量维度") as HTMLInputElement).value).toBe("1024");
  fireEvent.change(screen.getByLabelText("API 密钥"), { target: { value: "draft-secret" } });
  fireEvent.click(screen.getByRole("button", { name: "重排模型" }));
  expect((screen.getByLabelText("API 密钥") as HTMLInputElement).value).toBe("");
  expect(screen.queryByLabelText("清除已保存的 API 密钥")).toBeNull();
  await i18n.changeLanguage("en");
  await screen.findByRole("button", { name: "Rerank model" });
  expect(screen.getByRole("button", { name: "Test connection" })).toBeTruthy();
});
