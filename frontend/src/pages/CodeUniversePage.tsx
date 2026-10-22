import { AnimatePresence, motion } from "motion/react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { AiOrb, type AiOrbState } from "@/components/AiOrb";
import { Icon } from "@/components/icons";
import { IndexButton } from "@/components/IndexButton";
import { RepositoryRequired } from "@/components/RepositoryRequired";
import { NodeInspector } from "@/components/universe/NodeInspector";
import { UniverseCanvas, type UniverseTrail } from "@/components/universe/UniverseCanvas";
import { UniverseLegend, UniverseToolbar } from "@/components/universe/UniverseChrome";
import { Badge, Banner, EmptyState, ErrorState, Loading, PageHeader, ProgressBar } from "@/components/ui";
import {
  useFindingSummary,
  useFindings,
  useInvestigation,
  useInvestigations,
  useJobs,
  useOverview,
  useRepository,
  useUniverse,
} from "@/hooks/queries";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError } from "@/lib/api";
import { formatNumber, formatRelative, hasValue, titleCase } from "@/lib/format";
import type { FindingSeverity, Uuid } from "@/lib/types";
import {
  EMPTY_FILTERS,
  buildUniverse,
  evidenceNodeIds,
  filterGraph,
  shortestPath,
  type UniverseFilters,
  type UniverseGraph,
} from "@/lib/universe";
import { useMotion } from "@/state/motion";
import { useSelection } from "@/state/selection";

/**
 * Code Universe: the dependency graph as an explorable space.
 *
 * The screen draws a bounded slice assembled by the backend (`/universe`), states when it is
 * a slice, and never turns a static-analysis finding into a runtime claim. Severity rings
 * come only from a findings run whose revision matches the graph; if the run describes a
 * different revision the overlay is withheld and the page says why.
 */
