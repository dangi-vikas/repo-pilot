import { describe, expect, it } from "vitest";
import {
  EMPTY_FILTERS,
  buildUniverse,
  countKinds,
  dimmedBySeverityLens,
  evidenceNodeIds,
  filterGraph,
  neighborhood,
  nodeRadius,
  percentOf,
  searchUniverse,
  shortestPath,
} from "@/lib/universe";
import type {
  FindingView,
  InvestigationEvidence,
  UniverseEdge,
  UniverseNode,
  UniverseView,
} from "@/lib/types";

type FindingSeed = Pick<
  FindingView,
  | "id"
  | "severity"
  | "analyzerId"
  | "findingType"
  | "title"
  | "confidence"
  | "category"
  | "evidenceCategory"
  | "verificationStatus"
> & {
  symbolId?: string | null;
  sourcePath?: string | null;
  startLine?: number | null;
};

function finding(seed: FindingSeed): FindingView {
  return seed;
}

interface EvidenceSeed {
  key: string;
  path: string | null;
  startLine?: number | null;
  symbolName?: string | null;
}

function evidence(seed: EvidenceSeed): InvestigationEvidence {
  return {
    key: seed.key,
    kind: "SYMBOL",
    label: seed.key,
    detail: null,
    path: seed.path,
    startLine: seed.startLine ?? null,
    endLine: null,
    symbolName: seed.symbolName ?? null,
    symbolKind: null,
    retrievalScore: 1,
    retrievalMethod: "lexical",
    rank: 1,
    cited: true,
    sourceStatus: "CONFIRMED",
    verifiedAt: null,
    excerptChars: 120,
  };
}

const nodes: UniverseNode[] = [
  {
    id: "a",
    kind: "CLASS",
    name: "App",
    qualifiedName: "demo.App",
    path: "src/app/A.java",
    packageName: "app",
    startLine: 1,
    endLine: 50,
    complexity: 12,
    external: false,
  },
  {
    id: "b",
    kind: "METHOD",
    name: "run",
    qualifiedName: "demo.App.run",
    path: "src/app/A.java",
    packageName: "app",
    startLine: 10,
    endLine: 20,
    complexity: 4,
    external: false,
  },
  {
    id: "c",
    kind: "CLASS",
    name: "Service",
    qualifiedName: "demo.Service",
    path: "src/svc/B.java",
    packageName: "svc",
    startLine: 1,
    endLine: 80,
    complexity: 30,
    external: false,
  },
  {
    id: "d",
    kind: "EXTERNAL",
    name: "java.util.List",
    qualifiedName: "java.util.List",
    path: null,
    packageName: null,
    startLine: null,
    endLine: null,
    complexity: null,
    external: true,
  },
  {
    id: "e",
    kind: "CLASS",
    name: "Other",
    qualifiedName: "other.Other",
    path: "src/other/C.java",
    packageName: "other",
    startLine: 1,
    endLine: 30,
    external: false,
  },
  {
    id: "z",
    kind: "CLASS",
    name: "Zed",
    qualifiedName: "z.Zed",
    path: "src/z/Z.java",
    packageName: "z",
    startLine: 1,
    endLine: 10,
    external: false,
  },
];

const edges: UniverseEdge[] = [
  { id: "e1", source: "a", target: "b", type: "CALLS", resolution: "RESOLVED", sourceLine: 12 },
  { id: "e2", source: "a", target: "c", type: "TYPE_REFERENCE", resolution: "INFERRED", sourceLine: 3 },
  { id: "e3", source: "c", target: "d", type: "CALLS", resolution: "EXTERNAL", sourceLine: 40 },
  { id: "e4", source: "b", target: "e", type: "CALLS", resolution: "UNRESOLVED", sourceLine: 15 },
  { id: "dangling", source: "a", target: "ghost", type: "CALLS", resolution: "UNRESOLVED" },
];

const view: UniverseView = {
  revisionId: "revision-1",
  nodes,
  edges,
  totalSymbols: nodes.filter((node) => !node.external).length,
  totalEdges: edges.length,
  edgeLimit: 2_400,
  truncated: false,
};

