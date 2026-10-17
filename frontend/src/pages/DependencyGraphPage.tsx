import { useMemo, useState } from "react";
import { RepositoryRequired } from "@/components/RepositoryRequired";
import { Badge, Banner, Card, EmptyState, ErrorState, Loading, Metric, PageHeader, SearchInput } from "@/components/ui";
import { IndexButton } from "@/components/IndexButton";
import { useGraph } from "@/hooks/queries";
import { ApiError } from "@/lib/api";
import { formatLine, formatNumber, formatPercent, hasValue, titleCase } from "@/lib/format";
import type { EdgeResolution, EdgeView } from "@/lib/types";
import { useSelection } from "@/state/selection";

const RESOLUTIONS: EdgeResolution[] = ["RESOLVED", "INFERRED", "EXTERNAL", "UNRESOLVED"];

const RESOLUTION_COLOR: Record<EdgeResolution, string> = {
  RESOLVED: "var(--signal-success)",
  INFERRED: "var(--signal-warning)",
  EXTERNAL: "var(--signal-neutral)",
  UNRESOLVED: "var(--text-muted)",
};

/**
 * The dependency graph, rendered as evidence rather than as a picture.
 *
 * A force-directed drawing of a few thousand edges is decorative — it cannot be
 * counted, filtered or cited. What an engineer actually needs is the edge list, the
 * resolution split, and which targets dominate. Each edge keeps its resolution label,
 * because "A calls B" is a very different claim from "something named B might be
 * called here".
 */
