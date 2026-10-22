import { useMemo } from "react";
import { Icon } from "@/components/icons";
import { Badge, Loading } from "@/components/ui";
import { useFileContent } from "@/hooks/queries";
import { formatLineRange, severityTone, titleCase } from "@/lib/format";
import type { FindingSeverity, Uuid } from "@/lib/types";
import {
  EDGE_TYPE_META,
  SEVERITY_COLOR,
  kindColor,
  kindLabel,
  type UniverseGraph,
} from "@/lib/universe";

/**
 * The contextual inspector for one selected node.
 *
 * It answers the four questions a dependency graph should answer about a node: where it
 * lives, what it depends on, what depends on it, and what has been found in it. Source is
 * read through the API's sandboxed file endpoint, never from a cached copy, and the panel
 * says so.
 */
export function NodeInspector({
  repositoryId,
  revisionId,
  graph,
  nodeId,
  aiUsable,
  onClose,
  onSelectNode,
  onFocusNode,
  onExpand,
  onOpenSource,
  onAsk,
}: {
  repositoryId: Uuid;
  revisionId: Uuid | null;
  graph: UniverseGraph;
  nodeId: string;
  aiUsable: boolean;
  onClose: () => void;
  onSelectNode: (id: string) => void;
  onFocusNode: (id: string) => void;
  onExpand: (id: string) => void;
  onOpenSource: (path: string, line: number | null) => void;
  onAsk: (nodeId: string) => void;
}) {
  const node = graph.nodeById.get(nodeId);
  const findings = graph.findingsByNode.get(nodeId) ?? [];
  const severity = graph.severityByNode.get(nodeId);
  const content = useFileContent(
    repositoryId,
    node !== null && node !== undefined && !node.external ? node.path ?? null : null,
    revisionId,
  );

  const references = useMemo(() => {
    if (node === null || node === undefined) {
      return { outgoing: [], incoming: [] };
    }
    const outgoing = [];
    const incoming = [];
    for (const edge of graph.adjacency.get(node.id) ?? []) {
      if (edge.source === node.id) {
        outgoing.push(edge);
      } else {
        incoming.push(edge);
      }
    }
    return { outgoing, incoming };
  }, [graph, node]);

  if (node === null || node === undefined) {
    return (
      <aside className="node-inspector">
        <div className="ni-head">
          <span>Inspector</span>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="Close inspector">
            <Icon name="close" size={15} />
          </button>
        </div>
        <p className="rp-muted ni-empty">This node is no longer in the current graph. Choose another node.</p>
      </aside>
    );
  }

  const excerpt = buildExcerpt(content.data?.content, node.startLine, node.endLine);
  const degree = graph.degree.get(node.id) ?? 0;

  return (
    <aside className="node-inspector" aria-label={`Inspector for ${node.name}`}>
      <div className="ni-head">
        <span className="ni-eyebrow">
          <span className="u-node-dot" style={{ background: kindColor(node) }} />
          {kindLabel(node.kind)}
        </span>
        <button type="button" className="icon-btn" onClick={onClose} aria-label="Close inspector">
          <Icon name="close" size={15} />
        </button>
      </div>

      <div className="ni-title-block">
        <h2 className="ni-name rp-mono">{node.name}</h2>
        {node.qualifiedName !== null && node.qualifiedName !== undefined && node.qualifiedName !== node.name ? (
          <p className="ni-qualified rp-mono">{node.qualifiedName}</p>
        ) : null}
        {node.path !== null && node.path !== undefined ? (
          <button
            type="button"
            className="ni-path"
            onClick={() => onOpenSource(node.path as string, node.startLine ?? null)}
            title="Open this location in the Code Explorer"
          >
            <Icon name="file" size={13} />
            <span className="rp-mono">{node.path}</span>
            {node.startLine !== null && node.startLine !== undefined ? (
              <span className="ni-line">{formatLineRange(node.startLine, node.endLine)}</span>
            ) : null}
          </button>
        ) : (
          <p className="ni-external-note">
            This is an external or unresolved target, not a declaration in this revision. There is
            no source location to open.
          </p>
        )}
      </div>

      {severity !== undefined ? (
        <div className="ni-severity" style={{ borderColor: SEVERITY_COLOR[severity] }}>
          <span className="u-sev-dot" style={{ background: SEVERITY_COLOR[severity] }} />
          <strong>{severity}</strong> finding severity on this node
          <span className="ni-severity-count">{findings.length}</span>
        </div>
      ) : null}

      <div className="ni-actions">
        <button
          type="button"
          className="btn btn-sm btn-primary"
          onClick={() => onAsk(node.id)}
          disabled={!aiUsable || node.external}
          title={
            node.external
              ? "An external reference has no indexed evidence to investigate."
              : aiUsable
                ? "Prefill an investigation about this node"
                : "No AI provider is usable. Configure one on the backend."
          }
        >
          <Icon name="sparkles" size={14} />
          Ask AI
        </button>
        <button
          type="button"
          className="btn btn-sm"
          onClick={() => onFocusNode(node.id)}
          disabled={node.external}
        >
          <Icon name="target" size={14} />
          Focus chain
        </button>
        <button
          type="button"
          className="btn btn-sm"
          onClick={() => onExpand(node.id)}
          disabled={degree === 0}
          title="Add the direct neighbours to the selection"
        >
          <Icon name="layers" size={14} />
          Expand
        </button>
      </div>

      <dl className="ni-metrics">
        <div>
          <dt>Outgoing</dt>
          <dd>{references.outgoing.length}</dd>
        </div>
        <div>
          <dt>Incoming</dt>
          <dd>{references.incoming.length}</dd>
        </div>
        <div>
          <dt>Complexity</dt>
          <dd>{node.complexity ?? "—"}</dd>
        </div>
        <div>
          <dt>Lines</dt>
          <dd>{formatLineRange(node.startLine, node.endLine)}</dd>
        </div>
      </dl>

      {findings.length > 0 ? (
        <section className="ni-section">
          <h3>
            Findings <span>{findings.length}</span>
          </h3>
          <ul className="ni-findings">
            {findings.slice(0, 8).map((finding) => (
              <li key={finding.id}>
                <div className="ni-finding-head">
                  <Badge tone={severityTone(finding.severity)}>{titleCase(finding.severity)}</Badge>
                  <span className="ni-rule rp-mono">{finding.ruleId ?? finding.analyzerId}</span>
                </div>
                <button
                  type="button"
                  className="ni-finding-title"
                  onClick={() =>
                    finding.sourcePath !== null && finding.sourcePath !== undefined
                      ? onOpenSource(finding.sourcePath, finding.startLine ?? null)
                      : undefined
                  }
                >
                  {finding.title}
                </button>
                {finding.summary !== null && finding.summary !== undefined ? (
                  <p className="ni-finding-summary">{finding.summary}</p>
                ) : null}
              </li>
            ))}
          </ul>
          {findings.length > 8 ? (
            <p className="rp-faint ni-more">And {findings.length - 8} more in Findings.</p>
          ) : null}
        </section>
      ) : null}

      <section className="ni-section">
        <h3>
          Depends on <span>{references.outgoing.length}</span>
        </h3>
        {references.outgoing.length === 0 ? (
          <p className="rp-faint ni-muted">No outgoing relationships in this slice.</p>
        ) : (
          <ul className="ni-refs">
            {references.outgoing.slice(0, 8).map((edge) => {
              const target = graph.nodeById.get(edge.target);
              return (
                <li key={edge.id}>
                  <button type="button" onClick={() => onSelectNode(edge.target)} disabled={target === undefined}>
                    <span
                      className="u-edge-swatch"
                      style={{ background: EDGE_TYPE_META[edge.type]?.color ?? "#64748b" }}
                    />
                    <span className="ni-ref-name rp-mono">{target?.name ?? edge.target}</span>
                    <span className="ni-ref-type">{EDGE_TYPE_META[edge.type]?.label ?? edge.type}</span>
                    {edge.sourceLine !== null && edge.sourceLine !== undefined ? (
                      <span className="ni-ref-line">:{edge.sourceLine}</span>
                    ) : null}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
        {references.outgoing.length > 8 ? (
          <p className="rp-faint ni-more">And {references.outgoing.length - 8} more.</p>
        ) : null}
      </section>

      <section className="ni-section">
        <h3>
          Used by <span>{references.incoming.length}</span>
        </h3>
        {references.incoming.length === 0 ? (
          <p className="rp-faint ni-muted">Nothing in this slice references it.</p>
        ) : (
          <ul className="ni-refs">
            {references.incoming.slice(0, 8).map((edge) => {
              const source = graph.nodeById.get(edge.source);
              return (
                <li key={`in-${edge.id}`}>
                  <button type="button" onClick={() => onSelectNode(edge.source)} disabled={source === undefined}>
                    <span
                      className="u-edge-swatch"
                      style={{ background: EDGE_TYPE_META[edge.type]?.color ?? "#64748b" }}
                    />
                    <span className="ni-ref-name rp-mono">{source?.name ?? edge.source}</span>
                    <span className="ni-ref-type">{EDGE_TYPE_META[edge.type]?.label ?? edge.type}</span>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
        {references.incoming.length > 8 ? (
          <p className="rp-faint ni-more">And {references.incoming.length - 8} more.</p>
        ) : null}
      </section>

      {!node.external ? (
        <section className="ni-section ni-source">
          <h3>
            Source <span>{excerpt === null ? "" : `lines ${excerpt.start}–${excerpt.end}`}</span>
          </h3>
          {content.isLoading ? (
            <Loading label="Reading source" />
          ) : content.isError ? (
            <p className="rp-faint ni-muted">
              The file could not be read through the sandbox:{" "}
              {content.error instanceof Error ? content.error.message : "unknown error"}.
            </p>
          ) : excerpt === null ? (
            <p className="rp-faint ni-muted">No source line range was recorded for this symbol.</p>
          ) : (
            <div className="ni-code" aria-label="Source excerpt">
              {excerpt.lines.map((line) => (
                <div
                  key={line.number}
                  className={
                    node.startLine !== null &&
                    node.startLine !== undefined &&
                    line.number >= node.startLine &&
                    line.number <= (node.endLine ?? node.startLine)
                      ? "ni-code-line is-symbol"
                      : "ni-code-line"
                  }
                >
                  <span className="ni-code-gutter">{line.number}</span>
                  <span className="ni-code-text">{line.text.length > 0 ? line.text : " "}</span>
                </div>
              ))}
            </div>
          )}
          <p className="ni-code-note">Read from the indexed revision through the repository sandbox.</p>
        </section>
      ) : null}
    </aside>
  );
}

function buildExcerpt(
  content: string | undefined,
  startLine: number | null | undefined,
  endLine: number | null | undefined,
): { start: number; end: number; lines: Array<{ number: number; text: string }> } | null {
  if (content === undefined || content.length === 0) {
    return null;
  }
  const all = content.split(/\r\n|\r|\n/);
  const anchor = startLine !== null && startLine !== undefined && startLine > 0 ? startLine : 1;
  const symbolEnd = endLine !== null && endLine !== undefined ? endLine : anchor;
  const start = Math.max(1, anchor - 6);
  const end = Math.min(all.length, Math.max(symbolEnd + 2, start + 18), start + 31);
  if (start > all.length) {
    return null;
  }
  const lines: Array<{ number: number; text: string }> = [];
  for (let number = start; number <= end; number += 1) {
    lines.push({ number, text: all[number - 1] ?? "" });
  }
  return { start, end, lines };
}

export function severitySummary(values: Iterable<FindingSeverity>): Record<FindingSeverity, number> {
  const summary: Record<FindingSeverity, number> = {
    CRITICAL: 0,
    HIGH: 0,
    MEDIUM: 0,
    LOW: 0,
    INFO: 0,
  };
  for (const severity of values) {
    summary[severity] += 1;
  }
  return summary;
}
