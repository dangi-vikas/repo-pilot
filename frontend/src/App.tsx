import { QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter } from "react-router-dom";
import { AppShell } from "@/components/AppShell";
import { queryClient } from "@/lib/queryClient";
import { ActionsProvider } from "@/state/actions";
import { MotionProvider } from "@/state/motion";
import { SelectionProvider } from "@/state/selection";
import { SessionProvider } from "@/state/session";
import { ThemeProvider } from "@/state/theme";

/**
 * Provider order matters and is deliberate:
 *
 * - the query client wraps everything, because every screen reads through it;
 * - the session (bearer token) comes next, because the API client reads the token on
 *   each call rather than being constructed with it;
 * - the selection (workspace/repository/revision) sits inside the session, since a
 *   stale selection must be discarded the moment the token changes;
 * - the theme is pure presentation and can sit anywhere;
 * - the actions provider hosts the shell-level dialogs, which need both the selection
 *   and the query client.
 */
export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <SessionProvider>
          <SelectionProvider>
            <ThemeProvider>
              <MotionProvider>
                <ActionsProvider>
                  <AppShell />
                </ActionsProvider>
              </MotionProvider>
            </ThemeProvider>
          </SelectionProvider>
        </SessionProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
