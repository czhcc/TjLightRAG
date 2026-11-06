import { useTranslation } from "react-i18next";
export type Statistic = { tool: string; arguments: unknown; result: { count?: string; total?: string; groupTotal?: string; groups?: { key: unknown; count: string }[]; basis?: string } };
/** Authoritative counts are strings, preserving precision beyond JavaScript's safe integer range. */
export default function AgentStatistics({ items }: { items?: Statistic[] }) {
  const { t } = useTranslation();
  if (!items?.length) return null;
  return <details className="rounded border border-blue-100 p-3" open><summary>{t("agentTrace.statistics")}</summary>
    <p className="my-2 text-xs text-slate-500">{t("agentTrace.statBasis")}</p>
    {items.map((item, index) => <section key={index} className="my-2 text-sm">
      <p>{t("agentTrace.total")}: <strong>{item.result.total ?? item.result.count}</strong></p>
      {!!item.result.groups?.length && <table className="my-2 w-full text-left"><thead><tr><th>{t("agentTrace.group")}</th><th>{t("agentTrace.count")}</th></tr></thead>
        <tbody>{item.result.groups.map((group, n) => <tr key={n}><td>{JSON.stringify(group.key)}</td><td>{group.count}</td></tr>)}</tbody></table>}
      <details><summary>{t("agentTrace.filters")}</summary><pre className="overflow-auto whitespace-pre-wrap text-xs">{JSON.stringify(item.arguments, null, 2)}</pre></details>
    </section>)}
  </details>;
}
