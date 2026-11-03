export type GraphNode = {
  id: string; name: string; type: string; description: string; attributes: Record<string, unknown>;
};
export type GraphLink = {
  id: string; source: string; target: string; types: string[]; description: string; weight: number;
};
export type GraphSlice = {
  generationId: string; nodes: GraphNode[]; links: GraphLink[]; hasMore: boolean; nextOffset: number;
};

const colors = ["#2563eb", "#059669", "#d97706", "#9333ea", "#dc2626", "#0891b2", "#db2777", "#4f46e5"];
/** Stable type colors across queries and incremental expansion. */
export function typeColor(type: string) {
  let hash = 0;
  for (const char of type) hash = (hash * 31 + char.codePointAt(0)!) >>> 0;
  return colors[hash % colors.length];
}

/** Merge by immutable IDs; identically named entities with distinct types stay separate. */
export function mergeGraph(current: GraphSlice, added: GraphSlice): GraphSlice {
  if (current.generationId !== added.generationId) throw new Error("generation_mismatch");
  const nodes = new Map(current.nodes.map(node => [node.id, node]));
  const links = new Map(current.links.map(link => [link.id, link]));
  added.nodes.forEach(node => nodes.set(node.id, node));
  added.links.forEach(link => links.set(link.id, link));
  if (nodes.size > 500 || links.size > 1000) throw new Error("graph_limit");
  return { ...current, nodes: [...nodes.values()], links: [...links.values()] };
}
