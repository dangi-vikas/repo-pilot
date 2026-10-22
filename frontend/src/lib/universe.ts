import type {
  EdgeResolution,
  FindingSeverity,
  FindingView,
  InvestigationEvidence,
  UniverseEdge,
  UniverseNode,
  UniverseView,
} from "@/lib/types";

/**
 * Pure transformations behind the Code Universe.
 *
 * The canvas is deliberately dumb: it receives nodes, edges and a set of highlighted ids.
 * Everything that decides *what* is on screen — severity attribution, filtering, search,
 * neighbourhood and evidence mapping — lives here, without React or a DOM, so it can be
 * tested directly.
 */

export const NODE_KIND_META: Record<string, { label: string; color: string }> = {
  CLASS: { label: "Class", color: "#3ea6ff" },
  INTERFACE: { label: "Interface", color: "#a78bfa" },
  ENUM: { label: "Enum", color: "#f472b6" },
  RECORD: { label: "Record", color: "#22d3ee" },
  ANNOTATION: { label: "Annotation", color: "#c084fc" },
  METHOD: { label: "Method", color: "#38bdf8" },
  CONSTRUCTOR: { label: "Constructor", color: "#2dd4bf" },
  FIELD: { label: "Field", color: "#7c8da6" },
  ENUM_CONSTANT: { label: "Constant", color: "#f9a8d4" },
  EXTERNAL: { label: "External", color: "#526077" },
};

export const TYPE_KINDS = ["CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION"];

export const SEVERITY_ORDER: FindingSeverity[] = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"];

export const SEVERITY_RANK: Record<FindingSeverity, number> = {
  CRITICAL: 5,
  HIGH: 4,
  MEDIUM: 3,
  LOW: 2,
  INFO: 1,
};

export const SEVERITY_COLOR: Record<FindingSeverity, string> = {
  CRITICAL: "#ff3b5c",
  HIGH: "#ff7a45",
  MEDIUM: "#f5b544",
  LOW: "#a3e635",
  INFO: "#5eead4",
};

export const EDGE_TYPE_META: Record<string, { label: string; color: string }> = {
  EXTENDS: { label: "Extends", color: "#a3e635" },
  IMPLEMENTS: { label: "Implements", color: "#84cc16" },
  TYPE_REFERENCE: { label: "Type reference", color: "#3b82f6" },
  INSTANTIATES: { label: "Instantiates", color: "#a855f7" },
  CALLS: { label: "Calls", color: "#22d3ee" },
  INJECTS: { label: "Injects", color: "#f472b6" },
  ANNOTATION: { label: "Annotation", color: "#c084fc" },
  THROWS: { label: "Throws", color: "#fb7185" },
  KAFKA_PUBLISH: { label: "Kafka publish", color: "#fb923c" },
  KAFKA_CONSUME: { label: "Kafka consume", color: "#fdba74" },
  REST_ENDPOINT: { label: "REST endpoint", color: "#60a5fa" },
};

export const EDGE_TYPES = Object.keys(EDGE_TYPE_META);
export const RESOLUTIONS: EdgeResolution[] = ["RESOLVED", "INFERRED", "EXTERNAL", "UNRESOLVED"];

const PACKAGE_COLORS = ["#22d3ee", "#8b5cf6", "#f472b6", "#3b82f6", "#a3e635", "#fb923c", "#c084fc"];

/** Deterministic package tint. Two packages with the same tint are not a claim of relatedness. */
export function packageColor(packageName: string | null | undefined): string {
  if (packageName === null || packageName === undefined || packageName.length === 0) {
    return "#64748b";
  }
  let hash = 0;
  for (let index = 0; index < packageName.length; index += 1) {
    hash = (hash * 31 + packageName.charCodeAt(index)) | 0;
  }
  return PACKAGE_COLORS[Math.abs(hash) % PACKAGE_COLORS.length] ?? "#22d3ee";
}

export function kindColor(node: { kind: string }): string {
  return NODE_KIND_META[node.kind]?.color ?? "#7c8da6";
}

export function kindLabel(kind: string): string {
  return NODE_KIND_META[kind]?.label ?? kind;
}

