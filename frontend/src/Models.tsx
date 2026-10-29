import { useEffect, useRef, useState, type FormEvent } from "react";
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
const kinds = ["CHAT", "EMBEDDING", "RERANK"] as const;
type Probe = { state: "pending" | "success" | "failure"; ms?: number };
export default function Models({
  onSessionExpired,
}: {
  onSessionExpired: () => void;
}) {
  const { t } = useTranslation();
  const [probes, setProbes] = useState<Record<string, Probe>>({});
  const [pendingDelete, setPendingDelete] = useState("");
  const probeRequest = useRef<AbortController | null>(null);
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
    return () => {
      controller.abort();
      probeRequest.current?.abort();
    };
  }, []);
  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setNotice("");
    const body = { ...form };
    setForm((f) => ({ ...f, apiKey: "" }));
    try {
      await mutate(
        "/api/admin/models" + (profile ? "/" + profile : ""),
        body,
        profile ? "PUT" : "POST",
      );
      setForm({ ...defaults, kind: body.kind, dimensions: body.kind === "EMBEDDING" ? 1024 : 0 });
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
    if (busy) return;
    const controller = new AbortController();
    probeRequest.current = controller;
    setBusy(true);
    setError("");
    setNotice("");
    setProbes(current => ({ ...current, [id]: { state: "pending" } }));
    try {
      const result = await mutate<{ elapsedMs: number }>(
        "/api/admin/models/versions/" + id + "/test",
        undefined,
        "POST",
        controller.signal,
      );
      if (!controller.signal.aborted)
        setProbes(current => ({ ...current, [id]: { state: "success", ms: result.elapsedMs } }));
    } catch (e) {
      if (!controller.signal.aborted) {
        if (e instanceof ApiError && [401, 403].includes(e.status)) fail(e);
        setProbes(current => ({ ...current, [id]: { state: "failure" } }));
      }
    } finally {
      if (!controller.signal.aborted) setBusy(false);
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
  async function remove(id: string) {
    if (busy) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await mutate("/api/admin/models/versions/" + id, undefined, "DELETE");
      setModels(current => current.filter(model => model.id !== id));
      setPendingDelete("");
      setNotice(t("models.deleted"));
      await load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) setError(t("models.deleteConflict"));
      else if (e instanceof ApiError && e.status === 404) {
        setPendingDelete("");
        await load();
        setError(t("models.deleteMissing"));
      } else fail(e);
    } finally {
      setBusy(false);
    }
  }
  function category(kind: string) {
    setPendingDelete("");
    setProfile("");
    setForm({ ...defaults, kind, dimensions: kind === "EMBEDDING" ? 1024 : 0 });
    setError("");
    setNotice("");
  }
  return (
    <section className="space-y-6">
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
          <div role="group" aria-label={t("models.categories")} className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            {kinds.map(kind => (
              <button key={kind} type="button" disabled={busy}
                aria-pressed={form.kind === kind} onClick={() => { if (form.kind !== kind) category(kind); }}
                className={`rounded-xl border px-4 py-3 text-left font-medium disabled:opacity-40 ${form.kind === kind ? "border-blue-600 bg-blue-50 text-blue-700" : "border-slate-200 bg-white text-slate-600 hover:bg-slate-50"}`}>
                {t(`models.kinds.${kind}`)}
              </button>
            ))}
          </div>
          <div>
            <h2 className="text-xl font-semibold">{t(`models.kinds.${form.kind}`)}</h2>
            <p className="mt-2 text-sm text-slate-500">{t(`models.kindHints.${form.kind}`)}</p>
          </div>
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
            {(
              [
                "dimensions",
                "contextTokens",
                "maxOutputTokens",
                "timeoutSeconds",
              ] as const
            ).filter(key => key === "timeoutSeconds" ||
              (key === "dimensions" ? form.kind === "EMBEDDING" : form.kind === "CHAT")
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
                    category(form.kind);
                  }}
                >
                  {t("models.new")}
                </button>
              )}
            </div>
          </form>
          <section aria-labelledby="model-versions-title" className="space-y-3">
            <h2 id="model-versions-title" className="text-lg font-semibold">{t("models.versions")}</h2>
            <p className="text-sm text-slate-500">{t("models.testHint")}</p>
            <p className="text-sm text-slate-500">{t("models.deleteHint")}</p>
            {models.filter(m => m.kind === form.kind).length === 0 && (
              <p className="rounded-xl border border-dashed border-slate-300 bg-white p-5 text-sm text-slate-500">{t("models.emptyKind")}</p>
            )}
            <ul className="space-y-3">
              {models.filter(m => m.kind === form.kind).map(m => (
                <li key={m.id} className="space-y-3 rounded-xl border border-slate-200 bg-white p-5">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div>
                      <h3 className="font-semibold">{m.name}</h3>
                      <p className="text-sm text-slate-500">
                        v{m.version} · {m.model} · {t("models.kinds." + m.kind)}
                        {m.dimensions ? ` · ${m.dimensions}D` : ""} ·{" "}
                        {t(m.keyConfigured ? "models.keySet" : "models.keyAbsent")}
                      </p>
                      <p className="break-all text-xs text-slate-500">{m.baseUrl}</p>
                    </div>
                    <div className="flex gap-3">
                      <button disabled={busy} onClick={() => edit(m)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm disabled:opacity-40">
                        {t("models.edit")}
                      </button>
                      <button disabled={busy} onClick={() => void probe(m.id)} className="rounded-lg bg-blue-600 px-3 py-2 text-sm text-white disabled:opacity-40">
                        {t(probes[m.id]?.state === "pending" ? "models.testing" : "models.test")}
                      </button>
                      {models.some(other => other.profileId === m.profileId && other.version > m.version) && (
                        <button disabled={busy} onClick={() => setPendingDelete(m.id)}
                          className="rounded-lg border border-red-300 px-3 py-2 text-sm text-red-700 disabled:opacity-40">
                          {t("models.delete")}
                        </button>
                      )}
                    </div>
                  </div>
                  {pendingDelete === m.id && (
                    <div role="group" aria-label={t("models.confirmDelete")} className="rounded-lg bg-red-50 p-3">
                      <p className="text-sm text-red-800">{t("models.deletePrompt", { name: m.name, version: m.version })}</p>
                      <div className="mt-2 flex gap-3">
                        <button disabled={busy} onClick={() => void remove(m.id)} className="text-sm text-red-700 disabled:opacity-40">{t("models.confirmDelete")}</button>
                        <button disabled={busy} onClick={() => setPendingDelete("")} className="text-sm disabled:opacity-40">{t("models.cancelDelete")}</button>
                      </div>
                    </div>
                  )}
                  {probes[m.id] && (
                    <p role={probes[m.id].state === "failure" ? "alert" : "status"}
                      className={`text-sm ${probes[m.id].state === "failure" ? "text-red-700" : "text-blue-700"}`}>
                      {probes[m.id].state === "success" ? t("models.testOk", { ms: probes[m.id].ms }) :
                        t(probes[m.id].state === "pending" ? "models.testing" : "models.testFailed")}
                    </p>
                  )}
                </li>
              ))}
            </ul>
          </section>
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
        </>
      )}
    </section>
  );
}
