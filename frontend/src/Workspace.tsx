import { lazy, Suspense, useEffect, useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import RetrievalPanel from "./RetrievalPanel";
import QueryPanel from "./QueryPanel";
import Models from "./Models";
import DocumentPanel from "./DocumentPanel";
import { ApiError, mutate, request } from "./api";

type Base = { id: string; name: string; description: string; role: "OWNER" | "WRITER" | "READER" };
const GraphPanel = lazy(() => import("./GraphPanel"));
const features = [
  ["query", "query.title"], ["graph", "graph.title"], ["retrieval", "retrieval.title"],
  ["documents", "navigation.documents"], ["models", "models.title"],
] as const;
type Feature = (typeof features)[number][0];
const primary = "rounded-lg bg-blue-600 px-4 py-2 text-white disabled:opacity-40";

export default function Workspace({ onSessionExpired, admin = false }: {
  onSessionExpired: () => void; admin?: boolean;
}) {
  const { t } = useTranslation();
  const [page, setPage] = useState<Feature>("query");
  const [bases, setBases] = useState<Base[]>([]), [selectedId, setSelectedId] = useState("");
  const [loaded, setLoaded] = useState(false), [loading, setLoading] = useState(true);
  const [refresh, setRefresh] = useState(0), [name, setName] = useState("");
  const [busy, setBusy] = useState(false), [error, setError] = useState("");
  const session = useRef(onSessionExpired); session.current = onSessionExpired;
  const selected = bases.find(base => base.id === selectedId);
  function fail(e: unknown) {
    if (e instanceof ApiError && e.status === 401) { session.current(); return; }
    setError(e instanceof ApiError && e.status === 403 ? "permissionDenied" : "requestFailed");
  }
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError("");
    request<Base[]>("/api/knowledge-bases", { signal: controller.signal }).then(rows => {
      if (controller.signal.aborted) return;
      if (!Array.isArray(rows)) throw new Error("Unexpected response");
      setBases(rows); setLoaded(true);
      setSelectedId(current => rows.some(base => base.id === current) ? current : rows[0]?.id ?? "");
    }).catch(e => { if (!controller.signal.aborted) fail(e); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [refresh]);
  async function create(event: FormEvent) {
    event.preventDefault(); if (busy || !name.trim()) return;
    setBusy(true); setError("");
    try {
      const created = await mutate<Base>("/api/knowledge-bases", { name: name.trim(), description: "" });
      setBases(current => [created, ...current]); setSelectedId(created.id); setPage("query"); setName("");
    } catch (e) { fail(e); } finally { setBusy(false); }
  }
  const first = loaded && bases.length === 0;
  const createForm = <form className="space-y-4" onSubmit={create}>
    <label>{t("workspace.baseName")}<input autoFocus={first} required maxLength={100} value={name}
      disabled={busy} onChange={e => setName(e.target.value)} /></label>
    <button className={primary} disabled={busy || !name.trim()}>{t(busy ? "loading" : "workspace.createBase")}</button>
  </form>;
  if (loading) return <p role="status" className="p-8">{t("loading")}</p>;
  if (first) return <section aria-labelledby="first-base-title" className="mx-auto mt-12 max-w-lg rounded-2xl border border-slate-200 bg-white p-8 shadow-sm">
    <h1 id="first-base-title" className="text-2xl font-semibold">{t("workspace.firstBaseTitle")}</h1>
    <p className="mb-6 mt-3 text-sm text-slate-500">{t("workspace.firstBaseHint")}</p>{createForm}
    {error && <p role="alert" className="mt-4 text-red-700">{t(`workspace.${error}`)}</p>}
  </section>;
  return <div className="mx-auto grid max-w-screen-2xl items-start gap-6 p-4 md:grid-cols-[240px_minmax(0,1fr)] md:p-8">
    <aside className="space-y-6 rounded-xl border border-slate-200 bg-white p-5 md:sticky md:top-6">
      <label className="block text-sm font-medium">{t("workspace.currentBase")}
        <select className="mt-2 w-full rounded-lg border border-slate-300 bg-white p-3" value={selectedId} disabled={!selected}
          onChange={e => { setSelectedId(e.target.value); setError(""); }}>
          {bases.map(base => <option key={base.id} value={base.id}>{base.name}</option>)}
        </select>
      </label>
      <nav aria-label={t("navigation.features")} className="space-y-2 border-t border-slate-100 pt-5">
        {features.map(([feature, label]) => <button key={feature} aria-current={page === feature ? "page" : undefined}
          disabled={feature === "models" ? !admin && selected?.role !== "OWNER" : !selected}
          className={`block w-full rounded-lg px-3 py-3 text-left text-sm font-medium disabled:cursor-not-allowed disabled:opacity-40 ${page === feature ? "bg-blue-50 text-blue-700" : "text-slate-600 hover:bg-slate-50"}`}
          onClick={() => setPage(feature)}>{t(label)}</button>)}
      </nav>
      <details className="border-t border-slate-100 pt-5"><summary className="cursor-pointer text-sm font-medium">{t("workspace.createBase")}</summary><div className="mt-4">{createForm}</div></details>
    </aside>
    <section className="min-w-0 space-y-6">
      {error && <p role="alert" className="rounded-lg bg-red-50 p-4 text-red-700">{t(`workspace.${error}`)}</p>}
      {page === "models" && (admin || selected?.role === "OWNER") ? <Models key={`models-${selectedId}`} kb={selectedId || undefined} kbName={selected?.name} admin={admin} canBind={selected?.role === "OWNER"} onSessionExpired={onSessionExpired} /> : !selected ?
        <button className={primary} onClick={() => setRefresh(n => n + 1)}>{t("workspace.refresh")}</button> : <>
          <h2 className="text-2xl font-semibold">{selected.name}</h2>
          {selected.role === "READER" && <p className="text-sm text-slate-500">{t("workspace.readOnly")}</p>}
          {page === "query" && <QueryPanel key={`query-${selectedId}`} kb={selectedId} onSessionExpired={onSessionExpired} />}
          {page === "graph" && <Suspense fallback={<p role="status">{t("loading")}</p>}><GraphPanel key={`graph-${selectedId}`} kb={selectedId} onSessionExpired={onSessionExpired} /></Suspense>}
          {page === "retrieval" && <RetrievalPanel key={`retrieval-${selectedId}`} kb={selectedId} onSessionExpired={onSessionExpired} />}
          {page === "documents" && <DocumentPanel key={`documents-${selectedId}`} kb={selectedId} canWrite={selected.role !== "READER"} onSessionExpired={onSessionExpired} />}
        </>}
    </section>
  </div>;
}
