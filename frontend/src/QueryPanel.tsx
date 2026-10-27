import { useEffect, useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, mutate, request } from "./api";
import { queryStream } from "./queryStream";
type Origin = {
  documentId: string;
  revisionId: string;
  sourceName: string;
  chunkOrder: number;
};
type Evidence = {
  generationId: string;
  chunks: {
    chunk_id: string;
    content: string;
    reference_id: string;
    sources: Origin[];
  }[];
  references: { reference_id: string; file_path: string }[];
  context: string;
};
type Source = {
  sourceName: string;
  excerpt: string;
  excerptStart: number;
  sourceStart: number;
  sourceEnd: number;
};
type Preview = {
  retrieval: Evidence;
  prompt: { role: string; content: string }[];
};
type Active = { controller: AbortController; kb: string; id: string };
/** Single-turn answers remain plain text; document/model HTML is never executed. */
export default function QueryPanel({
  kb,
  onSessionExpired,
}: {
  kb: string;
  onSessionExpired: () => void;
}) {
  const { t } = useTranslation();
  const [question, setQuestion] = useState(""),
    [mode, setMode] = useState("mix"),
    [rerank, setRerank] = useState(false);
  const [busy, setBusy] = useState(false),
    [answer, setAnswer] = useState(""),
    [state, setState] = useState("");
  const [error, setError] = useState(""),
    [evidence, setEvidence] = useState<Evidence | null>(null);
  const [source, setSource] = useState<Source | null>(null),
    [preview, setPreview] = useState("");
  const [rerankStatus, setRerankStatus] = useState(""),
    [unknown, setUnknown] = useState(false);
  const active = useRef<Active | null>(null),
    sequence = useRef(0),
    sourceAbort = useRef<AbortController | null>(null);
  function cancel() {
    const run = active.current;
    if (run) {
      active.current = null;
      run.controller.abort();
      void mutate(
        `/api/knowledge-bases/${run.kb}/query/${run.id}/cancel`,
        undefined,
        "POST",
        AbortSignal.timeout(5000),
      ).catch(() => {});
    }
    sourceAbort.current?.abort();
  }
  useEffect(() => {
    sequence.current++;
    setQuestion("");
    setAnswer("");
    setEvidence(null);
    setSource(null);
    setPreview("");
    setState("");
    setError("");
    setBusy(false);
    setRerankStatus("");
    setUnknown(false);
    return () => {
      sequence.current++;
      cancel();
    };
  }, [kb]);
  function fail(e: unknown) {
    if (e instanceof ApiError && e.status === 401) onSessionExpired();
    else setError(t("query.failure"));
  }
  async function generate(event: FormEvent, kind = "answer") {
    event.preventDefault();
    cancel();
    const controller = new AbortController(),
      id = crypto.randomUUID(),
      seq = ++sequence.current;
    active.current = { controller, kb, id };
    setBusy(true);
    setAnswer("");
    setEvidence(null);
    setSource(null);
    setPreview("");
    setState("");
    setError("");
    setRerankStatus("");
    setUnknown(false);
    const current = () =>
      sequence.current === seq && !controller.signal.aborted;
    const body = {
      query: question,
      mode,
      enableRerank: rerank,
      requestId: id,
      preview: kind,
    };
    try {
      if (kind !== "answer") {
        const found = await mutate<Preview>(
          `/api/knowledge-bases/${kb}/query`,
          body,
          "POST",
          controller.signal,
        );
        if (current()) {
          setEvidence(found.retrieval);
          setPreview(
            kind === "prompt"
              ? found.prompt.map((m) => `${m.role}\n${m.content}`).join("\n\n")
              : found.retrieval.context,
          );
          setState("preview");
        }
      } else {
        await queryStream(
          `/api/knowledge-bases/${kb}/query/stream`,
          body,
          (event, data) => {
            if (!current()) return;
            if (event === "metadata")
              setRerankStatus((data as { rerankStatus: string }).rerankStatus);
            if (event === "references") setEvidence(data as Evidence);
            if (event === "delta")
              setAnswer((old) => old + (data as { text: string }).text);
            if (event === "done") {
              setState((data as { status: string }).status);
              setUnknown(
                (
                  (data as { unknownReferenceIds?: string[] })
                    .unknownReferenceIds ?? []
                ).length > 0,
              );
            }
          },
          controller.signal,
        );
      }
    } catch (e) {
      if (current()) fail(e);
    } finally {
      if (sequence.current === seq) {
        setBusy(false);
        active.current = null;
      }
    }
  }
  async function view(chunkId: string, origin: Origin) {
    if (!evidence) return;
    sourceAbort.current?.abort();
    const controller = new AbortController();
    sourceAbort.current = controller;
    const seq = sequence.current;
    setSource(null);
    const params = new URLSearchParams({
      generationId: evidence.generationId,
      chunkId,
      documentId: origin.documentId,
      revisionId: origin.revisionId,
      chunkOrder: String(origin.chunkOrder),
    });
    try {
      const found = await request<Source>(
        `/api/knowledge-bases/${kb}/retrieval/source?${params}`,
        { signal: controller.signal },
      );
      if (!controller.signal.aborted && sequence.current === seq)
        setSource(found);
    } catch (e) {
      if (!controller.signal.aborted && sequence.current === seq) fail(e);
    }
  }
  const chars = Array.from(source?.excerpt ?? ""),
    start = source ? source.sourceStart - source.excerptStart : 0,
    end = source ? source.sourceEnd - source.excerptStart : 0;
  return (
    <section className="space-y-5 rounded-xl border border-slate-200 bg-white p-6">
      <h2 className="text-xl font-semibold">{t("query.title")}</h2>
      <p className="text-sm text-slate-500">{t("query.hint")}</p>
      <form onSubmit={generate} className="space-y-4">
        <label className="block text-sm font-medium">
          {t("query.question")}
          <textarea
            className="mt-2 block w-full rounded-lg border border-slate-300 bg-white p-3 font-normal"
            required
            maxLength={8000}
            rows={3}
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
          />
        </label>
        <div className="flex flex-wrap items-end gap-5">
          <label className="text-sm font-medium">
            {t("query.mode")}
            <select
              className="mt-2 block rounded-lg border border-slate-300 bg-white px-3 py-2 font-normal"
              value={mode}
              onChange={(e) => setMode(e.target.value)}
            >
              {["naive", "local", "global", "hybrid", "mix"].map((m) => (
                <option key={m} value={m}>
                  {t("retrieval.modes." + m)}
                </option>
              ))}
            </select>
          </label>
          <label className="flex items-center gap-2 whitespace-nowrap py-2 text-sm">
            <input
              type="checkbox"
              className="m-0 h-4 w-4 shrink-0 p-0 accent-blue-600"
              checked={rerank}
              onChange={(e) => setRerank(e.target.checked)}
            />
            {t("query.rerank")}
          </label>
        </div>
        <div className="flex flex-wrap gap-4">
          <button
            disabled={busy}
            className="rounded-lg bg-blue-600 px-4 py-2 text-white disabled:opacity-40"
          >
            {busy ? t("loading") : t("query.generate")}
          </button>
          {busy && (
            <button
              type="button"
              onClick={() => {
                sequence.current++;
                cancel();
                setBusy(false);
                setState("cancelled");
              }}
            >
              {t("query.stop")}
            </button>
          )}
          <button
            type="button"
            disabled={busy || !question.trim()}
            className="rounded-lg border border-slate-300 px-3 py-2 text-sm disabled:opacity-40"
            onClick={(e) => void generate(e, "context")}
          >
            {t("query.previewContext")}
          </button>
          <button
            type="button"
            disabled={busy || !question.trim()}
            className="rounded-lg border border-slate-300 px-3 py-2 text-sm disabled:opacity-40"
            onClick={(e) => void generate(e, "prompt")}
          >
            {t("query.previewPrompt")}
          </button>
        </div>
      </form>
      {error && (
        <p role="alert" className="text-red-700">
          {error}
        </p>
      )}
      {answer && (
        <div
          className="whitespace-pre-wrap rounded-lg bg-slate-50 p-4 text-sm"
          aria-label={t("query.answer")}
        >
          {answer}
        </div>
      )}
      {state && (
        <p role="status" className="text-sm text-slate-600">
          {t("query.states." + state)}
        </p>
      )}
      {unknown && (
        <p className="text-amber-700">{t("query.unknownCitation")}</p>
      )}
      {rerankStatus && (
        <p className="text-xs text-slate-500">
          {t("query.rerankStates." + rerankStatus)}
        </p>
      )}
      {evidence && (
        <div className="space-y-4 border-t border-slate-100 pt-4">
          <h3 className="font-semibold">{t("retrieval.references")}</h3>
          {evidence.references.map((r) => (
            <p key={r.reference_id} className="text-sm">
              [{r.reference_id}] {r.file_path}
            </p>
          ))}
          <details>
            <summary className="cursor-pointer">
              {t("retrieval.evidence")}
            </summary>
            <ul className="mt-3 space-y-3">
              {evidence.chunks.map((c) => (
                <li key={c.chunk_id} className="rounded-lg bg-slate-50 p-3">
                  <p className="whitespace-pre-wrap text-sm">
                    [{c.reference_id}] {c.content}
                  </p>
                  <div className="mt-2 flex flex-wrap gap-3">
                    {c.sources.map((o, i) => (
                      <button
                        key={i}
                        className="text-sm text-blue-700"
                        onClick={() => void view(c.chunk_id, o)}
                      >
                        {t("retrieval.viewSource", {
                          name: o.sourceName,
                          order: o.chunkOrder + 1,
                        })}
                      </button>
                    ))}
                  </div>
                </li>
              ))}
            </ul>
          </details>
        </div>
      )}
      {source && (
        <section className="rounded-lg border border-blue-100 p-4">
          <h3 className="font-semibold">{source.sourceName}</h3>
          <p className="mt-3 whitespace-pre-wrap text-sm">
            {chars.slice(0, start).join("")}
            <mark>{chars.slice(start, end).join("")}</mark>
            {chars.slice(end).join("")}
          </p>
        </section>
      )}
      {preview && (
        <pre className="max-h-96 overflow-auto whitespace-pre-wrap rounded-lg bg-slate-50 p-3 text-xs">
          {preview}
        </pre>
      )}
    </section>
  );
}
