import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, mutate, request } from "./api";
type Generation = {
  id: string;
  state: string;
  active: boolean;
  dimensions: number;
  completedChunks: number;
  totalChunks: number;
  attempts: number;
  errorCode?: string;
  embeddingVersion: string;
  extractVersion: string;
  summaryVersion: string;
};
export default function IndexPanel({
  kb,
  owner,
  onSessionExpired,
}: {
  kb: string;
  owner: boolean;
  onSessionExpired: () => void;
}) {
  const { t } = useTranslation(),
    [items, setItems] = useState<Generation[]>([]),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [graph, setGraph] = useState<{
      entities: { name: string; entity_type: string; description: string }[];
      relations: {
        source: string;
        target: string;
        weight: number;
        description: string;
      }[];
    } | null>(null);
  const root = "/api/knowledge-bases/" + kb + "/indexes";
  function fail(e: unknown) {
    if (e instanceof ApiError && e.status === 401) onSessionExpired();
    setError(
      t(
        e instanceof ApiError && e.status === 409
          ? "indexes.conflict"
          : "indexes.failure",
      ),
    );
  }
  useEffect(() => {
    const controller = new AbortController();
    async function load() {
      try {
        const result = await request<Generation[]>(root, {
          signal: controller.signal,
        });
        if (!controller.signal.aborted)
          setItems(Array.isArray(result) ? result : []);
      } catch (e) {
        if (!controller.signal.aborted) fail(e);
      }
    }
    void load();
    const timer = setInterval(() => void load(), 3000);
    return () => {
      controller.abort();
      clearInterval(timer);
    };
  }, [root]);
  async function act(path: string, body?: unknown) {
    setBusy(true);
    setError("");
    try {
      await mutate(root + path, body);
      setItems(await request<Generation[]>(root));
      setGraph(null);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }
  async function preview(id: string) {
    setError("");
    try {
      setGraph(await request(root + "/" + id + "/graph"));
    } catch (e) {
      fail(e);
    }
  }
  const active = items.find((i) => i.active)?.id ?? null;
  return (
    <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h2 className="text-xl font-semibold">{t("indexes.title")}</h2>
        <button
          disabled={!owner || busy}
          onClick={() => void act("")}
          className="rounded-lg bg-blue-600 px-4 py-2 text-white disabled:opacity-40"
        >
          {t("indexes.build")}
        </button>
      </div>
      <p className="text-sm text-slate-500">{t("indexes.hint")}</p>
      {error && (
        <p role="alert" className="text-red-700">
          {error}
        </p>
      )}
      {items.length === 0 ? (
        <p className="text-slate-500">{t("indexes.empty")}</p>
      ) : (
        <ul className="divide-y divide-slate-100">
          {items.map((g) => (
            <li key={g.id} className="space-y-2 py-4">
              <div className="flex flex-wrap justify-between gap-3">
                <p className="font-medium">
                  {g.active
                    ? t("indexes.active")
                    : t("indexes.states." + g.state)}{" "}
                  · {g.dimensions}D · {g.completedChunks}/{g.totalChunks}
                </p>
                <div className="flex gap-4">
                  {g.state === "ready" && (
                    <>
                      <button
                        disabled={busy}
                        onClick={() => void preview(g.id)}
                      >
                        {t("indexes.preview")}
                      </button>
                      {!g.active && (
                        <button
                          disabled={!owner || busy}
                          onClick={() =>
                            void act("/" + g.id + "/activate", {
                              expectedActiveGenerationId: active,
                            })
                          }
                        >
                          {t("indexes.activate")}
                        </button>
                      )}
                    </>
                  )}
                  {["pending", "building", "failed"].includes(g.state) && (
                    <button
                      disabled={!owner || busy}
                      onClick={() => void act("/" + g.id + "/cancel")}
                    >
                      {t("indexes.cancel")}
                    </button>
                  )}
                  {["failed", "cancelled"].includes(g.state) && (
                    <button
                      disabled={!owner || busy}
                      onClick={() => void act("/" + g.id + "/retry")}
                    >
                      {t("indexes.retry")}
                    </button>
                  )}
                </div>
              </div>
              <p className="break-all text-xs text-slate-500">
                {g.id}
                <br />
                {t("indexes.modelVersion")}: {g.embeddingVersion}
              </p>
              {g.errorCode && (
                <p className="text-sm text-red-700">
                  {t("indexes.errorCode")}: {g.errorCode}
                </p>
              )}
            </li>
          ))}
        </ul>
      )}
      {graph && (
        <div className="grid gap-6 border-t border-slate-100 pt-4 md:grid-cols-2">
          <div>
            <h3 className="font-semibold">{t("indexes.entities")}</h3>
            {graph.entities.map((e, i) => (
              <p key={i} className="my-3 whitespace-pre-wrap text-sm">
                <strong>{e.name}</strong> · {e.entity_type}
                <br />
                {e.description}
              </p>
            ))}
          </div>
          <div>
            <h3 className="font-semibold">{t("indexes.relations")}</h3>
            {graph.relations.map((r, i) => (
              <p key={i} className="my-3 whitespace-pre-wrap text-sm">
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
      )}
    </section>
  );
}
