import { useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { AiOrb, type AiOrbState } from "@/components/AiOrb";
import { destinationByPath } from "@/components/destinations";
import { Icon } from "@/components/icons";
import { RepositoryPicker } from "@/components/RepositoryPicker";
import { useJobCounters, useJobs, useOverview, useRepository, useRevisions } from "@/hooks/queries";
import { formatRelative, hasValue, titleCase } from "@/lib/format";
import { useSelection } from "@/state/selection";

/**
 * The command bar: what you are looking at, what the system is doing, and the two ways in —
 * search and the AI.
 *
 * It reads live job state rather than decorating with fake telemetry. If a job is running
 * the bar says so and how far it has got; if nothing has ever been indexed it says that too.
 */
export function TopBar({ onOpenPalette }: { onOpenPalette: () => void }) {
  const location = useLocation();
  const navigate = useNavigate();
  const destination = destinationByPath(location.pathname);
  const { repositoryId } = useSelection();
  const repository = useRepository(repositoryId);
  const revisions = useRevisions(repositoryId);
  const jobs = useJobs(undefined);
  const counters = useJobCounters();
  const overview = useOverview();
  const ai = overview.data?.ai;
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const notificationRef = useRef<HTMLDivElement | null>(null);

  const allJobs = jobs.data?.items ?? [];
  const indexJob = allJobs.find(
    (job) =>
      job.repositoryId === repositoryId &&
      job.jobType === "REPOSITORY_INDEX" &&
      (job.status === "QUEUED" || job.status === "RUNNING"),
  );
  const analysisJob = allJobs.find(
    (job) =>
      job.repositoryId === repositoryId &&
      job.jobType === "STATIC_ANALYSIS" &&
      (job.status === "QUEUED" || job.status === "RUNNING"),
  );
  const lastAnalysis = allJobs.find(
    (job) =>
      job.repositoryId === repositoryId &&
      job.jobType === "STATIC_ANALYSIS" &&
      (job.status === "SUCCEEDED" || job.status === "FAILED" || job.status === "CANCELLED"),
  );
  const failedJobs = allJobs.filter((job) => job.status === "FAILED").slice(0, 5);
  const activeCount = (counters.data?.queued ?? 0) + (counters.data?.running ?? 0);
  const latestRevision = revisions.data?.[0];

  const aiState: AiOrbState =
    ai === undefined ? "idle" : !ai.enabled ? "offline" : !ai.usable ? "error" : analysisJob ? "analyzing" : "idle";

  useEffect(() => {
    if (!notificationsOpen) {
      return;
    }
    function onPointerDown(event: PointerEvent): void {
      if (notificationRef.current !== null && !notificationRef.current.contains(event.target as Node)) {
        setNotificationsOpen(false);
      }
    }
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [notificationsOpen]);

  return (
    <header className="topbar">
      <div className="topbar-context">
        <div className="topbar-repo">
          <RepositoryPicker />
        </div>
        {latestRevision !== undefined ? (
          <span className="topbar-branch rp-mono" title="Newest indexed revision">
            <Icon name="branch" size={13} />
            <span>{hasValue(latestRevision.branch) ? latestRevision.branch : "detached"}</span>
            <span className="branch-hash">{latestRevision.revision.slice(0, 7)}</span>
          </span>
        ) : repository.data !== undefined ? (
          <span className="topbar-branch is-empty">
            <Icon name="branch" size={13} />
            no revision
          </span>
        ) : null}
        {destination !== undefined ? (
          <span className="topbar-crumb">{destination.label}</span>
        ) : null}
      </div>

      <button type="button" className="topbar-search" onClick={onOpenPalette} aria-label="Open global search">
        <Icon name="search" size={15} />
        <span>Search everything…</span>
        <kbd>Ctrl K</kbd>
      </button>

      <div className="topbar-actions">
        <Link to="/history" className="topbar-state" title="Index and analysis state">
          <span
            className={
              indexJob !== undefined
                ? "status-dot busy"
                : repository.data?.status === "FAILED"
                  ? "status-dot down"
                  : repository.data?.lastIndexedAt
                    ? "status-dot up"
                    : "status-dot"
            }
          />
          <span className="topbar-state-text">
            <span className="topbar-state-label">
              {indexJob !== undefined
                ? `Indexing ${indexJob.progress}%`
                : repository.data?.lastIndexedAt
                  ? "Indexed"
                  : "Not indexed"}
            </span>
            <span className="topbar-state-sub">
              {indexJob !== undefined
                ? indexJob.stage ?? titleCase(indexJob.status)
                : repository.data?.lastIndexedAt
                  ? formatRelative(repository.data.lastIndexedAt)
                  : "run an index job"}
            </span>
          </span>
        </Link>

        <span className="topbar-divider" />

        <Link to="/findings" className="topbar-state" title="Static analysis state">
          <span className={analysisJob !== undefined ? "status-dot busy" : "status-dot"} />
          <span className="topbar-state-text">
            <span className="topbar-state-label">
              {analysisJob !== undefined ? "Analysing" : "Analysis"}
            </span>
            <span className="topbar-state-sub">
              {analysisJob !== undefined
                ? `${analysisJob.progress}%`
                : lastAnalysis !== undefined
                  ? `${titleCase(lastAnalysis.status)} · ${formatRelative(lastAnalysis.finishedAt)}`
                  : "not run"}
            </span>
          </span>
        </Link>

        <span className="topbar-divider" />

        <div className="topbar-notify" ref={notificationRef}>
          <button
            type="button"
            className={notificationsOpen ? "icon-btn is-on" : "icon-btn"}
            onClick={() => setNotificationsOpen((open) => !open)}
            aria-label={`Notifications${failedJobs.length > 0 ? `, ${failedJobs.length} failures` : ""}`}
            aria-expanded={notificationsOpen}
            title="Notifications"
          >
            <Icon name="bell" size={16} />
            {failedJobs.length > 0 ? <span className="notify-badge">{failedJobs.length}</span> : null}
            {activeCount > 0 && failedJobs.length === 0 ? <span className="notify-pulse" /> : null}
          </button>
          {notificationsOpen ? (
            <div className="notify-panel">
              <div className="notify-head">
                <strong>Notifications</strong>
                <Link to="/history" onClick={() => setNotificationsOpen(false)}>
                  All runs
                </Link>
              </div>
              {failedJobs.length === 0 && activeCount === 0 ? (
                <p className="notify-empty">No failures and no active jobs. Nothing needs attention.</p>
              ) : null}
              {activeCount > 0 ? (
                <div className="notify-item">
                  <span className="status-dot busy" />
                  <div>
                    <strong>{activeCount} job{activeCount === 1 ? "" : "s"} in flight</strong>
                    <p className="rp-faint">
                      {counters.data?.running ?? 0} running · {counters.data?.queued ?? 0} queued
                    </p>
                  </div>
                </div>
              ) : null}
              {failedJobs.map((job) => (
                <div className="notify-item" key={job.id}>
                  <span className="status-dot down" />
                  <div>
                    <strong>{job.jobType}</strong>
                    <p className="rp-faint">{job.errorMessage ?? job.message ?? "Failed"}</p>
                    <p className="rp-faint notify-time">{formatRelative(job.finishedAt)}</p>
                  </div>
                </div>
              ))}
            </div>
          ) : null}
        </div>

        <Link to="/settings" className="icon-btn" title="Settings" aria-label="Settings">
          <Icon name="settings" size={16} />
        </Link>

        <Link
          to="/investigation"
          className="topbar-ai"
          title={ai?.usable === true ? "Open AI Investigations" : ai?.message ?? "AI provider unavailable"}
        >
          <AiOrb state={aiState} size={26} />
          <span>
            <span className="topbar-ai-label">Ask RepoPilot</span>
            <span className="topbar-ai-sub">
              {ai?.usable === true ? ai.providerId ?? "ready" : ai?.enabled === false ? "disabled" : "offline"}
            </span>
          </span>
        </Link>

        <button
          type="button"
          className="topbar-ask-mobile icon-btn"
          onClick={() => navigate("/investigation")}
          aria-label="Open AI Investigations"
        >
          <Icon name="sparkles" size={17} />
        </button>
      </div>
    </header>
  );
}
