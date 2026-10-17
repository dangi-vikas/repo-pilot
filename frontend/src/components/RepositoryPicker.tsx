import { useEffect } from "react";
import { Icon } from "@/components/icons";
import { useRepositories, useWorkspaces } from "@/hooks/queries";
import { useActions } from "@/state/actions";
import { useSelection } from "@/state/selection";

/**
 * Workspace and repository selectors for the top bar.
 *
 * Every other screen is scoped by the selection held here, so this component also owns
 * the "make the selection valid" rules: adopt the first workspace when nothing is
 * chosen, and drop a workspace or repository that no longer exists. Doing it in one
 * place is why a deleted repository cannot leave the console rendering an empty
 * Code Explorer for a repository that is gone.
 */
export function RepositoryPicker() {
  const { workspaceId, repositoryId, selectWorkspace, selectRepository } = useSelection();
  const { openCreateWorkspace, openRegisterRepository } = useActions();
  const workspaces = useWorkspaces();
  const repositories = useRepositories(workspaceId);

  const workspaceList = workspaces.data ?? [];
  const repositoryList = repositories.data?.items ?? [];

  useEffect(() => {
    if (workspaces.data === undefined) {
      return;
    }
    if (workspaceId === null) {
      const first = workspaceList[0];
      if (first !== undefined) {
        selectWorkspace(first.id);
      }
      return;
    }
    if (!workspaceList.some((workspace) => workspace.id === workspaceId)) {
      selectWorkspace(workspaceList[0]?.id ?? null);
    }
  }, [workspaces.data, workspaceList, workspaceId, selectWorkspace]);

  useEffect(() => {
    if (repositories.data === undefined) {
      return;
    }
    if (repositoryId === null) {
      // Adopt the first repository so repository-scoped screens are useful on arrival
      // rather than showing an empty selection the operator has to fix by hand.
      const first = repositoryList[0];
      if (first !== undefined) {
        selectRepository(first.id);
      }
      return;
    }
    if (!repositoryList.some((repository) => repository.id === repositoryId)) {
      selectRepository(repositoryList[0]?.id ?? null);
    }
  }, [repositories.data, repositoryList, repositoryId, selectRepository]);

  if (workspaces.isLoading) {
    return <span className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>Loading workspaces…</span>;
  }

  if (workspaceList.length === 0) {
    return (
      <button type="button" className="btn btn-sm btn-primary" onClick={openCreateWorkspace}>
        <Icon name="plus" size={14} />
        New workspace
      </button>
    );
  }

  return (
    <div className="picker">
      <select
        className="select"
        aria-label="Workspace"
        value={workspaceId ?? ""}
        onChange={(event) => selectWorkspace(event.target.value.length > 0 ? event.target.value : null)}
      >
        {workspaceId === null ? <option value="">Select a workspace…</option> : null}
        {workspaceList.map((workspace) => (
          <option key={workspace.id} value={workspace.id}>
            {workspace.name}
          </option>
        ))}
      </select>
      <span className="picker-divider" />
      <select
        className="select"
        aria-label="Repository"
        value={repositoryId ?? ""}
        onChange={(event) => selectRepository(event.target.value.length > 0 ? event.target.value : null)}
      >
        {repositoryId === null ? (
          <option value="">
            {repositories.isLoading
              ? "Loading repositories…"
              : repositoryList.length === 0
                ? "No repositories"
                : "Select a repository…"}
          </option>
        ) : null}
        {repositoryList.map((repository) => (
          <option key={repository.id} value={repository.id}>
            {repository.name}
          </option>
        ))}
      </select>
      <button
        type="button"
        className="btn btn-ghost btn-icon btn-sm"
        onClick={openRegisterRepository}
        title="Register a repository"
        aria-label="Register a repository"
      >
        <Icon name="plus" size={15} />
      </button>
    </div>
  );
}
