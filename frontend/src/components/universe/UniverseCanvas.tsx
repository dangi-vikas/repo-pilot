import {
  forceCenter,
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation,
  forceX,
  forceY,
  type Simulation,
  type SimulationLinkDatum,
  type SimulationNodeDatum,
} from "d3-force";
import { useCallback, useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent } from "react";
import type { FindingSeverity, UniverseEdge, UniverseNode } from "@/lib/types";
import {
  dimmedBySeverityLens,
  edgeColor,
  kindColor,
  nodeRadius,
  severityColor,
  type UniverseFilters,
  type UniverseGraph,
} from "@/lib/universe";

interface SimNode extends UniverseNode, SimulationNodeDatum {
  x: number;
  y: number;
  order: number;
}

interface SimEdge extends SimulationLinkDatum<SimNode> {
  id: string;
  sourceId: string;
  targetId: string;
  type: string;
  resolution: UniverseEdge["resolution"];
}

export interface UniverseTrail {
  /** Ordered evidence nodes, used for numbered badges. */
  nodes: string[];
  /** Edge ids on the shortest paths between consecutive evidence nodes. */
  edges: string[];
}

export interface UniverseCanvasProps {
  graph: UniverseGraph;
  filters: UniverseFilters;
  severityLens: FindingSeverity[];
  query: string;
  selectedIds: string[];
  focusMode: boolean;
  trail?: UniverseTrail | null;
  particleDensity?: number;
  /** Bump to replay the scan reveal. */
  scanKey?: number;
  /** Live progress of an index job, 0..1. When present, it drives the reveal instead. */
  liveProgress?: number | null;
  /** Comes from the app's motion preference, which already folds in the OS setting. */
  reducedMotion?: boolean;
  mini?: boolean;
  onSelect: (id: string, additive: boolean) => void;
  onFocusNode: (id: string) => void;
  onClearSelection: () => void;
  onVisibleStats?: (stats: { nodes: number; edges: number }) => void;
}

interface Viewport {
  x: number;
  y: number;
  k: number;
}

const MIN_ZOOM = 0.08;
const MAX_ZOOM = 4;
const SCAN_MS = 1_900;

function clamp(value: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, value));
}

function hashString(value: string): number {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 31 + value.charCodeAt(index)) | 0;
  }
  return Math.abs(hash);
}

/**
 * The interactive dependency universe.
 *
 * Rendering is Canvas 2D, not SVG or DOM nodes: at a few thousand elements the element-per-
 * node approach spends its frame budget on style recalculation. The layout runs once with
 * d3-force, then the simulation is stopped; animation is drawn from a single requestAnimationFrame
 * loop while motion is allowed and the document is visible.
 *
 * Everything the canvas shows comes from the `graph` and `filters` props. It does not fetch,
 * and it does not invent: a node that is not in the payload is not drawn, and the evidence
 * trail is a set of real edges between real nodes.
 */
