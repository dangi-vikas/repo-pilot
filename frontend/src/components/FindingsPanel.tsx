import { useEffect, useMemo, useState } from "react";
import { Icon } from "@/components/icons";
import { Badge, Banner, Card, EmptyState, ErrorState, Loading, SearchInput } from "@/components/ui";
import {
  useFindingSummary,
  useFindings,
  useJobs,
  useStartAnalysis,
  type FindingQuery,
} from "@/hooks/queries";
import {
  formatFindingLocation,
  formatInstant,
  formatNumber,
  formatRelative,
  hasValue,
  severityTone,
  titleCase,
  verificationTone,
} from "@/lib/format";
import type {
  FindingCategory,
  FindingSeverity,
  FindingView,
  Uuid,
} from "@/lib/types";

const SEVERITIES: FindingSeverity[] = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"];
const CATEGORIES: FindingCategory[] = [
  "CORRECTNESS",
  "SECURITY",
  "PERFORMANCE",
  "CONCURRENCY",
  "RESOURCE",
  "MAINTAINABILITY",
  "STYLE",
];

const PAGE_SIZE = 50;

/**
 * Static-analysis findings for a repository.
 *
 * Three states are kept visually distinct, because collapsing them is the failure this
 * panel exists to avoid: nothing has been analysed (no run), a run found nothing, and a
 * run found something. The run header is always shown with the findings so a count can
 * never be read without knowing which revision produced it.
 */
