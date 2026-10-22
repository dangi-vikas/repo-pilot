import { useNavigate } from "react-router-dom";
import { FindingsPanel } from "@/components/FindingsPanel";
import { RepositoryRequired } from "@/components/RepositoryRequired";
import { Badge, Banner, PageHeader } from "@/components/ui";
import { useFindingSummary } from "@/hooks/queries";
import { formatNumber, formatRelative, hasValue, titleCase } from "@/lib/format";
import { SEVERITY_COLOR, SEVERITY_ORDER } from "@/lib/universe";
import { useSelection } from "@/state/selection";

/**
 * The findings destination: the whole static-analysis record for the selected revision.
 *
 * FindingsPanel owns the filtering and the run header; this page adds the severity
 * summary and the route that opens a finding's location in the Code Explorer.
 */
export function FindingsPage() {
  const { repositoryId, revisionId } = useSelection();
  const summary = useFindingSummary(repositoryId, revisionId);
  const navigate = useNavigate();

  if (repositoryId === null) {
    return (
      <>
        <PageHeader
          title="Findings"
          description="Static-analysis findings, with the run and revision that produced them."
        />
        <RepositoryRequired />
      </>
    );
  }

  const total = summary.data?.total ?? 0;

  return (
    <div className="findings-page">
      <PageHeader
        title="Findings"
        description="What the static analysers reported on this revision — each finding carries its severity, confidence and a location checked against the source."
        actions={
          summary.data?.runStatus !== null && summary.data?.runStatus !== undefined ? (
            <Badge
              tone={
                summary.data.runStatus === "SUCCEEDED"
                  ? "success"
                  : summary.data.runStatus === "PARTIAL"
                    ? "warning"
                    : summary.data.runStatus === "FAILED"
                      ? "danger"
                      : "neutral"
              }
              dot
            >
              {titleCase(summary.data.runStatus)}
              {hasValue(summary.data.finishedAt) ? ` · ${formatRelative(summary.data.finishedAt)}` : ""}
            </Badge>
          ) : (
            <Badge tone="neutral">No analysis run</Badge>
          )
        }
      />

      <Banner tone="info" title="Static evidence, not runtime proof">
        A finding is a rule's reading of the source. It is not a profiler measurement and it does
        not prove a defect at run time. Memory and performance screens show measured evidence only
        when a recording has actually been analysed.
      </Banner>

      <div className="findings-rail" aria-label="Findings by severity">
        <div className="findings-rail-total">
          <span className="rp-faint">Total findings</span>
          <strong>{formatNumber(total)}</strong>
          <span className="rp-faint">
            {summary.data?.runId !== null && summary.data?.runId !== undefined
              ? `run ${summary.data.runId.slice(0, 8)}`
              : "nothing analysed yet"}
          </span>
        </div>
        {SEVERITY_ORDER.map((severity) => {
          const count = summary.data?.bySeverity[severity] ?? 0;
          return (
            <div className="findings-rail-item" key={severity}>
              <span className="u-sev-dot" style={{ background: SEVERITY_COLOR[severity] }} />
              <span>{titleCase(severity)}</span>
              <strong>{formatNumber(count)}</strong>
              <span className="findings-rail-bar" aria-hidden="true">
                <span
                  style={{
                    width: total > 0 ? `${Math.max(2, (count / total) * 100)}%` : "0%",
                    background: SEVERITY_COLOR[severity],
                  }}
                />
              </span>
            </div>
          );
        })}
      </div>

      <FindingsPanel
        repositoryId={repositoryId}
        revisionId={revisionId}
        pathFilter={null}
        onClearPathFilter={() => undefined}
        onOpenLocation={(finding) => {
          if (finding.sourcePath === null || finding.sourcePath === undefined) {
            return;
          }
          const params = new URLSearchParams({ path: finding.sourcePath });
          if (finding.startLine !== null && finding.startLine !== undefined) {
            params.set("line", String(finding.startLine));
          }
          navigate(`/code?${params.toString()}`);
        }}
      />
    </div>
  );
}
