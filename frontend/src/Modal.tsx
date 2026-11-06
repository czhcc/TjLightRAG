import { useEffect, useRef, type ReactNode } from "react";

export default function Modal({ title, close, busy, children, closeLabel }: { title: string; close: () => void; busy: boolean; children: ReactNode; closeLabel?: string }) {
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
      {closeLabel ? <header className="sticky -top-6 z-10 -mx-6 -mt-6 mb-5 flex items-center justify-between gap-4 rounded-t-2xl border-b border-slate-100 bg-white px-6 py-4">
        <h2 className="text-xl font-semibold">{title}</h2>
        <button type="button" disabled={busy} onClick={close} className="shrink-0 rounded-lg border border-slate-300 px-3 py-2 text-sm disabled:opacity-40">{closeLabel}</button>
      </header> : <h2 className="mb-5 text-xl font-semibold">{title}</h2>}{children}
    </div>
  </div>;
}