export function edgeColor(edge: { type: string }): string {
  return EDGE_TYPE_META[edge.type]?.color ?? "#64748b";
}

export function severityColor(severity: FindingSeverity | null | undefined): string {
  return severity === null || severity === undefined ? "transparent" : SEVERITY_COLOR[severity];
}

/**
 * A node's derived findings.
 *
 * A finding with a `symbolId` belongs to that symbol only. A finding without one is
 * attributed to every declaration whose line range contains its start line — which is
 * exactly the claim the finding makes, no stronger. File-level findings with no line are
 * kept on the file node's class by nothing: there is no declaration to attach them to, so
 * they stay in the Findings screen rather than being guessed onto a node.
 */
export interface UniverseGraph {
  revisionId: string;
  nodes: UniverseNode[];
  edges: UniverseEdge[];
  nodeById: Map<string, UniverseNode>;
  adjacency: Map<string, UniverseEdge[]>;
  degree: Map<string, number>;
  findingsByNode: Map<string, FindingView[]>;
  severityByNode: Map<string, FindingSeverity>;
  packages: string[];
}

export function buildUniverse(view: UniverseView, findings: FindingView[]): UniverseGraph {
  const nodeById = new Map<string, UniverseNode>();
  for (const node of view.nodes) {
    nodeById.set(node.id, node);
  }

  const findingsBySymbol = new Map<string, FindingView[]>();
  const findingsByPath = new Map<string, FindingView[]>();
  for (const finding of findings) {
    if (finding.symbolId !== null && finding.symbolId !== undefined) {
      const list = findingsBySymbol.get(finding.symbolId) ?? [];
      list.push(finding);
      findingsBySymbol.set(finding.symbolId, list);
    } else if (finding.sourcePath !== null && finding.sourcePath !== undefined) {
      const list = findingsByPath.get(finding.sourcePath) ?? [];
      list.push(finding);
      findingsByPath.set(finding.sourcePath, list);
    }
  }

  const findingsByNode = new Map<string, FindingView[]>();
  const severityByNode = new Map<string, FindingSeverity>();
  for (const node of view.nodes) {
    if (node.external) {
      continue;
    }
    const attributed = [...(findingsBySymbol.get(node.id) ?? [])];
    if (node.path !== null && node.path !== undefined && node.startLine !== null && node.startLine !== undefined) {
      const start = node.startLine;
      const end = node.endLine ?? start;
      for (const finding of findingsByPath.get(node.path) ?? []) {
        const line = finding.startLine;
        if (line !== null && line !== undefined && line >= start && line <= end) {
          attributed.push(finding);
        }
      }
    }
    if (attributed.length === 0) {
      continue;
    }
    attributed.sort((left, right) => SEVERITY_RANK[right.severity] - SEVERITY_RANK[left.severity]);
    findingsByNode.set(node.id, attributed);
    severityByNode.set(node.id, attributed[0]!.severity);
  }

  const adjacency = new Map<string, UniverseEdge[]>();
  const degree = new Map<string, number>();
  for (const node of view.nodes) {
    adjacency.set(node.id, []);
  }
  for (const edge of view.edges) {
    if (!nodeById.has(edge.source) || !nodeById.has(edge.target)) {
      continue;
    }
    adjacency.get(edge.source)?.push(edge);
    adjacency.get(edge.target)?.push(edge);
  }
  for (const [id, list] of adjacency) {
    degree.set(id, list.length);
  }

  const packages = Array.from(
    new Set(
      view.nodes
        .map((node) => node.packageName)
        .filter((name): name is string => name !== null && name !== undefined && name.length > 0),
    ),
  ).sort((left, right) => left.localeCompare(right));

  return {
    revisionId: view.revisionId,
    nodes: view.nodes,
    edges: view.edges,
    nodeById,
    adjacency,
    degree,
    findingsByNode,
    severityByNode,
    packages,
  };
}

export interface UniverseFilters {
  kinds: string[];
  packages: string[];
  edgeTypes: string[];
  resolutions: EdgeResolution[];
  hideExternal: boolean;
  findingsOnly: boolean;
}