export function DependencyGraphPage() {
  const { repositoryId, revisionId } = useSelection();
  const graph = useGraph(repositoryId, revisionId);
  const [active, setActive] = useState<Set<EdgeResolution>>(() => new Set(RESOLUTIONS));
  const [edgeType, setEdgeType] = useState("ALL");
  const [query, setQuery] = useState("");

  // The API returns a bounded slice ({ items, totalEdges, truncated }) rather than every edge.
  // Everything derived below therefore describes the slice, and the page says so.
  const edges = useMemo(() => graph.data?.items ?? [], [graph.data]);

  const counts = useMemo(() => {
    const map = new Map<EdgeResolution, number>();
    for (const resolution of RESOLUTIONS) {
      map.set(resolution, 0);
    }
    for (const edge of edges) {
      map.set(edge.resolution, (map.get(edge.resolution) ?? 0) + 1);
    }
    return map;
  }, [edges]);

  const edgeTypes = useMemo(
    () => Array.from(new Set(edges.map((edge) => edge.edgeType))).sort(),
    [edges],
  );

  const topTargets = useMemo(() => {
    const byTarget = new Map<string, number>();
    for (const edge of edges) {
      byTarget.set(edge.targetName, (byTarget.get(edge.targetName) ?? 0) + 1);
    }
    return Array.from(byTarget.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 15);
  }, [edges]);

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return edges.filter((edge) => {
      if (!active.has(edge.resolution)) {
        return false;
      }
      if (edgeType !== "ALL" && edge.edgeType !== edgeType) {
        return false;
      }
      if (needle.length === 0) {
        return true;
      }
      return `${edge.targetName} ${edge.detail ?? ""} ${edge.edgeType}`.toLowerCase().includes(needle);
    });
  }, [edges, active, edgeType, query]);

  if (repositoryId === null) {
    return (
      <>
        <PageHeader title="Dependency Graph" description="Type and reference relationships between symbols." />
        <Card>
          <RepositoryRequired />
        </Card>
      </>
    );
  }

  const notIndexed = graph.isError && graph.error instanceof ApiError && graph.error.code === "CONFLICT";

  if (notIndexed) {
    return (
      <>
        <PageHeader title="Dependency Graph" description="Type and reference relationships between symbols." />
        <Card>
          <EmptyState
            icon="graph"
            title="This repository has not been indexed"
            message="Edges are produced by the indexer. Queue an indexing job and the graph will populate."
            action={<IndexButton repositoryId={repositoryId} />}
          />
        </Card>
      </>
    );
  }

  if (graph.isError) {
    return (
      <ErrorState
        message={graph.error instanceof Error ? graph.error.message : "The graph could not be loaded."}
        onRetry={() => void graph.refetch()}
      />
    );
  }

  if (graph.isLoading) {
    return <Loading label="Loading graph" />;
  }

  const total = edges.length;
  const matchingTotal = graph.data?.totalEdges ?? total;
  const bounded = graph.data?.truncated ?? false;
  const maxTarget = topTargets[0]?.[1] ?? 1;
  const unresolvedShare = total === 0 ? 0 : (counts.get("UNRESOLVED") ?? 0) / total;

  function toggle(resolution: EdgeResolution) {
    setActive((current) => {
      const next = new Set(current);
      if (next.has(resolution)) {
        next.delete(resolution);
      } else {
        next.add(resolution);
      }
      return next;
    });
  }

  return (
    <>
      <PageHeader
        title="Dependency Graph"
        description="Every recorded edge between symbols, with the strength of each claim stated."
      />

      <p className="rp-faint" style={{ marginBottom: "var(--space-4)", fontSize: "var(--text-xs)" }}>
        This view draws structural edges — extends, implements, type references, instantiations and calls.
        Annotation, thrown-type, dependency-injection and messaging edges are indexed and listed per symbol in the
        Code Explorer; drawing them here would turn the graph into a hairball.
      </p>

      {bounded ? (
        <div style={{ marginBottom: "var(--space-5)" }}>
          <Banner tone="info" title="Showing a bounded slice of the graph">
            This revision has {formatNumber(matchingTotal)} matching edges. The view holds the first{" "}
            {formatNumber(total)} in file order, and the counts below describe that slice — not the whole graph.
            Select a narrower edge type to see a different part of it.
          </Banner>
        </div>
      ) : null}

      {unresolvedShare > 0.5 && total > 0 ? (
        <div style={{ marginBottom: "var(--space-5)" }}>
          <Banner tone="warning" title="Most edges are unresolved">
            RepoPilot resolves references without a full symbol solver, so a majority of edges point at an external or
            unresolvable target. Treat the resolved subset as the reliable graph and the rest as leads.
          </Banner>
        </div>
      ) : null}

      <div className="grid grid-4" style={{ marginBottom: "var(--space-5)" }}>
        <Metric
          label="Edges"
          value={formatNumber(total)}
          foot={bounded ? `Slice of ${formatNumber(matchingTotal)} matching edges` : "Recorded relationships"}
        />
        <Metric
          label="Resolved"
          value={formatNumber(counts.get("RESOLVED") ?? 0)}
          foot={total === 0 ? "—" : formatPercent((counts.get("RESOLVED") ?? 0) / total) + " of all edges"}
        />
        <Metric
          label="Inferred"
          value={formatNumber(counts.get("INFERRED") ?? 0)}
          foot="Best-effort matches, not certain"
        />
        <Metric
          label="External / unresolved"
          value={formatNumber((counts.get("EXTERNAL") ?? 0) + (counts.get("UNRESOLVED") ?? 0))}
          foot="Outside the indexed sources or unmatched"
        />
      </div>

      <div className="grid grid-2" style={{ marginBottom: "var(--space-5)" }}>
        <Card title="Resolution split">
          {total === 0 ? (
            <p className="rp-muted">No edges recorded for this revision.</p>
          ) : (
            <>
              <div className="distribution">
                {RESOLUTIONS.map((resolution) =>
                  (counts.get(resolution) ?? 0) > 0 ? (
                    <div
                      key={resolution}
                      className="distribution-seg"
                      style={{
                        width: `${((counts.get(resolution) ?? 0) / total) * 100}%`,
                        backgroundColor: RESOLUTION_COLOR[resolution],
                      }}
                      title={`${titleCase(resolution)}: ${counts.get(resolution) ?? 0}`}
                    />
                  ) : null,
                )}
              </div>
              <div className="distribution-legend">
                {RESOLUTIONS.map((resolution) => (
                  <span key={resolution} className="legend-item">
                    <span className="legend-swatch" style={{ backgroundColor: RESOLUTION_COLOR[resolution] }} />
                    {titleCase(resolution)}
                    <span className="rp-faint"> {formatNumber(counts.get(resolution) ?? 0)}</span>
                  </span>
                ))}
              </div>
            </>
          )}
        </Card>

        <Card title="Most referenced targets">
          {topTargets.length === 0 ? (
            <p className="rp-muted">Nothing referenced.</p>
          ) : (
            <div>
              {topTargets.map(([target, count]) => (
                <div className="bar-row" key={target}>
                  <span className="bar-label" title={target}>
                    {target}
                  </span>
                  <div className="bar-track">
                    <div className="bar-fill" style={{ width: `${(count / maxTarget) * 100}%` }} />
                  </div>
                  <span className="bar-value">{count}</span>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>

      <Card
        title="Edges"
        flush
      >
        <div style={{ padding: "var(--space-4)", borderBottom: "1px solid var(--border-subtle)" }}>
          <div className="toolbar" style={{ marginBottom: "var(--space-3)" }}>
            {RESOLUTIONS.map((resolution) => (
              <button
                key={resolution}
                type="button"
                className="chip-btn"
                aria-pressed={active.has(resolution)}
                onClick={() => toggle(resolution)}
              >
                <span className="legend-swatch" style={{ backgroundColor: RESOLUTION_COLOR[resolution] }} />
                {titleCase(resolution)}
                <span className="chip-count">{formatNumber(counts.get(resolution) ?? 0)}</span>
              </button>
            ))}
            <div className="rp-spacer" />
            <select
              className="select"
              aria-label="Edge type"
              style={{ width: "auto" }}
              value={edgeType}
              onChange={(event) => setEdgeType(event.target.value)}
            >
              <option value="ALL">All edge types</option>
              {edgeTypes.map((type) => (
                <option key={type} value={type}>
                  {titleCase(type)}
                </option>
              ))}
            </select>
          </div>
          <SearchInput value={query} onChange={setQuery} ariaLabel="Filter edges" placeholder="Filter by target or detail…" />
        </div>

        {filtered.length === 0 ? (
          <EmptyState icon="graph" title="No edges match" message="Loosen the filters or clear the search." />
        ) : (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>Type</th>
                  <th>Source</th>
                  <th>Target</th>
                  <th>Resolution</th>
                  <th className="table-num">Line</th>
                  <th>Detail</th>
                </tr>
              </thead>
              <tbody>
                {filtered.slice(0, 500).map((edge: EdgeView) => (
                  <tr key={edge.id}>
                    <td className="nowrap">
                      <Badge tone="neutral">{titleCase(edge.edgeType)}</Badge>
                    </td>
                    <td className="rp-mono">{hasValue(edge.sourceSymbolId) ? edge.sourceSymbolId.slice(0, 8) : "—"}</td>
                    <td className="rp-mono rp-truncate" style={{ maxWidth: 320 }} title={edge.targetName}>
                      {edge.targetName}
                    </td>
                    <td className="nowrap">
                      <span className={`resolution-dot ${edge.resolution.toLowerCase()}`} />
                      {titleCase(edge.resolution)}
                    </td>
                    <td className="table-num">{formatLine(edge.sourceLine)}</td>
                    <td className="rp-truncate" style={{ maxWidth: 280 }} title={edge.detail ?? ""}>
                      {edge.detail ?? "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {filtered.length > 500 ? (
              <p className="rp-faint" style={{ padding: "var(--space-3) var(--space-4)", fontSize: "var(--text-xs)" }}>
                Showing the first 500 of {formatNumber(filtered.length)} rows in this slice. Filter by type or target to
                bring the others into view.
              </p>
            ) : null}
          </div>
        )}
      </Card>
    </>
  );
}
