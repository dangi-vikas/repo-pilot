import { NavLink } from "react-router-dom";
import { DESTINATIONS, SECTIONS } from "@/components/destinations";
import { Icon } from "@/components/icons";
import { useJobCounters, useReadiness } from "@/hooks/queries";
import { useSession } from "@/state/session";
import type { Theme } from "@/hooks/theme";

/**
 * The navigation rail: all nine destinations, always visible.
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
  const { disconnect } = useSession();

  const status: "up" | "down" | "checking" = readiness.isPending
    ? "checking"
    : readiness.isSuccess && readiness.data.status === "UP"
      ? "up"
      : "down";
  const statusText = status === "up" ? "Backend up" : status === "checking" ? "Checking…" : "Backend down";

  const activeJobs = (counters.data?.queued ?? 0) + (counters.data?.running ?? 0);

  return (
    <nav className="nav" aria-label="Primary">
      <div className="nav-brand">
        <span className="nav-logo" aria-hidden="true">
          RP
        </span>
        <span className="nav-wordmark">
          Repo<span>Pilot</span>
        </span>
      </div>

      <div className="nav-scroll">
        {SECTIONS.map((section) => (
          <div key={section}>
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
        </span>
        <button type="button" className="nav-item" onClick={onToggleCollapse}>
          <span className="nav-icon">
            <Icon name="panel-left" size={18} />
          </span>
          <span className="nav-label">{collapsed ? "Expand" : "Collapse"}</span>
        </button>
        <button type="button" className="nav-item" onClick={onToggleTheme}>
          <span className="nav-icon">
            <Icon name={theme === "dark" ? "sun" : "moon"} size={18} />
          </span>
          <span className="nav-label">{theme === "dark" ? "Light theme" : "Dark theme"}</span>
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
