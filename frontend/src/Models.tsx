import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, request, mutate } from "./api";
type Model = { id: string; name: string; kind: string; baseUrl: string; model: string; keyConfigured: boolean;
  dimensions: number; contextTokens: number; maxOutputTokens: number; timeoutSeconds: number; extraBody?: Record<string, unknown> };
const defaults = { name: "", kind: "CHAT", baseUrl: "", model: "", apiKey: "", clearApiKey: false,
  dimensions: 0, contextTokens: 16000, maxOutputTokens: 2000, timeoutSeconds: 30, extraBody: "" };
const roles = ["extract", "summary", "keywords", "answer", "embedding", "rerank"];
const kinds = ["CHAT", "EMBEDDING", "RERANK"];
const button = "rounded-lg border border-slate-300 px-4 py-2 disabled:opacity-40";
function Dialog({ title, busy, close, children }: { title: string; busy: boolean; close: () => void; children: ReactNode }) {
  const { t } = useTranslation();
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    ref.current?.querySelector<HTMLElement>("input,select,button")?.focus();
    return () => previous?.focus();
  }, []);
  return <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/50 p-4">
    <div ref={ref} role="dialog" aria-modal="true" aria-label={title} className="max-h-[90vh] w-full max-w-3xl overflow-y-auto rounded-2xl bg-white p-6 shadow-xl"
      onKeyDown={e => {
        if (e.key === "Escape" && !busy) { e.stopPropagation(); close(); }
        if (e.key === "Tab") {
          const items = Array.from(ref.current?.querySelectorAll<HTMLElement>('input:not(:disabled),select:not(:disabled),textarea:not(:disabled),button:not(:disabled)') ?? []);
          const next = e.shiftKey ? items.at(-1) : items[0];
          if ((e.shiftKey && document.activeElement === items[0]) || (!e.shiftKey && document.activeElement === items.at(-1))) { e.preventDefault(); next?.focus(); }
        }
      }}>
      <div className="mb-5 flex items-center justify-between gap-4"><h2 className="text-xl font-semibold">{title}</h2>
        <button type="button" disabled={busy} onClick={close} className={button}>{t("documents.close")}</button></div>
      {children}
    </div></div>;
}
/** Current model catalog and knowledge-base settings, with transient modal drafts. */
export default function Models({ kb, kbName, admin = true, canBind = false, onSessionExpired }:
  { kb?: string; kbName?: string; admin?: boolean; canBind?: boolean; onSessionExpired: () => void }) {
  const { t } = useTranslation();
  const [models, setModels] = useState<Model[]>([]), [bindings, setBindings] = useState<Record<string, string>>({});
  const [bindingDraft, setBindingDraft] = useState<Record<string, string>>({});
  const [kind, setKind] = useState("CHAT"), [form, setForm] = useState({ ...defaults }), [profile, setProfile] = useState("");
  const [modal, setModal] = useState<"edit" | "bindings" | "delete" | null>(null), [selected, setSelected] = useState<Model | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState(""), [notice, setNotice] = useState("");
  const [master, setMaster] = useState<boolean>(), [probes, setProbes] = useState<Record<string, string>>({});
  const active = useRef<AbortController | null>(null);
  function fail(e: unknown) {
    if (e instanceof ApiError && e.status === 401) onSessionExpired();
    setError(t(e instanceof ApiError && e.status === 409 ? "models.deleteConflict" : e instanceof ApiError && e.status === 403 ? "models.forbidden" : "models.failure"));
  }
  async function load(signal: AbortSignal) {
    const [items, bound, status] = await Promise.all([
      request<Model[]>(admin ? "/api/admin/models" : `/api/knowledge-bases/${kb}/model-options`, { signal }),
      kb ? request<Record<string, string>>(`/api/knowledge-bases/${kb}/model-bindings`, { signal }) : Promise.resolve({}),
      admin ? request<{ masterKeyConfigured: boolean }>("/api/admin/models/status", { signal }) : Promise.resolve({ masterKeyConfigured: true }),
    ]);
    if (!signal.aborted) { setModels(items); setBindings(bound); setMaster(status.masterKeyConfigured); }
  }
  useEffect(() => {
    const controller = new AbortController(); active.current = controller;
    setModal(null); setError(""); setModels([]); setBindings({}); setBusy(false); setNotice(""); setForm({ ...defaults });
    void load(controller.signal).catch(e => { if (!controller.signal.aborted) fail(e); });
    return () => controller.abort();
  }, [kb, admin]);
  function close() { setModal(null); setForm({ ...defaults, kind }); setProfile(""); setSelected(null); setError(""); }
  function edit(model?: Model) {
    setError(""); setNotice(""); setProfile(model?.id ?? "");
    setForm(model ? { name: model.name, kind: model.kind, baseUrl: model.baseUrl, model: model.model, apiKey: "", clearApiKey: false,
      dimensions: model.dimensions, contextTokens: model.contextTokens, maxOutputTokens: model.maxOutputTokens,
      timeoutSeconds: model.timeoutSeconds, extraBody: JSON.stringify(model.extraBody ?? {}, null, 2) }
      : { ...defaults, kind, dimensions: kind === "EMBEDDING" ? 1024 : 0 });
    setModal("edit");
  }
  let extraBody: Record<string, unknown> = {}, extraBodyValid = true;
  if (form.kind === "CHAT" && form.extraBody.trim()) {
    try { const value: unknown = JSON.parse(form.extraBody);
      if (!value || typeof value !== "object" || Array.isArray(value) || new Blob([form.extraBody]).size > 16384) extraBodyValid = false;
      else extraBody = value as Record<string, unknown>;
    } catch { extraBodyValid = false; }
  }
  async function execute(work: (signal: AbortSignal) => Promise<void>) {
    const signal = active.current?.signal; if (!signal || busy) return;
    setBusy(true); setError(""); setNotice("");
    try { await work(signal); } catch (e) { if (!signal.aborted) fail(e); }
    finally { if (!signal.aborted) setBusy(false); }
  }
  async function save(event: FormEvent) {
    event.preventDefault(); if (!extraBodyValid) return;
    await execute(async signal => {
      await mutate(`/api/admin/models${profile ? "/" + profile : ""}`, { ...form, extraBody: form.kind === "CHAT" ? extraBody : {} }, profile ? "PUT" : "POST", signal);
      if (!signal.aborted) { close(); setNotice(t("models.saved")); await load(signal); }
    });
  }
  async function bind() {
    if (!kb) return;
    await execute(async signal => {
      await mutate(`/api/knowledge-bases/${kb}/model-bindings`, Object.fromEntries(roles.map(role => [role, bindingDraft[role] || null])), "PUT", signal);
      if (!signal.aborted) { setBindings(bindingDraft); close(); setNotice(t("models.bindingSaved")); }
    });
  }
  async function remove() {
    if (!selected) return;
    await execute(async signal => {
      await mutate(`/api/admin/models/${selected.id}`, undefined, "DELETE", signal);
      if (!signal.aborted) { close(); setNotice(t("models.deleted")); await load(signal); }
    });
  }
  async function probe(id: string) {
    await execute(async signal => {
      setProbes(old => ({ ...old, [id]: t("models.testing") }));
      try {
        const out = await mutate<{ elapsedMs: number }>(`/api/admin/models/${id}/test`, undefined, "POST", signal);
        if (!signal.aborted) setProbes(old => ({ ...old, [id]: t("models.testOk", { ms: out.elapsedMs }) }));
      } catch (e) { if (!signal.aborted) setProbes(old => ({ ...old, [id]: t("models.testFailed") })); throw e; }
    });
  }
  return <section className="space-y-6">
    <div className="flex flex-wrap items-center justify-between gap-4"><h1 className="text-2xl font-semibold">{t("models.title")}</h1>
      <button className={button} disabled={busy || !kb || !canBind} onClick={() => { setBindingDraft({ ...bindings }); setError(""); setModal("bindings"); }}>{t("models.bindings")}</button></div>
    <p className="text-sm text-slate-600">{t("models.description")}</p>
    {notice && <p role="status" className="text-blue-700">{notice}</p>}
    {error && !modal && <p role="alert" className="text-red-700">{error}</p>}
    <div role="tablist" aria-label={t("models.categories")} className="flex flex-wrap gap-3">
      {kinds.map(value => <button key={value} role="tab" aria-selected={value === kind} aria-controls="models-panel"
        disabled={busy} className={`${button} ${value === kind ? "bg-blue-50 text-blue-700 border-blue-600" : "bg-white"}`}
        onClick={() => setKind(value)}>{t(`models.kinds.${value}`)}</button>)}</div>
    <div id="models-panel" role="tabpanel" aria-label={t(`models.kinds.${kind}`)} className="space-y-4">
      <div className="flex items-center justify-between gap-4"><h2 className="text-lg font-semibold">{t(`models.kinds.${kind}`)}</h2>
        {admin && <button className="rounded-lg bg-blue-600 px-4 py-2 text-white" disabled={busy} onClick={() => edit()}>{t("models.new")}</button>}</div>
      <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white"><table className="w-full text-left text-sm">
        <thead className="bg-slate-50"><tr>{["name", "model", "baseUrl", "actions"].map(key => <th className="px-4 py-3" key={key}>{t(`models.${key}`)}</th>)}</tr></thead>
        <tbody>{models.filter(model => model.kind === kind).map(model => <tr className="border-t border-slate-100" key={model.id}>
          <td className="px-4 py-4 font-medium">{model.name}</td><td className="px-4 py-4">{model.model ?? "—"}</td><td className="px-4 py-4 break-all">{model.baseUrl ?? "—"}</td>
          <td className="px-4 py-4"><div className="flex flex-wrap gap-4">{admin && <>
            <button disabled={busy} onClick={() => edit(model)}>{t("models.edit")}</button>
            <button disabled={busy} onClick={() => { setSelected(model); setError(""); setModal("delete"); }}>{t("models.delete")}</button>
            <button disabled={busy} onClick={() => void probe(model.id)}>{t("models.test")}</button></>}
          </div>{probes[model.id] && <p role="status" className="mt-2 text-blue-700">{probes[model.id]}</p>}</td></tr>)}</tbody>
      </table>{!models.some(model => model.kind === kind) && <p className="p-6 text-slate-500">{t("models.emptyKind")}</p>}</div>
    </div>
    {modal === "edit" && <Dialog title={t(profile ? "models.edit" : "models.new")} busy={busy} close={close}>
      {master === false && <p className="mb-4 bg-amber-50 p-3">{t("models.masterMissing")}</p>}
      {error && <p role="alert" className="mb-4 text-red-700">{error}</p>}
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
            {form.kind === "CHAT" && <div className="grid gap-2 md:col-span-2">
              <label htmlFor="model-extra-body">{t("models.extraBody")}</label>
              <textarea id="model-extra-body" rows={6} maxLength={16384} disabled={busy}
                className="w-full rounded-lg border border-slate-300 p-3 font-mono text-sm" spellCheck={false} value={form.extraBody}
                aria-invalid={!extraBodyValid} aria-describedby="model-extra-body-hint"
                placeholder={'{\n  "chat_template_kwargs": {\n    "enable_thinking": false\n  }\n}'}
                onChange={e => setForm({ ...form, extraBody: e.target.value })} />
              <p id="model-extra-body-hint" className="text-sm text-slate-500">{t("models.extraBodyHint")}</p>
              {!extraBodyValid && <p role="alert" className="text-sm text-red-700">{t("models.extraBodyInvalid")}</p>}
            </div>}
            {profile && (
              <label className="flex items-center gap-2 md:col-span-2">
                <input
                  type="checkbox"
                  className="mt-0 w-auto shrink-0"
                  checked={form.clearApiKey}
                  onChange={(e) =>
                    setForm({ ...form, clearApiKey: e.target.checked })
                  }
                />
                {t("models.clearKey")}
              </label>
            )}
            <div className="flex items-end justify-end gap-3 md:col-span-2">
              <button
                disabled={busy || !extraBodyValid || form.maxOutputTokens >= form.contextTokens}
                className="rounded-lg bg-blue-600 px-4 py-2 text-white disabled:opacity-40"
              >
                {t("models.save")}
              </button>

            </div>
          </form>
    </Dialog>}
    {modal === "bindings" && <Dialog title={t("models.bindings")} busy={busy} close={close}>
      <p className="mb-3 font-medium">{t("models.bindingScope", { name: kbName ?? kb })}</p>
      <p className="mb-5 text-sm text-slate-500">{t("models.bindingHint")}</p>
      {error && <p role="alert" className="mb-3 text-red-700">{error}</p>}
      <form onSubmit={e => { e.preventDefault(); void bind(); }} className="grid gap-4 sm:grid-cols-2">
        {roles.map(role => <label key={role}>{t(`models.roles.${role}`)}<select disabled={busy || !canBind} value={bindingDraft[role] || ""}
          onChange={e => setBindingDraft(old => ({ ...old, [role]: e.target.value }))}>
          <option value="">{t("models.unbound")}</option>{models.filter(model => model.kind === (role === "embedding" ? "EMBEDDING" : role === "rerank" ? "RERANK" : "CHAT"))
            .map(model => <option key={model.id} value={model.id}>{model.name}</option>)}</select></label>)}
        <div className="sm:col-span-2"><button className={button} disabled={busy || !canBind}>{t("models.saveBindings")}</button></div>
      </form>
    </Dialog>}
    {modal === "delete" && selected && <Dialog title={t("models.confirmDelete")} busy={busy} close={close}>
      <p className="mb-4">{t("models.deletePrompt", { name: selected.name })}</p>
      {error && <p role="alert" className="mb-4 text-red-700">{error}</p>}
      <button className={button} disabled={busy} onClick={() => void remove()}>{t(busy ? "models.deleting" : "models.confirmDelete")}</button>
    </Dialog>}
  </section>;
}
