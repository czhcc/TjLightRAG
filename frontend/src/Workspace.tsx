import { useEffect, useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import IndexPanel from "./IndexPanel";
import RetrievalPanel from "./RetrievalPanel";
import QueryPanel from "./QueryPanel";
import Models from "./Models";
import { ApiError, mutate, request, text as fetchText } from "./api";

type Base = {
  id: string;
  name: string;
  description: string;
  role: "OWNER" | "WRITER" | "READER";
};
type Document = {
  id: string;
  sourceName: string;
  revisionId: string;
  jobId: string;
  status: string;
  chunkCount: number;
  sizeBytes: number;
};
type Job = {
  id: string;
  documentId: string;
  revisionId: string;
  sourceName: string;
  status: string;
  attempts: number;
  maxAttempts: number;
  cancelRequested: boolean;
  errorCode?: string;
  events: { event: string; code?: string; createdAt: string }[];
};
type Chunk = {
  id: string;
  order: number;
  content: string;
  tokens: number;
  sourceStart: number;
  sourceEnd: number;
  headings: string[];
};
type Page<T> = { items: T[]; total: number; page: number; size: number };
type Submission = {
  documentId: string;
  revisionId: string;
  jobId: string;
  duplicate: boolean;
};
const empty = <T,>(): Page<T> => ({ items: [], total: 0, page: 0, size: 20 });
const primary =
  "rounded-lg bg-blue-600 px-4 py-2 text-white disabled:opacity-40";
const secondary =
  "rounded-lg border border-slate-300 px-3 py-2 text-sm disabled:opacity-40";
const features = [
  ["query", "query.title"],
  ["retrieval", "retrieval.title"],
  ["indexes", "indexes.title"],
  ["documents", "navigation.documents"],
  ["jobs", "navigation.jobs"],
  ["models", "models.title"],
] as const;
type Feature = (typeof features)[number][0];

export default function Workspace({
  onSessionExpired,
  admin = false,
}: {
  onSessionExpired: () => void;
  admin?: boolean;
}) {
  const { t } = useTranslation();
  const [page, setPage] = useState<Feature>("query");
  const [basesLoaded, setBasesLoaded] = useState(false);
  const [baseRefresh, setBaseRefresh] = useState(0);
  const [bases, setBases] = useState<Base[]>([]),
    [selectedId, setSelectedId] = useState("");
  const [newName, setNewName] = useState(""),
    [sourceName, setSourceName] = useState(""),
    [content, setContent] = useState("");
  const [format, setFormat] = useState("markdown"),
    [chunkSize, setChunkSize] = useState(512),
    [overlap, setOverlap] = useState(64);
  const [file, setFile] = useState<File | null>(null),
    [busy, setBusy] = useState(false),
    [loading, setLoading] = useState(true);
  const [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [refresh, setRefresh] = useState(0);
  const [documents, setDocuments] = useState<Page<Document>>(empty),
    [jobs, setJobs] = useState<Page<Job>>(empty);
  const [documentPage, setDocumentPage] = useState(0),
    [jobPage, setJobPage] = useState(0),
    [focusedId, setFocusedId] = useState("");
  const [focused, setFocused] = useState<Job | null>(null);
  const [preview, setPreview] = useState<{
    job: Job;
    text: string;
    chunks: Page<Chunk>;
  } | null>(null);
  const selection = useRef(selectedId);
  selection.current = selectedId;
  const previewRequest = useRef<AbortController | null>(null);
  const selected = bases.find((base) => base.id === selectedId),
    canWrite = !!selected && selected.role !== "READER";
  const baseUrl = `/api/knowledge-bases/${selectedId}`;
  const validWindow =
    Number.isInteger(chunkSize) &&
    Number.isInteger(overlap) &&
    chunkSize >= 16 &&
    chunkSize <= 4096 &&
    overlap >= 0 &&
    overlap <= Math.floor(chunkSize / 2);

  function failure(reason: unknown) {
    if (reason instanceof DOMException && reason.name === "AbortError") return;
    if (reason instanceof ApiError && reason.status === 401) {
      onSessionExpired();
      return;
    }
    setError(
      reason instanceof ApiError && reason.status === 403
        ? "permissionDenied"
        : reason instanceof ApiError && reason.status === 413
          ? "tooLarge"
          : "requestFailed",
    );
  }
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    request<Base[]>("/api/knowledge-bases", { signal: controller.signal })
      .then((rows) => {
        if (controller.signal.aborted) return;
        if (!Array.isArray(rows)) throw new Error("Unexpected response");
        setBases(rows);
        setBasesLoaded(true);
        setSelectedId((current) =>
          rows.some((b) => b.id === current) ? current : (rows[0]?.id ?? ""),
        );
      })
      .catch((reason) => {
        if (!controller.signal.aborted) failure(reason);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [onSessionExpired, baseRefresh]);
  useEffect(() => {
    setDocuments(empty());
    setJobs(empty());
    setFocused(null);
    setFocusedId("");
    setPreview(null);
    setDocumentPage(0);
    setJobPage(0);
    setNotice("");
    setError("");
    setSourceName("");
    setContent("");
    setFile(null);
    previewRequest.current?.abort();
  }, [selectedId]);
  useEffect(() => {
    if (!selectedId || !["documents", "jobs"].includes(page)) return;
    const controller = new AbortController();
    let inFlight = false;
    async function load() {
      if (inFlight || controller.signal.aborted) return;
      inFlight = true;
      try {
        const [docs, tasks] = await Promise.all([
          page === "documents" ? request<Page<Document>>(
            `${baseUrl}/documents?page=${documentPage}&size=20`,
            { signal: controller.signal },
          ) : Promise.resolve(empty<Document>()),
          page === "jobs" ? request<Page<Job>>(`${baseUrl}/jobs?page=${jobPage}&size=20`, {
            signal: controller.signal,
          }) : Promise.resolve(empty<Job>()),
        ]);
        if (controller.signal.aborted) return;
        setDocuments(docs);
        setJobs(tasks);
        if (focusedId) {
          const detail = await request<Job>(`${baseUrl}/jobs/${focusedId}`, {
            signal: controller.signal,
          });
          if (!controller.signal.aborted) setFocused(detail);
        }
      } catch (reason) {
        if (!controller.signal.aborted) failure(reason);
      } finally {
        inFlight = false;
      }
    }
    void load();
    const timer = setInterval(() => {
      if (!document.hidden) void load();
    }, 2000);
    return () => {
      controller.abort();
      clearInterval(timer);
    };
  }, [selectedId, page, documentPage, jobPage, focusedId, refresh, onSessionExpired]);
  useEffect(() => () => previewRequest.current?.abort(), []);
  useEffect(() => {
    previewRequest.current?.abort();
    setPreview(null);
    setError("");
    setNotice("");
  }, [page]);

  async function create(event: FormEvent) {
    event.preventDefault();
    if (busy || !newName.trim()) return;
    setBusy(true);
    setError("");
    try {
      const created = await mutate<Base>("/api/knowledge-bases", {
        name: newName.trim(),
        description: "",
      });
      setBases((current) => [created, ...current]);
      setSelectedId(created.id);
      setPage("query");
      setNewName("");
    } catch (reason) {
      failure(reason);
    } finally {
      setBusy(false);
    }
  }
  async function submit(event: FormEvent, upload: boolean) {
    event.preventDefault();
    if (!canWrite || !validWindow) return;
    setBusy(true);
    setError("");
    setNotice("");
    const kb = selectedId;
    try {
      let response: Submission;
      if (upload) {
        if (!file) return;
        if (file.size > 5 * 1024 * 1024) {
          setError("tooLarge");
          return;
        }
        const form = new FormData();
        form.append("file", file);
        response = await mutate<Submission>(
          `${baseUrl}/documents/upload?chunkSize=${chunkSize}&overlap=${overlap}`,
          form,
        );
      } else
        response = await mutate<Submission>(`${baseUrl}/documents/text`, {
          sourceName,
          text: content,
          format,
          chunkSize,
          overlap,
        });
      if (selection.current !== kb) return;
      setNotice(response.duplicate ? "duplicate" : "accepted");
      setFocusedId(response.jobId);
      setRefresh((n) => n + 1);
    } catch (reason) {
      if (selection.current === kb) failure(reason);
    } finally {
      setBusy(false);
    }
  }
  async function action(job: Job, name: "cancel" | "retry") {
    if (!canWrite) return;
    setBusy(true);
    setError("");
    const kb = selectedId;
    try {
      const result = await mutate<Job>(`${baseUrl}/jobs/${job.id}/${name}`);
      if (selection.current === kb) {
        setFocused(result);
        setRefresh((n) => n + 1);
      }
    } catch (reason) {
      if (selection.current === kb) failure(reason);
    } finally {
      setBusy(false);
    }
  }
  async function showPreview(job: Job, page = 0) {
    previewRequest.current?.abort();
    const controller = new AbortController();
    previewRequest.current = controller;
    const kb = selectedId;
    setError("");
    try {
      const prefix = `${baseUrl}/documents/${job.documentId}`;
      const [body, chunks] = await Promise.all([
        preview?.job.revisionId === job.revisionId
          ? Promise.resolve(preview.text)
          : fetchText(
              `${prefix}/content?revisionId=${job.revisionId}`,
              controller.signal,
            ),
        request<Page<Chunk>>(
          `${prefix}/chunks?revisionId=${job.revisionId}&page=${page}&size=20`,
          { signal: controller.signal },
        ),
      ]);
      if (!controller.signal.aborted && selection.current === kb)
        setPreview({ job, text: body, chunks });
    } catch (reason) {
      if (!controller.signal.aborted && selection.current === kb)
        failure(reason);
    }
  }
  function status(job: { status: string; cancelRequested?: boolean }) {
    return t(
      `workspace.status.${job.cancelRequested && ["parsing", "processing"].includes(job.status) ? "cancelling" : job.status}`,
    );
  }
  function pages(page: number, total: number, change: (n: number) => void) {
    return (
      <div className="mt-4 flex items-center justify-end gap-3 text-sm">
        <button
          className={secondary}
          disabled={page === 0}
          onClick={() => change(page - 1)}
        >
          {t("workspace.previous")}
        </button>
        <span>{page + 1}</span>
        <button
          className={secondary}
          disabled={(page + 1) * 20 >= total}
          onClick={() => change(page + 1)}
        >
          {t("workspace.next")}
        </button>
      </div>
    );
  }
  const firstBase = basesLoaded && bases.length === 0;
  const createForm = (
        <form className="space-y-4" onSubmit={create}>
          <label>
            {t("workspace.baseName")}
            <input
              autoFocus={firstBase}
              required
              maxLength={100}
              value={newName}
              disabled={busy}
              onChange={(event) => setNewName(event.target.value)}
            />
          </label>
          <button className={primary} disabled={busy || !newName.trim()}>
            {busy ? t("loading") : t("workspace.createBase")}
          </button>
        </form>
  );
  if (loading) return <p role="status" className="p-8">{t("loading")}</p>;
  if (firstBase) return (
    <section aria-labelledby="first-base-title" className="mx-auto mt-12 max-w-lg rounded-2xl border border-slate-200 bg-white p-8 shadow-sm">
      <h1 id="first-base-title" className="text-2xl font-semibold">{t("workspace.firstBaseTitle")}</h1>
      <p className="mt-3 mb-6 text-sm text-slate-500">{t("workspace.firstBaseHint")}</p>
      {createForm}
      {error && <p role="alert" className="mt-4 text-red-700">{t(`workspace.${error}`)}</p>}
    </section>
  );
  return (
    <div className="mx-auto grid max-w-screen-2xl items-start gap-6 p-4 md:grid-cols-[240px_minmax(0,1fr)] md:p-8">
      <aside className="space-y-6 rounded-xl border border-slate-200 bg-white p-5 md:sticky md:top-6">
        <label className="block text-sm font-medium">
          {t("workspace.currentBase")}
          <select className="mt-2 w-full rounded-lg border border-slate-300 bg-white p-3" value={selectedId} onChange={(event) => setSelectedId(event.target.value)} disabled={!selected}>
            {bases.map(base => <option key={base.id} value={base.id}>{base.name}</option>)}
          </select>
        </label>
        <nav aria-label={t("navigation.features")} className="space-y-2 border-t border-slate-100 pt-5">
          {features.map(([feature, label]) => (
            <button key={feature} aria-current={page === feature ? "page" : undefined}
              disabled={feature === "models" ? !admin : !selected}
              title={feature === "models" && !admin ? t("models.forbidden") : undefined}
              className={`block w-full rounded-lg px-3 py-3 text-left text-sm font-medium disabled:cursor-not-allowed disabled:opacity-40 ${page === feature ? "bg-blue-50 text-blue-700" : "text-slate-600 hover:bg-slate-50"}`}
              onClick={() => setPage(feature)}>{t(label)}</button>
          ))}
        </nav>
        <details className="border-t border-slate-100 pt-5">
          <summary className="cursor-pointer text-sm font-medium">{t("workspace.createBase")}</summary>
          <div className="mt-4">{createForm}</div>
        </details>
      </aside>
      <section className="min-w-0 space-y-6">
        {error && (
          <p role="alert" className="rounded-lg bg-red-50 p-4 text-red-700">
            {t(`workspace.${error}`)}
          </p>
        )}
        {notice && (
          <p role="status" className="rounded-lg bg-blue-50 p-4 text-blue-800">
            {t(`workspace.${notice}`)}
          </p>
        )}
        {page === "models" && admin ? (
          <Models onSessionExpired={onSessionExpired} />
        ) : !selected ? (
          <button className={secondary} onClick={() => setBaseRefresh(n => n + 1)}>
            {t("workspace.refresh")}
          </button>
        ) : (
          <>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 className="text-2xl font-semibold">{selected.name}</h2>
              </div>
              {["documents", "jobs"].includes(page) && <button
                className={secondary}
                onClick={() => {
                  setError("");
                  setRefresh((n) => n + 1);
                }}
              >
                {t("workspace.refresh")}
              </button>}
            </div>
            {!canWrite && (
              <p className="text-sm text-slate-500">
                {t("workspace.readOnly")}
              </p>
            )}
            {page === "query" && <QueryPanel
              key={"query-" + selectedId}
              kb={selectedId}
              onSessionExpired={onSessionExpired}
            />}
            {page === "retrieval" && <RetrievalPanel
              key={"retrieval-" + selectedId}
              kb={selectedId}
              onSessionExpired={onSessionExpired}
            />}
            {page === "indexes" && <IndexPanel
              key={selectedId}
              kb={selectedId}
              owner={selected?.role === "OWNER"}
              onSessionExpired={onSessionExpired}
            />}
            {page === "documents" && <>
            <section className="grid gap-6 rounded-xl border border-slate-200 bg-white p-6 lg:grid-cols-2">
              <form
                className="space-y-4"
                onSubmit={(event) => void submit(event, false)}
              >
                <h3 className="font-semibold">{t("workspace.paste")}</h3>
                <label className="block">
                  {t("workspace.sourceName")}
                  <input
                    required
                    maxLength={255}
                    value={sourceName}
                    onChange={(event) => setSourceName(event.target.value)}
                    disabled={!canWrite}
                  />
                </label>
                <label className="block">
                  {t("workspace.format")}
                  <select
                    className="ml-3 rounded border border-slate-300 p-2"
                    value={format}
                    onChange={(event) => setFormat(event.target.value)}
                    disabled={!canWrite}
                  >
                    <option value="markdown">Markdown</option>
                    <option value="text">TXT</option>
                  </select>
                </label>
                <label className="block">
                  {t("workspace.content")}
                  <textarea
                    className="mt-2 min-h-40 w-full rounded-lg border border-slate-300 p-3"
                    required
                    value={content}
                    onChange={(event) => setContent(event.target.value)}
                    disabled={!canWrite}
                  />
                </label>
                <button
                  className={primary}
                  disabled={busy || !canWrite || !validWindow}
                >
                  {t("workspace.submitText")}
                </button>
              </form>
              <div className="space-y-5">
                <form
                  className="space-y-4"
                  onSubmit={(event) => void submit(event, true)}
                >
                  <h3 className="font-semibold">{t("workspace.upload")}</h3>
                  <p className="text-sm text-slate-500">
                    {t("workspace.fileLimit")}
                  </p>
                  <label>
                    {t("workspace.chooseFile")}
                    <input
                      key={selectedId}
                      type="file"
                      accept=".txt,.md,.markdown,text/plain,text/markdown"
                      disabled={!canWrite}
                      onChange={(event) =>
                        setFile(event.target.files?.[0] ?? null)
                      }
                    />
                  </label>
                  <button
                    className={primary}
                    disabled={busy || !canWrite || !file || !validWindow}
                  >
                    {t("workspace.uploadFile")}
                  </button>
                </form>
                <details className="border-t border-slate-100 pt-4">
                  <summary className="cursor-pointer text-sm font-medium">
                    {t("workspace.chunkOptions")}
                  </summary>
                  <div className="mt-3 grid grid-cols-2 gap-3">
                    <label>
                      {t("workspace.chunkSize")}
                      <input
                        type="number"
                        min={16}
                        max={4096}
                        value={chunkSize}
                        onChange={(event) =>
                          setChunkSize(Number(event.target.value))
                        }
                        disabled={!canWrite}
                      />
                    </label>
                    <label>
                      {t("workspace.overlap")}
                      <input
                        type="number"
                        min={0}
                        max={Math.floor(chunkSize / 2)}
                        value={overlap}
                        onChange={(event) =>
                          setOverlap(Number(event.target.value))
                        }
                        disabled={!canWrite}
                      />
                    </label>
                  </div>
                  {!validWindow && (
                    <p className="mt-3 text-sm text-red-700">
                      {t("workspace.invalidWindow")}
                    </p>
                  )}
                </details>
              </div>
            </section>
            <section className="rounded-xl border border-slate-200 bg-white p-6">
              <h2 className="text-lg font-semibold">
                {t("workspace.documents")}
              </h2>
              {documents.items.length === 0 ? (
                <p className="mt-4 text-sm text-slate-500">
                  {t("workspace.noDocuments")}
                </p>
              ) : (
                <ul className="mt-4 divide-y divide-slate-100">
                  {documents.items.map((doc) => (
                    <li
                      key={doc.id}
                      className="flex flex-wrap items-center justify-between gap-2 py-3"
                    >
                      <button
                        className="text-left font-medium text-blue-700"
                        onClick={() => setFocusedId(doc.jobId)}
                      >
                        {doc.sourceName}
                      </button>
                      <span className="text-sm text-slate-500">
                        {status(doc)} ·{" "}
                        {t("workspace.chunkCount", { count: doc.chunkCount })}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
              {pages(documentPage, documents.total, setDocumentPage)}
            </section>
            </>}
            {page === "jobs" && <section className="rounded-xl border border-slate-200 bg-white p-6">
              <h2 className="text-lg font-semibold">{t("workspace.jobs")}</h2>
              {jobs.items.length === 0 ? (
                <p className="mt-4 text-sm text-slate-500">
                  {t("workspace.noJobs")}
                </p>
              ) : (
                <ul className="mt-4 divide-y divide-slate-100">
                  {jobs.items.map((job) => (
                    <li
                      key={job.id}
                      className="flex flex-wrap items-center justify-between gap-2 py-3"
                    >
                      <button
                        className="text-left text-blue-700"
                        onClick={() => setFocusedId(job.id)}
                      >
                        {job.sourceName}
                      </button>
                      <span className="text-sm text-slate-500">
                        {status(job)}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
              {pages(jobPage, jobs.total, setJobPage)}
            </section>}
            {["documents", "jobs"].includes(page) && focused && (
              <section className="rounded-xl border border-slate-200 bg-white p-6">
                <h2 className="text-lg font-semibold">
                  {t("workspace.jobDetails")}
                </h2>
                <p className="mt-3">
                  {focused.sourceName} · {status(focused)} ·{" "}
                  {t("workspace.attempts", {
                    count: focused.attempts,
                    max: focused.maxAttempts,
                  })}
                </p>
                {focused.errorCode && (
                  <p className="mt-3 text-red-700">
                    {t(`workspace.errors.${focused.errorCode}`, {
                      defaultValue: t("workspace.requestFailed"),
                    })}
                  </p>
                )}
                <div className="mt-4 flex flex-wrap gap-3">
                  <a
                    className={secondary}
                    href={`${baseUrl}/documents/${focused.documentId}/original?revisionId=${focused.revisionId}`}
                    download
                  >
                    {t("workspace.downloadOriginal")}
                  </a>
                  {["pending", "parsing", "processing"].includes(
                    focused.status,
                  ) && (
                    <button
                      className={secondary}
                      disabled={busy || !canWrite || focused.cancelRequested}
                      onClick={() => void action(focused, "cancel")}
                    >
                      {t("workspace.cancel")}
                    </button>
                  )}
                  {["failed", "cancelled"].includes(focused.status) && (
                    <button
                      className={secondary}
                      disabled={busy || !canWrite}
                      onClick={() => void action(focused, "retry")}
                    >
                      {t("workspace.retry")}
                    </button>
                  )}
                  {focused.status === "processed" && (
                    <button
                      className={secondary}
                      onClick={() => void showPreview(focused)}
                    >
                      {t("workspace.preview")}
                    </button>
                  )}
                </div>
                <ol className="mt-5 space-y-2 text-sm text-slate-500">
                  {focused.events?.map((event, index) => (
                    <li key={index}>
                      {t(`workspace.events.${event.event}`, {
                        defaultValue: event.event,
                      })}{" "}
                      · {new Date(event.createdAt).toLocaleString()}
                    </li>
                  ))}
                </ol>
              </section>
            )}
            {["documents", "jobs"].includes(page) && preview && (
              <section className="rounded-xl border border-slate-200 bg-white p-6">
                <h2 className="text-lg font-semibold">
                  {t("workspace.original")}
                </h2>
                <pre className="mt-4 max-h-80 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-slate-50 p-4 text-sm">
                  {preview.text}
                </pre>
                <h3 className="mt-6 font-semibold">{t("workspace.chunks")}</h3>
                <ol className="mt-4 space-y-4">
                  {preview.chunks.items.map((chunk) => (
                    <li
                      key={chunk.order}
                      className="rounded-lg border border-slate-200 p-4"
                    >
                      <p className="text-xs text-slate-500">
                        #{chunk.order + 1} · {chunk.tokens} token ·{" "}
                        {t("workspace.sourceRange", {
                          start: chunk.sourceStart,
                          end: chunk.sourceEnd,
                        })}
                      </p>
                      {chunk.headings.length > 0 && (
                        <p className="mt-2 text-sm font-medium">
                          {chunk.headings.join(" / ")}
                        </p>
                      )}
                      <pre className="mt-2 whitespace-pre-wrap break-all text-sm">
                        {chunk.content}
                      </pre>
                    </li>
                  ))}
                </ol>
                {pages(
                  preview.chunks.page,
                  preview.chunks.total,
                  (page) => void showPreview(preview.job, page),
                )}
              </section>
            )}
          </>
        )}
      </section>
    </div>
  );
}
