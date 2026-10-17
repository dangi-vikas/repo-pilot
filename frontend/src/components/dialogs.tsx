import { useState, type FormEvent } from "react";
import { Modal } from "@/components/Modal";
import { Banner } from "@/components/ui";
import { useCreateWorkspace, useRegisterRepository, useWorkspaces } from "@/hooks/queries";
import { ApiError } from "@/lib/api";
import { useSelection } from "@/state/selection";

function describeFailure(cause: unknown): string {
  if (cause instanceof ApiError) {
    return cause.message;
  }
  return "The request could not be completed.";
}

/** Creates a workspace and selects it, so the next step (registering a repository) is unblocked. */
export function CreateWorkspaceDialog({ onClose }: { onClose: () => void }) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);
  const create = useCreateWorkspace();
  const { selectWorkspace } = useSelection();

  async function submit(event: FormEvent) {
    event.preventDefault();
    const trimmed = name.trim();
    if (trimmed.length === 0) {
      setError("A workspace needs a name.");
      return;
    }
    setError(null);
    try {
      const created = await create.mutateAsync({
        name: trimmed,
        description: description.trim().length > 0 ? description.trim() : null,
      });
      selectWorkspace(created.id);
      onClose();
    } catch (cause) {
      setError(describeFailure(cause));
    }
  }

  return (
    <Modal
      title="New workspace"
      onClose={onClose}
      footer={
        <>
          <div className="rp-spacer" />
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" form="create-workspace" className="btn btn-primary" disabled={create.isPending}>
            {create.isPending ? "Creating…" : "Create workspace"}
          </button>
        </>
      }
    >
      <form id="create-workspace" className="stack-4" onSubmit={submit}>
        {error !== null ? <Banner tone="danger">{error}</Banner> : null}
        <div className="field">
          <label htmlFor="ws-name">Name</label>
          <input
            id="ws-name"
            className="input"
            value={name}
            autoFocus
            maxLength={200}
            placeholder="e.g. Payments platform"
            onChange={(event) => setName(event.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="ws-description">Description (optional)</label>
          <textarea
            id="ws-description"
            className="textarea"
            value={description}
            maxLength={2000}
            placeholder="What this set of repositories is for."
            onChange={(event) => setDescription(event.target.value)}
          />
        </div>
      </form>
    </Modal>
  );
}

/**
 * Registers a repository that lives under a permitted workspace root.
 *
 * The path is validated by the backend sandbox, not here: the browser cannot know the
 * configured roots, and pretending to validate them client-side would produce a
 * reassuring message that the server might still reject.
 */
export function RegisterRepositoryDialog({ onClose }: { onClose: () => void }) {
  const { workspaceId, selectWorkspace, selectRepository } = useSelection();
  const workspaces = useWorkspaces();
  const [chosenWorkspace, setChosenWorkspace] = useState<string>(workspaceId ?? "");
  const [name, setName] = useState("");
  const [path, setPath] = useState("");
  const [error, setError] = useState<string | null>(null);
  const register = useRegisterRepository(chosenWorkspace.length > 0 ? chosenWorkspace : null);

  const available = workspaces.data ?? [];

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (chosenWorkspace.length === 0) {
      setError("Choose a workspace for this repository.");
      return;
    }
    const trimmedPath = path.trim();
    if (trimmedPath.length === 0) {
      setError("A path is required.");
      return;
    }
    setError(null);
    try {
      const created = await register.mutateAsync({
        name: name.trim().length > 0 ? name.trim() : null,
        path: trimmedPath,
      });
      if (created.workspaceId !== chosenWorkspace) {
        selectWorkspace(created.workspaceId);
      }
      selectRepository(created.id);
      onClose();
    } catch (cause) {
      setError(describeFailure(cause));
    }
  }

  return (
    <Modal
      title="Register repository"
      onClose={onClose}
      footer={
        <>
          <div className="rp-spacer" />
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" form="register-repository" className="btn btn-primary" disabled={register.isPending}>
            {register.isPending ? "Registering…" : "Register"}
          </button>
        </>
      }
    >
      <form id="register-repository" className="stack-4" onSubmit={submit}>
        {error !== null ? <Banner tone="danger">{error}</Banner> : null}
        {available.length === 0 ? (
          <Banner tone="warning" title="No workspaces yet">
            Create a workspace first, then register repositories inside it.
          </Banner>
        ) : (
          <div className="field">
            <label htmlFor="repo-workspace">Workspace</label>
            <select
              id="repo-workspace"
              className="select"
              value={chosenWorkspace}
              onChange={(event) => setChosenWorkspace(event.target.value)}
            >
              <option value="">Select a workspace…</option>
              {available.map((workspace) => (
                <option key={workspace.id} value={workspace.id}>
                  {workspace.name}
                </option>
              ))}
            </select>
          </div>
        )}
        <div className="field">
          <label htmlFor="repo-path">Path</label>
          <input
            id="repo-path"
            className="input rp-mono"
            value={path}
            autoFocus
            placeholder="C:\src\my-service  or  my-service"
            onChange={(event) => setPath(event.target.value)}
          />
          <p className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
            Absolute, or relative to a permitted workspace root. RepoPilot only reads under those roots.
          </p>
        </div>
        <div className="field">
          <label htmlFor="repo-name">Display name (optional)</label>
          <input
            id="repo-name"
            className="input"
            value={name}
            maxLength={300}
            placeholder="Derived from the directory when left blank"
            onChange={(event) => setName(event.target.value)}
          />
        </div>
      </form>
    </Modal>
  );
}