const findings: FindingView[] = [
  finding({
    id: "f1",
    severity: "CRITICAL",
    analyzerId: "pmd",
    findingType: "BUG",
    title: "Unchecked call",
    confidence: "HIGH",
    category: "CORRECTNESS",
    evidenceCategory: "STATIC_FINDING",
    verificationStatus: "SOURCE_CONFIRMED",
    symbolId: "b",
    sourcePath: "src/app/A.java",
    startLine: 12,
  }),
  finding({
    id: "f2",
    severity: "LOW",
    analyzerId: "pmd",
    findingType: "STYLE",
    title: "Line-level note",
    confidence: "MEDIUM",
    category: "STYLE",
    evidenceCategory: "STATIC_FINDING",
    verificationStatus: "SOURCE_CONFIRMED",
    sourcePath: "src/app/A.java",
    startLine: 15,
  }),
  finding({
    id: "f3",
    severity: "HIGH",
    analyzerId: "spotbugs",
    findingType: "BUG",
    title: "Leaked resource",
    confidence: "CERTAIN",
    category: "RESOURCE",
    evidenceCategory: "STATIC_FINDING",
    verificationStatus: "SOURCE_CONFIRMED",
    sourcePath: "src/svc/B.java",
    startLine: 40,
  }),
  finding({
    id: "f4",
    severity: "HIGH",
    analyzerId: "pmd",
    findingType: "BUG",
    title: "Outside any declaration",
    confidence: "LOW",
    category: "CORRECTNESS",
    evidenceCategory: "STATIC_FINDING",
    verificationStatus: "SOURCE_CONFIRMED",
    sourcePath: "src/app/A.java",
    startLine: 999,
  }),
  finding({
    id: "f5",
    severity: "CRITICAL",
    analyzerId: "pmd",
    findingType: "BUG",
    title: "On an external target",
    confidence: "LOW",
    category: "CORRECTNESS",
    evidenceCategory: "STATIC_FINDING",
    verificationStatus: "SOURCE_CONFIRMED",
    symbolId: "d",
  }),
  finding({
    id: "f6",
    severity: "MEDIUM",
    analyzerId: "pmd",
    findingType: "BUG",
    title: "File-level, no line",
    confidence: "MEDIUM",
    category: "CORRECTNESS",
    evidenceCategory: "STATIC_FINDING",
    verificationStatus: "SOURCE_CONFIRMED",
    sourcePath: "src/other/C.java",
  }),
];

const graph = buildUniverse(view, findings);

describe("buildUniverse", () => {
  it("attributes findings by symbol id and by containing line range", () => {
    expect(graph.findingsByNode.get("b")?.map((item) => item.id)).toEqual(["f1", "f2"]);
    expect(graph.findingsByNode.get("a")?.map((item) => item.id)).toEqual(["f2"]);
    expect(graph.findingsByNode.get("c")?.map((item) => item.id)).toEqual(["f3"]);
  });

  it("uses the highest severity on a node as its severity", () => {
    expect(graph.severityByNode.get("b")).toBe("CRITICAL");
    expect(graph.severityByNode.get("a")).toBe("LOW");
    expect(graph.severityByNode.get("c")).toBe("HIGH");
  });

  it("never attaches findings to external nodes, out-of-range lines, or file-level findings with no line", () => {
    expect(graph.findingsByNode.has("d")).toBe(false);
    expect(graph.findingsByNode.has("e")).toBe(false);
    expect(graph.findingsByNode.has("z")).toBe(false);
    expect(graph.findingsByNode.size).toBe(3);
  });

  it("ignores edges whose endpoints are not in the slice", () => {
    expect(graph.edges.map((edge) => edge.id)).toContain("dangling");
    expect(graph.degree.get("a")).toBe(2);
    expect(graph.adjacency.get("a")?.map((edge) => edge.id)).toEqual(["e1", "e2"]);
  });

  it("lists packages once, sorted, excluding blank names", () => {
    expect(graph.packages).toEqual(["app", "other", "svc", "z"]);
  });
});

