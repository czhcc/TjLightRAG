import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, request } from "./api";
import GraphCanvas from "./GraphCanvas";
import { mergeGraph, typeColor, type GraphSlice } from "./graphData";

/** Knowledge-base graph search and one-hop expansion, isolated from model inference. */
export default function GraphPanel({ kb, onSessionExpired }: { kb: string; onSessionExpired: () => void }) {
  const { t } = useTranslation();
  const [types, setTypes] = useState<string[]>([]), [type, setType] = useState(""), [name, setName] = useState("");
  const [graph, setGraph] = useState<GraphSlice | null>(null), [selected, setSelected] = useState("");
  const [busy, setBusy] = useState(true), [error, setError] = useState(""), [notice, setNotice] = useState("");
  const run = useRef<AbortController | null>(null);
  const offsets = useRef(new Map<string, number | null>());
  const current = useRef(graph); current.current = graph;
  const busyRef = useRef(true);
  const session = useRef(onSessionExpired); session.current = onSessionExpired;
  const fail = useCallback((e: unknown) => {
    if (e instanceof ApiError && e.status === 401) { session.current(); return; }
    setError(e instanceof ApiError && e.code === "retrieval_no_active_index" ? "noIndex"
      : e instanceof ApiError && [403, 404].includes(e.status) ? "unavailable"
      : e instanceof Error && e.message === "graph_limit" ? "limit" : "failure");
  }, []);
  useEffect(() => {
    run.current?.abort(); const controller = new AbortController(); run.current = controller;
    setTypes([]); setType(""); setName(""); setGraph(null); setSelected(""); setError(""); setNotice("");
    offsets.current.clear(); busyRef.current = true; setBusy(true);
    void request<{ types: string[] }>(`/api/knowledge-bases/${kb}/graph/types`, { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setTypes(result.types); })
      .catch(e => { if (!controller.signal.aborted) fail(e); })
      .finally(() => { if (!controller.signal.aborted) { busyRef.current = false; setBusy(false); } });
    return () => { controller.abort(); run.current?.abort(); };
  }, [kb, fail]);

  async function search(event: FormEvent) {
    event.preventDefault(); if (busyRef.current) return;
    run.current?.abort(); const controller = new AbortController(); run.current = controller;
    busyRef.current = true; setBusy(true); setError(""); setNotice(""); setSelected("");
    setGraph(null); offsets.current.clear();
    try {
      const found = await request<GraphSlice>(`/api/knowledge-bases/${kb}/graph?${new URLSearchParams({ type, name })}`, { signal: controller.signal });
      if (!controller.signal.aborted) { setGraph(found); setNotice(found.hasMore ? "searchLimited" : ""); }
    } catch (e) { if (!controller.signal.aborted) fail(e); }
    finally { if (!controller.signal.aborted) { busyRef.current = false; setBusy(false); } }
  }
  const expand = useCallback(async (id: string) => {
    const shown = current.current;
    if (!shown || busyRef.current) return;
    const offset = offsets.current.get(id);
    if (offset === null) { setNotice("expanded"); return; }
    run.current?.abort(); const controller = new AbortController(); run.current = controller;
    busyRef.current = true; setBusy(true); setError(""); setNotice(""); setSelected(id);
    try {
      const params = new URLSearchParams({ generationId: shown.generationId, offset: String(offset ?? 0) });
      const added = await request<GraphSlice>(`/api/knowledge-bases/${kb}/graph/entities/${encodeURIComponent(id)}/neighbors?${params}`, { signal: controller.signal });
      if (!controller.signal.aborted) {
        const merged = mergeGraph(shown, added);
        setGraph(merged); offsets.current.set(id, added.hasMore ? added.nextOffset : null);
        setNotice(added.hasMore ? "moreNeighbors" : added.links.length ? "" : "noNeighbors");
      }
    } catch (e) { if (!controller.signal.aborted) fail(e); }
    finally { if (!controller.signal.aborted) { busyRef.current = false; setBusy(false); } }
  }, [kb, fail]);
  const select = useCallback((id: string) => setSelected(id), []);
  const node = graph?.nodes.find(item => item.id === selected);
  const legend = [...new Set(graph?.nodes.map(item => item.type) ?? [])].sort();
  return <section className="space-y-5 rounded-xl border border-slate-200 bg-white p-5">
    <header><h2 className="text-xl font-semibold">{t("graph.title")}</h2><p className="mt-2 text-sm text-slate-500">{t("graph.hint")}</p></header>
    <form onSubmit={search} className="flex flex-wrap items-end gap-4">
      <label className="text-sm font-medium">{t("graph.type")}<select value={type} onChange={e => setType(e.target.value)}
        className="mt-2 block min-w-40 rounded-lg border border-slate-300 bg-white p-3">
        <option value="">{t("graph.allTypes")}</option>{types.map(item => <option key={item} value={item}>{item}</option>)}
      </select></label>
      <label className="min-w-48 flex-1 text-sm font-medium">{t("graph.name")}<input maxLength={512} value={name}
        placeholder={t("graph.nameHint")} onChange={e => setName(e.target.value)} /></label>
      <button disabled={busy} className="rounded-lg bg-blue-600 px-5 py-3 text-white disabled:opacity-40">{t("graph.search")}</button>
    </form>
    {busy && <p role="status" className="text-sm text-slate-500">{t("loading")}</p>}
    {error && <p role="alert" className="text-red-700">{t(`graph.${error}`)}</p>}
    {notice && <p role="status" className="text-sm text-amber-700">{t(`graph.${notice}`)}</p>}
    {graph && <p className="text-sm text-slate-500">{t("graph.counts", { nodes: graph.nodes.length, links: graph.links.length })}</p>}
    <div className="flex flex-wrap gap-4 text-xs text-slate-600">{legend.map(item => <span key={item} className="flex items-center gap-2">
      <span className="h-3 w-3 rounded-full" style={{ background: typeColor(item) }} />{item}</span>)}</div>
    {!graph && !busy && !error && <p className="text-sm text-slate-500">{t("graph.start")}</p>}
    {graph && !graph.nodes.length && <p className="text-sm text-slate-500">{t("graph.empty")}</p>}
    <GraphCanvas nodes={graph?.nodes ?? EMPTY_NODES} links={graph?.links ?? EMPTY_LINKS} onExpand={expand} onSelect={select} />
    {node && <section className="space-y-3 rounded-lg bg-slate-50 p-4" aria-label={t("graph.details")}>
      <h3 className="font-semibold">{node.name} <span className="text-sm font-normal text-slate-500">{node.type}</span></h3>
      <p className="whitespace-pre-wrap break-words text-sm">{node.description}</p>
      <pre className="overflow-auto whitespace-pre-wrap break-words text-xs">{JSON.stringify(node.attributes, null, 2)}</pre>
      <button type="button" disabled={busy} className="text-sm text-blue-700 disabled:opacity-40" onClick={() => void expand(node.id)}>{t("graph.expandSelected")}</button>
    </section>}
  </section>;
}
const EMPTY_NODES: GraphSlice["nodes"] = [], EMPTY_LINKS: GraphSlice["links"] = [];
