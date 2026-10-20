import { useState } from "react";
import { Modal } from "@/components/Modal";
import { Badge, Banner, Card, EmptyState, ErrorState, KeyValue, Loading, PageHeader } from "@/components/ui";
import {
  useBootstrap,
  useDeleteRepository,
  usePurgeRetention,
  useReadiness,
  useRepositories,
  useRetentionPlan,
} from "@/hooks/queries";
import { ApiError } from "@/lib/api";
import { formatBytes, formatNumber, formatRelative, titleCase } from "@/lib/format";
import type { RepositorySummary, Uuid } from "@/lib/types";
import { useSelection } from "@/state/selection";
import { useSession } from "@/state/session";
import { useThemeContext } from "@/state/theme";

/**
 * Settings: what this instance is, what it can reach, and what it refuses to do.
 *
 * The repository-access and privacy panels are the important ones. They state the
 * sandbox rule, the exclusion policy and the fact that this build exposes no API for
 * reading or changing workspace roots — rather than showing controls that would not
 * do anything.
 */
export function SettingsPage() {
  const { token, disconnect } = useSession();
  const { theme, toggle } = useThemeContext();
  const bootstrap = useBootstrap();
  const readiness = useReadiness();
  const { workspaceId, repositoryId, selectRepository } = useSelection();
  const repositories = useRepositories(workspaceId);
  const deleteRepository = useDeleteRepository();

  const [pendingDelete, setPendingDelete] = useState<RepositorySummary | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const components = Object.entries(readiness.data?.components ?? {});

  return (
    <>
      <PageHeader
        title="Settings"
        description="Connection, repository access, privacy controls and appearance."
      />

      <div className="stack-4">
        <Card title="Connection">
          <KeyValue
            items={[
              {
                label: "Backend",
                value: readiness.isLoading ? (
                  "Checking…"
                ) : (
                  <Badge tone={readiness.isSuccess && readiness.data.status === "UP" ? "success" : "danger"} dot>
                    {readiness.isSuccess ? readiness.data.status : "UNREACHABLE"}
                  </Badge>
                ),
              },
              {
                label: "Auth mode",
                value: bootstrap.data !== undefined ? `${bootstrap.data.authMode} (${bootstrap.data.header}: ${bootstrap.data.scheme})` : "—",
              },
              {
                label: "Token in this tab",
                value: token !== null ? <Badge tone="success">present</Badge> : <Badge tone="neutral">absent</Badge>,
              },
              {
                label: "Token source",
                value:
                  bootstrap.data !== undefined
                    ? bootstrap.data.tokenGenerated
                      ? `Generated on first run — ${bootstrap.data.tokenLocationHint}`
                      : `REPOPILOT_AUTH_TOKEN — ${bootstrap.data.tokenLocationHint}`
                    : "—",
              },
            ]}
          />
          <div className="toolbar" style={{ marginTop: "var(--space-4)" }}>
            <button type="button" className="btn btn-sm" onClick={disconnect} disabled={token === null}>
              Disconnect
            </button>
          </div>
          <p className="rp-faint" style={{ fontSize: "var(--text-xs)", marginTop: "var(--space-3)" }}>
            The token lives in this browser tab&apos;s session storage. It is not written to disk by the browser, not
            placed in a cookie, and not logged by the backend.
          </p>
        </Card>

        <Card title="Repository access">
          <div className="stack-3">
            <p className="rp-muted" style={{ fontSize: "var(--text-sm)" }}>
              RepoPilot reads only inside the workspace roots configured on the backend (the{" "}
              <code className="rp-mono">REPOPILOT_WORKSPACE_ROOTS</code> setting). Every path a job receives is
              resolved and re-checked against those roots, symlink targets included; anything that escapes them is
              rejected with <code className="rp-mono">PATH_REJECTED</code>.
            </p>
            <Banner tone="info" title="Workspace roots are not editable from here">
              This build does not expose an endpoint that reads or changes the roots, so the console cannot show you
              their current values. That is deliberate: a screen that displayed them would be guessing, and one that
              changed them would be editing server configuration over the API.
            </Banner>
          </div>
        </Card>

        <Card title="Data and privacy">
          <div className="stack-3">
            <ul className="capability-list">
              <li>
                <span className="bullet" />
                RepoPilot is local-first. Indexing, parsing and static analysis run on this machine and never send
                source code anywhere.
              </li>
              <li>
                <span className="bullet" />
                The only outbound traffic is to the model provider you configure — by default a local Ollama instance
                on <code className="rp-mono">127.0.0.1</code>.
              </li>
              <li>
                <span className="bullet" />
                Indexing never writes to the repositories it reads.
              </li>
              <li>
                <span className="bullet" />
                Files and directories that look like credentials (<code className="rp-mono">.env</code>,{" "}
                <code className="rp-mono">.ssh</code>, keystores, cloud credential files) are excluded by name before
                any read attempt, so their contents never enter the index.
              </li>
              <li>
                <span className="bullet" />
                Uploaded runtime artefacts are stored locally and never uploaded anywhere. Retention removes the bytes
                of old ones and keeps the numbers derived from them — see below.
              </li>
              <li>
                <span className="bullet" />
                Repository content is treated as untrusted input. Text inside a source file that tries to instruct the
                model is data, not a command.
              </li>
            </ul>
          </div>
        </Card>

        <RetentionCard repositoryId={repositoryId} />

        <Card title="Appearance">
          <div className="rp-row">
            <span className="rp-muted" style={{ fontSize: "var(--text-sm)" }}>
              Theme
            </span>
            <div className="segmented">
              <button type="button" aria-pressed={theme === "dark"} onClick={() => theme !== "dark" && toggle()}>
                Dark
              </button>
              <button type="button" aria-pressed={theme === "light"} onClick={() => theme !== "light" && toggle()}>
                Light
              </button>
            </div>
          </div>
        </Card>

        <Card title="Backend health components">
          {readiness.isLoading ? (
            <Loading label="Probing readiness" />
          ) : components.length === 0 ? (
            <p className="rp-muted">
              No component detail was reported. The readiness group reports per-dependency status when the actuator is
              configured to show it.
            </p>
          ) : (
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>Component</th>
                    <th>Status</th>
                    <th>Details</th>
                  </tr>
                </thead>
                <tbody>
                  {components.map(([name, component]) => (
                    <tr key={name}>
                      <td className="rp-mono">{name}</td>
                      <td>
                        <Badge tone={component.status === "UP" ? "success" : "danger"} dot>
                          {component.status}
                        </Badge>
                      </td>
                      <td className="rp-mono rp-truncate" style={{ maxWidth: 460 }} title={JSON.stringify(component.details ?? {})}>
                        {component.details !== undefined ? JSON.stringify(component.details) : "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <Card title="Registered repositories" flush>
          {deleteError !== null ? (
            <div style={{ padding: "var(--space-4)" }}>
              <Banner tone="danger">{deleteError}</Banner>
            </div>
          ) : null}
          {(repositories.data?.items ?? []).length === 0 ? (
            <EmptyState
              icon="folder"
              title="No repositories in this workspace"
              message="Repositories registered here can be removed; removing one deletes its index and analysis rows."
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
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {(repositories.data?.items ?? []).map((repository) => (
                    <tr key={repository.id}>
                      <td>{repository.name}</td>
                      <td>
                        <Badge tone="neutral">{repository.status}</Badge>
                      </td>
                      <td className="rp-mono rp-truncate" style={{ maxWidth: 320 }}>
                        {repository.displayLocation}
                      </td>
                      <td className="table-num">{repository.analyzedFileCount}</td>
                      <td className="align-right">
                        <button
                          type="button"
                          className="btn btn-sm btn-danger"
                          onClick={() => {
                            setDeleteError(null);
                            setPendingDelete(repository);
                          }}
                        >
                          Remove
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

      {pendingDelete !== null ? (
        <Modal
          title="Remove repository"
          onClose={() => setPendingDelete(null)}
          footer={
            <>
              <div className="rp-spacer" />
              <button type="button" className="btn" onClick={() => setPendingDelete(null)}>
                Cancel
              </button>
              <button
                type="button"
                className="btn btn-danger"
                disabled={deleteRepository.isPending}
                onClick={() => {
                  const target = pendingDelete;
                  deleteRepository.mutate(target.id, {
                    onSuccess: () => {
                      selectRepository(null);
                      setPendingDelete(null);
                    },
                    onError: (cause) =>
                      setDeleteError(cause instanceof ApiError ? cause.message : "The repository could not be removed."),
                  });
                }}
              >
                {deleteRepository.isPending ? "Removing…" : "Remove repository"}
              </button>
            </>
          }
        >
          <p>
            Remove <strong>{pendingDelete.name}</strong> from RepoPilot?
          </p>
          <p className="rp-muted" style={{ fontSize: "var(--text-sm)" }}>
            This deletes RepoPilot&apos;s index, symbols, edges and revision rows for this repository. Files on disk are
            not touched.
          </p>
        </Modal>
      ) : null}
    </>
  );
}

/**
 * Artefact retention: what is stored, what a pass would remove, and what it would keep.
 *
 * The screen is built around one distinction that the backend enforces and that a "delete" button
 * would hide: a purge removes the uploaded file and nothing else. The artefact row, the analysis run
 * and every measurement stay, which is why the candidate table states the number of measurements each
 * file produced, and why the confirmation repeats it. A window of zero means automatic pruning is off,
 * and then the screen says so instead of showing an empty list that would read as "nothing is old".
 */
function RetentionCard({ repositoryId }: { repositoryId: Uuid | null }) {
  const [window, setWindow] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [queued, setQueued] = useState<string | null>(null);

  // An empty box means "the window configured on the backend"; a number previews that window
  // instead. The preview and the purge use the same number, so what is confirmed is what was shown.
  // A box holding something that is not a whole number of days disables the button rather than
  // falling back to the configured window: a control that says one thing and acts on another is
  // worse than a disabled control.
  const enteredText = window.trim();
  const entered = enteredText === "" ? undefined : Number(enteredText);
  const enteredIsWindow = entered !== undefined && Number.isFinite(entered) && entered >= 1;
  const preview = enteredIsWindow ? Math.trunc(entered) : undefined;
  const plan = useRetentionPlan(repositoryId, preview);
  const purge = usePurgeRetention(repositoryId);

  const data = plan.data;
  const days = entered === undefined ? (data?.retentionDays ?? 0) : preview ?? 0;
  const candidates = data?.candidates ?? [];
  const removableBytes = candidates.reduce((total, candidate) => total + candidate.sizeBytes, 0);
  const measurementsKept = candidates.reduce((total, candidate) => total + candidate.measurementCount, 0);

  return (
    <Card
      title="Artefact retention"
      actions={
        data !== undefined ? (
          <Badge tone={data.automatic ? "success" : "neutral"} dot>
            {data.automatic ? `window ${formatNumber(data.retentionDays)} day(s)` : "pruning off"}
          </Badge>
        ) : null
      }
    >
      {repositoryId === null ? (
        <p className="rp-muted" style={{ fontSize: "var(--text-sm)" }}>
          Select a repository to see what retention would remove from it. Uploaded runtime artefacts are stored per
          repository.
        </p>
      ) : plan.isLoading ? (
        <Loading label="Reading the retention plan" />
      ) : plan.isError || data === undefined ? (
        <ErrorState message="The retention plan could not be read." onRetry={() => void plan.refetch()} />
      ) : (
        <div className="stack-3">
          <p className="rp-muted" style={{ fontSize: "var(--text-sm)" }}>
            A retention pass removes the <strong>stored file</strong> of an old artefact. It does not delete the
            artefact, the analysis run or any measurement: those are the evidence, and the file is only the input it
            was read from. Purging an artefact makes it impossible to analyse again until the file is uploaded once
            more.
          </p>

          <KeyValue
            items={[
              {
                label: "Configured policy",
                value: data.automatic
                  ? `Remove stored files older than ${formatNumber(data.retentionDays)} day(s)`
                  : "Automatic pruning is off; a pass runs only when you name a window",
              },
              {
                label: "Cutoff for this plan",
                value: `${formatRelative(data.cutoff)}${data.oldestStoredAt != null ? ` · oldest stored file ${formatRelative(data.oldestStoredAt)}` : ""}`,
              },
              {
                label: "Stored files",
                value: `${formatNumber(data.storedArtefacts)} (${formatBytes(data.storedBytes)})`,
              },
              {
                label: "Files already purged",
                value:
                  data.purgedArtefacts === 0
                    ? "none"
                    : `${formatNumber(data.purgedArtefacts)} (${formatBytes(data.purgedBytes)} released) — their measurements are still here`,
              },
            ]}
          />

          <div className="toolbar">
            <label className="rp-muted" htmlFor="retention-window" style={{ fontSize: "var(--text-sm)" }}>
              Window (days)
            </label>
            <input
              id="retention-window"
              className="input"
              type="number"
              min={1}
              step={1}
              style={{ maxWidth: 140 }}
              value={window}
              placeholder={data.retentionDays > 0 ? String(data.retentionDays) : "30"}
              onChange={(event) => {
                setQueued(null);
                setWindow(event.target.value);
              }}
            />
            <button
              type="button"
              className="btn btn-danger btn-sm"
              disabled={days < 1 || candidates.length === 0 || purge.isPending}
              title={
                days < 1
                  ? "Name a window of at least one day. An empty window is not read as \"purge everything\"."
                  : candidates.length === 0
                    ? "No stored file is past this window."
                    : "Queue a pass that removes the stored files listed below"
              }
              onClick={() => {
                setQueued(null);
                setConfirming(true);
              }}
            >
              Purge stored files
            </button>
            <span className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
              {candidates.length === 0
                ? "Nothing is past this window."
                : `${formatNumber(candidates.length)} file(s), ${formatBytes(removableBytes)} removable, ${formatNumber(measurementsKept)} measurement(s) kept`}
            </span>
          </div>

          {purge.isError ? (
            <Banner tone="danger" title="The purge could not be queued">
              {purge.error instanceof ApiError
                ? purge.error.message
                : purge.error instanceof Error
                  ? purge.error.message
                  : "Unknown error."}
            </Banner>
          ) : null}

          {queued !== null ? (
            <Banner tone="success" title="Purge queued">
              {queued}
            </Banner>
          ) : null}

          {candidates.length === 0 ? null : (
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>File</th>
                    <th>Type</th>
                    <th>Stored</th>
                    <th className="table-num">Bytes</th>
                    <th className="table-num">Measurements kept</th>
                  </tr>
                </thead>
                <tbody>
                  {candidates.map((candidate) => (
                    <tr key={candidate.artifactId}>
                      <td className="rp-truncate" style={{ maxWidth: 320 }} title={candidate.originalFilename}>
                        {candidate.originalFilename}
                      </td>
                      <td>{titleCase(candidate.artifactType)}</td>
                      <td className="nowrap">{formatRelative(candidate.createdAt)}</td>
                      <td className="table-num nowrap">{formatBytes(candidate.sizeBytes)}</td>
                      <td className="table-num">{formatNumber(candidate.measurementCount)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {confirming && data !== undefined ? (
        <Modal
          title="Purge stored artefact files"
          onClose={() => setConfirming(false)}
          footer={
            <>
              <div className="rp-spacer" />
              <button type="button" className="btn" onClick={() => setConfirming(false)}>
                Cancel
              </button>
              <button
                type="button"
                className="btn btn-danger"
                disabled={purge.isPending}
                onClick={() => {
                  purge.mutate(days, {
                    onSuccess: () => {
                      setConfirming(false);
                      setQueued(
                        `A retention pass for files older than ${formatNumber(days)} day(s) is queued. It removes ${formatNumber(candidates.length)} stored file(s) and keeps the ${formatNumber(measurementsKept)} measurement(s) derived from them.`,
                      );
                    },
                  });
                }}
              >
                {purge.isPending ? "Queueing…" : "Purge stored files"}
              </button>
            </>
          }
        >
          <p>
            Remove the stored bytes of <strong>{formatNumber(candidates.length)} file(s)</strong> older than{" "}
            <strong>{formatNumber(days)} day(s)</strong>, reclaiming{" "}
            <strong>{formatBytes(removableBytes)}</strong>?
          </p>
          <p className="rp-muted" style={{ fontSize: "var(--text-sm)" }}>
            The {formatNumber(measurementsKept)} measurement(s) recorded from these files are kept, and so are their
            analysis runs. Re-analysing a purged artefact will be refused until its file is uploaded again.
          </p>
        </Modal>
      ) : null}
    </Card>
  );
}
