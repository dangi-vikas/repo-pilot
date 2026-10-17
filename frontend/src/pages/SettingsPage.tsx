import { useState } from "react";
import { Modal } from "@/components/Modal";
import { Badge, Banner, Card, EmptyState, KeyValue, Loading, PageHeader } from "@/components/ui";
import { useBootstrap, useDeleteRepository, useReadiness, useRepositories } from "@/hooks/queries";
import { ApiError } from "@/lib/api";
import type { RepositorySummary } from "@/lib/types";
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
  const { workspaceId, selectRepository } = useSelection();
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
                Repository content is treated as untrusted input. Text inside a source file that tries to instruct the
                model is data, not a command.
              </li>
            </ul>
          </div>
        </Card>

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
