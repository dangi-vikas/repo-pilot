import { EmptyState } from "@/components/ui";
import { useActions } from "@/state/actions";

/**
 * Shown by repository-scoped screens when no repository is selected.
 *
 * It offers the two real ways forward instead of a dead-end message, and states the
 * sandbox rule up front so a rejected path later is not a surprise.
 */
export function RepositoryRequired() {
  const { openCreateWorkspace, openRegisterRepository } = useActions();
  return (
    <EmptyState
      icon="folder"
      title="No repository selected"
      message="Pick a repository in the top bar, or register one. RepoPilot only reads files under the workspace roots configured on the backend."
      action={
        <div className="toolbar" style={{ justifyContent: "center" }}>
          <button type="button" className="btn btn-sm" onClick={openCreateWorkspace}>
            New workspace
          </button>
          <button type="button" className="btn btn-sm btn-primary" onClick={openRegisterRepository}>
            Register repository
          </button>
        </div>
      }
    />
  );
}
