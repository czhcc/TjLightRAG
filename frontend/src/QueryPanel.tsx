import { useEffect, useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, mutate, request } from "./api";
import { queryStream } from "./queryStream";
import MarkdownAnswer from "./MarkdownAnswer";
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
type Conversation = { id: string; title: string };
type Turn = { id: string; sequence: number; question: string; answer: string; status: string;
  result?: { retrieval?: Evidence; unknownReferenceIds?: string[]; rerankStatus?: string } | null };
/** Private conversations with safe Markdown replies and plain-text evidence and questions. */
export default function QueryPanel({
  kb,
  onSessionExpired,
}: {
  kb: string;
  onSessionExpired: () => void;
}) {
  const { t } = useTranslation();
  const [conversations, setConversations] = useState<Conversation[]>([]), [conversation, setConversation] = useState("");
  const [turns, setTurns] = useState<Turn[]>([]), [submitted, setSubmitted] = useState("");
  const [loadingHistory, setLoadingHistory] = useState(true), [historyPage, setHistoryPage] = useState(0);
  const [moreConversations, setMoreConversations] = useState(false), [moreTurns, setMoreTurns] = useState(false);
  const navigation = useRef<AbortController | null>(null);
  const log = useRef<HTMLDivElement | null>(null);
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
  useEffect(() => { if (log.current) log.current.scrollTop = log.current.scrollHeight; }, [submitted, conversation, loadingHistory]);
  useEffect(() => {
    const element = log.current;
    if (element && element.scrollHeight - element.scrollTop - element.clientHeight < 160) element.scrollTop = element.scrollHeight;
  }, [answer]);
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
    const seq = ++sequence.current;
    const controller = new AbortController(); navigation.current = controller;
    setConversations([]); setConversation(""); setTurns([]); setSubmitted(""); setLoadingHistory(true);
    setHistoryPage(0); setMoreConversations(false); setMoreTurns(false);
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
    void (async () => {
      try {
        const items = await request<Conversation[]>(`/api/knowledge-bases/${kb}/conversations`, { signal: controller.signal });
        if (controller.signal.aborted || sequence.current !== seq) return;
        setConversations(items); setMoreConversations(items.length === 50);
      } catch (e) { if (!controller.signal.aborted && sequence.current === seq) fail(e, true); }
      finally { if (!controller.signal.aborted && sequence.current === seq) setLoadingHistory(false); }
    })();
    return () => {
      sequence.current++;
      controller.abort(); navigation.current?.abort();
      cancel();
    };
  }, [kb]);
  async function choose(id: string) {
    sequence.current++; cancel(); navigation.current?.abort();
    const controller = new AbortController(); navigation.current = controller;
    const seq = sequence.current;
    setConversation(id); setTurns([]); setSubmitted(""); setQuestion(""); setAnswer(""); setState("");
    setEvidence(null); setPreview(""); setSource(null); setError(""); setUnknown(false); setRerankStatus("");
    setBusy(false); setMoreTurns(false); setLoadingHistory(!!id);
    if (!id) return;
    try {
      const saved = await request<Turn[]>(`/api/knowledge-bases/${kb}/conversations/${id}/turns`, { signal: controller.signal });
      if (!controller.signal.aborted && sequence.current === seq) { setTurns(saved); setMoreTurns(saved.length === 50); }
    } catch (e) { if (!controller.signal.aborted && sequence.current === seq) fail(e, true); }
    finally { if (!controller.signal.aborted && sequence.current === seq) setLoadingHistory(false); }
  }
  async function older(kind: "conversations" | "turns") {
    const signal = navigation.current?.signal; if (!signal || signal.aborted || busy || loadingHistory) return;
    const seq = sequence.current; setLoadingHistory(true);
    try {
      if (kind === "conversations") {
        const page = historyPage + 1;
        const items = await request<Conversation[]>(`/api/knowledge-bases/${kb}/conversations?page=${page}`, { signal });
        if (!signal.aborted && sequence.current === seq) {
          setConversations(old => [...old, ...items.filter(item => !old.some(existing => existing.id === item.id))]);
          setHistoryPage(page); setMoreConversations(items.length === 50);
        }
      } else {
        const items = await request<Turn[]>(`/api/knowledge-bases/${kb}/conversations/${conversation}/turns?before=${turns[0].sequence}`, { signal });
        if (!signal.aborted && sequence.current === seq) { setTurns(old => [...items, ...old]); setMoreTurns(items.length === 50); }
      }
    } catch (e) { if (!signal.aborted && sequence.current === seq) fail(e, true); }
    finally { if (!signal.aborted && sequence.current === seq) setLoadingHistory(false); }
  }
  function fail(e: unknown, history = false) {
    if (e instanceof ApiError && e.status === 401) onSessionExpired();
    else if (e instanceof ApiError && e.code === "retrieval_no_active_index")
      setError(t("retrieval.errors.retrieval_no_active_index"));
    else if (e instanceof ApiError && ["query_budget_too_small", "retrieval_budget_too_small", "model_context_exceeded"].includes(e.code ?? ""))
      setError(t("query.budgetFailure"));
    else if (e instanceof ApiError && e.code === "query_conversation_busy") setError(t("query.conversationBusy"));
    else setError(t(history ? "query.historyFailure" : "query.failure"));
  }
  async function generate(event: FormEvent, kind = "answer") {
    event.preventDefault();
    if (busy || loadingHistory || !question.trim()) return;
    cancel();
    const controller = new AbortController(),
      id = crypto.randomUUID(),
      seq = ++sequence.current;
    active.current = { controller, kb, id };
    setBusy(true);
    if (submitted) setTurns(old => [...old, { id: `local-${seq}`, sequence: 0, question: submitted, answer,
      status: state || "failed", result: { retrieval: evidence ?? undefined, rerankStatus, unknownReferenceIds: unknown ? ["?"] : [] } }]);
    setSubmitted(kind === "answer" ? question : ""); setAnswer("");
    if (kind === "answer") setQuestion("");
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
      conversationId: conversation || undefined,
    };
    try {
      if (kind === "answer" && !body.conversationId) {
        const created = await mutate<Conversation>(`/api/knowledge-bases/${kb}/conversations`, undefined, "POST", controller.signal);
        if (!current()) return;
        body.conversationId = created.id; setConversation(created.id);
        setConversations(old => [{ ...created, title: Array.from(question).slice(0, 80).join("") }, ...old]);
      }
      if (kind === "answer") setConversations(old => {
        const selected = old.find(item => item.id === body.conversationId);
        return selected ? [{ ...selected, title: selected.title || Array.from(question).slice(0, 80).join("") }, ...old.filter(item => item.id !== selected.id)] : old;
      });
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
              const result = data as { retrieval?: Evidence };
              if (result.retrieval) setEvidence(result.retrieval);
            }
          },
          controller.signal,
        );
      }
    } catch (e) {
      if (current()) { if (kind === "answer") setState("failed"); fail(e); }
    } finally {
      if (sequence.current === seq) {
        setBusy(false);
        active.current = null;
      }
    }
  }
  async function view(chunkId: string, origin: Origin, context = evidence) {
    if (!context) return;
    sourceAbort.current?.abort();
    const controller = new AbortController();
    sourceAbort.current = controller;
    const seq = sequence.current;
    setSource(null);
    const params = new URLSearchParams({
      generationId: context.generationId,
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
  const empty = !turns.length && !submitted && !busy && !preview && !evidence && !error && !state;
  return (
    <section className="conversation-shell">
      <aside className="conversation-sidebar">
        <button type="button" className="w-full rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium" onClick={() => void choose("")}><span aria-hidden="true">＋ </span>{t("query.newConversation")}</button>
        <h3 className="mt-6 mb-3 text-xs font-semibold text-slate-500">{t("query.history")}</h3>
        <nav aria-label={t("query.history")} className="conversation-history">
          {conversations.map(item => <button key={item.id} type="button" title={item.title || t("query.newConversation")}
            aria-current={conversation === item.id ? "page" : undefined}
            onClick={() => void choose(item.id)}>{item.title || t("query.newConversation")}</button>)}
        </nav>
        {moreConversations && <button type="button" disabled={loadingHistory || busy} onClick={() => void older("conversations")}>{t("query.moreHistory")}</button>}
      </aside>
      <div className="conversation-main">
      <header className="conversation-header flex flex-wrap items-center gap-4"><h2 className="text-lg font-semibold">{t("query.title")}</h2>
        <div className="ml-auto flex flex-wrap items-center justify-end gap-5">
          <label className="flex items-center gap-2 text-sm font-medium">
            {t("query.mode")}
            <select
              className="block rounded-lg border border-slate-300 bg-white px-3 py-2 font-normal"
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
      </header>
      {loadingHistory && <p role="status">{t("loading")}</p>}
      <div className="conversation-stage" data-empty={empty}>
      <div ref={log} role="log" aria-label={t("query.messages")} aria-live="polite" className="conversation-messages space-y-5" hidden={empty}>
        {moreTurns && <button type="button" disabled={loadingHistory || busy} onClick={() => void older("turns")}>{t("query.olderMessages")}</button>}
        {turns.map(turn => <article key={turn.id} className="space-y-3 border-b border-slate-100 pb-5">
          <p className="ml-auto w-fit max-w-1/2 whitespace-pre-wrap break-words rounded-lg bg-blue-50 p-3 text-right">{turn.question}</p>
          <p className="text-sm font-medium">{t("query.assistant")}</p>
          <div aria-label={t("query.answer")} className="rounded-lg bg-slate-50 p-4 text-sm"><MarkdownAnswer text={turn.answer} /></div>
          <p className="text-sm text-slate-500">{t(`query.states.${turn.status}`)}</p>
          {!!turn.result?.unknownReferenceIds?.length && <p className="text-amber-700">{t("query.unknownCitation")}</p>}
          {turn.result?.rerankStatus && <p className="text-xs text-slate-500">{t(`query.rerankStates.${turn.result.rerankStatus}`)}</p>}
          {turn.result?.retrieval && <details>
            <summary className="cursor-pointer text-sm">{t("retrieval.evidence")}</summary>
            <div className="mt-3 space-y-3">
              {!turn.result.retrieval.chunks.length && <p className="text-sm text-slate-500">{t("retrieval.noChunks")}</p>}
              {turn.result.retrieval.references.map(r => <p key={r.reference_id} className="text-sm">[{r.reference_id}] {r.file_path}</p>)}
              {turn.result.retrieval.chunks.map(c => <div className="rounded-lg bg-slate-50 p-3" key={c.chunk_id}>
                <p className="whitespace-pre-wrap text-sm">[{c.reference_id}] {c.content}</p>
                <div className="mt-2 flex flex-wrap gap-3">{c.sources.map((origin, i) => <button key={i} className="text-sm text-blue-700"
                  onClick={() => void view(c.chunk_id, origin, turn.result!.retrieval)}>{t("retrieval.viewSource", { name: origin.sourceName, order: origin.chunkOrder + 1 })}</button>)}</div>
              </div>)}
            </div>
          </details>}
        </article>)}
        {submitted && <>
          <p className="ml-auto w-fit max-w-1/2 whitespace-pre-wrap break-words rounded-lg bg-blue-50 p-3 text-right">{submitted}</p><p className="text-sm font-medium">{t("query.assistant")}</p></>}

      {error && (
        <p role="alert" className="text-red-700">
          {error}
        </p>
      )}
      {answer && (
        <div
          className="rounded-lg bg-slate-50 p-4 text-sm"
          aria-label={t("query.answer")}
        >
          <MarkdownAnswer text={answer} />
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
              {!evidence.chunks.length && <li className="text-sm text-slate-500">{t("retrieval.noChunks")}</li>}
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
      </div>
      <div className="conversation-composer-area">
      {empty && <div className="mb-6 text-center"><h3 className="text-2xl font-semibold text-slate-800">{t("query.emptyConversation")}</h3><p className="mt-3 text-sm leading-6 text-slate-500">{t("query.hint")}</p></div>}
      <form onSubmit={generate} aria-label={t("query.compose")} data-position={empty ? "center" : "bottom"} className="conversation-composer space-y-3">
        <label className="block text-sm font-medium">
          {t("query.question")}
          <textarea
            className="mt-2 block w-full resize-none rounded-lg border-0 bg-white p-2 font-normal"
            placeholder={t("query.placeholder")}
            required
            maxLength={8000}
            rows={3}
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing && e.keyCode !== 229) {
                e.preventDefault();
                e.currentTarget.form?.requestSubmit();
              }
            }}
          />
        </label>
        <div className="flex flex-wrap justify-end gap-4">
          <button
            disabled={busy || loadingHistory}
            className="rounded-lg bg-blue-600 px-4 py-2 text-white disabled:opacity-40"
          >
            {busy ? t("loading") : t("query.send")}
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
            disabled={busy || loadingHistory || !question.trim()}
            className="rounded-lg border border-slate-300 px-3 py-2 text-sm disabled:opacity-40"
            onClick={(e) => void generate(e, "context")}
          >
            {t("query.previewContext")}
          </button>
          <button
            type="button"
            disabled={busy || loadingHistory || !question.trim()}
            className="rounded-lg border border-slate-300 px-3 py-2 text-sm disabled:opacity-40"
            onClick={(e) => void generate(e, "prompt")}
          >
            {t("query.previewPrompt")}
          </button>
        </div>
      </form>
      <p className="mt-2 text-center text-xs text-slate-400">{t("query.keyboardHint")}</p>
      </div>
      </div>
      </div>
    </section>
  );
}
