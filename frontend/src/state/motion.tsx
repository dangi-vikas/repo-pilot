import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

const STORAGE_KEY = "repopilot.motion";

export type MotionMode = "full" | "reduced";

interface MotionValue {
  mode: MotionMode;
  /** True when animation should be avoided. Always true for `reduced`. */
  reduced: boolean;
  setMode: (mode: MotionMode) => void;
  toggle: () => void;
}

const MotionContext = createContext<MotionValue | null>(null);

/**
 * Motion preference for the whole application.
 *
 * Two sources are respected, in this order: an explicit choice made in the UI, and the
 * operating system's `prefers-reduced-motion` setting. The latter is the default rather
 * than an afterthought — a graph that cannot be stilled is unusable for some people.
 */
export function MotionProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<MotionMode>(() => {
    try {
      const stored = window.localStorage.getItem(STORAGE_KEY);
      if (stored === "reduced" || stored === "full") {
        return stored;
      }
    } catch {
      // Storage is a convenience.
    }
    try {
      return window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "reduced" : "full";
    } catch {
      return "full";
    }
  });

  const setMode = useCallback((next: MotionMode) => {
    setModeState(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Ignore: the choice still applies to this session.
    }
  }, []);

  const toggle = useCallback(() => {
    setMode(mode === "full" ? "reduced" : "full");
  }, [mode, setMode]);

  useEffect(() => {
    document.documentElement.dataset.motion = mode;
  }, [mode]);

  const value = useMemo<MotionValue>(
    () => ({ mode, reduced: mode === "reduced", setMode, toggle }),
    [mode, setMode, toggle],
  );

  return <MotionContext.Provider value={value}>{children}</MotionContext.Provider>;
}

export function useMotion(): MotionValue {
  const context = useContext(MotionContext);
  if (context === null) {
    throw new Error("useMotion must be used within a MotionProvider");
  }
  return context;
}