export function UniverseCanvas({
  graph,
  filters,
  severityLens,
  query,
  selectedIds,
  focusMode,
  trail = null,
  particleDensity = 0.4,
  scanKey = 0,
  liveProgress = null,
  reducedMotion = false,
  mini = false,
  onSelect,
  onFocusNode,
  onClearSelection,
  onVisibleStats,
}: UniverseCanvasProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const miniRef = useRef<HTMLCanvasElement | null>(null);

  const simulationRef = useRef<Simulation<SimNode, SimEdge> | null>(null);
  const nodesRef = useRef<SimNode[]>([]);
  const nodeByIdRef = useRef<Map<string, SimNode>>(new Map());
  const edgesRef = useRef<SimEdge[]>([]);
  const viewRef = useRef<Viewport>({ x: 0, y: 0, k: 1 });
  const sizeRef = useRef({ width: 800, height: 560 });
  const dprRef = useRef(1);
  const fitPendingRef = useRef(true);
  const scanRef = useRef<number | null>(null);

  const graphRef = useRef(graph);
  const filtersRef = useRef(filters);
  const severityLensRef = useRef(severityLens);
  const queryRef = useRef(query);
  const selectedRef = useRef(selectedIds);
  const focusRef = useRef(focusMode);
  const trailRef = useRef<UniverseTrail | null>(trail);
  const densityRef = useRef(particleDensity);
  const reducedRef = useRef(false);
  const hoverIdRef = useRef<string | null>(null);
  const pointerRef = useRef({ x: 0, y: 0 });
  const miniRectRef = useRef<{ x: number; y: number; w: number; h: number } | null>(null);
  const liveProgressRef = useRef<number | null>(liveProgress);

  const dragRef = useRef<{
    kind: "node" | "pan" | null;
    node: SimNode | null;
    startX: number;
    startY: number;
    viewX: number;
    viewY: number;
    moved: boolean;
  }>({ kind: null, node: null, startX: 0, startY: 0, viewX: 0, viewY: 0, moved: false });

  const [hover, setHover] = useState<{ node: UniverseNode; x: number; y: number } | null>(null);
  const [ready, setReady] = useState(false);

  const drawRef = useRef<(now: number) => void>(() => {});

  const requestDraw = useCallback(() => {
    if (reducedRef.current) {
      drawRef.current(performance.now());
    }
  }, []);

  // ---------------------------------------------------------------- derived sets

  const visibleNodeIds = useRef(new Set<string>());
  const visibleEdgeIds = useRef(new Set<string>());
  const matchIds = useRef<Set<string>>(new Set());
  const focusIds = useRef<Set<string> | null>(null);
  const selectedSet = useRef(new Set<string>());
  const trailNodes = useRef(new Set<string>());
  const trailEdges = useRef(new Set<string>());

  useEffect(() => {
    graphRef.current = graph;
    filtersRef.current = filters;
    severityLensRef.current = severityLens;
    queryRef.current = query;
    selectedRef.current = selectedIds;
    focusRef.current = focusMode;
    trailRef.current = trail;
    densityRef.current = particleDensity;
    liveProgressRef.current = liveProgress;

    const filteredNodes = graph.nodes.filter((node) => {
      if (filters.hideExternal && node.external) {
        return false;
      }
      if (filters.kinds.length > 0 && !filters.kinds.includes(node.kind)) {
        return false;
      }
      if (
        filters.packages.length > 0 &&
        !(node.packageName !== null && node.packageName !== undefined && filters.packages.includes(node.packageName))
      ) {
        return false;
      }
      if (filters.findingsOnly && !graph.severityByNode.has(node.id)) {
        return false;
      }
      return true;
    });
    visibleNodeIds.current = new Set(filteredNodes.map((node) => node.id));
    const edgeIds = new Set<string>();
    for (const edge of graph.edges) {
      if (!visibleNodeIds.current.has(edge.source) || !visibleNodeIds.current.has(edge.target)) {
        continue;
      }
      if (filters.edgeTypes.length > 0 && !filters.edgeTypes.includes(edge.type)) {
        continue;
      }
      if (filters.resolutions.length > 0 && !filters.resolutions.includes(edge.resolution)) {
        continue;
      }
      edgeIds.add(edge.id);
    }
    visibleEdgeIds.current = edgeIds;

    const term = query.trim().toLowerCase();
    if (term.length === 0) {
      matchIds.current = new Set();
    } else {
      matchIds.current = new Set(
        graph.nodes
          .filter(
            (node) =>
              node.name.toLowerCase().includes(term) ||
              (node.qualifiedName ?? "").toLowerCase().includes(term) ||
              (node.path ?? "").toLowerCase().includes(term),
          )
          .map((node) => node.id),
      );
    }

    selectedSet.current = new Set(selectedIds);
    trailNodes.current = new Set(trail?.nodes ?? []);
    trailEdges.current = new Set(trail?.edges ?? []);

    if (focusMode && selectedIds.length > 0) {
      const origin = selectedIds[0]!;
      const seen = new Set<string>([origin]);
      let frontier = [origin];
      for (let depth = 0; depth < 2; depth += 1) {
        const next: string[] = [];
        for (const current of frontier) {
          for (const edge of graph.adjacency.get(current) ?? []) {
            const other = edge.source === current ? edge.target : edge.source;
            if (!seen.has(other)) {
              seen.add(other);
              next.push(other);
            }
          }
        }
        frontier = next;
      }
      focusIds.current = seen;
    } else {
      focusIds.current = null;
    }

    onVisibleStats?.({ nodes: filteredNodes.length, edges: edgeIds.size });
    requestDraw();
  }, [graph, filters, severityLens, query, selectedIds, focusMode, trail, particleDensity, liveProgress, requestDraw]);

  const fitView = useCallback(() => {
    const nodes = nodesRef.current;
    if (nodes.length === 0) {
      return;
    }
    const { width, height } = sizeRef.current;
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const node of nodes) {
      minX = Math.min(minX, node.x);
      minY = Math.min(minY, node.y);
      maxX = Math.max(maxX, node.x);
      maxY = Math.max(maxY, node.y);
    }
    const pad = 70;
    const bw = Math.max(1, maxX - minX);
    const bh = Math.max(1, maxY - minY);
    const k = clamp(Math.min((width - pad * 2) / bw, (height - pad * 2) / bh), 0.12, 1.7);
    viewRef.current = {
      x: width / 2 - ((minX + maxX) / 2) * k,
      y: height / 2 - ((minY + maxY) / 2) * k,
      k,
    };
    fitPendingRef.current = false;
    requestDraw();
  }, [requestDraw]);

  // ---------------------------------------------------------------- simulation

  useEffect(() => {
    const { width, height } = sizeRef.current;
    const cx = width / 2;
    const cy = height / 2;
    const degree = graph.degree;

    const nodes: SimNode[] = graph.nodes.map((node, index) => {
      const angle = index * 2.399963229728653;
      const radius = 18 * Math.sqrt(index + 1);
      return {
        ...node,
        order: index,
        x: cx + Math.cos(angle) * radius,
        y: cy + Math.sin(angle) * radius * 0.72,
        vx: 0,
        vy: 0,
      };
    });
    const byId = new Map(nodes.map((node) => [node.id, node]));
    const idSet = new Set(byId.keys());
    const links: SimEdge[] = graph.edges
      .filter((edge) => idSet.has(edge.source) && idSet.has(edge.target))
      .map((edge) => ({
        id: edge.id,
        sourceId: edge.source,
        targetId: edge.target,
        source: edge.source,
        target: edge.target,
        type: edge.type,
        resolution: edge.resolution,
      }));

    const simulation = forceSimulation<SimNode, SimEdge>(nodes)
      .force(
        "link",
        forceLink<SimNode, SimEdge>(links)
          .id((node) => node.id)
          .distance((edge) => (edge.type === "CALLS" ? 58 : edge.type === "TYPE_REFERENCE" ? 88 : 104))
          .strength(0.24),
      )
      .force("charge", forceManyBody<SimNode>().strength(-110).distanceMax(720))
      .force("center", forceCenter(cx, cy))
      .force("x", forceX<SimNode>(cx).strength(0.028))
      .force("y", forceY<SimNode>(cy).strength(0.028))
      .force(
        "collide",
        forceCollide<SimNode>((node) => nodeRadius(node, degree.get(node.id) ?? 0, false) + 4)
          .iterations(1)
          .strength(0.7),
      )
      .alpha(1)
      .alphaMin(0.004)
      .stop();

    // A fixed number of pre-ticks produces a stable first frame. Letting the simulation
    // settle live would show a graph reorganising itself for several seconds, which reads
    // as loading rather than as analysis.
    const ticks = nodes.length > 1_200 ? 90 : nodes.length > 600 ? 130 : 180;
    simulation.tick(ticks);

    nodesRef.current = nodes;
    nodeByIdRef.current = byId;
    edgesRef.current = links;
    simulationRef.current = simulation;
    fitPendingRef.current = true;
    if (!reducedMotion) {
      scanRef.current = performance.now();
    }
    setReady(true);
    // Wait one frame so the ResizeObserver has reported the real container size before
    // fitting; otherwise the first view is laid out for the default 800x560 box.
    window.requestAnimationFrame(() => {
      if (fitPendingRef.current) {
        fitView();
      }
    });

    return () => {
      simulation.stop();
      simulationRef.current = null;
      nodesRef.current = [];
      edgesRef.current = [];
      nodeByIdRef.current = new Map();
    };
  }, [graph, fitView, reducedMotion]);

  // ---------------------------------------------------------------- sizing

  useEffect(() => {
    const container = containerRef.current;
    const canvas = canvasRef.current;
    if (container === null || canvas === null) {
      return;
    }
    function resize(): void {
      if (container === null || canvas === null) {
        return;
      }
      const rect = container.getBoundingClientRect();
      const width = Math.max(240, Math.round(rect.width));
      const height = Math.max(180, Math.round(rect.height));
      const previous = sizeRef.current;
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      sizeRef.current = { width, height };
      // Keep the world point at the centre of the viewport fixed when the stage grows or
      // shrinks (opening the inspector, collapsing the console). Without this, the right
      // half of a fitted graph slides out of view when the inspector opens.
      if (!fitPendingRef.current && (previous.width !== width || previous.height !== height)) {
        const view = viewRef.current;
        viewRef.current = {
          ...view,
          x: view.x + (width - previous.width) / 2,
          y: view.y + (height - previous.height) / 2,
        };
      }
      dprRef.current = dpr;
      canvas.width = Math.round(width * dpr);
      canvas.height = Math.round(height * dpr);
      canvas.style.width = `${width}px`;
      canvas.style.height = `${height}px`;
      const miniCanvas = miniRef.current;
      if (miniCanvas !== null) {
        const mw = 150;
        const mh = 96;
        miniCanvas.width = Math.round(mw * dpr);
        miniCanvas.height = Math.round(mh * dpr);
        miniCanvas.style.width = `${mw}px`;
        miniCanvas.style.height = `${mh}px`;
      }
      if (fitPendingRef.current) {
        fitView();
      } else {
        requestDraw();
      }
    }
    const observer = new ResizeObserver(resize);
    observer.observe(container);
    resize();
    return () => observer.disconnect();
  }, [requestDraw]);

  // ---------------------------------------------------------------- motion loop

  useEffect(() => {
    reducedRef.current = reducedMotion;
    if (reducedMotion) {
      drawRef.current(performance.now());
      return;
    }
    let frame = 0;
    let last = 0;
    const tick = (now: number) => {
      if (!document.hidden && now - last >= 30) {
        last = now;
        drawRef.current(now);
      }
      frame = window.requestAnimationFrame(tick);
    };
    frame = window.requestAnimationFrame(tick);
    return () => window.cancelAnimationFrame(frame);
  }, [graph, reducedMotion]);

  // Replay the scan when the page asks for it.
  useEffect(() => {
    if (scanKey > 0) {
      scanRef.current = performance.now();
      requestDraw();
    }
  }, [scanKey, requestDraw]);

  // ---------------------------------------------------------------- drawing

  const draw = useCallback((now: number) => {
    const canvas = canvasRef.current;
    if (canvas === null) {
      return;
    }
    const context = canvas.getContext("2d");
    if (context === null) {
      return;
    }
    const { width, height } = sizeRef.current;
    const dpr = dprRef.current;
    context.setTransform(dpr, 0, 0, dpr, 0, 0);
    context.clearRect(0, 0, width, height);

    const graphData = graphRef.current;
    const view = viewRef.current;
    const nodes = nodesRef.current;
    const edges = edgesRef.current;
    if (nodes.length === 0) {
      return;
    }

    const reduced = reducedRef.current;
    const selected = selectedSet.current;
    const query = queryRef.current.trim().toLowerCase();
    const matches = matchIds.current;
    const focusSet = focusIds.current;
    const lens = severityLensRef.current;
    const trailData = trailRef.current;
    const trailEdgeSet = trailEdges.current;
    const trailNodeSet = trailNodes.current;
    const visibleIds = visibleNodeIds.current;
    const visibleEdges = visibleEdgeIds.current;
    const primary = selectedRef.current[0] ?? null;

    let reveal = 1;
    const live = liveProgressRef.current;
    if (live !== null && live !== undefined) {
      reveal = clamp(live, 0, 1);
    } else if (scanRef.current !== null) {
      const progress = (now - scanRef.current) / SCAN_MS;
      reveal = clamp(progress, 0, 1);
      if (progress >= 1) {
        scanRef.current = null;
      }
    }
    if (reduced) {
      reveal = 1;
      scanRef.current = null;
    }

    // Scan order is payload order, which is package then qualified name. It is stable, so a
    // replay looks the same twice, and it is not a claim about indexing order.
    const revealCount = Math.ceil(nodes.length * reveal);
    const isRevealed = (node: SimNode): boolean => reveal >= 1 || node.order < revealCount;
    const position = (node: SimNode) => ({
      x: node.x * view.k + view.x,
      y: node.y * view.k + view.y,
    });

    const attention = (node: UniverseNode): boolean => {
      if (!visibleIds.has(node.id)) {
        return false;
      }
      if (query.length > 0 && !matches.has(node.id)) {
        return false;
      }
      if (focusSet !== null && focusRef.current && !focusSet.has(node.id)) {
        return false;
      }
      if (dimmedBySeverityLens(graphData.severityByNode.get(node.id), lens)) {
        return false;
      }
      return true;
    };
    const trailActive = trailData !== null && trailData.nodes.length > 0;

    const connectedEdges = new Set<string>();
    if (primary !== null) {
      for (const edge of graphData.adjacency.get(primary) ?? []) {
        connectedEdges.add(edge.id);
      }
    }

    const nodeScale = clamp(Math.sqrt(view.k), 0.42, 1.7);

    // ---- edges
    for (const edge of edges) {
      if (!visibleEdges.has(edge.id)) {
        continue;
      }
      const source = nodeByIdRef.current.get(edge.sourceId);
      const target = nodeByIdRef.current.get(edge.targetId);
      if (source === undefined || target === undefined) {
        continue;
      }
      if (!isRevealed(source) || !isRevealed(target)) {
        continue;
      }
      const from = position(source);
      const to = position(target);
      if (
        (from.x < -80 && to.x < -80) ||
        (from.y < -80 && to.y < -80) ||
        (from.x > width + 80 && to.x > width + 80) ||
        (from.y > height + 80 && to.y > height + 80)
      ) {
        continue;
      }
      const isTrail = trailEdgeSet.has(edge.id);
      const isConnected = connectedEdges.has(edge.id);
      const bright = isTrail || isConnected;
      const baseAlpha =
        isTrail ? 0.92 : isConnected ? 0.62 : trailActive ? 0.025 : focusSet !== null || query.length > 0 ? 0.05 : 0.16;
      const alpha = bright ? baseAlpha : baseAlpha * (reduced ? 1 : 1);
      const color = isTrail ? "#a78bfa" : edgeColor(edge);
      const mx = (from.x + to.x) / 2;
      const my = (from.y + to.y) / 2;
      const dx = to.x - from.x;
      const dy = to.y - from.y;
      const length = Math.hypot(dx, dy) || 1;
      const bow = Math.min(46, length * 0.16) * (hashString(edge.id) % 2 === 0 ? 1 : -1);
      const cxp = mx - (dy / length) * bow;
      const cyp = my + (dx / length) * bow;

      context.beginPath();
      context.moveTo(from.x, from.y);
      context.quadraticCurveTo(cxp, cyp, to.x, to.y);
      context.strokeStyle = color;
      context.globalAlpha = alpha;
      context.lineWidth = isTrail ? 2.1 : isConnected ? 1.7 : 0.8 + 0.3 * nodeScale;
      if (edge.resolution === "EXTERNAL" || edge.resolution === "UNRESOLVED") {
        context.setLineDash([4, 5]);
      } else {
        context.setLineDash([]);
      }
      context.stroke();
    }
    context.setLineDash([]);
    context.globalAlpha = 1;

    // ---- particles: meaning, not confetti. Only on the trail and around the selection,
    // plus a sparse ambient sample when motion is allowed.
    const particlesOn = !reduced && densityRef.current > 0;
    if (particlesOn) {
      const ambientStep = Math.max(2, Math.round(14 - densityRef.current * 12));
      const drawParticle = (edge: SimEdge, t: number, radius: number, alpha: number) => {
        const source = nodeByIdRef.current.get(edge.sourceId);
        const target = nodeByIdRef.current.get(edge.targetId);
        if (source === undefined || target === undefined) {
          return;
        }
        const from = position(source);
        const to = position(target);
        if (from.x < 0 || from.y < 0 || from.x > width || from.y > height) {
          return;
        }
        const mx = (from.x + to.x) / 2;
        const my = (from.y + to.y) / 2;
        const dx = to.x - from.x;
        const dy = to.y - from.y;
        const length = Math.hypot(dx, dy) || 1;
        const bow = Math.min(46, length * 0.16) * (hashString(edge.id) % 2 === 0 ? 1 : -1);
        const cxp = mx - (dy / length) * bow;
        const cyp = my + (dx / length) * bow;
        const oneMinus = 1 - t;
        const px = oneMinus * oneMinus * from.x + 2 * oneMinus * t * cxp + t * t * to.x;
        const py = oneMinus * oneMinus * from.y + 2 * oneMinus * t * cyp + t * t * to.y;
        context.beginPath();
        context.arc(px, py, radius, 0, Math.PI * 2);
        context.fillStyle = trailEdgeSet.has(edge.id) ? "#ddd6fe" : edgeColor(edge);
        context.globalAlpha = alpha;
        context.fill();
      };

      edges.forEach((edge, index) => {
        if (!visibleEdges.has(edge.id)) {
          return;
        }
        const source = nodeByIdRef.current.get(edge.sourceId);
        const target = nodeByIdRef.current.get(edge.targetId);
        if (source === undefined || target === undefined || !isRevealed(source) || !isRevealed(target)) {
          return;
        }
        const phase = (hashString(edge.id) % 1000) / 1000;
        const t = ((now / 2_800 + phase) % 1 + 1) % 1;
        if (trailEdgeSet.has(edge.id)) {
          drawParticle(edge, t, 2.4, 0.95);
          drawParticle(edge, (t + 0.35) % 1, 1.7, 0.7);
        } else if (connectedEdges.has(edge.id)) {
          drawParticle(edge, t, 1.9, 0.8);
        } else if (index % ambientStep === 0 && focusSet === null && !trailActive) {
          drawParticle(edge, t, 1.2, 0.32);
        }
      });
      context.globalAlpha = 1;
    }

    // ---- nodes, back to front
    const ordered = [...nodes].sort((left, right) => {
      const leftSelected = selected.has(left.id) ? 1 : 0;
      const rightSelected = selected.has(right.id) ? 1 : 0;
      if (leftSelected !== rightSelected) {
        return leftSelected - rightSelected;
      }
      return (graphData.degree.get(right.id) ?? 0) - (graphData.degree.get(left.id) ?? 0);
    });

    for (const node of ordered) {
      if (!visibleIds.has(node.id) || !isRevealed(node)) {
        continue;
      }
      const point = position(node);
      const radius = Math.max(2.2, nodeRadius(node, graphData.degree.get(node.id) ?? 0, false) * nodeScale);
      if (point.x < -60 || point.y < -60 || point.x > width + 60 || point.y > height + 60) {
        continue;
      }
      const active = attention(node);
      const isSelected = selected.has(node.id);
      const isHovered = hoverIdRef.current === node.id;
      const isTrailNode = trailNodeSet.has(node.id);
      const severity = graphData.severityByNode.get(node.id);
      const color = kindColor(node);
      const alpha = active ? (node.external ? 0.55 : 1) : 0.14;

      if (!node.external && (isSelected || isHovered || isTrailNode || (severity !== undefined && active))) {
        context.beginPath();
        context.arc(point.x, point.y, radius + (isSelected ? 7 : 5), 0, Math.PI * 2);
        const glow = context.createRadialGradient(point.x, point.y, radius * 0.4, point.x, point.y, radius + 10);
        glow.addColorStop(0, `${isTrailNode ? "#a78bfa" : color}66`);
        glow.addColorStop(1, "rgba(0,0,0,0)");
        context.fillStyle = glow;
        context.globalAlpha = active ? 0.75 : 0.2;
        context.fill();
      }

      context.globalAlpha = alpha;
      context.beginPath();
      if (node.external) {
        // External targets are diamonds: they are references, not declarations you can open.
        context.moveTo(point.x, point.y - radius);
        context.lineTo(point.x + radius, point.y);
        context.lineTo(point.x, point.y + radius);
        context.lineTo(point.x - radius, point.y);
        context.closePath();
        context.fillStyle = "rgba(71, 85, 105, 0.55)";
        context.fill();
        context.strokeStyle = "rgba(148, 163, 184, 0.55)";
        context.lineWidth = 1;
        context.stroke();
      } else {
        context.arc(point.x, point.y, radius, 0, Math.PI * 2);
        context.fillStyle = color;
        context.fill();
        context.lineWidth = 1;
        context.strokeStyle = "rgba(7, 11, 20, 0.9)";
        context.stroke();
        if (radius > 5) {
          context.beginPath();
          context.arc(point.x, point.y, Math.max(1.4, radius * 0.34), 0, Math.PI * 2);
          context.fillStyle = "rgba(6, 10, 18, 0.7)";
          context.fill();
        }
      }

      if (severity !== undefined) {
        context.beginPath();
        context.arc(point.x, point.y, radius + 2.4, 0, Math.PI * 2);
        context.strokeStyle = severityColor(severity);
        context.lineWidth = isSelected ? 2.6 : 1.8;
        context.globalAlpha = active ? 0.95 : 0.25;
        context.stroke();
      }

      if (isSelected || isHovered) {
        context.beginPath();
        context.arc(point.x, point.y, radius + 4.5, 0, Math.PI * 2);
        context.strokeStyle = "#e2e8f0";
        context.lineWidth = 1.1;
        context.globalAlpha = 0.85;
        context.stroke();
      }

      if (isTrailNode && trailData !== null) {
        const order = trailData.nodes.indexOf(node.id) + 1;
        context.beginPath();
        context.arc(point.x + radius + 5, point.y - radius - 5, 7, 0, Math.PI * 2);
        context.fillStyle = "#7c3aed";
        context.globalAlpha = 1;
        context.fill();
        context.strokeStyle = "#ddd6fe";
        context.lineWidth = 1;
        context.stroke();
        context.fillStyle = "#f5f3ff";
        context.font = "600 9px ui-monospace, monospace";
        context.textAlign = "center";
        context.textBaseline = "middle";
        context.fillText(String(order), point.x + radius + 5, point.y - radius - 4.5);
      }

      // Labels: only where they remain readable. At low zoom the graph is a constellation,
      // not a wall of overlapping text.
      const degree = graphData.degree.get(node.id) ?? 0;
      const showLabel =
        (isSelected || isHovered || isTrailNode) ||
        (view.k >= 0.72 && (node.external ? false : degree >= 6 || radius > 7)) ||
        view.k >= 1.35;
      if (showLabel && !node.external) {
        const label = node.name.length > 24 ? `${node.name.slice(0, 23)}…` : node.name;
        context.font = `${isSelected ? "600" : "500"} ${isSelected ? 12 : 11}px ui-monospace, SFMono-Regular, Consolas, monospace`;
        context.textAlign = "left";
        context.textBaseline = "middle";
        context.globalAlpha = active ? 0.95 : 0.2;
        context.strokeStyle = "rgba(4, 7, 13, 0.85)";
        context.lineWidth = 3;
        context.strokeText(label, point.x + radius + 5, point.y);
        context.fillStyle = isSelected ? "#f8fafc" : "rgba(203, 213, 225, 0.92)";
        context.fillText(label, point.x + radius + 5, point.y);
      }
    }
    context.globalAlpha = 1;

    // ---- scan wave
    if (scanRef.current !== null || (live !== null && live !== undefined && live < 1)) {
      const waveX = width * reveal;
      const gradient = context.createLinearGradient(waveX - 90, 0, waveX + 12, 0);
      gradient.addColorStop(0, "rgba(34, 211, 238, 0)");
      gradient.addColorStop(0.82, "rgba(34, 211, 238, 0.16)");
      gradient.addColorStop(1, "rgba(167, 139, 250, 0.55)");
      context.fillStyle = gradient;
      context.fillRect(waveX - 90, 0, 102, height);
      context.beginPath();
      context.moveTo(waveX, 0);
      context.lineTo(waveX, height);
      context.strokeStyle = "rgba(202, 240, 255, 0.85)";
      context.lineWidth = 1.2;
      context.stroke();
    }

    // ---- minimap
    const miniCanvas = miniRef.current;
    if (miniCanvas !== null && !mini) {
      const miniContext = miniCanvas.getContext("2d");
      if (miniContext !== null) {
        const mw = miniCanvas.width / dpr;
        const mh = miniCanvas.height / dpr;
        miniContext.setTransform(dpr, 0, 0, dpr, 0, 0);
        miniContext.clearRect(0, 0, mw, mh);
        miniContext.fillStyle = "rgba(6, 10, 18, 0.72)";
        miniContext.fillRect(0, 0, mw, mh);

        let minX = Infinity;
        let minY = Infinity;
        let maxX = -Infinity;
        let maxY = -Infinity;
        for (const node of nodes) {
          if (!visibleIds.has(node.id)) {
            continue;
          }
          minX = Math.min(minX, node.x);
          minY = Math.min(minY, node.y);
          maxX = Math.max(maxX, node.x);
          maxY = Math.max(maxY, node.y);
        }
        if (Number.isFinite(minX)) {
          const spanX = Math.max(1, maxX - minX);
          const spanY = Math.max(1, maxY - minY);
          const scale = Math.min((mw - 14) / spanX, (mh - 14) / spanY);
          const ox = (mw - spanX * scale) / 2;
          const oy = (mh - spanY * scale) / 2;
          const toMini = (x: number, y: number) => ({
            x: ox + (x - minX) * scale,
            y: oy + (y - minY) * scale,
          });
          miniRectRef.current = { x: minX, y: minY, w: spanX, h: spanY };
          for (const node of nodes) {
            if (!visibleIds.has(node.id)) {
              continue;
            }
            const point = toMini(node.x, node.y);
            miniContext.beginPath();
            miniContext.arc(point.x, point.y, node.external ? 0.7 : 1.1, 0, Math.PI * 2);
            miniContext.fillStyle = node.external ? "rgba(100,116,139,0.6)" : kindColor(node);
            miniContext.globalAlpha = attention(node) ? 0.9 : 0.2;
            miniContext.fill();
          }
          miniContext.globalAlpha = 1;
          // Viewport rectangle
          const topLeft = toMini(-view.x / view.k, -view.y / view.k);
          const bottomRight = toMini((width - view.x) / view.k, (height - view.y) / view.k);
          miniContext.strokeStyle = "rgba(167, 139, 250, 0.9)";
          miniContext.lineWidth = 1;
          miniContext.strokeRect(
            clamp(topLeft.x, 1, mw - 2),
            clamp(topLeft.y, 1, mh - 2),
            Math.max(2, Math.min(mw, bottomRight.x) - Math.max(0, topLeft.x)),
            Math.max(2, Math.min(mh, bottomRight.y) - Math.max(0, topLeft.y)),
          );
        }
      }
    }
  }, []);

  drawRef.current = draw;

  // ---------------------------------------------------------------- pointer + wheel

  const toWorld = useCallback((clientX: number, clientY: number) => {
    const canvas = canvasRef.current;
    if (canvas === null) {
      return { x: 0, y: 0 };
    }
    const rect = canvas.getBoundingClientRect();
    const view = viewRef.current;
    return {
      x: (clientX - rect.left - view.x) / view.k,
      y: (clientY - rect.top - view.y) / view.k,
    };
  }, []);

  const hitTest = useCallback((clientX: number, clientY: number): SimNode | null => {
    const world = toWorld(clientX, clientY);
    const view = viewRef.current;
    const nodeScale = clamp(Math.sqrt(view.k), 0.42, 1.7);
    const graphData = graphRef.current;
    let best: SimNode | null = null;
    let bestDistance = Infinity;
    for (const node of nodesRef.current) {
      if (!visibleNodeIds.current.has(node.id)) {
        continue;
      }
      const radius = Math.max(2.2, nodeRadius(node, graphData.degree.get(node.id) ?? 0, false) * nodeScale) + 4;
      const distance = Math.hypot(node.x - world.x, node.y - world.y);
      if (distance <= radius && distance < bestDistance) {
        best = node;
        bestDistance = distance;
      }
    }
    return best;
  }, [toWorld]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (canvas === null || mini) {
      return;
    }
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      const rect = canvas.getBoundingClientRect();
      const view = viewRef.current;
      const pointerX = event.clientX - rect.left;
      const pointerY = event.clientY - rect.top;
      const factor = Math.exp(-event.deltaY * 0.0015);
      const nextK = clamp(view.k * factor, MIN_ZOOM, MAX_ZOOM);
      const ratio = nextK / view.k;
      viewRef.current = {
        k: nextK,
        x: pointerX - (pointerX - view.x) * ratio,
        y: pointerY - (pointerY - view.y) * ratio,
      };
      requestDraw();
    };
    canvas.addEventListener("wheel", onWheel, { passive: false });
    return () => canvas.removeEventListener("wheel", onWheel);
  }, [mini, requestDraw]);

  function pointerDown(event: ReactPointerEvent<HTMLCanvasElement>): void {
    if (mini) {
      return;
    }
    const canvas = event.currentTarget;
    canvas.setPointerCapture(event.pointerId);
    const hit = hitTest(event.clientX, event.clientY);
    const view = viewRef.current;
    if (hit !== null) {
      hit.fx = hit.x;
      hit.fy = hit.y;
      dragRef.current = {
        kind: "node",
        node: hit,
        startX: event.clientX,
        startY: event.clientY,
        viewX: view.x,
        viewY: view.y,
        moved: false,
      };
      hoverIdRef.current = hit.id;
    } else {
      dragRef.current = {
        kind: "pan",
        node: null,
        startX: event.clientX,
        startY: event.clientY,
        viewX: view.x,
        viewY: view.y,
        moved: false,
      };
    }
    requestDraw();
  }

  function pointerMove(event: ReactPointerEvent<HTMLCanvasElement>): void {
    pointerRef.current = { x: event.clientX, y: event.clientY };
    const drag = dragRef.current;
    if (drag.kind === "node" && drag.node !== null) {
      const dx = event.clientX - drag.startX;
      const dy = event.clientY - drag.startY;
      if (Math.hypot(dx, dy) > 3) {
        drag.moved = true;
      }
      const world = toWorld(event.clientX, event.clientY);
      drag.node.fx = world.x;
      drag.node.fy = world.y;
      simulationRef.current?.alpha(0.35).restart();
      requestDraw();
      return;
    }
    if (drag.kind === "pan") {
      const dx = event.clientX - drag.startX;
      const dy = event.clientY - drag.startY;
      if (Math.hypot(dx, dy) > 3) {
        drag.moved = true;
      }
      viewRef.current = { ...viewRef.current, x: drag.viewX + dx, y: drag.viewY + dy };
      requestDraw();
      return;
    }
    const hit = hitTest(event.clientX, event.clientY);
    const canvas = canvasRef.current;
    if (hit === null) {
      if (hoverIdRef.current !== null) {
        hoverIdRef.current = null;
        setHover(null);
        requestDraw();
      }
      if (canvas !== null) {
        canvas.style.cursor = "grab";
      }
      return;
    }
    if (hoverIdRef.current !== hit.id) {
      hoverIdRef.current = hit.id;
      const rect = canvas?.getBoundingClientRect();
      setHover({
        node: hit,
        x: event.clientX - (rect?.left ?? 0),
        y: event.clientY - (rect?.top ?? 0),
      });
      requestDraw();
    } else if (canvas !== null) {
      canvas.style.cursor = "pointer";
    }
  }

  function pointerUp(event: ReactPointerEvent<HTMLCanvasElement>): void {
    const drag = dragRef.current;
    const hit = drag.node;
    if (drag.kind === "node" && hit !== null) {
      hit.fx = null;
      hit.fy = null;
      simulationRef.current?.alpha(0.2).restart();
      if (!drag.moved) {
        onSelect(hit.id, event.shiftKey || event.metaKey || event.ctrlKey);
      }
    }
    dragRef.current = { kind: null, node: null, startX: 0, startY: 0, viewX: 0, viewY: 0, moved: false };
    requestDraw();
  }

  function doubleClick(event: ReactMouseEvent<HTMLCanvasElement>): void {
    const hit = hitTest(event.clientX, event.clientY);
    if (hit !== null) {
      onFocusNode(hit.id);
    }
  }

  function keyDown(event: ReactKeyboardEvent<HTMLCanvasElement>): void {
    if (event.key === "+" || event.key === "=") {
      event.preventDefault();
      zoomBy(1.25);
    } else if (event.key === "-") {
      event.preventDefault();
      zoomBy(0.8);
    } else if (event.key.toLowerCase() === "f") {
      event.preventDefault();
      fitView();
    } else if (event.key === "Escape") {
      onClearSelection();
    }
  }

  const zoomBy = useCallback(
    (factor: number) => {
      const { width, height } = sizeRef.current;
      const view = viewRef.current;
      const nextK = clamp(view.k * factor, MIN_ZOOM, MAX_ZOOM);
      const ratio = nextK / view.k;
      viewRef.current = {
        k: nextK,
        x: width / 2 - (width / 2 - view.x) * ratio,
        y: height / 2 - (height / 2 - view.y) * ratio,
      };
      requestDraw();
    },
    [requestDraw],
  );

  function minimapClick(event: ReactMouseEvent<HTMLCanvasElement>): void {
    const rect = event.currentTarget.getBoundingClientRect();
    const box = miniRectRef.current;
    if (box === null) {
      return;
    }
    const miniCanvas = event.currentTarget;
    const mw = miniCanvas.width / dprRef.current;
    const mh = miniCanvas.height / dprRef.current;
    const scale = Math.min((mw - 14) / box.w, (mh - 14) / box.h);
    const ox = (mw - box.w * scale) / 2;
    const oy = (mh - box.h * scale) / 2;
    const worldX = box.x + (event.clientX - rect.left - ox) / scale;
    const worldY = box.y + (event.clientY - rect.top - oy) / scale;
    const { width, height } = sizeRef.current;
    const view = viewRef.current;
    viewRef.current = { ...view, x: width / 2 - worldX * view.k, y: height / 2 - worldY * view.k };
    requestDraw();
  }

  return (
    <div className={mini ? "universe-canvas universe-canvas-mini" : "universe-canvas"} ref={containerRef}>
      <canvas
        ref={canvasRef}
        className="universe-surface"
        role="application"
        aria-label="Interactive code dependency universe. Drag to pan, scroll to zoom, click a node to inspect it, double-click to focus its dependency chain."
        tabIndex={mini ? -1 : 0}
        onPointerDown={pointerDown}
        onPointerMove={pointerMove}
        onPointerUp={pointerUp}
        onPointerCancel={pointerUp}
        onPointerLeave={() => {
          hoverIdRef.current = null;
          setHover(null);
          requestDraw();
        }}
        onDoubleClick={doubleClick}
        onKeyDown={keyDown}
      />

      {hover !== null && !mini ? (
        <div
          className="universe-tooltip"
          style={{
            left: Math.min(hover.x + 16, sizeRef.current.width - 260),
            top: Math.min(hover.y + 14, sizeRef.current.height - 90),
          }}
        >
          <span className={`u-kind u-kind-${hover.node.kind.toLowerCase()}`}>{hover.node.kind}</span>
          <strong>{hover.node.name}</strong>
          <span className="rp-mono">{hover.node.path ?? "external reference"}</span>
          <span className="rp-faint">
            {hover.node.external
              ? "Not in this repository"
              : `${graph.degree.get(hover.node.id) ?? 0} connections${hover.node.complexity ? ` · complexity ${hover.node.complexity}` : ""}`}
          </span>
        </div>
      ) : null}

      {ready && !mini ? (
        <div className="universe-zoom" role="group" aria-label="Graph controls">
          <button type="button" onClick={() => zoomBy(1.3)} aria-label="Zoom in">
            +
          </button>
          <button type="button" onClick={() => zoomBy(0.77)} aria-label="Zoom out">
            −
          </button>
          <button type="button" onClick={fitView} aria-label="Fit graph to view">
            Fit
          </button>
        </div>
      ) : null}

      {!mini ? (
        <canvas
          ref={miniRef}
          className="universe-minimap"
          aria-label="Minimap. Click to move the viewport."
          onClick={minimapClick}
        />
      ) : null}
    </div>
  );
}
