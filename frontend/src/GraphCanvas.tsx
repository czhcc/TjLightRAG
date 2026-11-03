import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import {
  select, forceSimulation, forceLink, forceManyBody, forceCenter, forceCollide, drag, zoom, zoomIdentity,
  type SimulationNodeDatum, type SimulationLinkDatum, type ZoomBehavior,
} from "d3";
import { typeColor, type GraphNode, type GraphLink } from "./graphData";

type Node = GraphNode & SimulationNodeDatum;
type Link = Omit<GraphLink, "source" | "target"> & SimulationLinkDatum<Node>;
const WIDTH = 1000, HEIGHT = 600;

/** D3 force-directed SVG graph with zoom, node dragging and accessible expansion. */
export default function GraphCanvas({ nodes, links, onExpand, onSelect }: {
  nodes: GraphNode[]; links: GraphLink[]; onExpand: (id: string) => void; onSelect: (id: string) => void;
}) {
  const { t } = useTranslation();
  const svg = useRef<SVGSVGElement>(null);
  const positions = useRef(new Map<string, Node>());
  const behavior = useRef<ZoomBehavior<SVGSVGElement, unknown> | null>(null);
  useEffect(() => {
    if (!svg.current) return;
    const root = select(svg.current);
    const viewport = root.append("g");
    const zoomer = zoom<SVGSVGElement, unknown>().extent([[0, 0], [WIDTH, HEIGHT]])
      .scaleExtent([0.15, 4]).on("zoom", event => viewport.attr("transform", event.transform.toString()));
    behavior.current = zoomer;
    root.call(zoomer).on("dblclick.zoom", null);
    viewport.attr("transform", (svg.current as SVGSVGElement & { __zoom?: { toString(): string } }).__zoom?.toString() ?? "");
    const previous = positions.current;
    const points: Node[] = nodes.map(node => ({ ...node, x: previous.get(node.id)?.x, y: previous.get(node.id)?.y }));
    positions.current = new Map(points.map(node => [node.id, node]));
    const ids = new Set(points.map(node => node.id));
    const edges: Link[] = links.filter(link => ids.has(link.source) && ids.has(link.target)).map(link => ({ ...link }));
    const simulation = forceSimulation(points)
      .force("link", forceLink<Node, Link>(edges).id(node => node.id).distance(160))
      .force("charge", forceManyBody().strength(-450))
      .force("center", forceCenter(WIDTH / 2, HEIGHT / 2))
      .force("collide", forceCollide(48));
    const lines = viewport.append("g").selectAll<SVGGElement, Link>("g").data(edges, edge => edge.id).join("g");
    lines.append("line").attr("stroke", "#94a3b8").attr("stroke-width", 1.5);
    lines.append("text").attr("text-anchor", "middle").attr("font-size", 11).attr("fill", "#64748b")
      .attr("paint-order", "stroke").attr("stroke", "#fff").attr("stroke-width", 4)
      .text(edge => Array.from(edge.types.join("、") || t("graph.relation")).slice(0, 20).join(""));
    lines.append("title").text(edge => `${edge.types.join("、")}\n${edge.description}`);
    const circles = viewport.append("g").selectAll<SVGGElement, Node>("g").data(points, node => node.id).join("g")
      .attr("role", "button").attr("tabindex", 0).attr("cursor", "pointer")
      .attr("aria-label", node => t("graph.expand", { name: node.name, type: node.type }))
      .on("click", (_event, node) => onSelect(node.id))
      .on("dblclick", (event, node) => { event.preventDefault(); event.stopPropagation(); onExpand(node.id); })
      .on("keydown", (event: KeyboardEvent, node) => {
        if (event.key === "Enter" || event.key === " ") { event.preventDefault(); onExpand(node.id); }
      });
    circles.append("circle").attr("r", 22).attr("fill", node => typeColor(node.type)).attr("stroke", "#fff").attr("stroke-width", 3);
    circles.append("text").attr("text-anchor", "middle").attr("dy", 41).attr("font-size", 13).attr("fill", "#334155")
      .text(node => Array.from(node.name).length > 16 ? `${Array.from(node.name).slice(0, 16).join("")}…` : node.name);
    circles.append("text").attr("text-anchor", "middle").attr("dy", 5).attr("font-size", 12).attr("fill", "#fff")
      .attr("aria-hidden", "true").text(node => Array.from(node.type).slice(0, 2).join(""));
    circles.append("title").text(node => `${node.name}（${node.type}）\n${node.description}`);
    circles.call(drag<SVGGElement, Node>().on("start", (event, node) => {
      if (!event.active) simulation.alphaTarget(0.3).restart();
      node.fx = node.x; node.fy = node.y;
    }).on("drag", (event, node) => { node.fx = event.x; node.fy = event.y; })
      .on("end", (event, node) => { if (!event.active) simulation.alphaTarget(0); node.fx = null; node.fy = null; }));
    const draw = () => {
      circles.attr("transform", node => `translate(${node.x ?? 0},${node.y ?? 0})`);
      lines.select("line").attr("x1", edge => (edge.source as Node).x ?? 0).attr("y1", edge => (edge.source as Node).y ?? 0)
        .attr("x2", edge => (edge.target as Node).x ?? 0).attr("y2", edge => (edge.target as Node).y ?? 0);
      lines.select("text").attr("x", edge => (((edge.source as Node).x ?? 0) + ((edge.target as Node).x ?? 0)) / 2)
        .attr("y", edge => (((edge.source as Node).y ?? 0) + ((edge.target as Node).y ?? 0)) / 2 - 5);
    };
    simulation.on("tick", draw); draw();
    return () => { simulation.stop(); root.on(".zoom", null); viewport.remove(); behavior.current = null; };
  }, [nodes, links, onExpand, onSelect, t]);

  function scale(factor: number) { if (svg.current && behavior.current) select(svg.current).call(behavior.current.scaleBy, factor); }
  return <div role="region" aria-label={t("graph.canvas")} className="relative overflow-hidden rounded-xl border border-slate-200 bg-slate-50">
    <div className="absolute top-3 right-3 z-10 flex gap-2 rounded-lg bg-white p-2 shadow-sm">
      <button type="button" aria-label={t("graph.zoomIn")} className="px-2" onClick={() => scale(1.25)}>＋</button>
      <button type="button" aria-label={t("graph.zoomOut")} className="px-2" onClick={() => scale(0.8)}>−</button>
      <button type="button" className="px-2 text-sm" onClick={() => {
        if (svg.current && behavior.current) select(svg.current).call(behavior.current.transform, zoomIdentity);
      }}>{t("graph.resetView")}</button>
    </div>
    <svg ref={svg} viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className="block min-h-96 w-full touch-none" aria-label={t("graph.canvas")} />
  </div>;
}
