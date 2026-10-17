import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { clearToken, getToken, setToken } from "@/lib/session";

interface SessionValue {
  token: string | null;
  connected: boolean;
  connect: (token: string) => void;
  disconnect: () => void;
}

const SessionContext = createContext<SessionValue | null>(null);

/**
 * Holds the bearer token in React state, mirrored into `sessionStorage` so a reload
 * within the same tab does not force a re-paste. It is not persisted beyond the tab.
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(() => getToken());

  const connect = useCallback((next: string) => {
    const trimmed = next.trim();
    if (trimmed.length === 0) {
      return;
    }
    setToken(trimmed);
    setTokenState(trimmed);
  }, []);

  const disconnect = useCallback(() => {
    clearToken();
    setTokenState(null);
  }, []);

  const value = useMemo<SessionValue>(
    () => ({ token, connected: token !== null, connect, disconnect }),
    [token, connect, disconnect],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const context = useContext(SessionContext);
  if (context === null) {
    throw new Error("useSession must be used within a SessionProvider");
  }
  return context;
}