describe("filterGraph", () => {
  it("hides external nodes and the edges touching them", () => {
    const filtered = filterGraph(graph, { ...EMPTY_FILTERS, hideExternal: true });
    expect(filtered.nodes.map((node) => node.id)).not.toContain("d");
    expect(filtered.edges.map((edge) => edge.id)).toEqual(["e1", "e2", "e4"]);
  });

  it("filters by kind and package", () => {
    const classes = filterGraph(graph, { ...EMPTY_FILTERS, kinds: ["CLASS"] });
    expect(classes.nodes.map((node) => node.id)).toEqual(["a", "c", "e", "z"]);
    expect(classes.edges.map((edge) => edge.id)).toEqual(["e2"]);

    const app = filterGraph(graph, { ...EMPTY_FILTERS, packages: ["app"] });
    expect(app.nodes.map((node) => node.id)).toEqual(["a", "b"]);
    expect(app.edges.map((edge) => edge.id)).toEqual(["e1"]);
  });

  it("keeps only nodes with attributed findings when asked", () => {
    const only = filterGraph(graph, { ...EMPTY_FILTERS, findingsOnly: true });
    expect(only.nodes.map((node) => node.id)).toEqual(["a", "b", "c"]);
  });

  it("filters edges by type and resolution independently of node filters", () => {
    const calls = filterGraph(graph, { ...EMPTY_FILTERS, edgeTypes: ["CALLS"] });
    expect(calls.edges.map((edge) => edge.id)).toEqual(["e1", "e3", "e4"]);

    const resolved = filterGraph(graph, { ...EMPTY_FILTERS, resolutions: ["RESOLVED"] });
    expect(resolved.edges.map((edge) => edge.id)).toEqual(["e1"]);
  });

  it("drops an edge when a combined filter removes one endpoint", () => {
    const filtered = filterGraph(graph, {
      ...EMPTY_FILTERS,
      hideExternal: true,
      kinds: ["CLASS"],
      packages: ["svc"],
    });
    expect(filtered.nodes.map((node) => node.id)).toEqual(["c"]);
    expect(filtered.edges).toEqual([]);
  });
});

describe("severity lens", () => {
  it("does not dim anything when no lens is active", () => {
    expect(dimmedBySeverityLens("CRITICAL", [])).toBe(false);
    expect(dimmedBySeverityLens(undefined, [])).toBe(false);
  });

  it("dims nodes outside the selected severities, including nodes with no finding", () => {
    expect(dimmedBySeverityLens("CRITICAL", ["CRITICAL"])).toBe(false);
    expect(dimmedBySeverityLens("HIGH", ["CRITICAL"])).toBe(true);
    expect(dimmedBySeverityLens(undefined, ["CRITICAL"])).toBe(true);
  });
});

describe("searchUniverse", () => {
  it("ranks an exact name above a path-only match", () => {
    const hits = searchUniverse(graph, "app");
    expect(hits.map((hit) => hit.node.id)).toEqual(["a", "b"]);
    expect(hits[0]?.score).toBe(100);
  });

  it("matches a fully qualified name case-insensitively", () => {
    const hits = searchUniverse(graph, "DEMO.SERVICE");
    expect(hits[0]?.node.id).toBe("c");
    expect(hits[0]?.score).toBe(95);
  });

  it("returns nothing for a blank query and honours the limit", () => {
    expect(searchUniverse(graph, "   ")).toEqual([]);
    expect(searchUniverse(graph, "a", 1)).toHaveLength(1);
  });

  it("ranks a name match above a method whose file path matches", () => {
    const named = buildUniverse(
      {
        ...view,
        nodes: [
          {
            id: "decl",
            kind: "CLASS",
            name: "PaymentService",
            qualifiedName: "demo.PaymentService",
            path: "src/payment/PaymentService.java",
            packageName: "payment",
            startLine: 1,
            endLine: 20,
            complexity: null,
            external: false,
          },
          {
            id: "method",
            kind: "METHOD",
            name: "run",
            qualifiedName: "demo.Other.run",
            path: "src/service/Other.java",
            packageName: "service",
            startLine: 1,
            endLine: 10,
            complexity: null,
            external: false,
          },
        ],
        edges: [],
      },
      [],
    );
    expect(searchUniverse(named, "service").map((hit) => hit.node.id)).toEqual(["decl", "method"]);
  });
});

