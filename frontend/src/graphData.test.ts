import { expect, it } from "vitest";
import { mergeGraph, type GraphSlice } from "./graphData";

it("keeps same-named entities with different IDs and rejects mixed generations and oversized graphs", () => {
  const person = { id: "a", name: "长安", type: "人", description: "", attributes: {} };
  const current: GraphSlice = { generationId: "g", nodes: [person], links: [], hasMore: false, nextOffset: 0 };
  const added = { ...current, nodes: [person, { ...person, id: "b", type: "地" }] };
  expect(mergeGraph(current, added).nodes).toHaveLength(2);
  expect(current.nodes).toHaveLength(1);
  expect(() => mergeGraph(current, { ...added, generationId: "other" })).toThrow("generation_mismatch");
  expect(() => mergeGraph(current, { ...added, nodes: Array.from({ length: 501 }, (_, i) => ({ ...person, id: String(i) })) })).toThrow("graph_limit");
});