export const EMPTY_FILTERS: UniverseFilters = {
  kinds: [],
  packages: [],
  edgeTypes: [],
  resolutions: [],
  hideExternal: false,
  findingsOnly: false,
};

export interface FilteredGraph {
  nodes: UniverseNode[];
  edges: UniverseEdge[];
  allowed: Set<string>;
}

/** Applies the hard filters. Severity is a lens, not a filter — see `dimmedByLens`. */
export function filterGraph(graph: UniverseGraph, filters: UniverseFilters): FilteredGraph {
  const nodes = graph.nodes.filter((node) => {
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
  const allowed = new Set(nodes.map((node) => node.id));
  const edges = graph.edges.filter(
    (edge) =>
      allowed.has(edge.source) &&
      allowed.has(edge.target) &&
      (filters.edgeTypes.length === 0 || filters.edgeTypes.includes(edge.type)) &&
      (filters.resolutions.length === 0 || filters.resolutions.includes(edge.resolution)),
  );
  return { nodes, edges, allowed };
}

/**
 * True when the severity lens should dim this node.
 *
 * Severity is deliberately a lens rather than a filter. Removing every node without a
 * critical finding would leave a graph that cannot explain the finding's context, which is
 * the one thing the screen exists to show.
 */
export function dimmedBySeverityLens(
  severity: FindingSeverity | undefined,
  lens: FindingSeverity[],
): boolean {
  if (lens.length === 0) {
    return false;
  }
  return severity === undefined || !lens.includes(severity);
}

export interface SearchHit {
  node: UniverseNode;
  score: number;
}

export function searchUniverse(graph: UniverseGraph, query: string, limit = 12): SearchHit[] {
  const term = query.trim().toLowerCase();
  if (term.length === 0) {
    return [];
  }
  const hits: SearchHit[] = [];
  for (const node of graph.nodes) {
    const name = node.name.toLowerCase();
    const qualified = (node.qualifiedName ?? "").toLowerCase();
    const path = (node.path ?? "").toLowerCase();
    let score = -1;
    if (name === term) {
      score = 100;
    } else if (qualified === term) {
      score = 95;
    } else if (name.startsWith(term)) {
      score = 80;
    } else if (qualified.endsWith("." + term) || qualified.endsWith("$" + term)) {
      score = 70;
    } else if (name.includes(term)) {
      // A declaration whose own name contains the term outranks a qualified name that
      // only contains it through its enclosing class. Without this, searching "Service"
      // returns the methods of Service.java before the Service class itself.
      score = 55;
    } else if (qualified.includes(term)) {
      score = 45;
    } else if (path.includes(term)) {
      score = 25;
    }
    if (score >= 0) {
      hits.push({ node, score });
    }
  }
  hits.sort(
    (left, right) =>
      right.score - left.score ||
      (graph.degree.get(right.node.id) ?? 0) - (graph.degree.get(left.node.id) ?? 0) ||
      left.node.name.localeCompare(right.node.name),
  );
  return hits.slice(0, limit);
}

/** Undirected neighbourhood up to `depth` hops. Includes the origin. */
export function neighborhood(graph: UniverseGraph, id: string, depth = 1): Set<string> {
  const seen = new Set<string>([id]);
  let frontier = [id];
  for (let hop = 0; hop < depth; hop += 1) {
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
    if (frontier.length === 0) {
      break;
    }
  }
  return seen;
}

/**
 * The shortest undirected path between two nodes, or an empty array.
 *
 * Used to draw the AI evidence trail. A missing path is returned as empty rather than
 * bridged with a made-up edge: the trail must be a path that exists in the index.
 */
export function shortestPath(
  graph: UniverseGraph,
  from: string,
  to: string,
  maxDepth = 8,
): string[] {
  if (from === to) {
    return graph.nodeById.has(from) ? [from] : [];
  }
  if (!graph.nodeById.has(from) || !graph.nodeById.has(to)) {
    return [];
  }
  const previous = new Map<string, string>();
  const seen = new Set<string>([from]);
  let frontier = [from];
  for (let depth = 0; depth < maxDepth && frontier.length > 0; depth += 1) {
    const next: string[] = [];
    for (const current of frontier) {
      for (const edge of graph.adjacency.get(current) ?? []) {
        const other = edge.source === current ? edge.target : edge.source;
        if (seen.has(other)) {
          continue;
        }
        seen.add(other);
        previous.set(other, current);
        if (other === to) {
          const path = [to];
          let step = to;
          while (step !== from) {
            const parent = previous.get(step);
            if (parent === undefined) {
              return [];
            }
            path.push(parent);
            step = parent;
          }
          return path.reverse();
        }
        next.push(other);
      }
    }
    frontier = next;
  }
  return [];
}

/**
 * Maps investigation evidence to nodes in the current graph.
 *
 * Evidence carries a path and a line range, not a symbol id, because the investigation may
 * describe a revision other than the one on screen. A path with no matching node is skipped:
 * drawing it would claim the graph contains something it does not.
 */
export function evidenceNodeIds(graph: UniverseGraph, evidence: InvestigationEvidence[]): string[] {
  const ordered: string[] = [];
  const seen = new Set<string>();
  for (const item of evidence) {
    if (item.path === null || item.path === undefined) {
      continue;
    }
    const candidates = graph.nodes.filter(
      (node) => !node.external && node.path === item.path,
    );
    if (candidates.length === 0) {
      continue;
    }
    const line = item.startLine ?? null;
    const withLine =
      line === null
        ? candidates
        : candidates.filter(
            (node) =>
              node.startLine !== null &&
              node.startLine !== undefined &&
              node.startLine <= line &&
              line <= (node.endLine ?? node.startLine),
          );
    const pool = withLine.length > 0 ? withLine : candidates;
    const named =
      item.symbolName === null || item.symbolName === undefined
        ? []
        : pool.filter((node) => node.name === item.symbolName);
    // With a location, the tightest enclosing declaration is the honest match: a line
    // inside a method is also inside its class, but the method is what the evidence is
    // about. The type-declaration preference is only for path-only evidence, where
    // there is no span to compare.
    const span = (candidate: UniverseNode): number =>
      (candidate.endLine ?? candidate.startLine ?? 0) - (candidate.startLine ?? 0);
    const tightest =
      line !== null && withLine.length > 0
        ? (() => {
            const smallest = Math.min(...pool.map(span));
            return pool.filter((candidate) => span(candidate) === smallest);
          })()
        : [];
    const preferred =
      named.length > 0
        ? named
        : tightest.length > 0
          ? tightest
          : pool.filter((node) => TYPE_KINDS.includes(node.kind));
    const pick = (preferred.length > 0 ? preferred : pool)[0];
    if (pick !== undefined && !seen.has(pick.id)) {
      seen.add(pick.id);
      ordered.push(pick.id);
    }
  }
  return ordered;
}

/** Radius is a visual encoding of degree and complexity, clamped so one hub cannot fill the screen. */
export function nodeRadius(node: UniverseNode, degree: number, selected: boolean): number {
  if (node.external) {
    return 2.6 + Math.min(2.4, Math.sqrt(degree) * 0.9);
  }
  const base = TYPE_KINDS.includes(node.kind) ? 7 : node.kind === "METHOD" || node.kind === "CONSTRUCTOR" ? 4.2 : 4.8;
  const growth = Math.sqrt(Math.max(0, degree)) * 1.9;
  const complexity = node.complexity === null || node.complexity === undefined ? 0 : Math.min(4, node.complexity / 10);
  return Math.min(22, base + growth + complexity) + (selected ? 3 : 0);
}

/** Counts for the legend; computed from the same slice the canvas draws. */
export function countKinds(nodes: UniverseNode[]): Array<{ kind: string; count: number }> {
  const counts = new Map<string, number>();
  for (const node of nodes) {
    counts.set(node.kind, (counts.get(node.kind) ?? 0) + 1);
  }
  return Array.from(counts.entries())
    .map(([kind, count]) => ({ kind, count }))
    .sort((left, right) => right.count - left.count);
}

export function percentOf(part: number, whole: number): number {
  return whole <= 0 ? 0 : Math.round((part / whole) * 100);
}
