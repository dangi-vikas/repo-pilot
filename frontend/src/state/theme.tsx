import { createContext, useContext, type ReactNode } from "react";
import { useTheme, type Theme } from "@/hooks/theme";

interface ThemeValue {
  theme: Theme;
  toggle: () => void;
}

const ThemeContext = createContext<ThemeValue | null>(null);

/**
 * One theme instance for the whole app.
 *
 * The shell, the rail and Settings all need to read and change the theme. Calling the
 * hook in each of them would mean three independent states fighting over the same
 * `data-theme` attribute, so the state lives here and is shared.
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const value = useTheme();
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useThemeContext(): ThemeValue {
  const context = useContext(ThemeContext);
  if (context === null) {
    throw new Error("useThemeContext must be used within a ThemeProvider");
  }
  return context;
}