export function CodeUniversePage() {
  const { repositoryId, revisionId } = useSelection();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { reduced, mode, toggle } = useMotion();

  const repository = useRepository(repositoryId);
  const universe = useUniverse(repositoryId, revisionId);
  const jobs = useJobs(undefined);
  const overview = useOverview();
  const ai = overview.data?.ai;

  const resolvedRevisionId = universe.data?.revisionId ?? revisionId;
  const summary = useFindingSummary(repositoryId, resolvedRevisionId);
  const analysisActive =
    jobs.data?.items.some(
      (job) =>
        job.repositoryId === repositoryId &&
        job.jobType === "STATIC_ANALYSIS" &&
        (job.status === "QUEUED" || job.status === "RUNNING"),
    ) ?? false;
  const findings = useFindings(
    repositoryId,
    { size: 200 },
    { enabled: repositoryId !== null, refetchWhileActive: analysisActive },
  );
  const investigations = useInvestigations(repositoryId);

  const indexJob = jobs.data?.items.find(
    (job) =>
      job.repositoryId === repositoryId &&
      job.jobType === "REPOSITORY_INDEX" &&
      (job.status === "QUEUED" || job.status === "RUNNING"),
  );

  const findingsMatchRevision =
    summary.data?.revisionId !== null &&
    summary.data?.revisionId !== undefined &&
    universe.data !== undefined &&
    summary.data.revisionId === universe.data.revisionId;

  const graph = useMemo(() => {
    if (universe.data === undefined) {
      return null;
    }
    const items = findingsMatchRevision ? findings.data?.items ?? [] : [];
    return buildUniverse(universe.data, items);
  }, [universe.data, findings.data, findingsMatchRevision]);

  const [filters, setFilters] = useState<UniverseFilters>(EMPTY_FILTERS);
  const [severityLens, setSeverityLens] = useState<FindingSeverity[]>([]);
  const [query, setQuery] = useState("");
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [focusMode, setFocusMode] = useState(false);
  const [inspectorOpen, setInspectorOpen] = useState(false);
  const [scanKey, setScanKey] = useState(0);
  const [density, setDensity] = useState(0.4);
  const [searchParams] = useSearchParams();
  const trailParam = searchParams.get("trail");
  const [trailId, setTrailId] = useState<Uuid | null>(trailParam);
  const [visible, setVisible] = useState({ nodes: 0, edges: 0 });

  // An investigation can hand off its evidence trail through the URL. The revision guard
  // below is what stops an old trail from being drawn over a newer graph.
  useEffect(() => {
    if (trailParam !== null && trailParam.length > 0) {
      setTrailId(trailParam);
    }
  }, [trailParam]);

  const trailDetail = useInvestigation(repositoryId, trailId);
  const trailRevisionMatches =
    trailDetail.data?.revisionId == null ||
    graph === null ||
    trailDetail.data.revisionId === graph.revisionId;
  const finishedInvestigations = (investigations.data?.items ?? []).filter(
    (item) => item.status === "SUCCEEDED",
  );

  const trail = useMemo<UniverseTrail | null>(() => {
    if (graph === null || trailDetail.data === undefined || !trailRevisionMatches) {
      return null;
    }
    const ordered = evidenceNodeIds(graph, trailDetail.data.evidence);
    if (ordered.length === 0) {
      return null;
    }
    const edgeIds = new Set<string>();
    for (let index = 0; index < ordered.length - 1; index += 1) {
      const path = shortestPath(graph, ordered[index]!, ordered[index + 1]!, 8);
      for (let step = 0; step < path.length - 1; step += 1) {
        const from = path[step]!;
        const to = path[step + 1]!;
        const edge = (graph.adjacency.get(from) ?? []).find(
          (candidate) =>
            (candidate.source === from && candidate.target === to) ||
            (candidate.source === to && candidate.target === from),
        );
        if (edge !== undefined) {
          edgeIds.add(edge.id);
        }
      }
    }
    return { nodes: ordered, edges: Array.from(edgeIds) };
  }, [graph, trailDetail.data, trailRevisionMatches]);

  const visibleNow = useMemo(() => {
    if (graph === null) {
      return { nodes: 0, edges: 0 };
    }
    const filtered = filterGraph(graph, filters);
    return { nodes: filtered.nodes.length, edges: filtered.edges.length };
  }, [graph, filters]);

  useEffect(() => {
    setVisible(visibleNow);
  }, [visibleNow]);

  // Drop selected ids that the new graph does not contain, rather than leaving the
  // inspector pointing at a node from the previous revision.
  useEffect(() => {
    if (graph === null) {
      setSelectedIds([]);
      return;
    }
    setSelectedIds((current) => current.filter((id) => graph.nodeById.has(id)));
  }, [graph]);

  // When an index job finishes, the graph query is stale by definition. Invalidate the
  // revision-scoped reads so the new universe replaces the old one without a reload.
  const indexWasActive = useRef(false);
  useEffect(() => {
    const active = indexJob !== undefined;
    if (indexWasActive.current && !active && repositoryId !== null) {
      void queryClient.invalidateQueries({ queryKey: ["universe", repositoryId] });
      void queryClient.invalidateQueries({ queryKey: ["revisions", repositoryId] });
      void queryClient.invalidateQueries({ queryKey: ["repository", repositoryId] });
      void queryClient.invalidateQueries({ queryKey: ["coverage", repositoryId] });
      void queryClient.invalidateQueries({ queryKey: ["finding-summary", repositoryId] });
    }
    indexWasActive.current = active;
  }, [indexJob, repositoryId, queryClient]);

  const selectedNode = useMemo(() => {
    if (graph === null || selectedIds.length === 0) {
      return null;
    }
    return graph.nodeById.get(selectedIds[0]!) ?? null;
  }, [graph, selectedIds]);

  const selectNode = useCallback((id: string, additive = false) => {
    setSelectedIds((current) => {
      if (!additive) {
        return [id];
      }
      return current.includes(id) ? current.filter((value) => value !== id) : [...current, id];
    });
    setInspectorOpen(true);
  }, []);

  const focusNode = useCallback((id: string) => {
    setSelectedIds([id]);
    setFocusMode(true);
    setInspectorOpen(true);
  }, []);

  const expandNode = useCallback(
    (id: string) => {
      if (graph === null) {
        return;
      }
      const neighbours = new Set<string>([id]);
      for (const edge of graph.adjacency.get(id) ?? []) {
        neighbours.add(edge.source === id ? edge.target : edge.source);
      }
      setSelectedIds(Array.from(neighbours));
      setInspectorOpen(true);
    },
    [graph],
  );

  const openSource = useCallback(
    (path: string, line: number | null) => {
      const params = new URLSearchParams({ path });
      if (line !== null && line > 0) {
        params.set("line", String(line));
      }
      navigate(`/code?${params.toString()}`);
    },
    [navigate],
  );

  const askAbout = useCallback(
    (id: string) => {
      if (graph === null) {
        return;
      }
      const node = graph.nodeById.get(id);
      if (node === undefined || node.external) {
        return;
      }
      const question = `Explain what ${node.name} does, what it depends on, and any risks visible in its implementation.`;
      const scope = node.qualifiedName ?? node.path ?? node.name;
      const params = new URLSearchParams({ ask: question, scope });
      navigate(`/investigation?${params.toString()}`);
    },
    [graph, navigate],
  );

  const onVisibleStats = useCallback((stats: { nodes: number; edges: number }) => {
    setVisible(stats);
  }, []);

  if (repositoryId === null) {
    return (
      <>
        <PageHeader
          title="Code Universe"
          description="An explorable map of the indexed codebase: packages, classes, methods and the relationships between them."
        />
        <EmptyState
          icon="graph"
          title="Select a repository to map its universe"
          message="The universe is assembled from one indexed revision. Pick a repository in the top bar, or register one."
        />
        <RepositoryRequired />
      </>
    );
  }

  const notIndexed =
    universe.isError &&
    universe.error instanceof ApiError &&
    universe.error.code === "CONFLICT";

  const aiState: AiOrbState =
    ai === undefined ? "idle" : !ai.enabled ? "offline" : !ai.usable ? "error" : "idle";

  return (
    <div className="universe-page">
      <PageHeader
        title="Code Universe"
        description={
          repository.data !== undefined
            ? `${repository.data.name} · ${repository.data.analyzedFileCount.toLocaleString()} files indexed · ${formatRelative(repository.data.lastIndexedAt) ?? "never indexed"}`
            : "Packages, classes, methods and the relationships the index resolved."
        }
        actions={
          <>
            <span className="u-ai-chip" title={ai?.message ?? "AI provider status"}>
              <AiOrb state={aiState} size={22} />
              <span>{ai?.usable ? "AI ready" : ai?.enabled ? "AI degraded" : "AI offline"}</span>
            </span>
            <button
              type="button"
              className="btn btn-sm"
              onClick={() => {
                setScanKey((value) => value + 1);
                if (reduced) {
                  toggle();
                }
              }}
              title="Replay the reveal of the current index. This is a replay, not a new scan."
            >
              <Icon name="refresh" size={14} />
              Replay reveal
            </button>
            <IndexButton repositoryId={repositoryId} disabled={indexJob !== undefined} />
            <button
              type="button"
              className={inspectorOpen ? "btn btn-sm btn-ghost is-on" : "btn btn-sm btn-ghost"}
              onClick={() => setInspectorOpen((open) => !open)}
              aria-pressed={inspectorOpen}
              title="Toggle the contextual inspector"
            >
              <Icon name="eye" size={14} />
              Inspector
            </button>
          </>
        }
      />

      {indexJob !== undefined ? (
        <div className="universe-indexing">
          <div className="ui-row">
            <span className="u-pulse-dot" />
            <strong>Indexing {repository.data?.name ?? "repository"}</strong>
            <span className="rp-faint">
              {indexJob.stage ?? titleCase(indexJob.status)} · {indexJob.progress}%
            </span>
            <span className="rp-spacer" />
            <span className="rp-faint u-small">
              The graph shows the last completed revision until this job finishes.
            </span>
          </div>
          <ProgressBar value={indexJob.progress} />
        </div>
      ) : null}

      {notIndexed ? (
        <div className="card">
          <EmptyState
            icon="graph"
            title="This repository has no indexed revision yet"
            message="Run an index job to extract files, symbols and relationships. The universe is built from what the parser actually recorded."
            action={<IndexButton repositoryId={repositoryId} />}
          />
        </div>
      ) : universe.isLoading ? (
        <div className="universe-stage">
          <Loading label="Assembling the code universe" />
        </div>
      ) : universe.isError ? (
        <ErrorState
          title="The universe could not be loaded"
          message={universe.error instanceof Error ? universe.error.message : "Unknown error."}
          onRetry={() => void universe.refetch()}
        />
      ) : graph === null || universe.data === undefined ? null : graph.nodes.length === 0 ? (
        <div className="card">
          <EmptyState
            icon="graph"
            title="No relationships were indexed"
            message="The revision has no symbols or no structural edges in the selected relationship types. Try the Code Explorer to inspect what was parsed."
            action={
              <button type="button" className="btn btn-sm" onClick={() => navigate("/code")}>
                Open Code Explorer
              </button>
            }
          />
        </div>
      ) : (
        <>
          <UniverseToolbar
            graph={graph}
            filters={filters}
            onFilters={setFilters}
            severityLens={severityLens}
            onSeverityLens={setSeverityLens}
            query={query}
            onQuery={setQuery}
            focusMode={focusMode}
            onFocusMode={setFocusMode}
            visible={visible}
            onSelectNode={(id) => selectNode(id)}
            onClearFilters={() => {
              setFilters(EMPTY_FILTERS);
              setSeverityLens([]);
              setQuery("");
            }}
          />

          {universe.data.truncated ? (
            <Banner tone="info">
              Showing {formatNumber(universe.data.edges.length)} of {formatNumber(universe.data.totalEdges)}{" "}
              indexed relationships ({formatNumber(universe.data.nodes.filter((node) => !node.external).length)}{" "}
              of {formatNumber(universe.data.totalSymbols)} symbols, plus external targets). The slice is
              bounded so the canvas stays interactive; it is not the whole graph.
            </Banner>
          ) : null}

          {!findingsMatchRevision && (findings.data?.items.length ?? 0) > 0 ? (
            <p className="u-note">
              Findings are not overlaid: the current findings run describes{" "}
              {summary.data?.revisionId === null || summary.data?.revisionId === undefined
                ? "no indexed revision"
                : "a different revision"}{" "}
              than the graph. Analyse this revision to attach severity to nodes.
            </p>
          ) : null}

          <div className={inspectorOpen ? "universe-stage has-inspector" : "universe-stage"}>
            <div className="universe-main">
              {finishedInvestigations.length > 0 ? (
                <div className="universe-trail-bar">
                  <Icon name="sparkles" size={14} />
                  <label htmlFor="trail-select">AI evidence trail</label>
                  <select
                    id="trail-select"
                    className="input input-sm"
                    value={trailId ?? ""}
                    onChange={(event) => setTrailId(event.target.value === "" ? null : event.target.value)}
                  >
                    <option value="">Off</option>
                    {finishedInvestigations.map((item) => (
                      <option key={item.id} value={item.id}>
                        {item.title.length > 64 ? `${item.title.slice(0, 63)}…` : item.title}
                      </option>
                    ))}
                  </select>
                  {trailId !== null && trailDetail.data !== undefined && !trailRevisionMatches ? (
                    <span className="trail-summary trail-mismatch">
                      Withheld: the investigation ran on revision{" "}
                      <span className="rp-mono">{trailDetail.data.revisionId?.slice(0, 8) ?? "unknown"}</span>{" "}
                      while the graph shows{" "}
                      <span className="rp-mono">{graph?.revisionId.slice(0, 8) ?? "unknown"}</span>.
                    </span>
                  ) : null}
                  {trail !== null ? (
                    <span className="trail-summary">
                      <Badge tone={trailDetail.data?.grounded === true ? "success" : "warning"} dot>
                        {trailDetail.data?.grounded === true ? "grounded" : "not grounded"}
                      </Badge>
                      {trail.nodes.length} evidence node{trail.nodes.length === 1 ? "" : "s"}
                      {trail.edges.length > 0 ? ` · ${trail.edges.length} path edges` : " · no connected path"}
                    </span>
                  ) : null}
                  {trailDetail.isLoading ? <span className="rp-faint">Loading evidence…</span> : null}
                </div>
              ) : null}

              <UniverseCanvas
                graph={graph}
                filters={filters}
                severityLens={severityLens}
                query={query}
                selectedIds={selectedIds}
                focusMode={focusMode}
                trail={trail}
                particleDensity={density}
                scanKey={scanKey}
                reducedMotion={reduced}
                onSelect={selectNode}
                onFocusNode={focusNode}
                onClearSelection={() => setSelectedIds([])}
                onVisibleStats={onVisibleStats}
              />

              <div className="universe-legend-wrap">
                <UniverseLegend graph={graph} />
              </div>

              <div className="universe-flow">
                <label htmlFor="flow-density">
                  <span>Connection flow</span>
                  <input
                    id="flow-density"
                    type="range"
                    min={0}
                    max={1}
                    step={0.1}
                    value={density}
                    onChange={(event) => setDensity(Number(event.target.value))}
                    disabled={reduced}
                  />
                  <span className="rp-mono">{Math.round(density * 100)}%</span>
                </label>
                <button
                  type="button"
                  className="flow-motion"
                  onClick={toggle}
                  title={mode === "full" ? "Reduce motion across the app" : "Enable full motion"}
                >
                  {reduced ? "Reduced motion" : "Full motion"}
                </button>
              </div>

              <div className="universe-hint" aria-hidden="true">
                <span>scroll to zoom</span>
                <span>drag empty space to pan</span>
                <span>double-click a node to focus</span>
              </div>

              {selectedIds.length > 1 ? (
                <div className="universe-multiselect">
                  <Icon name="layers" size={13} />
                  {selectedIds.length} selected
                  <button type="button" onClick={() => setSelectedIds([])}>
                    clear
                  </button>
                </div>
              ) : null}
            </div>

            <AnimatePresence initial={false}>
              {inspectorOpen ? (
                <motion.div
                  key="inspector"
                  className="universe-inspector-wrap"
                  initial={reduced ? false : { x: 36, opacity: 0 }}
                  animate={{ x: 0, opacity: 1 }}
                  exit={reduced ? { opacity: 1 } : { x: 36, opacity: 0 }}
                  transition={reduced ? { duration: 0 } : { type: "spring", stiffness: 260, damping: 32 }}
                >
                  {selectedNode !== null ? (
                    <NodeInspector
                      repositoryId={repositoryId}
                      revisionId={resolvedRevisionId}
                      graph={graph}
                      nodeId={selectedNode.id}
                      aiUsable={ai?.usable === true}
                      onClose={() => setInspectorOpen(false)}
                      onSelectNode={(id) => selectNode(id)}
                      onFocusNode={focusNode}
                      onExpand={expandNode}
                      onOpenSource={openSource}
                      onAsk={askAbout}
                    />
                  ) : (
                    <aside className="node-inspector">
                      <div className="ni-head">
                        <span className="ni-eyebrow">Inspector</span>
                        <button
                          type="button"
                          className="icon-btn"
                          onClick={() => setInspectorOpen(false)}
                          aria-label="Close inspector"
                        >
                          <Icon name="close" size={15} />
                        </button>
                      </div>
                      <div className="ni-placeholder">
                        <span className="ni-placeholder-orb">
                          <Icon name="target" size={20} />
                        </span>
                        <h3>Select a node</h3>
                        <p>
                          A class, method or external target opens here with its source, references
                          and findings. Double-click a node to focus its dependency chain.
                        </p>
                        <dl className="ni-placeholder-stats">
                          <div>
                            <dt>Symbols in slice</dt>
                            <dd>{formatNumber(universe.data.nodes.filter((node) => !node.external).length)}</dd>
                          </div>
                          <div>
                            <dt>Relationships</dt>
                            <dd>{formatNumber(universe.data.edges.length)}</dd>
                          </div>
                          <div>
                            <dt>With findings</dt>
                            <dd>{formatNumber(graph.severityByNode.size)}</dd>
                          </div>
                        </dl>
                        {findingsMatchRevision && hasValue(summary.data?.runId) ? (
                          <p className="rp-faint ni-run">
                            Findings run {summary.data?.runId?.slice(0, 8)} · revision{" "}
                            {universe.data.revisionId.slice(0, 8)}
                          </p>
                        ) : (
                          <p className="rp-faint ni-run">
                            No findings run matches this revision, so no severity is overlaid.
                          </p>
                        )}
                      </div>
                    </aside>
                  )}
                </motion.div>
              ) : null}
            </AnimatePresence>
          </div>
        </>
      )}
    </div>
  );
}

export function universeSummary(graph: UniverseGraph | null): string {
  if (graph === null) {
    return "No universe loaded.";
  }
  return `${graph.nodes.length} nodes, ${graph.edges.length} edges`;
}
