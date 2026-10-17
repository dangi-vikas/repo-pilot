import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import type { Uuid } from "@/lib/types";

const WORKSPACE_KEY = "repopilot.workspaceId";
const REPOSITORY_KEY = "repopilot.repositoryId";
const REVISION_KEY = "repopilot.revisionId";

interface SelectionValue {
  workspaceId: Uuid | null;
  repositoryId: Uuid | null;
  revisionId: Uuid | null;
  selectWorkspace: (id: Uuid | null) => void;
  selectRepository: (id: Uuid | null) => void;
  selectRevision: (id: Uuid | null) => void;
}

const SelectionContext = createContext<SelectionValue | null>(null);

function read(key: string): Uuid | null {
  try {
    const value = window.localStorage.getItem(key);
    return value && value.length > 0 ? value : null;
  } catch {
    return null;
  }
}

function write(key: string, value: Uuid | null): void {
  try {
    if (value === null) {
      window.localStorage.removeItem(key);
    } else {
      window.localStorage.setItem(key, value);
    }
  } catch {
    // Persisting selection is a convenience; ignore storage failures.
  }
}

/**
 * Remembers which workspace/repository/revision the operator is looking at.
 *
 * Selection is not a secret, so it is kept in `localStorage` (unlike the token). It
 * is centralised here because most screens are scoped to a repository, and a stale
 * selection that outlives a deleted repository must be clearable from one place.
 */
export function SelectionProvider({ children }: { children: ReactNode }) {
  const [workspaceId, setWorkspaceId] = useState<Uuid | null>(() => read(WORKSPACE_KEY));
  const [repositoryId, setRepositoryId] = useState<Uuid | null>(() => read(REPOSITORY_KEY));
  const [revisionId, setRevisionId] = useState<Uuid | null>(() => read(REVISION_KEY));

  useEffect(() => write(WORKSPACE_KEY, workspaceId), [workspaceId]);
  useEffect(() => write(REPOSITORY_KEY, repositoryId), [repositoryId]);
  useEffect(() => write(REVISION_KEY, revisionId), [revisionId]);

  const selectWorkspace = useCallback((id: Uuid | null) => {
    setWorkspaceId(id);
    // A repository belongs to exactly one workspace, so changing workspace clears it.
    setRepositoryId(null);
    setRevisionId(null);
  }, []);

  const selectRepository = useCallback((id: Uuid | null) => {
    setRepositoryId(id);
    setRevisionId(null);
  }, []);

  const selectRevision = useCallback((id: Uuid | null) => {
    setRevisionId(id);
  }, []);

  const value = useMemo<SelectionValue>(
    () => ({
      workspaceId,
      repositoryId,
      revisionId,
      selectWorkspace,
      selectRepository,
      selectRevision,
    }),
    [workspaceId, repositoryId, revisionId, selectWorkspace, selectRepository, selectRevision],
  );

  return <SelectionContext.Provider value={value}>{children}</SelectionContext.Provider>;
}

export function useSelection(): SelectionValue {
  const context = useContext(SelectionContext);
  if (context === null) {
    throw new Error("useSelection must be used within a SelectionProvider");
  }
  return context;
}
