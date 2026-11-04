import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, mutate, request, text } from "./api";

type DocumentRow = {
  id: string; sourceName: string; revisionId: string; status: string;
  chunkCount: number; sizeBytes: number; errorCode?: string;
};
type Page = { items: DocumentRow[]; total: number };
type Upload = { file: File; name: string; state: "pending" | "success" | "failed" };
const primary = "rounded-lg bg-blue-600 px-4 py-2 text-white disabled:opacity-40";
const secondary = "rounded-lg border border-slate-300 px-3 py-2 text-sm disabled:opacity-40";
const active = new Set(["pending", "parsing", "processing", "waiting_index", "indexing"]);
const acceptedFiles = ".txt,.md,.markdown,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.odt,.ods,.odp,.rtf,.html,.htm,.csv";
const supportedFile = /\.(txt|md|markdown|pdf|docx?|xlsx?|pptx?|odt|ods|odp|rtf|html?|csv)$/i;

function Modal({ title, close, busy, children }: { title: string; close: () => void; busy: boolean; children: ReactNode }) {
  const root = useRef<HTMLDivElement>(null);
  const closer = useRef(close); closer.current = close;
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    root.current?.querySelector<HTMLElement>("input,textarea,button,select")?.focus();
    return () => { if (previous?.isConnected) previous.focus(); };
  }, []);
  return <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
    <div ref={root} role="dialog" aria-modal="true" aria-label={title} tabIndex={-1}
      className="max-h-[90vh] w-full max-w-3xl overflow-y-auto rounded-2xl bg-white p-6 shadow-xl"
      onKeyDown={event => {
        if (event.key === "Escape" && !busy) { event.preventDefault(); closer.current(); }
        if (event.key === "Tab") {
          const fields = root.current?.querySelectorAll<HTMLElement>("button:not(:disabled),input:not(:disabled),textarea:not(:disabled),select:not(:disabled),[tabindex='0']");
          if (!fields?.length) { event.preventDefault(); return; }
          const first = fields[0], last = fields[fields.length - 1];
          if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
          else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
        }
      }}>
      <h2 className="mb-5 text-xl font-semibold">{title}</h2>{children}
    </div>
  </div>;
}

