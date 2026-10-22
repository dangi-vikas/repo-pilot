import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Icon } from "@/components/icons";
import { useJobCounters, useJobs } from "@/hooks/queries";
import { formatRelative, titleCase } from "@/lib/format";
import type { JobStatus, JobView } from "@/lib/types";

const OPEN_KEY = "repopilot.console.open";
const FILTERS = ["ALL", "ACTIVE", "FAILED"] as const;
type ConsoleFilter = (typeof FILTERS)[number];

/**
 * The activity console: a real job stream, not a simulated terminal.
 *
 * Every line is a job row the backend returned. When nothing has run, it says so instead
 * of printing invented progress. Auto-refresh follows the same rule as the job table:
 * quickly while work is in flight, slowly when the queue is quiet.
 */
export function ActivityConsole() {
  const jobs = useJobs(undefined);
  const counters = useJobCounters();
  const navigate = useNavigate();
  const [open, setOpen] = useState(() => {
    try {
      return window.localStorage.getItem(OPEN_KEY) === "true";
    } catch {
      return false;
    }
  });
  const [filter, setFilter] = useState<ConsoleFilter>("ALL");

  useEffect(() => {
    try {
      window.localStorage.setItem(OPEN_KEY, open ? "true" : "false");
    } catch {
      // Preference only.
    }
  }, [open]);

  const all = jobs.data?.items ?? [];
  const items = useMemo(() => {
    if (filter === "ACTIVE") {
      return all.filter((job) => job.status === "QUEUED" || job.status === "RUNNING");
    }
    if (filter === "FAILED") {
      return all.filter((job) => job.status === "FAILED");
    }
    return all;
  }, [all, filter]);

  const active = (counters.data?.queued ?? 0) + (counters.data?.running ?? 0);

  if (!open) {
    return (
      <button
        type="button"
        className="console-collapsed"
        onClick={() => setOpen(true)}
        aria-expanded={false}
      >
        <span className="console-mark">
          <Icon name="terminal" size={14} />
        </span>
        <span>Activity</span>
        {active > 0 ? <span className="console-live-dot" /> : null}
        <span className="console-summary rp-faint">
          {active > 0
            ? `${counters.data?.running ?? 0} running · ${counters.data?.queued ?? 0} queued`
            : all.length > 0
              ? `last event ${formatRelative(all[0]?.finishedAt ?? all[0]?.createdAt ?? null)}`
              : "no events yet"}
        </span>
        <span className="rp-spacer" />
        <Icon name="chevron-up" size={14} />
      </button>
    );
  }

  return (
    <section className="activity-console" aria-label="Activity console">
      <header className="console-head">
        <button
          type="button"
          className="console-title"
          onClick={() => setOpen(false)}
          aria-expanded
          title="Collapse activity console"
        >
          <span className="console-mark">
            <Icon name="terminal" size={14} />
          </span>
          <strong>Activity</strong>
          {active > 0 ? <span className="console-live-dot" /> : null}
          <Icon name="chevron-down" size={13} />
        </button>

        <div className="console-tabs" role="tablist" aria-label="Activity filter">
          {FILTERS.map((value) => (
            <button
              key={value}
              type="button"
              role="tab"
              aria-selected={filter === value}
              className={filter === value ? "console-tab is-on" : "console-tab"}
              onClick={() => setFilter(value)}
            >
              {value === "ALL" ? "All" : value === "ACTIVE" ? "Active" : "Failed"}
              {value === "ACTIVE" && active > 0 ? <span>{active}</span> : null}
              {value === "FAILED" && (counters.data?.failed ?? 0) > 0 ? (
                <span>{counters.data?.failed}</span>
              ) : null}
            </button>
          ))}
        </div>

        <span className="rp-spacer" />
        <span className="console-clock rp-mono">
          {counters.data === undefined
            ? "connecting"
            : `${counters.data.running} running · ${counters.data.queued} queued · ${counters.data.failed} failed`}
        </span>
        <button
          type="button"
          className="icon-btn"
          onClick={() => navigate("/history")}
          title="Open Analysis History"
          aria-label="Open Analysis History"
        >
          <Icon name="external" size={14} />
        </button>
        <button
          type="button"
          className="icon-btn"
          onClick={() => setOpen(false)}
          title="Collapse"
          aria-label="Collapse activity console"
        >
          <Icon name="chevron-down" size={14} />
        </button>
      </header>

      <div className="console-body" role="log" aria-live="polite" aria-relevant="additions text">
        {jobs.isLoading ? (
          <p className="console-line is-muted">reading the job ledger…</p>
        ) : items.length === 0 ? (
          <p className="console-line is-muted">
            {filter === "FAILED"
              ? "No failed jobs recorded."
              : filter === "ACTIVE"
                ? "Nothing is running. Index or analyse a repository to see events here."
                : "No jobs yet. Events appear when a repository is indexed or analysed."}
          </p>
        ) : (
          items.slice(0, 40).map((job) => <ConsoleLine key={job.id} job={job} />)
        )}
      </div>
    </section>
  );
}

function ConsoleLine({ job }: { job: JobView }) {
  const timestamp = job.finishedAt ?? job.startedAt ?? job.createdAt ?? null;
  const status = job.status.toLowerCase();
  return (
    <div className={`console-line console-${status}`}>
      <span className="console-time rp-mono">{formatRelative(timestamp)}</span>
      <span className={`console-status status-${status}`}>
        {job.status === "RUNNING" ? "▸" : job.status === "SUCCEEDED" ? "✓" : job.status === "FAILED" ? "✗" : job.status === "CANCELLED" ? "⊘" : "·"}
        {" "}
        {titleCase(job.status)}
      </span>
      <span className="console-job rp-mono">{job.jobType}</span>
      <span className="console-message rp-truncate">
        {job.errorMessage ?? job.message ?? job.stage ?? job.resultSummary ?? "—"}
      </span>
      {job.status === "RUNNING" ? (
        <span className="console-progress" aria-label={`${job.progress}% complete`}>
          <span style={{ width: `${Math.max(2, Math.min(100, job.progress))}%` }} />
        </span>
      ) : null}
      <span className="console-attempt rp-mono">
        {job.attempt > 0 ? `${job.attempt}/${job.maxAttempts}` : ""}
      </span>
    </div>
  );
}

export function isTerminal(status: JobStatus): boolean {
  return status === "SUCCEEDED" || status === "FAILED" || status === "CANCELLED";
}
