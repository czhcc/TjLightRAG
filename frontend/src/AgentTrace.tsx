import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, request } from "./api";
import Modal from "./Modal";
type Span = { id: string; parentId?: string; sequence: number; name: string; label: string; status: string; offsetMs: number; durationMs: number; revision: number; summary?: unknown };
type Trace = { status: string; availability: string; spans: Span[] };
type Payload = { availability: string; content: string; parts: number };
/** An owned-turn timeline; protocol bodies remain plain text and are fetched only on demand. */
export default function AgentTrace({ kb, conversation, turn, close, onSessionExpired, live = false }: {
  kb: string; conversation: string; turn: string; close: () => void; onSessionExpired?: () => void; live?: boolean;
}) {
  const { t } = useTranslation();
  const [trace, setTrace] = useState<Trace | null>(null), [error, setError] = useState("");
  const [selected, setSelected] = useState(""), [side, setSide] = useState("request"), [part, setPart] = useState(0);
  const [payload, setPayload] = useState<Payload | null>(null), [loading, setLoading] = useState(false);
  const payloadController = useRef<AbortController | null>(null);
  const base = `/api/knowledge-bases/${kb}/conversations/${conversation}/turns/${turn}/trace`;
  useEffect(() => {
    const controller = new AbortController(); let timer: ReturnType<typeof setTimeout>;
    async function refresh() {
      try {
        // Refresh loaded spans too: running spans retain their sequence as revisions advance.
        const result = await request<Trace>(`${base}?size=200`, { signal: controller.signal });
        if (controller.signal.aborted) return;
        setTrace(result); setError("");
        if (result.status === "running" || live && result.status === "not_recorded") timer = setTimeout(() => void refresh(), 1500);
      } catch (e) {
        if (controller.signal.aborted) return;
        if (live && e instanceof ApiError && e.status === 404) { timer = setTimeout(() => void refresh(), 1500); return; }
        if (e instanceof ApiError && e.status === 401) onSessionExpired?.();
        setError(t("agentTrace.failure"));
      }
    }
    void refresh(); return () => { controller.abort(); clearTimeout(timer); payloadController.current?.abort(); };
  }, [base, t, live]);
  useEffect(() => {
    payloadController.current?.abort(); setPayload(null);
    if (!selected) return;
    const controller = new AbortController(); payloadController.current = controller; setLoading(true);
    void request<Payload>(`${base}/spans/${selected}?side=${side}&part=${part}`, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setPayload(value); })
      .catch(e => { if (!controller.signal.aborted) { if (e instanceof ApiError && e.status === 401) onSessionExpired?.(); setError(t("agentTrace.failure")); } })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [base, selected, side, part, t]);
  const state = (value: string) => t(`agentTrace.states.${value}`, { defaultValue: value });
  return <Modal title={t("agentTrace.title")} close={close} closeLabel={t("agentTrace.close")} busy={false}>
    {error && <p role="alert">{error}</p>}
    {!trace && !error && <p role="status">{t("loading")}</p>}
    {trace && <>
      <p className="text-sm text-slate-500">{state(trace.status)} · {state(trace.availability)}</p>
      <ol className="my-4 max-h-72 space-y-2 overflow-auto" aria-label={t("agentTrace.timeline")}>
        {trace.spans.map(span => <li key={span.id} className={span.parentId ? "ml-5 border-l pl-3" : "border-l pl-3"}>
          <button type="button" className="text-left text-sm text-blue-700" onClick={() => { setSelected(span.id); setPart(0); }}>
            {span.sequence}. {span.label} ({span.name}) · {state(span.status)} · +{span.offsetMs ?? 0}ms / {span.durationMs}ms
          </button>
          {!!span.summary && <pre className="overflow-auto whitespace-pre-wrap text-xs">{JSON.stringify(span.summary, null, 2)}</pre>}
        </li>)}
      </ol>
    </>}
    {selected && <section className="space-y-3 border-t pt-3">
      <label>{t("agentTrace.side")} <select value={side} onChange={e => { setSide(e.target.value); setPart(0); }}>
        <option value="request">{t("agentTrace.request")}</option><option value="response">{t("agentTrace.response")}</option>
      </select></label>
      {loading && <p role="status">{t("loading")}</p>}
      {payload && <><p className="text-xs">{state(payload.availability)}</p>
        <pre className="max-h-96 overflow-auto whitespace-pre-wrap break-words rounded bg-slate-50 p-3 text-xs">{payload.content}</pre>
        {payload.parts > 1 && <div className="flex gap-3"><button disabled={!part} onClick={() => setPart(p => p - 1)}>{t("agentTrace.previous")}</button>
          <span>{part + 1} / {payload.parts}</span><button disabled={part + 1 >= payload.parts} onClick={() => setPart(p => p + 1)}>{t("agentTrace.next")}</button></div>}
      </>}
    </section>}
  </Modal>;
}
