import { useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, request, mutate } from "./api";
type Model = {
  id: string;
  profileId: string;
  name: string;
  kind: string;
  version: number;
  baseUrl: string;
  model: string;
  keyConfigured: boolean;
  dimensions: number;
  contextTokens: number;
  maxOutputTokens: number;
  timeoutSeconds: number;
};
const defaults = {
  name: "",
  kind: "CHAT",
  baseUrl: "",
  model: "",
  apiKey: "",
  clearApiKey: false,
  dimensions: 0,
  contextTokens: 16000,
  maxOutputTokens: 2000,
  timeoutSeconds: 30,
};
const roles = [
  "extract",
  "summary",
  "keywords",
  "answer",
  "embedding",
  "rerank",
];
export default function Models({
  onSessionExpired,
}: {
  onSessionExpired: () => void;
}) {
  const { t } = useTranslation();
  const [models, setModels] = useState<Model[]>([]),
    [bindings, setBindings] = useState<Record<string, string>>({}),
    [form, setForm] = useState({ ...defaults }),
    [profile, setProfile] = useState(""),
    [busy, setBusy] = useState(false),
    [denied, setDenied] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [master, setMaster] = useState<boolean | undefined>();
  function fail(e: unknown) {
    if (e instanceof ApiError && e.status === 401) onSessionExpired();
    if (e instanceof ApiError && e.status === 403) setDenied(true);
    setError(
      t(
        e instanceof ApiError && e.status === 403
          ? "models.forbidden"
          : "models.failure",
      ),
    );
  }
  async function load() {
    try {
      const [items, bound, status] = await Promise.all([
        request<Model[]>("/api/admin/models"),
        request<Record<string, string>>("/api/admin/models/bindings"),
        request<{ masterKeyConfigured: boolean }>("/api/admin/models/status"),
      ]);
      setModels(items);
      setBindings(bound);
      setMaster(status.masterKeyConfigured);
    } catch (e) {
      fail(e);
    }
  }
  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      request<Model[]>("/api/admin/models", { signal: controller.signal }),
      request<Record<string, string>>("/api/admin/models/bindings", {
        signal: controller.signal,
      }),
      request<{ masterKeyConfigured: boolean }>("/api/admin/models/status", {
        signal: controller.signal,
      }),
    ])
      .then(([items, bound, status]) => {
        if (!controller.signal.aborted) {
          setModels(items);
          setBindings(bound);
          setMaster(status.masterKeyConfigured);
        }
      })
      .catch((e) => {
        if (!controller.signal.aborted) fail(e);
      });
    return () => controller.abort();
  }, []);
  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const body = { ...form };
    setForm((f) => ({ ...f, apiKey: "" }));
    try {
      await mutate(
        "/api/admin/models" + (profile ? "/" + profile : ""),
        body,
        profile ? "PUT" : "POST",
      );
      setForm({ ...defaults });
      setProfile("");
      setNotice(t("models.saved"));
      await load();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }
  async function bind() {
    setBusy(true);
    setError("");
    try {
      await mutate(
        "/api/admin/models/bindings",
        Object.fromEntries(roles.map((r) => [r, bindings[r] || null])),
        "PUT",
      );
      setNotice(t("models.bindingSaved"));
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }
  async function probe(id: string) {
    setBusy(true);
    setError("");
    try {
      const result = await mutate<{ elapsedMs: number }>(
        "/api/admin/models/versions/" + id + "/test",
      );
      setNotice(t("models.testOk", { ms: result.elapsedMs }));
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }
  function edit(m: Model) {
    setProfile(m.profileId);
    setForm({
      name: m.name,
      kind: m.kind,
      baseUrl: m.baseUrl,
      model: m.model,
      apiKey: "",
      clearApiKey: false,
      dimensions: m.dimensions,
      contextTokens: m.contextTokens,
      maxOutputTokens: m.maxOutputTokens,
      timeoutSeconds: m.timeoutSeconds,
    });
    setNotice("");
  }
  return (
    <section className="mx-auto max-w-screen-xl space-y-6 p-8">
      <h1 className="text-2xl font-semibold">{t("models.title")}</h1>
      <p className="text-sm text-slate-600">{t("models.description")}</p>
      {error && (
        <p role="alert" className="text-red-700">
          {error}
        </p>
      )}
      {notice && (
        <p role="status" className="text-blue-700">
          {notice}
        </p>
      )}
      {!denied && (
        <>
          {master === false && (
            <p className="rounded-lg bg-amber-50 p-4">
              {t("models.masterMissing")}
            </p>
          )}
          <form
            onSubmit={save}
            className="grid gap-4 rounded-xl border border-slate-200 bg-white p-6 md:grid-cols-2"
          >
            {(["name", "baseUrl", "model", "apiKey"] as const).map((key) => (
              <label key={key}>
                {t("models." + key)}
                <input
                  type={
                    key === "apiKey"
                      ? "password"
                      : key === "baseUrl"
                        ? "url"
                        : "text"
                  }
                  autoComplete={key === "apiKey" ? "new-password" : "off"}
                  required={key !== "apiKey"}
                  value={form[key]}
                  maxLength={key === "apiKey" ? 4096 : 1000}
                  onChange={(e) => setForm({ ...form, [key]: e.target.value })}
                />
              </label>
            ))}
            <label>
              {t("models.kind")}
              <select
                value={form.kind}
                disabled={!!profile}
                onChange={(e) =>
                  setForm({
                    ...form,
                    kind: e.target.value,
                    dimensions: e.target.value === "EMBEDDING" ? 1024 : 0,
                  })
                }
              >
                {["CHAT", "EMBEDDING", "RERANK"].map((k) => (
                  <option key={k} value={k}>
                    {t("models.kinds." + k)}
                  </option>
                ))}
              </select>
            </label>
            {(
              [
                "dimensions",
                "contextTokens",
                "maxOutputTokens",
                "timeoutSeconds",
              ] as const
            ).map((key) => (
              <label key={key}>
                {t("models." + key)}
                <input
                  type="number"
                  required
                  min={
                    key === "dimensions"
                      ? form.kind === "EMBEDDING"
                        ? 1
                        : 0
                      : key === "timeoutSeconds"
                        ? 1
                        : key === "maxOutputTokens"
                          ? 64
                          : 1024
                  }
                  max={
                    key === "dimensions"
                      ? 2000
                      : key === "timeoutSeconds"
                        ? 120
                        : key === "maxOutputTokens"
                          ? 32000
                          : 1000000
                  }
                  disabled={key === "dimensions" && form.kind !== "EMBEDDING"}
                  value={form[key]}
                  onChange={(e) =>
                    setForm({ ...form, [key]: Number(e.target.value) })
                  }
                />
              </label>
            ))}
            {profile && (
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={form.clearApiKey}
                  onChange={(e) =>
                    setForm({ ...form, clearApiKey: e.target.checked })
                  }
                />
                {t("models.clearKey")}
              </label>
            )}
            <div className="flex items-end gap-3">
              <button
                disabled={busy || form.maxOutputTokens >= form.contextTokens}
                className="rounded-lg bg-blue-600 px-4 py-2 text-white disabled:opacity-40"
              >
                {t("models.save")}
              </button>
              {profile && (
                <button
                  type="button"
                  onClick={() => {
                    setProfile("");
                    setForm({ ...defaults });
                  }}
                >
                  {t("models.new")}
                </button>
              )}
            </div>
          </form>
          <div className="space-y-3 rounded-xl border border-slate-200 bg-white p-6">
            <h2 className="text-xl font-semibold">{t("models.bindings")}</h2>
            <p className="text-sm text-slate-500">{t("models.bindingHint")}</p>
            <div className="grid gap-4 md:grid-cols-2">
              {roles.map((role) => (
                <label key={role}>
                  {t("models.roles." + role)}
                  <select
                    value={bindings[role] || ""}
                    onChange={(e) =>
                      setBindings({ ...bindings, [role]: e.target.value })
                    }
                  >
                    <option value="">{t("models.unbound")}</option>
                    {models
                      .filter(
                        (m) =>
                          m.kind ===
                          (role === "embedding"
                            ? "EMBEDDING"
                            : role === "rerank"
                              ? "RERANK"
                              : "CHAT"),
                      )
                      .map((m) => (
                        <option key={m.id} value={m.id}>
                          {m.name} · v{m.version} · {m.model}
                        </option>
                      ))}
                  </select>
                </label>
              ))}
            </div>
            <button
              disabled={busy}
              onClick={() => void bind()}
              className="rounded-lg border border-slate-300 px-4 py-2"
            >
              {t("models.saveBindings")}
            </button>
          </div>
          <ul className="space-y-3">
            {models.map((m) => (
              <li
                key={m.id}
                className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white p-5"
              >
                <div>
                  <h2 className="font-semibold">{m.name}</h2>
                  <p className="text-sm text-slate-500">
                    v{m.version} · {m.model} · {t("models.kinds." + m.kind)}
                    {m.dimensions ? ` · ${m.dimensions}D` : ""} ·{" "}
                    {t(m.keyConfigured ? "models.keySet" : "models.keyAbsent")}
                  </p>
                  <p className="break-all text-xs text-slate-500">
                    {m.baseUrl}
                  </p>
                </div>
                <div className="flex gap-4">
                  <button disabled={busy} onClick={() => edit(m)}>
                    {t("models.edit")}
                  </button>
                  <button disabled={busy} onClick={() => void probe(m.id)}>
                    {t("models.test")}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
