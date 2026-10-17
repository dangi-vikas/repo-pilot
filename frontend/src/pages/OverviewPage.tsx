import { Link } from "react-router-dom";
import { Badge, Banner, Card, EmptyState, ErrorState, Loading, Metric, PageHeader } from "@/components/ui";
import { IndexButton } from "@/components/IndexButton";
import { JobTable } from "@/components/JobTable";
import { useActions } from "@/state/actions";
import { useSelection } from "@/state/selection";
import {
  useJobCounters,
  useJobs,
  useOverview,
  useRepositories,
  useWorkspaces,
} from "@/hooks/queries";
import { formatNumber, formatRelative, hasValue, repositoryStatusTone } from "@/lib/format";

/** The landing screen: what exists, what is running, and how the AI layer is doing. */
export function OverviewPage() {
  const overview = useOverview();
  const counters = useJobCounters();
  const workspaces = useWorkspaces();
  const jobs = useJobs(undefined);
  const { workspaceId } = useSelection();
  const repositories = useRepositories(workspaceId);
  const { openCreateWorkspace, openRegisterRepository } = useActions();

  if (overview.isError) {
    return (
      <ErrorState
        message={overview.error instanceof Error ? overview.error.message : "The overview could not be loaded."}
        onRetry={() => void overview.refetch()}
      />
    );
  }
  if (overview.isLoading) {
    return <Loading label="Loading overview" />;
  }

  const data = overview.data;
  const workspaceCount = data?.workspaceCount ?? 0;
  const repositoryCount = data?.repositoryCount ?? 0;
  const queued = counters.data?.queued ?? data?.queuedJobCount ?? 0;
  const running = counters.data?.running ?? data?.runningJobCount ?? 0;
  const ai = data?.ai;

  if (workspaceCount === 0) {
    return (
      <>
        <PageHeader
          title="Overview"
          description="RepoPilot indexes Java repositories locally and answers questions about them from indexed evidence."
        />
        <Card>
          <EmptyState
            icon="overview"
            title="Nothing registered yet"
            message="Start by creating a workspace — a named container for the repositories you want RepoPilot to read. Everything else hangs off that."
            action={
              <button type="button" className="btn btn-primary" onClick={openCreateWorkspace}>
                Create the first workspace
              </button>
            }
          />
        </Card>
      </>
    );
  }

  const recent = (jobs.data?.items ?? []).slice(0, 8);

  return (
    <>
      <PageHeader
        title="Overview"
        description="Repository inventory, job activity and system status at a glance."
        actions={
          <>
            <button type="button" className="btn btn-sm" onClick={openCreateWorkspace}>
              New workspace
            </button>
            <button type="button" className="btn btn-sm" onClick={openRegisterRepository}>
              Register repository
            </button>
          </>
        }
      />

      {ai !== undefined && !ai.usable ? (
        <div style={{ marginBottom: "var(--space-5)" }}>
          <Banner tone="warning" title="AI provider unavailable">
            {ai.message ?? "No model provider is reachable."} Indexing, the Code Explorer and the Dependency Graph
            work without it; AI investigation stays disabled until a provider (Ollama by default) is running.
          </Banner>
        </div>
      ) : null}

      <div className="grid grid-4" style={{ marginBottom: "var(--space-5)" }}>
        <Metric label="Workspaces" value={formatNumber(workspaceCount)} foot={`${formatNumber(repositoryCount)} repositories registered`} />
        <Metric label="Active jobs" value={formatNumber(queued + running)} foot={`${formatNumber(queued)} queued · ${formatNumber(running)} running`} accent={queued + running > 0} />
        <Metric label="Completed runs" value={formatNumber(counters.data?.succeeded ?? data?.succeededJobCount ?? 0)} foot="Jobs that finished successfully" />
        <Metric
          label="Failed runs"
          value={formatNumber(counters.data?.failed ?? data?.failedJobCount ?? 0)}
          foot={(counters.data?.failed ?? 0) > 0 ? "Open Analysis History for details" : "No failures recorded"}
        />
      </div>

      <div className="grid grid-2" style={{ marginBottom: "var(--space-5)" }}>
        <Card title="AI layer">
          {ai === undefined ? (
            <p className="rp-muted">Unknown.</p>
          ) : (
            <dl className="kv">
              <dt>Status</dt>
              <dd>
                <Badge tone={ai.usable ? "success" : ai.enabled ? "warning" : "neutral"} dot>
                  {ai.usable ? "usable" : ai.enabled ? "degraded" : "disabled"}
                </Badge>
              </dd>
              <dt>Provider</dt>
              <dd>{ai.providerId ?? "—"}</dd>
              <dt>Reachable</dt>
              <dd>{ai.reachable ? "yes" : "no"}</dd>
              <dt>Embeddings</dt>
              <dd>{ai.embeddingsEnabled ? "enabled" : "disabled"}</dd>
              <dt>Configured providers</dt>
              <dd>{ai.availableProviderIds.length === 0 ? "—" : ai.availableProviderIds.join(", ")}</dd>
            </dl>
          )}
          <p className="rp-faint" style={{ fontSize: "var(--text-xs)", marginTop: "var(--space-4)" }}>
            RepoPilot never sends source code anywhere unless you configure a provider and ask it a question. The
            default provider is a local Ollama instance.
          </p>
        </Card>

        <Card title="Workspaces">
          {workspaces.isLoading ? (
            <Loading label="Loading workspaces" />
          ) : (workspaces.data ?? []).length === 0 ? (
            <p className="rp-muted">No workspaces.</p>
          ) : (
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>Name</th>
                    <th className="table-num">Repositories</th>
                    <th>Created</th>
                  </tr>
                </thead>
                <tbody>
                  {(workspaces.data ?? []).map((workspace) => (
                    <tr key={workspace.id}>
                      <td>{workspace.name}</td>
                      <td className="table-num">{formatNumber(workspace.repositoryCount)}</td>
                      <td className="nowrap">{formatRelative(workspace.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

      <div style={{ marginBottom: "var(--space-5)" }}>
        <Card
          title="Repositories"
          actions={
            <button type="button" className="btn btn-sm" onClick={openRegisterRepository}>
              Register
            </button>
          }
          flush
        >
          {repositories.isLoading ? (
            <Loading label="Loading repositories" />
          ) : (repositories.data?.items ?? []).length === 0 ? (
            <EmptyState
              icon="folder"
              title="No repositories in this workspace"
              message="Register a directory under a permitted workspace root, then run an index job."
              action={
                <button type="button" className="btn btn-primary btn-sm" onClick={openRegisterRepository}>
                  Register repository
                </button>
              }
            />
          ) : (
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Status</th>
                    <th>Location</th>
                    <th className="table-num">Files</th>
                    <th>Last indexed</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {(repositories.data?.items ?? []).map((repository) => (
                    <tr key={repository.id}>
                      <td>
                        <Link to="/code">{repository.name}</Link>
                      </td>
                      <td>
                        <Badge tone={repositoryStatusTone(repository.status)} dot>
                          {repository.status}
                        </Badge>
                      </td>
                      <td className="rp-mono rp-truncate" style={{ maxWidth: 260 }} title={repository.displayLocation}>
                        {repository.displayLocation}
                      </td>
                      <td className="table-num">{formatNumber(repository.analyzedFileCount)}</td>
                      <td className="nowrap">
                        {hasValue(repository.lastIndexedAt) ? formatRelative(repository.lastIndexedAt) : "never"}
                      </td>
                      <td>
                        <IndexButton repositoryId={repository.id} disabled={repository.status === "INDEXING"} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

      <Card
        title="Recent runs"
        actions={
          <Link to="/history" className="btn btn-sm btn-ghost">
            All runs
          </Link>
        }
        flush
      >
        {jobs.isLoading ? <Loading label="Loading jobs" /> : <JobTable jobs={recent} />}
      </Card>
    </>
  );
}
