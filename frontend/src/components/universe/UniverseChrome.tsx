import { useMemo, useState } from "react";
import { Icon } from "@/components/icons";
import type { EdgeResolution, FindingSeverity } from "@/lib/types";
import {
  EDGE_TYPE_META,
  RESOLUTIONS,
  SEVERITY_COLOR,
  SEVERITY_ORDER,
  countKinds,
  kindColor,
  kindLabel,
  packageColor,
  searchUniverse,
  type UniverseFilters,
  type UniverseGraph,
} from "@/lib/universe";

const FILTERABLE_KINDS = [
  "CLASS",
  "INTERFACE",
  "ENUM",
  "RECORD",
  "METHOD",
  "CONSTRUCTOR",
  "FIELD",
  "EXTERNAL",
];

export function UniverseToolbar({
  graph,
  filters,
  onFilters,
  severityLens,
  onSeverityLens,
  query,
  onQuery,
  focusMode,
  onFocusMode,
  visible,
  onSelectNode,
  onClearFilters,
}: {
  graph: UniverseGraph;
  filters: UniverseFilters;
  onFilters: (next: UniverseFilters) => void;
  severityLens: FindingSeverity[];
  onSeverityLens: (next: FindingSeverity[]) => void;
  query: string;
  onQuery: (next: string) => void;
  focusMode: boolean;
  onFocusMode: (next: boolean) => void;
  visible: { nodes: number; edges: number };
  onSelectNode: (id: string) => void;
  onClearFilters: () => void;
}) {
  const [searchOpen, setSearchOpen] = useState(false);
  const [filtersOpen, setFiltersOpen] = useState(false);

  const hits = useMemo(() => searchUniverse(graph, query, 8), [graph, query]);
  const kinds = useMemo(() => countKinds(graph.nodes), [graph.nodes]);
  const edgeCounts = useMemo(() => {
    const counts = new Map<string, number>();
    for (const edge of graph.edges) {
      counts.set(edge.type, (counts.get(edge.type) ?? 0) + 1);
    }
    return counts;
  }, [graph.edges]);
  const severityCounts = useMemo(() => {
    const counts = new Map<FindingSeverity, number>();
    for (const severity of graph.severityByNode.values()) {
      counts.set(severity, (counts.get(severity) ?? 0) + 1);
    }
    return counts;
  }, [graph.severityByNode]);

  const activeFilterCount =
    filters.kinds.length +
    filters.packages.length +
    filters.edgeTypes.length +
    filters.resolutions.length +
    (filters.hideExternal ? 1 : 0) +
    (filters.findingsOnly ? 1 : 0) +
    severityLens.length;

  function toggleKind(kind: string): void {
    onFilters({
      ...filters,
      kinds: filters.kinds.includes(kind)
        ? filters.kinds.filter((value) => value !== kind)
        : [...filters.kinds, kind],
    });
  }

  function toggleEdgeType(type: string): void {
    onFilters({
      ...filters,
      edgeTypes: filters.edgeTypes.includes(type)
        ? filters.edgeTypes.filter((value) => value !== type)
        : [...filters.edgeTypes, type],
    });
  }

  function toggleResolution(resolution: EdgeResolution): void {
    onFilters({
      ...filters,
      resolutions: filters.resolutions.includes(resolution)
        ? filters.resolutions.filter((value) => value !== resolution)
        : [...filters.resolutions, resolution],
    });
  }

  function toggleSeverity(severity: FindingSeverity): void {
    onSeverityLens(
      severityLens.includes(severity)
        ? severityLens.filter((value) => value !== severity)
        : [...severityLens, severity],
    );
  }

  return (
    <div className="universe-toolbar">
      <div className="universe-search">
        <span className="search-icon">
          <Icon name="search" size={15} />
        </span>
        <input
          className="input"
          type="search"
          value={query}
          placeholder="Search classes, methods, files…"
          aria-label="Search the code universe"
          onChange={(event) => {
            onQuery(event.target.value);
            setSearchOpen(true);
          }}
          onFocus={() => setSearchOpen(true)}
          onBlur={() => window.setTimeout(() => setSearchOpen(false), 160)}
          onKeyDown={(event) => {
            if (event.key === "Escape") {
              setSearchOpen(false);
            }
            if (event.key === "Enter" && hits[0] !== undefined) {
              onSelectNode(hits[0].node.id);
              setSearchOpen(false);
            }
          }}
        />
        {searchOpen && query.trim().length > 0 ? (
          <div className="universe-search-results" role="listbox" aria-label="Search results">
            {hits.length === 0 ? (
              <p className="rp-faint u-search-empty">No indexed symbol or file matches “{query.trim()}”.</p>
            ) : (
              hits.map((hit) => (
                <button
                  key={hit.node.id}
                  type="button"
                  role="option"
                  aria-selected={false}
                  className="universe-search-hit"
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => {
                    onSelectNode(hit.node.id);
                    setSearchOpen(false);
                  }}
                >
                  <span className="u-node-dot" style={{ background: kindColor(hit.node) }} />
                  <span className="hit-name rp-mono">{hit.node.name}</span>
                  <span className="hit-kind">{kindLabel(hit.node.kind)}</span>
                  <span className="hit-path rp-mono">{hit.node.path ?? "external"}</span>
                </button>
              ))
            )}
          </div>
        ) : null}
      </div>

      <button
        type="button"
        className={filtersOpen ? "btn btn-sm u-filter-toggle is-open" : "btn btn-sm u-filter-toggle"}
        onClick={() => setFiltersOpen((open) => !open)}
        aria-expanded={filtersOpen}
      >
        <Icon name="settings" size={14} />
        Filters
        {activeFilterCount > 0 ? <span className="u-filter-count">{activeFilterCount}</span> : null}
      </button>

      <button
        type="button"
        className={focusMode ? "btn btn-sm u-focus-toggle is-on" : "btn btn-sm u-focus-toggle"}
        onClick={() => onFocusMode(!focusMode)}
        title="Dim everything outside the selected node's two-hop neighbourhood"
        aria-pressed={focusMode}
      >
        <Icon name="graph" size={14} />
        Focus
      </button>

      <div className="universe-stats" aria-live="polite">
        <span>
          <strong>{visible.nodes.toLocaleString()}</strong> nodes
        </span>
        <span>
          <strong>{visible.edges.toLocaleString()}</strong> edges
        </span>
        {graph.severityByNode.size > 0 ? (
          <span className="u-stat-findings">
            <span className="u-sev-dot" style={{ background: SEVERITY_COLOR.CRITICAL }} />
            <strong>{graph.severityByNode.size}</strong> with findings
          </span>
        ) : null}
      </div>

      {filtersOpen ? (
        <div className="universe-filter-panel">
          <div className="ufp-head">
            <strong>Refine the universe</strong>
            <button type="button" className="btn btn-ghost btn-sm" onClick={onClearFilters}>
              Reset all
            </button>
          </div>

          <div className="ufp-section">
            <span className="ufp-label">Node kinds</span>
            <div className="chip-row">
              {kinds
                .filter(({ kind }) => FILTERABLE_KINDS.includes(kind) || kind.length > 0)
                .map(({ kind, count }) => {
                  const active = filters.kinds.includes(kind);
                  return (
                    <button
                      key={kind}
                      type="button"
                      className={active ? "chip is-on" : "chip"}
                      onClick={() => toggleKind(kind)}
                      aria-pressed={active}
                    >
                      <span className="u-node-dot" style={{ background: kindColor({ kind }) }} />
                      {kindLabel(kind)}
                      <span className="chip-count">{count}</span>
                    </button>
                  );
                })}
            </div>
          </div>

          <div className="ufp-section">
            <span className="ufp-label">Package</span>
            <select
              className="input"
              value={filters.packages[0] ?? ""}
              onChange={(event) =>
                onFilters({
                  ...filters,
                  packages: event.target.value === "" ? [] : [event.target.value],
                })
              }
              aria-label="Filter by package"
            >
              <option value="">All {graph.packages.length} packages</option>
              {graph.packages.map((name) => (
                <option key={name} value={name}>
                  {name}
                </option>
              ))}
            </select>
            {filters.packages.length > 0 ? (
              <span className="ufp-package-swatch">
                <span className="u-node-dot" style={{ background: packageColor(filters.packages[0]!) }} />
                <span className="rp-mono">{filters.packages[0]}</span>
              </span>
            ) : null}
          </div>

          <div className="ufp-section">
            <span className="ufp-label">Relationship types</span>
            <div className="chip-row">
              {Array.from(edgeCounts.entries())
                .sort((left, right) => right[1] - left[1])
                .map(([type, count]) => {
                  const active = filters.edgeTypes.includes(type);
                  return (
                    <button
                      key={type}
                      type="button"
                      className={active ? "chip is-on" : "chip"}
                      onClick={() => toggleEdgeType(type)}
                      aria-pressed={active}
                    >
                      <span
                        className="u-edge-swatch"
                        style={{ background: EDGE_TYPE_META[type]?.color ?? "#64748b" }}
                      />
                      {EDGE_TYPE_META[type]?.label ?? type}
                      <span className="chip-count">{count}</span>
                    </button>
                  );
                })}
            </div>
          </div>

          <div className="ufp-section">
            <span className="ufp-label">Resolution</span>
            <div className="chip-row">
              {RESOLUTIONS.map((resolution) => {
                const active = filters.resolutions.includes(resolution);
                return (
                  <button
                    key={resolution}
                    type="button"
                    className={active ? "chip is-on" : "chip"}
                    onClick={() => toggleResolution(resolution)}
                    aria-pressed={active}
                  >
                    {resolution === "RESOLVED"
                      ? "Resolved"
                      : resolution === "INFERRED"
                        ? "Inferred"
                        : resolution === "EXTERNAL"
                          ? "External"
                          : "Unresolved"}
                  </button>
                );
              })}
            </div>
          </div>

          <div className="ufp-section">
            <span className="ufp-label">Severity lens</span>
            <div className="chip-row">
              {SEVERITY_ORDER.map((severity) => {
                const active = severityLens.includes(severity);
                return (
                  <button
                    key={severity}
                    type="button"
                    className={active ? "chip is-on" : "chip"}
                    onClick={() => toggleSeverity(severity)}
                    aria-pressed={active}
                  >
                    <span className="u-node-dot" style={{ background: SEVERITY_COLOR[severity] }} />
                    {severity[0] + severity.slice(1).toLowerCase()}
                    <span className="chip-count">{severityCounts.get(severity) ?? 0}</span>
                  </button>
                );
              })}
            </div>
            <p className="ufp-note">
              The lens dims nodes without a matching finding instead of removing them: a hotspot
              cannot be understood without its context.
            </p>
          </div>

          <div className="ufp-section ufp-checks">
            <label>
              <input
                type="checkbox"
                checked={filters.hideExternal}
                onChange={(event) => onFilters({ ...filters, hideExternal: event.target.checked })}
              />
              Hide external dependencies
            </label>
            <label>
              <input
                type="checkbox"
                checked={filters.findingsOnly}
                onChange={(event) => onFilters({ ...filters, findingsOnly: event.target.checked })}
              />
              Only nodes with findings
            </label>
          </div>
        </div>
      ) : null}
    </div>
  );
}

