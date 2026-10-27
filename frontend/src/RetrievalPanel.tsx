import { useEffect, useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, mutate, request } from "./api";
type Origin = {
  documentId: string;
  revisionId: string;
  sourceName: string;
  chunkOrder: number;
  sourceStart: number;
  sourceEnd: number;
  headings: string[];
};
type Chunk = {
  chunk_id: string;
  content: string;
  reference_id: string;
  reference_ids?: string[];
  sources: Origin[];
  retrievalPaths?: string[];
  score?: number;
};
type Result = {
  status: string;
  mode: string;
  generationId: string;
  embeddingVersion: string;
  entities: { id: string; name: string; type: string; description: string }[];
  relations: {
    id: string;
    source: string;
    target: string;
    description: string;
    weight: number;
  }[];
  chunks: Chunk[];
  references: { reference_id: string; file_path: string }[];
  context: string;
  metadata: {
    branches: string[];
    budget: { totalTokens: number; maxTotalTokens: number };
    counts?: Record<string, number>;
    vectorStrategy?: string;
    elapsedMs?: number;
  };
};
type Source = {
  sourceName: string;
  excerpt: string;
  excerptStart: number;
  sourceStart: number;
  sourceEnd: number;
};
const defaults = {
  mode: "mix",
  topK: 20,
  chunkTopK: 20,
  relatedChunkNumber: 5,
  chunkSelection: "VECTOR",
  maxEntityTokens: 6000,
  maxRelationTokens: 8000,
  maxTotalTokens: 16000,
  cosineThreshold: 0.2,
};
export default function RetrievalPanel({
  kb,
  onSessionExpired,
}: {
  kb: string;
  onSessionExpired: () => void;
}) {
  const { t } = useTranslation(),
    [query, setQuery] = useState(""),
    [form, setForm] = useState({ ...defaults }),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [result, setResult] = useState<Result | null>(null),
    [source, setSource] = useState<Source | null>(null),
    [manual, setManual] = useState(false),
    [high, setHigh] = useState(""),
    [low, setLow] = useState("");
  const queryAbort = useRef<AbortController | null>(null),
    sourceAbort = useRef<AbortController | null>(null),
    sequence = useRef(0);
  useEffect(() => {
    sequence.current++;
    setResult(null);
    setSource(null);
    setError("");
    setBusy(false);
    setQuery("");
    setHigh("");
    setLow("");
    return () => {
      queryAbort.current?.abort();
      sourceAbort.current?.abort();
    };
  }, [kb]);
  function fail(e: unknown) {
    if (e instanceof ApiError && e.status === 401) {
      onSessionExpired();
      return;
    }
    const known =
      e instanceof ApiError &&
      e.code &&
      t("retrieval.errors." + e.code, { defaultValue: "" });
    setError(known || t("retrieval.failure"));
  }
  async function search(event: FormEvent) {
    event.preventDefault();
    queryAbort.current?.abort();
    sourceAbort.current?.abort();
    const controller = new AbortController();
    queryAbort.current = controller;
    const seq = ++sequence.current;
    setBusy(true);
    setResult(null);
    setSource(null);
    setError("");
    try {
      const body = {
        query,
        ...form,
        ...(manual && form.mode !== "naive"
          ? {
              highLevelKeywords: high
                .split("\n")
                .map((v) => v.trim())
                .filter(Boolean),
              lowLevelKeywords: low
                .split("\n")
                .map((v) => v.trim())
                .filter(Boolean),
            }
          : {}),
      };
      const found = await mutate<Result>(
        "/api/knowledge-bases/" + kb + "/retrieval",
        body,
        "POST",
        controller.signal,
      );
      if (!controller.signal.aborted && sequence.current === seq)
        setResult(found);
    } catch (e) {
      if (!controller.signal.aborted && sequence.current === seq) fail(e);
    } finally {
      if (sequence.current === seq) setBusy(false);
    }
  }
  async function preview(chunk: Chunk, origin: Origin) {
    if (!result) return;
    sourceAbort.current?.abort();
    const controller = new AbortController();
    sourceAbort.current = controller;
    const seq = sequence.current;
    setError("");
    setSource(null);
    const params = new URLSearchParams({
      generationId: result.generationId,
      chunkId: chunk.chunk_id,
      documentId: origin.documentId,
      revisionId: origin.revisionId,
      chunkOrder: String(origin.chunkOrder),
    });
    try {
      const found = await request<Source>(
        "/api/knowledge-bases/" + kb + "/retrieval/source?" + params,
        { signal: controller.signal },
      );
      if (!controller.signal.aborted && seq === sequence.current)
        setSource(found);
    } catch (e) {
      if (!controller.signal.aborted && seq === sequence.current) fail(e);
    }
  }
  function numeric(
    key: keyof typeof defaults,
    label: string,
    min: number,
    max: number,
    step = 1,
  ) {
    return (
      <label>
        {t("retrieval." + label)}
        <input
          type="number"
          required
          min={min}
          max={max}
          step={step}
          value={form[key]}
          onChange={(e) => setForm({ ...form, [key]: Number(e.target.value) })}
        />
      </label>
    );
  }
  const characters = source ? Array.from(source.excerpt) : [],
    start = source ? source.sourceStart - source.excerptStart : 0,
    end = source ? source.sourceEnd - source.excerptStart : 0;
  return (
    <section className="space-y-5 rounded-xl border border-slate-200 bg-white p-6">
      <h2 className="text-xl font-semibold">{t("retrieval.title")}</h2>
      <p className="text-sm text-slate-500">{t("retrieval.hint")}</p>
      <form onSubmit={search} className="space-y-4">
        <label className="block">
          {t("retrieval.query")}
          <textarea
            required
            maxLength={8000}
            rows={3}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </label>
        <div className="grid gap-4 md:grid-cols-3">
          <label>
            {t("retrieval.mode")}
            <select
              value={form.mode}
              onChange={(e) => setForm({ ...form, mode: e.target.value })}
            >
              {["naive", "local", "global", "hybrid", "mix"].map((m) => (
                <option key={m} value={m}>
                  {t("retrieval.modes." + m)}
                </option>
              ))}
            </select>
          </label>
          {numeric("topK", "topK", 1, 100)}
          {numeric("chunkTopK", "chunkTopK", 1, 100)}
        </div>
        <details>
          <summary className="cursor-pointer text-sm text-slate-600">
            {t("retrieval.advanced")}
          </summary>
          <div className="mt-4 grid gap-4 md:grid-cols-3">
            {numeric("relatedChunkNumber", "related", 0, 20)}
            <label>
              {t("retrieval.selection")}
              <select
                value={form.chunkSelection}
                onChange={(e) =>
                  setForm({ ...form, chunkSelection: e.target.value })
                }
              >
                <option value="VECTOR">{t("retrieval.vector")}</option>
                <option value="WEIGHT">{t("retrieval.weight")}</option>
              </select>
            </label>
            {numeric("cosineThreshold", "threshold", -1, 1, 0.01)}
            {numeric("maxEntityTokens", "entityBudget", 0, 16000)}
            {numeric("maxRelationTokens", "relationBudget", 0, 16000)}
            {numeric("maxTotalTokens", "totalBudget", 1024, 64000)}
          </div>
          <label className="my-3 flex items-center gap-2">
            <input
              type="checkbox"
              checked={manual}
              disabled={form.mode === "naive"}
              onChange={(e) => setManual(e.target.checked)}
            />
            {t("retrieval.manual")}
          </label>
          {manual && form.mode !== "naive" && (
            <div className="grid gap-4 md:grid-cols-2">
              <label>
                {t("retrieval.high")}
                <textarea
                  rows={3}
                  value={high}
                  onChange={(e) => setHigh(e.target.value)}
                />
              </label>
              <label>
                {t("retrieval.low")}
                <textarea
                  rows={3}
                  value={low}
                  onChange={(e) => setLow(e.target.value)}
                />
              </label>
            </div>
          )}
        </details>
        <div className="flex gap-4">
          <button
            disabled={busy}
            className="rounded-lg bg-blue-600 px-4 py-2 text-white disabled:opacity-40"
          >
            {busy ? t("loading") : t("retrieval.search")}
          </button>
          {busy && (
            <button
              type="button"
              onClick={() => {
                queryAbort.current?.abort();
                setBusy(false);
              }}
            >
              {t("retrieval.stopWaiting")}
            </button>
          )}
        </div>
      </form>
      {error && (
        <p role="alert" className="text-red-700">
          {error}
        </p>
      )}
      {result && (
        <div className="space-y-5 border-t border-slate-100 pt-5">
          {result.status === "empty" ? (
            <p>{t("retrieval.empty")}</p>
          ) : (
            <>
              <h3 className="font-semibold">{t("retrieval.evidence")}</h3>
              <ul className="space-y-4">
                {result.chunks.map((c) => (
                  <li key={c.chunk_id} className="rounded-lg bg-slate-50 p-4">
                    <p className="mb-2 text-xs text-slate-500">
                      [{c.reference_id}] ·{" "}
                      {(c.retrievalPaths ?? [])
                        .map((p) => t("retrieval.paths." + p))
                        .join(" / ")}
                    </p>
                    <p className="whitespace-pre-wrap text-sm">{c.content}</p>
                    <div className="mt-3 flex flex-wrap gap-3">
                      {(c.sources ?? []).map((o, i) => (
                        <button
                          key={i}
                          className="text-sm text-blue-700"
                          onClick={() => void preview(c, o)}
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
              <div>
                <h3 className="mb-2 font-semibold">
                  {t("retrieval.references")}
                </h3>
                <ul className="space-y-1 text-sm">
                  {result.references.map((r) => (
                    <li key={r.reference_id}>
                      [{r.reference_id}] <span>{r.file_path}</span>
                    </li>
                  ))}
                </ul>
              </div>
            </>
          )}
          {source && (
            <section className="rounded-lg border border-blue-100 p-4">
              <h3 className="font-semibold">{source.sourceName}</h3>
              <p className="mt-3 whitespace-pre-wrap text-sm">
                {characters.slice(0, start).join("")}
                <mark>{characters.slice(start, end).join("")}</mark>
                {characters.slice(end).join("")}
              </p>
            </section>
          )}
          <details>
            <summary className="cursor-pointer">{t("retrieval.graph")}</summary>
            <div className="mt-3 grid gap-5 md:grid-cols-2">
              <div>
                {result.entities.map((e) => (
                  <p key={e.id} className="my-3 text-sm">
                    <strong>{e.name}</strong> · {e.type}
                    <br />
                    {e.description}
                  </p>
                ))}
              </div>
              <div>
                {result.relations.map((r) => (
                  <p key={r.id} className="my-3 text-sm">
                    <strong>
                      {r.source} ↔ {r.target}
                    </strong>{" "}
                    · {r.weight}
                    <br />
                    {r.description}
                  </p>
                ))}
              </div>
            </div>
          </details>
          <details>
            <summary className="cursor-pointer">
              {t("retrieval.diagnostics")}
            </summary>
            <p className="my-3 break-all text-xs text-slate-500">
              {t("retrieval.generation")}: {result.generationId}
            </p>
            <pre className="overflow-auto rounded-lg bg-slate-50 p-3 text-xs">
              {JSON.stringify(result.metadata, null, 2)}
            </pre>
            <h3 className="mt-4 font-semibold">{t("retrieval.context")}</h3>
            <pre className="max-h-96 overflow-auto whitespace-pre-wrap rounded-lg bg-slate-50 p-3 text-xs">
              {result.context}
            </pre>
          </details>
        </div>
      )}
    </section>
  );
}