export function FindingsPanel({
  repositoryId,
  revisionId,
  pathFilter,
  onClearPathFilter,
  onOpenLocation,
}: {
  repositoryId: Uuid;
  revisionId: Uuid | null;
  /** Restricts the list to one file, set by the explorer when a file is open. */
  pathFilter: string | null;
  onClearPathFilter: () => void;
  /** Opens a finding's location in the code view. */
  onOpenLocation: (finding: FindingView) => void;
}) {
  const [severity, setSeverity] = useState<FindingSeverity | "">("");
  const [category, setCategory] = useState<FindingCategory | "">("");
  const [analyzerId, setAnalyzerId] = useState("");
  const [text, setText] = useState("");
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<FindingView | null>(null);

  // Any filter change invalidates the current page: page 3 of a different filter is
  // not "the same place".
  useEffect(() => {
    setPage(0);
  }, [severity, category, analyzerId, text, pathFilter]);

  const jobs = useJobs(undefined);
  const summary = useFindingSummary(repositoryId, revisionId);
  const analyse = useStartAnalysis(repositoryId);

  const analysisActive =
    jobs.data?.items.some(
      (job) =>
        job.jobType === "STATIC_ANALYSIS" &&
        job.repositoryId === repositoryId &&
        (job.status === "QUEUED" || job.status === "RUNNING"),
    ) ?? false;

  const filter: FindingQuery = useMemo(
    () => ({
      severity: severity === "" ? undefined : severity,
      category: category === "" ? undefined : category,
      analyzerId: analyzerId === "" ? undefined : analyzerId,
      path: pathFilter ?? undefined,
      text: text.trim() === "" ? undefined : text.trim(),
      page,
      size: PAGE_SIZE,
    }),
    [severity, category, analyzerId, pathFilter, text, page],
  );

  const findings = useFindings(repositoryId, filter, { refetchWhileActive: analysisActive });

  const bySeverity = summary.data?.bySeverity ?? {};
  const hasRun = hasValue(summary.data?.runId);
  const runDescribesAnotherRevision =
    hasRun &&
    revisionId !== null &&
    hasValue(summary.data?.revisionId) &&
    summary.data?.revisionId !== revisionId;

  const total = findings.data?.totalItems ?? 0;
  const shown = findings.data?.items.length ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const activeJob = jobs.data?.items.find(
    (job) =>
      job.jobType === "STATIC_ANALYSIS" &&
      job.repositoryId === repositoryId &&
      (job.status === "QUEUED" || job.status === "RUNNING"),
  );

  return (
    <div className="stack-3">
      {analyse.isError ? (
        <Banner tone="danger" title="The analysis job could not be started">
          {analyse.error instanceof Error ? analyse.error.message : "Unknown error."}
        </Banner>
      ) : null}

      {activeJob !== undefined ? (
        <Banner tone="info" title="A static-analysis job is running">
          {hasValue(activeJob.stage) ? `${activeJob.stage} · ` : ""}
          {formatNumber(activeJob.progress)}% complete
          {hasValue(activeJob.message) ? ` — ${activeJob.message}` : ""}
          {". "}
          The findings below describe the run that finished before this one until it completes.
        </Banner>
      ) : null}

      {runDescribesAnotherRevision ? (
        <Banner tone="warning" title="These findings are for a different revision">
          The newest analysis ran against revision{" "}
          <span className="rp-mono">{String(summary.data?.revisionId).slice(0, 8)}</span>, not the
          revision selected above. Run the analysis again after indexing to analyse the selected
          revision.
        </Banner>
      ) : null}

      {summary.isLoading ? (
        <Loading label="Loading findings summary" />
      ) : summary.isError ? (
        <ErrorState
          message={
            summary.error instanceof Error
              ? summary.error.message
              : "The findings summary could not be loaded."
          }
          onRetry={() => void summary.refetch()}
        />
      ) : !hasRun ? (
        <EmptyState
          icon="alert"
          title="Nothing has been analysed yet"
          message={
            <>
              Static analysis has not run on this repository, so there is no result to show — which
              is different from a run that found nothing. Indexing records what is in the code;
              analysis is a separate job that reads it and reports findings.
            </>
          }
          action={
            <button
              type="button"
              className="btn btn-primary btn-sm"
              disabled={analyse.isPending}
              onClick={() => analyse.mutate()}
            >
              <Icon name="play" size={14} />
              {analyse.isPending ? "Starting…" : "Run static analysis"}
            </button>
          }
        />
      ) : (
        <>
          <Card
            title="Analysis run"
            actions={
              <>
                {hasValue(summary.data?.runStatus) ? (
                  <Badge
                    tone={summary.data?.runStatus === "SUCCEEDED" ? "success" : "warning"}
                    dot
                  >
                    {titleCase(String(summary.data?.runStatus))}
                  </Badge>
                ) : null}
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  disabled={analyse.isPending || analysisActive}
                  onClick={() => analyse.mutate()}
                >
                  <Icon name="refresh" size={14} />
                  {analyse.isPending ? "Starting…" : "Run again"}
                </button>
              </>
            }
          >
            <div className="chip-row" style={{ marginBottom: "var(--space-3)" }}>
              {SEVERITIES.filter((level) => (bySeverity[level] ?? 0) > 0).map((level) => (
                <Badge key={level} tone={severityTone(level)}>
                  {formatNumber(bySeverity[level] ?? 0)} {level.toLowerCase()}
                </Badge>
              ))}
              <Badge tone="neutral">{formatNumber(summary.data?.total ?? 0)} total</Badge>
              {Object.entries(summary.data?.byAnalyzer ?? {}).map(([analyzer, count]) => (
                <Badge key={analyzer} tone="info">
                  {analyzer}: {formatNumber(count)}
                </Badge>
              ))}
            </div>
            <p className="rp-faint" style={{ fontSize: "var(--text-xs)", marginBottom: "var(--space-2)" }}>
              Revision{" "}
              <span className="rp-mono">{String(summary.data?.revisionId ?? "").slice(0, 8)}</span>
              {hasValue(summary.data?.startedAt)
                ? ` · started ${formatInstant(summary.data?.startedAt)} (${formatRelative(summary.data?.startedAt)})`
                : ""}
              {hasValue(summary.data?.analyzerVersions)
                ? ` · analyzers ${summary.data?.analyzerVersions}`
                : ""}
            </p>
            {hasValue(summary.data?.runSummary) ? (
              <p className="rp-muted" style={{ fontSize: "var(--text-sm)" }}>
                {summary.data?.runSummary}
              </p>
            ) : null}
          </Card>

          <Card
            title="Findings"
            actions={
              <>
                <select
                  className="select"
                  aria-label="Filter by severity"
                  value={severity}
                  onChange={(event) => setSeverity(event.target.value as FindingSeverity | "")}
                >
                  <option value="">All severities</option>
                  {SEVERITIES.map((level) => (
                    <option key={level} value={level}>
                      {titleCase(level)}
                    </option>
                  ))}
                </select>
                <select
                  className="select"
                  aria-label="Filter by category"
                  value={category}
                  onChange={(event) => setCategory(event.target.value as FindingCategory | "")}
                >
                  <option value="">All categories</option>
                  {CATEGORIES.map((value) => (
                    <option key={value} value={value}>
                      {titleCase(value)}
                    </option>
                  ))}
                </select>
                <select
                  className="select"
                  aria-label="Filter by analyzer"
                  value={analyzerId}
                  onChange={(event) => setAnalyzerId(event.target.value)}
                >
                  <option value="">All analyzers</option>
                  {Object.keys(summary.data?.byAnalyzer ?? {}).map((analyzer) => (
                    <option key={analyzer} value={analyzer}>
                      {analyzer}
                    </option>
                  ))}
                </select>
              </>
            }
          >
            <div className="toolbar" style={{ marginBottom: "var(--space-3)" }}>
              <SearchInput
                value={text}
                onChange={setText}
                ariaLabel="Search findings"
                placeholder="Search title, rule or path…"
              />
              <div className="rp-spacer" />
              {pathFilter !== null ? (
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={onClearPathFilter}
                  title="Remove the file filter"
                >
                  <Icon name="close" size={14} />
                  <span className="rp-mono rp-truncate" style={{ maxWidth: 260 }}>
                    {pathFilter}
                  </span>
                </button>
              ) : null}
            </div>

            {findings.isLoading ? (
              <Loading label="Loading findings" />
            ) : findings.isError ? (
              <ErrorState
                message={
                  findings.error instanceof Error
                    ? findings.error.message
                    : "The findings could not be loaded."
                }
                onRetry={() => void findings.refetch()}
              />
            ) : shown === 0 ? (
              <EmptyState
                icon="check"
                title={pathFilter === null ? "No findings from this run" : "No findings in this file"}
                message={
                  pathFilter === null
                    ? "The run completed and reported nothing under these filters. A clean result is evidence about the rules that ran, not proof that the code is correct — every finding states what its rule cannot see."
                    : "This file produced no findings under these filters. Clear the file filter to see the whole run."
                }
              />
            ) : (
              <>
                {/*
                  A list rather than a table. This panel lives in the explorer's right-hand
                  pane, which is about 500px wide: a six-column table there pushed the
                  citation — the one column a reader checks first — off the edge. Each row
                  now carries its severity, rule, citation and citation verdict by itself.
                */}
                <ul className="findings-list">
                  {findings.data?.items.map((finding) => (
                    <li key={finding.id}>
                      <button
                        type="button"
                        className={
                          finding.id === selected?.id ? "finding-row selected" : "finding-row"
                        }
                        aria-pressed={finding.id === selected?.id}
                        onClick={() => setSelected(finding)}
                      >
                        <span className="finding-row-head">
                          <Badge tone={severityTone(finding.severity)} dot>
                            {finding.severity}
                          </Badge>
                          <span className="rp-mono finding-row-rule">
                            {finding.ruleId ?? finding.findingType}
                          </span>
                          <span className="rp-spacer" />
                          {/* The verdict on the citation sits next to the citation itself: a
                              line number whose text was not checked must never look like one
                              whose text was. */}
                          <Badge tone={verificationTone(finding.verificationStatus)}>
                            {finding.verificationStatus === "SOURCE_CONFIRMED"
                              ? "line confirmed"
                              : finding.verificationStatus === "LOCATION_STALE"
                                ? "line stale"
                                : "no line"}
                          </Badge>
                        </span>
                        <span className="finding-row-title">{finding.title}</span>
                        {hasValue(finding.summary) ? (
                          <span className="finding-row-summary">{finding.summary}</span>
                        ) : null}
                        <span className="finding-row-cite rp-mono">
                          {formatFindingLocation(finding.sourcePath, finding.startLine)}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>

                <div className="rp-row" style={{ marginTop: "var(--space-3)" }}>
                  <span className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                    Showing {formatNumber(shown)} of {formatNumber(total)}
                    {total > shown ? ` (${formatNumber(total - shown)} not on this page)` : ""}
                  </span>
                  <div className="rp-spacer" />
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm"
                    disabled={page === 0}
                    onClick={() => setPage((current) => Math.max(0, current - 1))}
                  >
                    Previous
                  </button>
                  <span className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                    Page {page + 1} of {pageCount}
                  </span>
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm"
                    disabled={page + 1 >= pageCount}
                    onClick={() => setPage((current) => current + 1)}
                  >
                    Next
                  </button>
                </div>
              </>
            )}
          </Card>

          {selected !== null ? <FindingDetail finding={selected} onOpenLocation={onOpenLocation} /> : null}
        </>
      )}
    </div>
  );
}

/**
 * One finding in full.
 *
 * The limitations are rendered with the same weight as the recommendation, because a
 * finding without its boundary is a claim the reader cannot properly judge — and this is
 * the screen where they decide whether to act on it.
 */
function FindingDetail({
  finding,
  onOpenLocation,
}: {
  finding: FindingView;
  onOpenLocation: (finding: FindingView) => void;
}) {
  const location = formatFindingLocation(finding.sourcePath, finding.startLine);
  return (
    <Card
      title={finding.title}
      actions={
        <>
          <Badge tone={severityTone(finding.severity)} dot>
            {finding.severity}
          </Badge>
          <Badge tone={verificationTone(finding.verificationStatus)}>
            {titleCase(finding.verificationStatus)}
          </Badge>
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            disabled={!hasValue(finding.sourcePath)}
            onClick={() => onOpenLocation(finding)}
          >
            <Icon name="external" size={14} />
            Open in source
          </button>
        </>
      }
    >
      <div className="chip-row" style={{ marginBottom: "var(--space-3)" }}>
        <Badge tone="info">{finding.analyzerId}</Badge>
        <Badge tone="neutral">{finding.ruleId ?? finding.findingType}</Badge>
        <Badge tone="neutral">{titleCase(finding.category)}</Badge>
        <Badge tone="neutral">{titleCase(finding.confidence)} confidence</Badge>
        {/*
          Not a Badge: badges uppercase their contents, and a path is case-sensitive. A
          citation printed as SRC/MAIN/... is not the path that was analysed.
        */}
        <span className="finding-row-cite rp-mono">{location}</span>
      </div>
      {hasValue(finding.summary) ? (
        <p className="rp-muted" style={{ fontSize: "var(--text-sm)", marginBottom: "var(--space-3)" }}>
          {finding.summary}
        </p>
      ) : null}
      <dl className="rp-detail">
        {hasValue(finding.impact) ? (
          <>
            <dt>Why it matters</dt>
            <dd>{finding.impact}</dd>
          </>
        ) : null}
        {hasValue(finding.recommendedAction) ? (
          <>
            <dt>Suggested action</dt>
            <dd>{finding.recommendedAction}</dd>
          </>
        ) : null}
        {hasValue(finding.limitations) ? (
          <>
            <dt>What this rule cannot see</dt>
            <dd>{finding.limitations}</dd>
          </>
        ) : null}
        <dt>Evidence</dt>
        <dd>
          Static analysis of the source at this revision. Not a runtime measurement: nothing was
          executed to produce this finding.
        </dd>
      </dl>
    </Card>
  );
}