/**
 * Legend. It doubles as a count of what is actually on screen, so it is information rather
 * than decoration.
 */
export function UniverseLegend({ graph }: { graph: UniverseGraph }) {
  const [open, setOpen] = useState(false);
  const kinds = useMemo(() => countKinds(graph.nodes).slice(0, 5), [graph.nodes]);
  return (
    <div className={open ? "universe-legend" : "universe-legend is-closed"}>
      <button
        type="button"
        className="legend-head"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
      >
        <span>Legend</span>
        <Icon name={open ? "chevron-down" : "chevron-up" as never} size={13} />
      </button>
      {open ? (
        <div className="legend-body">
          <div className="legend-group">
            {kinds.map(({ kind }) => (
              <span className="legend-item" key={kind}>
                <span className="u-node-dot" style={{ background: kindColor({ kind }) }} />
                {kindLabel(kind)}
              </span>
            ))}
          </div>
          <div className="legend-group">
            {(["CALLS", "TYPE_REFERENCE", "EXTENDS", "INSTANTIATES", "INJECTS"] as const).map((type) => (
              <span className="legend-item" key={type}>
                <span className="u-edge-swatch" style={{ background: EDGE_TYPE_META[type]?.color }} />
                {EDGE_TYPE_META[type]?.label}
              </span>
            ))}
          </div>
          <div className="legend-group">
            <span className="legend-item">
              <span className="u-ring u-ring-critical" /> Critical finding
            </span>
            <span className="legend-item">
              <span className="u-ring u-ring-high" /> High finding
            </span>
            <span className="legend-item">
              <span className="u-diamond" /> External target
            </span>
          </div>
          <p className="legend-hint">
            Click to inspect · double-click to focus the chain · shift-click to multi-select
          </p>
        </div>
      ) : null}
    </div>
  );
}
