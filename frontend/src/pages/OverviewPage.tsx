import { useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { AiOrb, type AiOrbState } from "@/components/AiOrb";
import { Icon } from "@/components/icons";
import { JobTable } from "@/components/JobTable";
import { UniverseCanvas } from "@/components/universe/UniverseCanvas";
import { Badge, Banner, Card, EmptyState, Loading, Metric } from "@/components/ui";
import {
  useAskInvestigation,
  useCoverage,
  useFindingSummary,
  useJobs,
  useOverview,
  useRepositories,
  useRuntimeArtifacts,
  useUniverse,
} from "@/hooks/queries";
import { useCountUp } from "@/hooks/useCountUp";
import { ApiError } from "@/lib/api";
import {
  formatBytes,
  formatNumber,
  formatPercent,
  formatRelative,
  hasValue,
  repositoryStatusTone,
  titleCase,
} from "@/lib/format";
import type { FindingCategory, RuntimeArtifactType } from "@/lib/types";
import { EMPTY_FILTERS, SEVERITY_COLOR, SEVERITY_ORDER, buildUniverse } from "@/lib/universe";
import { useActions } from "@/state/actions";
import { useMotion } from "@/state/motion";
import { useSelection } from "@/state/selection";

const EXAMPLE_QUESTIONS = [
  "Find possible memory-retention risks and explain the evidence.",
  "Which classes are most central to the dependency graph?",
  "Explain how the HTTP endpoints reach the database.",
  "Where are blocking calls in reactive flows?",
];

const RUNTIME_CARDS: Array<{ type: RuntimeArtifactType; title: string; path: string; icon: "gauge" | "memory" | "threads"; hint: string }> = [
  { type: "JFR_RECORDING", title: "Performance Lab", path: "/performance", icon: "gauge", hint: "JFR recordings and benchmark results" },
  { type: "HEAP_DUMP", title: "Memory Analysis", path: "/memory", icon: "memory", hint: "Heap-object census and the bytes it recorded" },
  { type: "THREAD_DUMP", title: "Concurrency Inspector", path: "/concurrency", icon: "threads", hint: "Thread states and monitor contention" },
];

/**
 * The overview: an honest command centre.
 *
 * Every number here is read from an API response for the selected revision. The coverage
 * ring is parsed files over enumerated files — not a "code health score" invented by the
 * UI. Runtime cards are driven by uploaded artefacts, so an empty card means "nothing was
 * recorded", which is different from "nothing was wrong".
 */
export function OverviewPage() {
  const navigate = useNavigate();
  const { workspaceId, repositoryId, revisionId } = useSelection();
  const overview = useOverview();
  const repositories = useRepositories(workspaceId);
  const jobs = useJobs(undefined);
  const coverage = useCoverage(repositoryId, revisionId);
  const universe = useUniverse(repositoryId, revisionId);
  const summary = useFindingSummary(repositoryId, revisionId);
  const artifacts = useRuntimeArtifacts(repositoryId);
  const ask = useAskInvestigation(repositoryId);
  const { openCreateWorkspace, openRegisterRepository } = useActions();
  const { reduced } = useMotion();
  const [question, setQuestion] = useState("");

  const resolvedRevision = universe.data?.revisionId ?? revisionId;
  const graph = useMemo(
    () => (universe.data === undefined ? null : buildUniverse(universe.data, [])),
    [universe.data],
  );
  const repository = (repositories.data?.items ?? []).find((item) => item.id === repositoryId);
  const ai = overview.data?.ai;
  const workspaceCount = overview.data?.workspaceCount ?? 0;
  const repositoryCount = overview.data?.repositoryCount ?? 0;
  const activeJobs = (overview.data?.queuedJobCount ?? 0) + (overview.data?.runningJobCount ?? 0);

  const files = coverage.data?.totalFiles ?? repository?.analyzedFileCount ?? 0;
  const parsedFiles = coverage.data?.parsedFiles ?? 0;
  const parseRatio = files > 0 ? parsedFiles / files : 0;
  const parsePercent = formatPercent(parseRatio);
  const parsePercentValue = Math.round(parseRatio * 100);
  const findingsTotal = summary.data?.total ?? 0;
  const affectedFiles = new Set(
    (jobs.data?.items ?? []).filter((job) => job.status === "FAILED").map((job) => job.repositoryId ?? ""),
  ).size;

  const filesCount = useCountUp(files);
  const symbolCount = useCountUp(coverage.data?.symbolCount ?? universe.data?.totalSymbols ?? 0);
  const edgeCount = useCountUp(coverage.data?.edgeCount ?? universe.data?.totalEdges ?? 0);
  const findingCount = useCountUp(findingsTotal);
  const activeCount = useCountUp(activeJobs);

  const severityMax = Math.max(1, ...SEVERITY_ORDER.map((severity) => summary.data?.bySeverity[severity] ?? 0));
  const categories = Object.entries(summary.data?.byCategory ?? {})
    .filter((entry): entry is [FindingCategory, number] => typeof entry[1] === "number" && entry[1] > 0)
    .sort((left, right) => right[1] - left[1])
    .slice(0, 6);
  const latestArtifact = (type: RuntimeArtifactType) =>
    (artifacts.data?.items ?? []).find((artifact) => artifact.artifactType === type);

  const aiState: AiOrbState =
    ai === undefined ? "idle" : !ai.enabled ? "offline" : !ai.usable ? "error" : activeJobs > 0 ? "analyzing" : "idle";

  const askError = ask.error instanceof ApiError ? ask.error : null;
  const canAsk = ai?.usable === true && question.trim().length >= 8 && !ask.isPending && repositoryId !== null;

  function submitQuestion(): void {
    const trimmed = question.trim();
    if (trimmed.length < 8) {
      return;
    }
    ask.mutate(
      { question: trimmed, scope: "" },
      {
        onSuccess: () => {
          setQuestion("");
          navigate("/investigation");
        },
      },
    );
  }

  if (overview.isLoading) {
    return <Loading label="Reading the workspace" />;
  }

  if (workspaceCount === 0) {
    return (
      <div className="overview-page">
        <section className="overview-hero onboarding">
          <div className="hero-grid" aria-hidden="true" />
          <div className="hero-copy">
            <span className="hero-eyebrow">
              <span className="hero-eyebrow-dot" />
              LOCAL-FIRST · EVIDENCE-GROUNDED
            </span>
            <h1>
              Your codebase,
              <br />
              <span className="hero-gradient">illuminated.</span>
            </h1>
            <p>
              RepoPilot indexes Java repositories on this machine, maps their dependencies as an
              explorable universe, and answers questions with citations that can be checked. Nothing
              leaves the machine unless you point it at a model provider and ask.
            </p>
            <div className="hero-actions">
              <button type="button" className="btn btn-primary btn-lg" onClick={openCreateWorkspace}>
                <Icon name="plus" size={16} />
                Create the first workspace
              </button>
              <Link to="/investigation" className="btn btn-ghost btn-lg">
                How investigations work
              </Link>
            </div>
          </div>
          <div className="hero-visual" aria-hidden="true">
            <div className="hero-orbit hero-orbit-a" />
            <div className="hero-orbit hero-orbit-b" />
            <div className="hero-core">
              <AiOrb state={aiState} size={120} label={false} />
            </div>
            <span className="hero-tag hero-tag-a">index</span>
            <span className="hero-tag hero-tag-b">resolve</span>
            <span className="hero-tag hero-tag-c">investigate</span>
          </div>
        </section>
        <Card>
          <EmptyState
            icon="overview"
            title="Nothing registered yet"
            message="Create a workspace — a named container for the repositories RepoPilot may read — then register a repository under a permitted root."
            action={
              <button type="button" className="btn btn-primary" onClick={openCreateWorkspace}>
                Create workspace
              </button>
            }
          />
        </Card>
      </div>
    );
  }

  return (
    <div className="overview-page">
      <section className="overview-hero">
        <div className="hero-grid" aria-hidden="true" />
        <div className="hero-copy">
          <span className="hero-eyebrow">
            <span className="hero-eyebrow-dot" />
            {repository !== undefined ? repository.name.toUpperCase() : "NO REPOSITORY SELECTED"}
            {repository !== undefined ? ` · ${repository.status}` : ""}
          </span>
          <h1>
            Your codebase,
            <br />
            <span className="hero-gradient">illuminated.</span>
          </h1>
          <p>
            {repository !== undefined
              ? `${formatNumber(files)} files, ${formatNumber(coverage.data?.symbolCount ?? 0)} symbols and ${formatNumber(coverage.data?.edgeCount ?? 0)} relationships indexed from ${repository.displayLocation}.`
              : "Select a repository in the command bar to light up its universe."}
          </p>
          <div className="hero-actions">
            <Link to="/universe" className="btn btn-primary btn-lg">
              <Icon name="graph" size={16} />
              Explore code universe
            </Link>
            <Link to="/investigation" className="btn btn-ghost btn-lg">
              <Icon name="sparkles" size={15} />
              Ask the AI
            </Link>
            {repository !== undefined ? (
              <span className="hero-stamp rp-mono">
                last indexed {formatRelative(repository.lastIndexedAt)}
              </span>
            ) : null}
          </div>
        </div>
        <div className="hero-visual" aria-hidden="true">
          <div className="hero-orbit hero-orbit-a" />
          <div className="hero-orbit hero-orbit-b" />
          <div className="hero-core">
            <AiOrb state={aiState} size={124} label={false} />
          </div>
          <span className="hero-tag hero-tag-a">files {formatNumber(files)}</span>
          <span className="hero-tag hero-tag-b">edges {formatNumber(coverage.data?.edgeCount ?? 0)}</span>
          <span className="hero-tag hero-tag-c">findings {formatNumber(findingsTotal)}</span>
        </div>
      </section>

      {ai !== undefined && !ai.usable ? (
        <Banner tone="warning" title="AI provider unavailable">
          {ai.message ?? "No model provider is reachable."} Indexing, the Code Explorer, the universe
          and every analysis screen still work. AI investigation stays disabled until a local provider
          (Ollama by default) is running.
        </Banner>
      ) : null}

      <div className="overview-metrics">
        <Metric label="Files indexed" value={formatNumber(Math.round(filesCount))} foot={`${formatNumber(parsedFiles)} parsed · ${parsePercent}`} />
        <Metric label="Symbols" value={formatNumber(Math.round(symbolCount))} foot="classes, methods and fields" />
        <Metric label="Relationships" value={formatNumber(Math.round(edgeCount))} foot={`${formatNumber(coverage.data?.resolvedEdges ?? 0)} resolved`} />
        <Metric
          label="Findings"
          value={formatNumber(Math.round(findingCount))}
          foot={summary.data?.runId !== null && summary.data?.runId !== undefined ? `run ${summary.data.runId.slice(0, 8)}` : "no analysis run"}
          accent={findingsTotal > 0}
        />
        <Metric label="Jobs in flight" value={formatNumber(Math.round(activeCount))} foot={`${formatNumber(activeJobs)} queued or running`} accent={activeJobs > 0} />
        <Metric
          label="Repositories"
          value={formatNumber(repositoryCount)}
          foot={`${formatNumber(workspaceCount)} workspace${workspaceCount === 1 ? "" : "s"}${affectedFiles > 0 ? ` · ${affectedFiles} with failed runs` : ""}`}
        />
      </div>

      <div className="overview-main-grid">
        <Card title="Universe preview" className="overview-universe-card">
          <div className="overview-universe">
            {graph === null || graph.nodes.length === 0 ? (
              <EmptyState
                icon="graph"
                title={repositoryId === null ? "No repository selected" : "No universe yet"}
                message={
                  repositoryId === null
                    ? "Choose a repository in the command bar to render its dependency map."
                    : "Index the repository to assemble a universe from parsed symbols and relationships."
                }
                action={
                  repositoryId === null ? (
                    <button type="button" className="btn btn-sm" onClick={openRegisterRepository}>
                      Register repository
                    </button>
                  ) : (
                    <Link to="/universe" className="btn btn-sm btn-primary">
                      Open Code Universe
                    </Link>
                  )
                }
              />
            ) : (
              <>
                <UniverseCanvas
                  graph={graph}
                  filters={EMPTY_FILTERS}
                  severityLens={[]}
                  query=""
                  selectedIds={[]}
                  focusMode={false}
                  particleDensity={reduced ? 0 : 0.3}
                  reducedMotion={reduced}
                  mini
                  onSelect={() => undefined}
                  onFocusNode={() => undefined}
                  onClearSelection={() => undefined}
                />
                <Link to="/universe" className="universe-preview-overlay" aria-label="Open the interactive code universe">
                  <span className="preview-badge">
                    <span className="u-live-dot" />
                    {formatNumber(graph.nodes.length)} nodes · {formatNumber(graph.edges.length)} edges
                  </span>
                  <span className="preview-cta">
                    Enter the universe <Icon name="chevron-right" size={14} />
                  </span>
                </Link>
              </>
            )}
          </div>
        </Card>

        <div className="overview-side">
          <Card title="Index coverage">
            <div className="coverage-ring-wrap">
              <div
                className="coverage-ring"
                style={{
                  background: `conic-gradient(#22d3ee 0deg, #8b5cf6 ${Math.max(1, parsePercentValue) * 3.6}deg, rgba(148,163,184,0.14) ${Math.max(1, parsePercentValue) * 3.6}deg 360deg)`,
                }}
                role="img"
                aria-label={`${parsePercent} of enumerated files parsed`}
              >
                <div className="coverage-ring-inner">
                  <strong>{parsePercent}</strong>
                  <span>parsed</span>
                </div>
              </div>
              <dl className="coverage-facts">
                <div>
                  <dt>Parsed</dt>
                  <dd>{formatNumber(parsedFiles)}</dd>
                </div>
                <div>
                  <dt>With problems</dt>
                  <dd>{formatNumber(coverage.data?.problemFiles ?? 0)}</dd>
                </div>
                <div>
                  <dt>Failed</dt>
                  <dd>{formatNumber(coverage.data?.failedFiles ?? 0)}</dd>
                </div>
                <div>
                  <dt>Excluded</dt>
                  <dd>{formatNumber(coverage.data?.excludedFiles ?? 0)}</dd>
                </div>
              </dl>
            </div>
            <p className="coverage-note">
              Parsed files over enumerated files for revision{" "}
              <span className="rp-mono">{resolvedRevision?.slice(0, 8) ?? "—"}</span>. Excluded files
              were never read.
            </p>
          </Card>

          <Card title="Findings by severity">
            {summary.data?.runId === null || summary.data?.runId === undefined ? (
              <EmptyState
                icon="alert"
                title="Nothing analysed yet"
                message="Run a static analysis to see rule-based findings. This is static evidence, not a runtime measurement."
                action={
                  <Link to="/findings" className="btn btn-sm">
                    Open Findings
                  </Link>
                }
              />
            ) : (
              <>
                <ul className="severity-bars">
                  {SEVERITY_ORDER.map((severity) => {
                    const count = summary.data?.bySeverity[severity] ?? 0;
                    return (
                      <li key={severity}>
                        <span className="severity-label">
                          <span className="u-sev-dot" style={{ background: SEVERITY_COLOR[severity] }} />
                          {titleCase(severity)}
                        </span>
                        <span className="severity-track">
                          <span
                            style={{
                              width: `${(count / severityMax) * 100}%`,
                              background: SEVERITY_COLOR[severity],
                            }}
                          />
                        </span>
                        <strong>{formatNumber(count)}</strong>
                      </li>
                    );
                  })}
                </ul>
                {categories.length > 0 ? (
                  <div className="category-chips">
                    {categories.map(([category, count]) => (
                      <span key={category} className="category-chip">
                        {titleCase(category)} <strong>{count}</strong>
                      </span>
                    ))}
                  </div>
                ) : null}
                <Link to="/findings" className="card-link">
                  Read every finding <Icon name="chevron-right" size={13} />
                </Link>
              </>
            )}
          </Card>
        </div>
      </div>

      <div className="overview-main-grid">
        <Card title="Ask an investigation" className="overview-ask">
          <div className="ask-panel">
            <div className="ask-orb">
              <AiOrb state={ask.isPending ? "generating" : aiState} size={44} />
            </div>
            <div className="ask-copy">
              <p>
                Questions are answered from indexed evidence: symbols, files, findings and runtime
                summaries. Citations are checked after the model answers, and the verdict is shown
                beside every claim.
              </p>
              <textarea
                className="input ask-input"
                rows={3}
                maxLength={2000}
                value={question}
                onChange={(event) => setQuestion(event.target.value)}
                placeholder="Why is the empty catch in PaymentService hiding failures?"
                disabled={ai?.usable !== true}
                aria-label="Investigation question"
              />
              <div className="ask-actions">
                <div className="ask-examples">
                  {EXAMPLE_QUESTIONS.slice(0, 2).map((example) => (
                    <button key={example} type="button" onClick={() => setQuestion(example)} disabled={ai?.usable !== true}>
                      {example}
                    </button>
                  ))}
                </div>
                <button type="button" className="btn btn-primary" onClick={submitQuestion} disabled={!canAsk}>
                  {ask.isPending ? "Queuing…" : "Investigate"}
                  <Icon name="sparkles" size={14} />
                </button>
              </div>
              {askError !== null ? (
                <p className="ask-error">
                  {askError.message}
                  {askError.code === "DEPENDENCY_UNAVAILABLE"
                    ? " Start Ollama or configure a provider, then try again."
                    : ""}
                </p>
              ) : null}
            </div>
          </div>
        </Card>

        <Card title="Runtime evidence" className="overview-runtime">
          <p className="runtime-intro">
            Profiling is shown only when a recording has been uploaded and analysed. Empty cards are
            empty on purpose.
          </p>
          <div className="runtime-cards">
            {RUNTIME_CARDS.map((card) => {
              const artifact = latestArtifact(card.type);
              return (
                <Link key={card.type} to={card.path} className="runtime-card">
                  <span className="runtime-card-icon">
                    <Icon name={card.icon} size={18} />
                  </span>
                  <span className="runtime-card-body">
                    <strong>{card.title}</strong>
                    {artifact === undefined ? (
                      <span className="rp-faint">{card.hint}</span>
                    ) : (
                      <>
                        <span className="rp-mono runtime-file">{artifact.originalFilename}</span>
                        <span className="rp-faint">
                          {formatNumber(artifact.measurementCount)} measurements ·{" "}
                          {formatBytes(artifact.sizeBytes)} · {formatRelative(artifact.createdAt)}
                        </span>
                      </>
                    )}
                  </span>
                  <Badge tone={artifact === undefined ? "neutral" : artifact.analysisRunId ? "success" : "warning"} dot>
                    {artifact === undefined ? "none" : artifact.analysisRunId ? "analysed" : "stored"}
                  </Badge>
                </Link>
              );
            })}
          </div>
        </Card>
      </div>

      <div className="overview-main-grid">
        <Card
          title="Recent analyses"
          actions={
            <Link to="/history" className="btn btn-sm btn-ghost">
              All runs
            </Link>
          }
          flush
        >
          {jobs.isLoading ? (
            <Loading label="Loading runs" />
          ) : (jobs.data?.items ?? []).length === 0 ? (
            <EmptyState
              icon="history"
              title="No runs yet"
              message="Index or analyse a repository and its jobs will appear here with their real outcome."
            />
          ) : (
            <JobTable jobs={(jobs.data?.items ?? []).slice(0, 6)} />
          )}
        </Card>

        <Card
          title="Repositories"
          actions={
            <button type="button" className="btn btn-sm btn-ghost" onClick={openRegisterRepository}>
              Register
            </button>
          }
          flush
        >
          {(repositories.data?.items ?? []).length === 0 ? (
            <EmptyState
              icon="folder"
              title="No repositories in this workspace"
              message="Register a directory under a permitted root, then run an index job."
            />
          ) : (
            <div className="table-wrap">
              <table className="data overview-repos">
                <thead>
                  <tr>
                    <th>Repository</th>
                    <th>Status</th>
                    <th className="table-num">Files</th>
                    <th>Last indexed</th>
                  </tr>
                </thead>
                <tbody>
                  {(repositories.data?.items ?? []).map((item) => (
                    <tr key={item.id} className={item.id === repositoryId ? "selected" : undefined}>
                      <td>
                        <Link to="/universe">{item.name}</Link>
                        <span className="repo-path rp-mono">{item.displayLocation}</span>
                      </td>
                      <td>
                        <Badge tone={repositoryStatusTone(item.status)} dot>
                          {item.status}
                        </Badge>
                      </td>
                      <td className="table-num">{formatNumber(item.analyzedFileCount)}</td>
                      <td className="nowrap">
                        {hasValue(item.lastIndexedAt) ? formatRelative(item.lastIndexedAt) : "never"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

      <section className="overview-footer-strip">
        <div className="footer-strip-item">
          <Icon name="database" size={16} />
          <div>
            <strong>Local storage</strong>
            <span>Index, findings and recordings stay on this machine.</span>
          </div>
        </div>
        <div className="footer-strip-item">
          <Icon name="sparkles" size={16} />
          <div>
            <strong>{ai?.providerId ?? "No provider"}</strong>
            <span>{ai?.usable === true ? "Model calls are local and recorded as provenance." : ai?.message ?? "AI disabled."}</span>
          </div>
        </div>
        <div className="footer-strip-item">
          <Icon name="refresh" size={16} />
          <div>
            <strong>{formatNumber(overview.data?.succeededJobCount ?? 0)} completed runs</strong>
            <span>{formatNumber(overview.data?.failedJobCount ?? 0)} failed · {formatNumber(overview.data?.cancelledJobCount ?? 0)} cancelled</span>
          </div>
        </div>
        <div className="footer-strip-item">
          <Icon name="branch" size={16} />
          <div>
            <strong>{resolvedRevision?.slice(0, 8) ?? "No revision"}</strong>
            <span>{universe.data?.truncated ? "Universe slice is bounded; totals are stated." : "Graph matches the indexed revision."}</span>
          </div>
        </div>
      </section>
    </div>
  );
}
