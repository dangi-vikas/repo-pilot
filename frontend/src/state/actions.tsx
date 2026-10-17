import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { CreateWorkspaceDialog, RegisterRepositoryDialog } from "@/components/dialogs";

interface ActionsValue {
  openCreateWorkspace: () => void;
  openRegisterRepository: () => void;
}

const ActionsContext = createContext<ActionsValue | null>(null);

type OpenDialog = "none" | "workspace" | "repository";

/**
 * Hosts the shell-level create/register dialogs.
 *
 * Both forms can be reached from several places (the repository picker, the overview
 * empty state, the command palette, Settings). Keeping one instance behind a context
 * means there is exactly one dialog in the DOM and one place that decides what
 * "register a repository" means.
 */
export function ActionsProvider({ children }: { children: ReactNode }) {
  const [dialog, setDialog] = useState<OpenDialog>("none");

  const openCreateWorkspace = useCallback(() => setDialog("workspace"), []);
  const openRegisterRepository = useCallback(() => setDialog("repository"), []);
  const close = useCallback(() => setDialog("none"), []);

  const value = useMemo<ActionsValue>(
    () => ({ openCreateWorkspace, openRegisterRepository }),
    [openCreateWorkspace, openRegisterRepository],
  );

  return (
    <ActionsContext.Provider value={value}>
      {children}
      {dialog === "workspace" ? <CreateWorkspaceDialog onClose={close} /> : null}
      {dialog === "repository" ? <RegisterRepositoryDialog onClose={close} /> : null}
    </ActionsContext.Provider>
  );
}

export function useActions(): ActionsValue {
  const context = useContext(ActionsContext);
  if (context === null) {
    throw new Error("useActions must be used within an ActionsProvider");
  }
  return context;
}
