import { useEffect, useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, request } from "./api";
import Modal from "./Modal";

type Entity = { id: string; name: string; type: string; description: string; attributes: Record<string, unknown> };
type Page<T> = { items: T[]; total: number; page: number; size: number };
type Listing = { generationId: string; allCount: number; types: { type: string; count: number }[]; objects: Page<Entity> };
type Detail = { generationId: string; entity: Entity;
  texts: Page<{ chunkId: string; documentId: string; revisionId: string; sourceName: string; chunkOrder: number; content: string }>;
  related: Page<{ entity: Entity; types: string[]; description: string }> };
type Props = { kb: string; onSessionExpired: () => void };
const button = "rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm disabled:cursor-default disabled:opacity-40";

/** Remounts all browsing state when the knowledge-base boundary changes. */
export default function ObjectExplorer(props: Props) {
  return <Explorer key={props.kb} {...props} />;
}

/** Lists merged objects and their original evidence without invoking a model. */
function Explorer({ kb, onSessionExpired }: Props) {
  const { t } = useTranslation();
  const [listing, setListing] = useState<Listing | null>(null);
  const [type, setType] = useState(""), [name, setName] = useState(""), [filter, setFilter] = useState("");
  const [page, setPage] = useState(0), [size, setSize] = useState(20), [refresh, setRefresh] = useState(0);
  const [busy, setBusy] = useState(true), [error, setError] = useState("");
  const generation = useRef<string | undefined>(undefined);
  const [selected, setSelected] = useState(""), [detail, setDetail] = useState<Detail | null>(null);
  const [detailBusy, setDetailBusy] = useState(false), [detailError, setDetailError] = useState("");
  const detailRequest = useRef<AbortController | null>(null);
  const session = useRef(onSessionExpired); session.current = onSessionExpired;

  function message(e: unknown) {
    if (e instanceof ApiError && e.status === 401) { session.current(); return t("sessionExpired"); }
    if (e instanceof ApiError && e.code === "retrieval_no_active_index") return t("retrieval.errors.retrieval_no_active_index");
    if (e instanceof ApiError && e.status === 404) return t("explorer.unavailable");
    return t("explorer.failure");
  }
  useEffect(() => {
    const controller = new AbortController();
    setBusy(true); setError("");
    const params = new URLSearchParams({ type, name: filter, page: String(page), size: String(size) });
    if (generation.current) params.set("generationId", generation.current);
    void request<Listing>(`/api/knowledge-bases/${kb}/objects?${params}`, { signal: controller.signal }).then(result => {
      if (controller.signal.aborted) return;
      generation.current = result.generationId; setListing(result);
    }).catch(e => { if (!controller.signal.aborted) { setListing(null); setError(message(e)); } })
      .finally(() => { if (!controller.signal.aborted) setBusy(false); });
    return () => controller.abort();
  }, [kb, type, filter, page, size, refresh]);
  useEffect(() => () => detailRequest.current?.abort(), []);

  function close() {
    detailRequest.current?.abort(); setSelected(""); setDetail(null); setDetailBusy(false); setDetailError("");
  }
  async function view(id: string, textPage = 0, relationPage = 0) {
    if (!generation.current) return;
    detailRequest.current?.abort();
    const controller = new AbortController(); detailRequest.current = controller;
    if (id !== selected) setDetail(null);
    setSelected(id); setDetailBusy(true); setDetailError("");
    const params = new URLSearchParams({ generationId: generation.current, textPage: String(textPage), relationPage: String(relationPage) });
    try {
      const result = await request<Detail>(`/api/knowledge-bases/${kb}/objects/${id}?${params}`, { signal: controller.signal });
      if (!controller.signal.aborted) setDetail(result);
    } catch (e) { if (!controller.signal.aborted) { setDetail(null); setDetailError(message(e)); } }
    finally { if (!controller.signal.aborted) setDetailBusy(false); }
  }
  function search(event: FormEvent) {
    event.preventDefault(); if (busy) return;
    setFilter(name.trim()); setPage(0);
  }
  function pager<T>(value: Page<T>, change: (page: number) => void, disabled: boolean) {
    return <div className="mt-4 flex flex-wrap items-center justify-end gap-3">
      <span className="text-sm text-slate-500">{t("explorer.page", { page: value.page + 1, total: value.total })}</span>
      <button type="button" className={button} disabled={disabled || value.page === 0} onClick={() => change(value.page - 1)}>{t("explorer.previous")}</button>
      <button type="button" className={button} disabled={disabled || (value.page + 1) * value.size >= value.total} onClick={() => change(value.page + 1)}>{t("explorer.next")}</button>
    </div>;
  }
  const attributes = Object.entries(detail?.entity.attributes ?? {}).flatMap(([name, values]) =>
    (Array.isArray(values) ? values : [values]).map(value => {
      const typed = value !== null && typeof value === "object" && "value" in value;
      return { name, type: typed ? String(value.type ?? "") : typeof value, value: typed ? value.value : value };
    }));
  const display = (value: unknown) => typeof value === "string" ? value : JSON.stringify(value) ?? "";
  return <section className="space-y-5 rounded-xl border border-slate-200 bg-white p-5">
    <header className="flex items-center justify-between gap-4">
      <h2 className="text-lg font-semibold">{t("explorer.title")}</h2>
      <button type="button" className={button} disabled={busy} onClick={() => { close(); generation.current = undefined; setPage(0); setRefresh(n => n + 1); }}>{t("explorer.refresh")}</button>
    </header>
    <p className="text-sm text-slate-500">{t("explorer.hint")}</p>
    <div className="grid items-start gap-5 md:grid-cols-[11rem_minmax(0,1fr)]">
      <aside className="rounded-lg bg-slate-50 p-3">
        <h3 className="mb-3 font-medium">{t("explorer.types")}</h3>
        <nav aria-label={t("explorer.types")} className="max-h-[65vh] space-y-1 overflow-auto">
          {[{ type: "", count: listing?.allCount ?? 0 }, ...(listing?.types ?? [])].map(item => <button key={item.type} type="button"
            disabled={busy} aria-current={type === item.type ? "true" : undefined}
            className={`block w-full break-words rounded-lg px-3 py-2 text-left text-sm disabled:opacity-50 ${type === item.type ? "bg-blue-100 text-blue-800" : "hover:bg-slate-200"}`}
            onClick={() => { setType(item.type); setPage(0); }}>
            {t("explorer.typeCount", { type: item.type || t("explorer.all"), count: item.count })}
          </button>)}
        </nav>
      </aside>
      <div className="min-w-0">
        <form onSubmit={search} className="mb-4 flex flex-wrap items-end gap-3">
          <label className="min-w-0 flex-1 text-sm">{t("explorer.name")}<input maxLength={512} value={name} onChange={event => setName(event.target.value)} /></label>
          <button className={button} disabled={busy}>{t("explorer.search")}</button>
        </form>
        {busy && <p role="status">{t("loading")}</p>}
        {error && <p role="alert" className="mb-4 text-red-700">{error}</p>}
        {listing && <>
          <div className="overflow-x-auto"><table className="w-full text-left text-sm" aria-label={t("explorer.list")}>
            <thead className="border-b border-slate-200 text-slate-500"><tr>
              <th className="p-3">{t("explorer.name")}</th><th className="p-3">{t("explorer.type")}</th><th className="p-3">{t("explorer.operations")}</th>
            </tr></thead>
            <tbody>{listing.objects.items.map(item => <tr key={item.id} className="border-b border-slate-100">
              <td className="break-all p-3">{item.name}</td><td className="break-all p-3">{item.type}</td>
              <td className="p-3"><button type="button" className="text-blue-700 disabled:opacity-40" disabled={busy} onClick={() => void view(item.id)}>{t("explorer.details")}</button></td>
            </tr>)}</tbody>
          </table></div>
          {!listing.objects.items.length && <p className="py-6 text-center text-sm text-slate-500">{t("explorer.empty")}</p>}
          <label className="mt-4 flex items-center justify-end gap-2 text-sm">{t("explorer.size")}
            <select className="rounded-lg border border-slate-300 p-2" value={size} disabled={busy} onChange={event => { setSize(Number(event.target.value)); setPage(0); }}>
              {[20, 50, 100].map(value => <option key={value} value={value}>{value}</option>)}
            </select>
          </label>
          {pager({ ...listing.objects, page, size }, setPage, busy)}
        </>}
      </div>
    </div>
    {selected && <Modal title={t("explorer.detailTitle")} busy={false} close={close}>
      {detailBusy && <p role="status">{t("loading")}</p>}
      {detailError && <div><p role="alert" className="text-red-700">{detailError}</p><button type="button" className={button} onClick={() => void view(selected)}>{t("explorer.retry")}</button></div>}
      {detail && <div className="space-y-6">
        <div><h3 className="break-words text-lg font-semibold">{detail.entity.name} · {detail.entity.type}</h3><p className="mt-2 whitespace-pre-wrap break-words text-sm text-slate-600">{detail.entity.description}</p></div>
        <section aria-label={t("explorer.attributes")}><h3 className="mb-3 font-semibold">{t("explorer.attributes")}</h3>
          {attributes.length ? <div className="overflow-x-auto"><table className="w-full text-left text-sm"><thead><tr>
            <th className="p-2">{t("explorer.attributeName")}</th><th className="p-2">{t("explorer.attributeType")}</th><th className="p-2">{t("explorer.attributeValue")}</th>
          </tr></thead><tbody>{attributes.map((item, index) => <tr key={index} className="border-t border-slate-100">
            <td className="break-all p-2">{item.name}</td><td className="p-2">{item.type}</td><td className="whitespace-pre-wrap break-all p-2">{display(item.value)}</td>
          </tr>)}</tbody></table></div> : <p className="text-sm text-slate-500">{t("explorer.noAttributes")}</p>}
        </section>
        <section aria-label={t("explorer.texts")}><h3 className="mb-3 font-semibold">{t("explorer.texts")}</h3>
          {detail.texts.items.map(item => <article key={`${item.documentId}-${item.revisionId}-${item.chunkOrder}`} className="mb-3 rounded-lg bg-slate-50 p-3">
            <h4 className="break-words text-sm font-medium">{t("explorer.source", { name: item.sourceName, order: item.chunkOrder + 1 })}</h4>
            <p className="mt-2 whitespace-pre-wrap break-words text-sm">{item.content}</p>
          </article>)}
          {!detail.texts.items.length && <p className="text-sm text-slate-500">{t("explorer.noTexts")}</p>}
          {pager(detail.texts, next => void view(selected, next, detail.related.page), detailBusy)}
        </section>
        <section aria-label={t("explorer.related")}><h3 className="mb-3 font-semibold">{t("explorer.related")}</h3>
          {detail.related.items.map(item => <article key={item.entity.id} className="mb-3 rounded-lg border border-slate-200 p-3">
            <button type="button" className="break-words text-sm font-medium text-blue-700 disabled:opacity-40" disabled={detailBusy} onClick={() => void view(item.entity.id)}>{item.entity.name}</button>
            <span className="ml-3 text-sm text-slate-500">{item.entity.type}</span>
            <p className="mt-2 break-words text-sm">{item.types.join("、")}</p><p className="mt-1 whitespace-pre-wrap break-words text-sm text-slate-600">{item.description}</p>
          </article>)}
          {!detail.related.items.length && <p className="text-sm text-slate-500">{t("explorer.noRelated")}</p>}
          {pager(detail.related, next => void view(selected, detail.texts.page, next), detailBusy)}
        </section>
      </div>}
      <div className="mt-5 flex justify-end"><button type="button" className={button} onClick={close}>{t("explorer.close")}</button></div>
    </Modal>}
  </section>;
}
