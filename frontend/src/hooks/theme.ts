import { useCallback, useEffect, useState } from "react";

export type Theme = "dark" | "light";

const THEME_KEY = "repopilot.theme";

function readInitialTheme(): Theme {
  try {
    const stored = window.localStorage.getItem(THEME_KEY);
    if (stored === "dark" || stored === "light") {
      return stored;
    }
  } catch {
    // Storage may be unavailable; the default below is still correct.
  }
  return "dark";
}

/**
 * Owns the colour theme.
 *
 * Dark is the default because RepoPilot is a console for long analysis sessions and
 * usually sits beside an IDE. The choice is written to `localStorage` (a preference,
 * not a secret) and applied as `data-theme` on the document element, which is the
 * only selector the token stylesheet keys off.
 */
export function useTheme(): { theme: Theme; toggle: () => void } {
  const [theme, setTheme] = useState<Theme>(readInitialTheme);

  useEffect(() => {
    const root = document.documentElement;
    root.dataset["theme"] = theme;
    root.classList.toggle("dark", theme === "dark");
    root.classList.toggle("light", theme === "light");
    try {
      window.localStorage.setItem(THEME_KEY, theme);
    } catch {
      // Persisting the preference is a convenience; ignore storage failures.
    }
  }, [theme]);

  const toggle = useCallback(() => {
    setTheme((current) => (current === "dark" ? "light" : "dark"));
  }, []);

  return { theme, toggle };
}
