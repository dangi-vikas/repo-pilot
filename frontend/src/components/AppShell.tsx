import { useEffect, useState } from "react";
import { Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { CommandPalette } from "@/components/CommandPalette";
import { ConnectionGate } from "@/components/ConnectionGate";
import { DESTINATIONS } from "@/components/destinations";
import { NavRail } from "@/components/NavRail";
import { TopBar } from "@/components/TopBar";
import { AnalysisHistoryPage } from "@/pages/AnalysisHistoryPage";
import { CapabilityPage } from "@/pages/CapabilityPage";
import { CodeExplorerPage } from "@/pages/CodeExplorerPage";
import { ConcurrencyInspectorPage } from "@/pages/ConcurrencyInspectorPage";
import { DependencyGraphPage } from "@/pages/DependencyGraphPage";
import { MemoryPage } from "@/pages/MemoryPage";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { OverviewPage } from "@/pages/OverviewPage";
import { PerformanceLabPage } from "@/pages/PerformanceLabPage";
import { SettingsPage } from "@/pages/SettingsPage";
import { useActions } from "@/state/actions";
import { useSession } from "@/state/session";
import { useThemeContext } from "@/state/theme";

const COLLAPSE_KEY = "repopilot.nav.collapsed";

function readCollapsed(): boolean {
  try {
    return window.localStorage.getItem(COLLAPSE_KEY) === "true";
  } catch {
    return false;
  }
}

/**
 * The routes are generated from the same declaration the nav rail renders, so a
 * destination can never exist in the navigation without a page behind it, or vice
 * versa.
 */
function elementFor(destinationId: string) {
  switch (destinationId) {
    case "overview":
      return <OverviewPage />;
    case "code":
      return <CodeExplorerPage />;
    case "graph":
      return <DependencyGraphPage />;
    case "performance":
      return <PerformanceLabPage />;
    case "concurrency":
      return <ConcurrencyInspectorPage />;
    case "memory":
      return <MemoryPage />;
    case "history":
      return <AnalysisHistoryPage />;
    case "settings":
      return <SettingsPage />;
    default:
      return <CapabilityPage destinationId={destinationId} />;
  }
}

export function AppShell() {
  const { connected } = useSession();
  const { theme, toggle } = useThemeContext();
  const { openCreateWorkspace, openRegisterRepository } = useActions();
  const navigate = useNavigate();
  const [collapsed, setCollapsed] = useState(readCollapsed);
  const [paletteOpen, setPaletteOpen] = useState(false);

  useEffect(() => {
    try {
      window.localStorage.setItem(COLLAPSE_KEY, collapsed ? "true" : "false");
    } catch {
      // Ignore: the layout still works, the preference just is not remembered.
    }
  }, [collapsed]);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setPaletteOpen((open) => !open);
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  if (!connected) {
    return <ConnectionGate />;
  }

  return (
    <div className="app" data-collapsed={collapsed ? "true" : "false"}>
      <NavRail
        collapsed={collapsed}
        onToggleCollapse={() => setCollapsed((value) => !value)}
        theme={theme}
        onToggleTheme={toggle}
      />
      <div className="main">
        <TopBar onOpenPalette={() => setPaletteOpen(true)} />
        <main className="content">
          <div className="content-inner">
            <Routes>
              <Route path="/" element={<Navigate to="/overview" replace />} />
              {DESTINATIONS.map((destination) => (
                <Route key={destination.id} path={destination.path} element={elementFor(destination.id)} />
              ))}
              <Route path="*" element={<NotFoundPage />} />
            </Routes>
          </div>
        </main>
      </div>
      {paletteOpen ? (
        <CommandPalette
          theme={theme}
          onClose={() => setPaletteOpen(false)}
          onNavigate={(path) => navigate(path)}
          onToggleTheme={toggle}
          onOpenCreateWorkspace={openCreateWorkspace}
          onOpenRegisterRepository={openRegisterRepository}
        />
      ) : null}
    </div>
  );
}
