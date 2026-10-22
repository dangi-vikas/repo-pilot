import { NavLink } from "react-router-dom";
import { AiOrb, type AiOrbState } from "@/components/AiOrb";
import { DESTINATIONS, SECTIONS } from "@/components/destinations";
import { Icon } from "@/components/icons";
import { useFindingSummary, useJobCounters, useOverview, useReadiness } from "@/hooks/queries";
import { formatNumber } from "@/lib/format";
import type { Theme } from "@/hooks/theme";
import { useMotion } from "@/state/motion";
import { useSelection } from "@/state/selection";
import { useSession } from "@/state/session";

/**
 * The navigation rail: the nine destinations, a live system footer, and the product mark.
 *
 * Destinations whose backend does not exist yet are rendered and labelled rather than
 * hidden, because an operator deciding whether to trust a tool needs to know what it
 * does not do yet.
 */
export function NavRail({
  collapsed,
  onToggleCollapse,
  theme,
  onToggleTheme,
}: {
  collapsed: boolean;
  onToggleCollapse: () => void;
  theme: Theme;
  onToggleTheme: () => void;
}) {
  const readiness = useReadiness();
  const counters = useJobCounters();
  const overview = useOverview();
  const { repositoryId, revisionId } = useSelection();
  const findingSummary = useFindingSummary(repositoryId, revisionId);
  const { disconnect } = useSession();
  const { mode, toggle: toggleMotion } = useMotion();

  const status: "up" | "down" | "checking" = readiness.isPending
    ? "checking"
    : readiness.isSuccess && readiness.data.status === "UP"
      ? "up"
      : "down";
  const statusText =
    status === "up" ? "Backend up" : status === "checking" ? "Checking…" : "Backend down";

  const activeJobs = (counters.data?.queued ?? 0) + (counters.data?.running ?? 0);
  const ai = overview.data?.ai;
  const aiState: AiOrbState =
    ai === undefined ? "idle" : !ai.enabled ? "offline" : !ai.usable ? "error" : activeJobs > 0 ? "analyzing" : "idle";

  return (
    <nav className="nav" aria-label="Primary">
      <div className="nav-brand">
        <span className="nav-logo" aria-hidden="true">
          <span className="nav-logo-core">R</span>
          <span className="nav-logo-orbit" />
        </span>
        <span className="nav-wordmark">
          Repo<span>Pilot</span>
        </span>
      </div>

      <div className="nav-intel" title={ai?.message ?? "Local AI status"}>
        <AiOrb state={aiState} size={30} label={false} />
        <span className="nav-intel-text">
          <span className="nav-intel-label">AI CORE</span>
          <span className="nav-intel-state">
            {ai?.usable === true
              ? ai.providerId ?? "ready"
              : ai?.enabled === false
                ? "disabled"
                : ai === undefined
                  ? "checking"
                  : "offline"}
          </span>
        </span>
        <span className={`nav-intel-dot is-${ai?.usable === true ? "ok" : "down"}`} />
      </div>

      <div className="nav-scroll">
        {SECTIONS.map((section) => (
          <div className="nav-section-group" key={section}>
            <div className="nav-section">{section}</div>
            {DESTINATIONS.filter((destination) => destination.section === section).map((destination) => (
              <NavLink
                key={destination.id}
                to={destination.path}
                className={({ isActive }) => (isActive ? "nav-item active" : "nav-item")}
                title={destination.description}
              >
                <span className="nav-icon">
                  <Icon name={destination.icon} size={18} />
                </span>
                <span className="nav-label">{destination.label}</span>
                {!destination.available ? <span className="nav-badge">soon</span> : null}
                {destination.id === "findings" && (findingSummary.data?.total ?? 0) > 0 ? (
                  <span className="nav-badge nav-badge-findings">
                    {formatNumber(findingSummary.data?.total)}
                  </span>
                ) : null}
                {destination.id === "history" && activeJobs > 0 ? (
                  <span className="nav-badge">{activeJobs}</span>
                ) : null}
              </NavLink>
            ))}
          </div>
        ))}
      </div>

      <div className="nav-footer">
        <span className="status-pill" title={`Readiness: ${statusText}`}>
          <span className={`status-dot ${status}`} />
          <span className="nav-label">{statusText}</span>
          <span className="nav-version nav-label rp-mono">v0.1</span>
        </span>
        <button
          type="button"
          className="nav-item"
          onClick={toggleMotion}
          title={mode === "full" ? "Reduce motion" : "Enable full motion"}
        >
          <span className="nav-icon">
            <Icon name="zap" size={18} />
          </span>
          <span className="nav-label">{mode === "full" ? "Reduce motion" : "Full motion"}</span>
        </button>
        <button type="button" className="nav-item" onClick={onToggleTheme}>
          <span className="nav-icon">
            <Icon name={theme === "dark" ? "sun" : "moon"} size={18} />
          </span>
          <span className="nav-label">{theme === "dark" ? "Light theme" : "Dark theme"}</span>
        </button>
        <button type="button" className="nav-item" onClick={onToggleCollapse}>
          <span className="nav-icon">
            <Icon name="panel-left" size={18} />
          </span>
          <span className="nav-label">{collapsed ? "Expand" : "Collapse"}</span>
        </button>
        <button
          type="button"
          className="nav-item"
          onClick={() => {
            disconnect();
          }}
        >
          <span className="nav-icon">
            <Icon name="plug" size={18} />
          </span>
          <span className="nav-label">Disconnect</span>
        </button>
      </div>
    </nav>
  );
}