describe("neighborhood", () => {
  it("returns one hop and two hops, including the origin", () => {
    expect([...neighborhood(graph, "a", 1)].sort()).toEqual(["a", "b", "c"]);
    expect([...neighborhood(graph, "a", 2)].sort()).toEqual(["a", "b", "c", "d", "e"]);
  });

  it("does not cross into an unconnected component", () => {
    expect(neighborhood(graph, "a", 3).has("z")).toBe(false);
  });
});

describe("shortestPath", () => {
  it("returns a direct edge as two nodes", () => {
    expect(shortestPath(graph, "b", "e")).toEqual(["b", "e"]);
  });

  it("takes the shortest path through the undirected graph", () => {
    expect(shortestPath(graph, "b", "d")).toEqual(["b", "a", "c", "d"]);
  });

  it("returns an empty path rather than inventing a connection", () => {
    expect(shortestPath(graph, "a", "z")).toEqual([]);
    expect(shortestPath(graph, "missing", "a")).toEqual([]);
    expect(shortestPath(graph, "a", "d", 1)).toEqual([]);
  });

  it("treats a node as connected to itself only when it exists", () => {
    expect(shortestPath(graph, "a", "a")).toEqual(["a"]);
    expect(shortestPath(graph, "ghost", "ghost")).toEqual([]);
  });
});

describe("evidenceNodeIds", () => {
  it("maps an evidence line to the declaration that contains it", () => {
    expect(evidenceNodeIds(graph, [evidence({ key: "E1", path: "src/app/A.java", startLine: 12 })])).toEqual([
      "b",
    ]);
    expect(evidenceNodeIds(graph, [evidence({ key: "E2", path: "src/app/A.java", startLine: 1 })])).toEqual([
      "a",
    ]);
  });

  it("prefers a matching symbol name, then a type declaration, then the first candidate", () => {
    expect(
      evidenceNodeIds(graph, [evidence({ key: "E3", path: "src/app/A.java", symbolName: "run" })]),
    ).toEqual(["b"]);
    expect(evidenceNodeIds(graph, [evidence({ key: "E4", path: "src/app/A.java" })])).toEqual(["a"]);
  });

  it("skips evidence that does not exist in the slice, and de-duplicates nodes", () => {
    const ids = evidenceNodeIds(graph, [
      evidence({ key: "E5", path: "missing/File.java", startLine: 4 }),
      evidence({ key: "E6", path: "src/app/A.java", startLine: 12 }),
      evidence({ key: "E7", path: "src/app/A.java", startLine: 13 }),
      evidence({ key: "E8", path: null }),
    ]);
    expect(ids).toEqual(["b"]);
  });

  it("finds a node from a path with no recorded line", () => {
    expect(evidenceNodeIds(graph, [evidence({ key: "E9", path: "src/svc/B.java" })])).toEqual(["c"]);
  });
});

describe("visual encodings", () => {
  it("sizes declarations above externals and adds a halo for selection", () => {
    const declaration = nodes[0]!;
    const external = nodes[3]!;
    expect(nodeRadius(declaration, 2, false)).toBeGreaterThan(nodeRadius(external, 2, false));
    expect(nodeRadius(declaration, 2, true)).toBe(nodeRadius(declaration, 2, false) + 3);
  });

  it("clamps a hub so it cannot fill the screen", () => {
    expect(nodeRadius(nodes[0]!, 10_000, false)).toBe(22);
    expect(nodeRadius(nodes[0]!, 10_000, true)).toBe(25);
  });

  it("counts kinds from the same slice the legend draws", () => {
    expect(countKinds(nodes).slice(0, 2)).toEqual([
      { kind: "CLASS", count: 4 },
      { kind: "METHOD", count: 1 },
    ]);
  });

  it("reports percentages without dividing by zero", () => {
    expect(percentOf(2, 4)).toBe(50);
    expect(percentOf(1, 0)).toBe(0);
  });
});