export default function DocumentPanel({ kb, canWrite, onSessionExpired }: {
  kb: string; canWrite: boolean; onSessionExpired: () => void;
}) {
  const { t } = useTranslation();
  const [page, setPage] = useState(0), [size, setSize] = useState(20), [refresh, setRefresh] = useState(0);
  const [data, setData] = useState<Page>({ items: [], total: 0 });
  const [loading, setLoading] = useState(true), [busy, setBusy] = useState(false);
  const [error, setError] = useState(""), [notice, setNotice] = useState("");
  const [dialog, setDialog] = useState<"upload" | "text" | "view" | "delete" | null>(null);
  const [selected, setSelected] = useState<DocumentRow | null>(null), [preview, setPreview] = useState("");
  const [uploads, setUploads] = useState<Upload[]>([]);
  const [source, setSource] = useState(""), [content, setContent] = useState(""), [format, setFormat] = useState("markdown");
  const [chunkSize, setChunkSize] = useState(512), [overlap, setOverlap] = useState(64);
  const mutation = useRef<AbortController | null>(null), viewer = useRef<AbortController | null>(null);
  const session = useRef(onSessionExpired); session.current = onSessionExpired;
  const root = `/api/knowledge-bases/${kb}/documents`;
  const windowValid = Number.isInteger(chunkSize) && chunkSize >= 16 && chunkSize <= 4096 &&
    Number.isInteger(overlap) && overlap >= 0 && overlap <= Math.floor(chunkSize / 2);
  function fail(reason: unknown) {
    if (reason instanceof DOMException && reason.name === "AbortError") return;
    if (reason instanceof ApiError && reason.status === 401) { session.current(); return; }
    setError(t(reason instanceof ApiError && reason.status === 403 ? "workspace.permissionDenied" :
      reason instanceof ApiError && reason.status === 413 ? "workspace.tooLarge" :
      reason instanceof ApiError && reason.status === 409 ? "documents.notReady" : "workspace.requestFailed"));
  }
  useEffect(() => {
    const controller = new AbortController(); let inFlight = false;
    setLoading(true);
    async function load() {
      if (inFlight || controller.signal.aborted) return;
      inFlight = true;
      try {
        const result = await request<Page>(`${root}?page=${page}&size=${size}`, { signal: controller.signal });
        if (!controller.signal.aborted) {
          if (page > 0 && result.items.length === 0) setPage(Math.max(0, Math.ceil(result.total / size) - 1));
          else setData(result);
        }
      } catch (e) { if (!controller.signal.aborted) fail(e); }
      finally { if (!controller.signal.aborted) { setLoading(false); inFlight = false; } }
    }
    void load(); const timer = setInterval(() => void load(), 2500);
    return () => { controller.abort(); clearInterval(timer); };
  }, [kb, page, size, refresh]);
  useEffect(() => () => { mutation.current?.abort(); viewer.current?.abort(); }, []);
  function open(mode: "upload" | "text") { setDialog(mode); setError(""); setNotice(""); }
  function close() { viewer.current?.abort(); setDialog(null); setSelected(null); setPreview(""); setError(""); }
  async function act(row: DocumentRow, action: "pause" | "run" | "rebuild" | "delete") {
    if (busy || !canWrite) return;
    const controller = new AbortController(); mutation.current = controller;
    setBusy(true); setError(""); setNotice("");
    try {
      await mutate(`${root}/${row.id}${action === "delete" ? "" : "/" + action}`, undefined,
        action === "delete" ? "DELETE" : "POST", controller.signal);
      if (!controller.signal.aborted) {
        if (action === "delete") close();
        setNotice(t(`documents.notices.${action}`)); setRefresh(n => n + 1);
      }
    } catch (e) { if (!controller.signal.aborted) fail(e); }
    finally { if (!controller.signal.aborted) setBusy(false); }
  }
  async function view(row: DocumentRow) {
    viewer.current?.abort(); const controller = new AbortController(); viewer.current = controller;
    setSelected(row); setPreview(""); setDialog("view"); setError("");
    try {
      const parsed = await text(`${root}/${row.id}/content?revisionId=${encodeURIComponent(row.revisionId)}`, controller.signal);
      if (!controller.signal.aborted) setPreview(parsed);
    } catch (e) { if (!controller.signal.aborted) fail(e); }
  }
  async function submit(event: FormEvent) {
    event.preventDefault(); if (busy || !canWrite || !windowValid) return;
    const controller = new AbortController(); mutation.current = controller; setBusy(true); setError("");
    try {
      await mutate(root + "/text", { sourceName: source, text: content, format, chunkSize, overlap }, "POST", controller.signal);
      if (!controller.signal.aborted) {
        close(); setSource(""); setContent(""); setNotice(t("documents.accepted")); setPage(0); setRefresh(n => n + 1);
      }
    } catch (e) { if (!controller.signal.aborted) fail(e); }
    finally { if (!controller.signal.aborted) setBusy(false); }
  }
  function choose(files: FileList | null) {
    // Snapshot the live FileList before the input reset clears it.
    const selectedFiles = Array.from(files ?? []);
    setUploads(current => {
      const result = [...current];
      for (const file of selectedFiles) {
        const name = file.webkitRelativePath || file.name;
        if (!result.some(item => item.name === name)) result.push({ file, name, state: "pending" });
      }
      return result;
    });
  }
  async function upload() {
    if (busy || !canWrite || !windowValid) return;
    const controller = new AbortController(); mutation.current = controller;
    setBusy(true); setError(""); let failed = false;
    try {
      for (const item of uploads.filter(item => item.state !== "success")) {
        if (controller.signal.aborted) return;
        try {
          if (!supportedFile.test(item.name) || item.file.size === 0 || item.file.size > 5 * 1024 * 1024)
            throw new Error("Unsupported upload");
          const body = new FormData(); body.append("file", item.file, item.name);
          body.append("chunkSize", String(chunkSize)); body.append("overlap", String(overlap));
          await mutate(root + "/upload", body, "POST", controller.signal);
          if (controller.signal.aborted) return;
          setUploads(current => current.map(row => row.name === item.name ? { ...row, state: "success" } : row));
        } catch (e) {
          if (controller.signal.aborted) return;
          failed = true;
          setUploads(current => current.map(row => row.name === item.name ? { ...row, state: "failed" } : row));
          if (e instanceof ApiError && [401, 403].includes(e.status)) { fail(e); return; }
        }
      }
      if (failed) setError(t("documents.uploadPartial"));
      else { close(); setUploads([]); setNotice(t("documents.accepted")); }
    } finally {
      if (!controller.signal.aborted) { setBusy(false); setPage(0); setRefresh(n => n + 1); }
    }
  }
  const options = <details className="rounded-lg border border-slate-200 p-3">
    <summary className="cursor-pointer text-sm">{t("workspace.chunkOptions")}</summary>
    <div className="mt-3 grid gap-3 sm:grid-cols-2">
      <label>{t("workspace.chunkSize")}<input type="number" min={16} max={4096} disabled={busy} value={chunkSize} onChange={e => setChunkSize(Number(e.target.value))} /></label>
      <label>{t("workspace.overlap")}<input type="number" min={0} max={Math.floor(chunkSize / 2)} disabled={busy} value={overlap} onChange={e => setOverlap(Number(e.target.value))} /></label>
    </div>{!windowValid && <p role="alert">{t("workspace.invalidWindow")}</p>}
  </details>;
  return <section className="space-y-4">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <h2 className="text-xl font-semibold">{t("workspace.documents")}</h2>
      <div className="flex gap-3">
        <button className={primary} disabled={!canWrite || busy} onClick={() => open("upload")}>{t("documents.upload")}</button>
        <button className={secondary} disabled={!canWrite || busy} onClick={() => open("text")}>{t("documents.submit")}</button>
        <button className={secondary} disabled={busy} onClick={() => { setError(""); setRefresh(n => n + 1); }}>{t("workspace.refresh")}</button>
      </div>
    </div>
    <p className="text-sm text-slate-500">{t("documents.hint")}</p>
    {!dialog && error && <p role="alert" className="text-red-700">{error}</p>}
    {notice && <p role="status" className="text-blue-700">{notice}</p>}
    <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-slate-200 bg-slate-50"><tr>
          {["name", "size", "chunks", "status", "actions"].map(key => <th key={key} scope="col" className="px-4 py-3 font-medium">{t(`documents.columns.${key}`)}</th>)}
        </tr></thead>
        <tbody>{data.items.map(row => <tr key={row.id} className="border-b border-slate-100 last:border-0">
          <td className="max-w-xs break-words px-4 py-4 font-medium">{row.sourceName}</td>
          <td className="whitespace-nowrap px-4 py-4">{Math.ceil(row.sizeBytes / 1024)} KB</td>
          <td className="px-4 py-4">{row.chunkCount}</td>
          <td className="px-4 py-4"><div className="flex items-center gap-2">
            {active.has(row.status) && <span aria-label={t("documents.working")} className="inline-block h-4 w-4 shrink-0 animate-spin rounded-full border-2 border-blue-200 border-t-blue-600 motion-reduce:animate-none" />}
            <span>{t(`documents.states.${row.status}`, { defaultValue: t("documents.states.pending") })}</span>
            {(active.has(row.status) || ["paused", "failed", "cancelled"].includes(row.status)) && <button
              className="whitespace-nowrap text-blue-700 disabled:opacity-40" disabled={busy || !canWrite}
              onClick={() => void act(row, active.has(row.status) ? "pause" : "run")}>{t(active.has(row.status) ? "documents.pause" : "documents.run")}</button>}
          </div>{row.errorCode && <p className="mt-1 text-xs text-red-700">{t(`workspace.errors.${row.errorCode}`, { defaultValue: t("documents.pipelineFailed") })}</p>}</td>
          <td className="px-4 py-4"><div className="flex gap-3 whitespace-nowrap">
            <button disabled={busy || !canWrite} className="text-blue-700 disabled:opacity-40" onClick={() => void act(row, "rebuild")}>{t("documents.rebuild")}</button>
            <button disabled={busy} className="text-blue-700 disabled:opacity-40" onClick={() => void view(row)}>{t("documents.view")}</button>
            <button disabled={busy || !canWrite} className="text-red-700 disabled:opacity-40" onClick={() => { setSelected(row); setDialog("delete"); setError(""); }}>{t("documents.delete")}</button>
          </div></td>
        </tr>)}</tbody>
      </table>
      {data.items.length === 0 && <p role="status" className="p-8 text-center text-slate-500">{t(loading ? "loading" : "workspace.noDocuments")}</p>}
    </div>
    <div className="flex flex-wrap items-center justify-between gap-3 text-sm">
      <label className="flex items-center gap-2">{t("documents.pageSize")}
        <select value={size} onChange={e => { setSize(Number(e.target.value)); setPage(0); }}>{[10, 20, 50, 100].map(value => <option key={value} value={value}>{value}</option>)}</select>
      </label>
      <div className="flex items-center gap-3">
        <span>{t("documents.pageInfo", { page: page + 1, pages: Math.max(1, Math.ceil(data.total / size)), total: data.total })}</span>
        <button className={secondary} disabled={page === 0 || loading} onClick={() => setPage(n => n - 1)}>{t("workspace.previous")}</button>
        <button className={secondary} disabled={(page + 1) * size >= data.total || loading} onClick={() => setPage(n => n + 1)}>{t("workspace.next")}</button>
      </div>
    </div>
    {dialog && <Modal title={t(`documents.dialogs.${dialog}`)} busy={busy} close={close}>
      {error && <p role="alert" className="mb-4 text-red-700">{error}</p>}
      {dialog === "text" && <form className="space-y-4" onSubmit={submit}>
        <label>{t("workspace.sourceName")}<input required maxLength={255} disabled={busy} value={source} onChange={e => setSource(e.target.value)} /></label>
        <label>{t("workspace.format")}<select disabled={busy} value={format} onChange={e => setFormat(e.target.value)}><option value="markdown">Markdown</option><option value="text">TXT</option></select></label>
        <label>{t("workspace.content")}<textarea rows={10} required disabled={busy} value={content} onChange={e => setContent(e.target.value)} /></label>
        {options}<div className="flex gap-3"><button className={primary} disabled={busy || !windowValid || !content.trim()}>{t(busy ? "loading" : "workspace.submitText")}</button><button type="button" disabled={busy} className={secondary} onClick={close}>{t("documents.close")}</button></div>
      </form>}
      {dialog === "upload" && <div className="space-y-4">
        <p className="text-sm text-slate-500">{t("workspace.fileLimit")}</p>
        <label>{t("workspace.chooseFile")}<input type="file" multiple accept={acceptedFiles} disabled={busy} onChange={e => { choose(e.target.files); e.target.value = ""; }} /></label>
        <label>{t("documents.chooseDirectory")}<input type="file" multiple {...{ webkitdirectory: "" }} disabled={busy} onChange={e => { choose(e.target.files); e.target.value = ""; }} /></label>
        {uploads.length > 0 && <ul className="max-h-48 overflow-y-auto rounded-lg bg-slate-50 p-3 text-sm">{uploads.map(item => <li key={item.name} className="flex justify-between gap-3 py-1"><span className="break-all">{item.name}</span><span className={item.state === "failed" ? "text-red-700" : "text-slate-500"}>{t(`documents.uploadStates.${item.state}`)}</span></li>)}</ul>}
        {options}<div className="flex gap-3"><button className={primary} disabled={busy || !windowValid || uploads.length === 0 || uploads.every(item => item.state === "success")} onClick={() => void upload()}>{t(busy ? "documents.uploading" : "documents.startUpload")}</button><button className={secondary} disabled={busy} onClick={close}>{t("documents.close")}</button><button className={secondary} disabled={busy || uploads.length === 0} onClick={() => setUploads([])}>{t("documents.clearFiles")}</button></div>
      </div>}
      {dialog === "view" && <><p className="mb-3 break-words text-sm text-slate-500">{selected?.sourceName}</p><pre className="max-h-[60vh] overflow-auto whitespace-pre-wrap break-words rounded-lg bg-slate-50 p-4 text-sm">{preview || (!error ? t("loading") : "")}</pre><button className={`${secondary} mt-4`} onClick={close}>{t("documents.close")}</button></>}
      {dialog === "delete" && selected && <><p>{t("documents.deletePrompt", { name: selected.sourceName })}</p><div className="mt-5 flex gap-3"><button className="rounded-lg bg-red-600 px-4 py-2 text-white disabled:opacity-40" disabled={busy} onClick={() => void act(selected, "delete")}>{t("documents.confirmDelete")}</button><button className={secondary} disabled={busy} onClick={close}>{t("documents.close")}</button></div></>}
    </Modal>}
  </section>;
}
