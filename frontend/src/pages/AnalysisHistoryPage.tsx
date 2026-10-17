import { useState } from "react";
import { JobTable } from "@/components/JobTable";
import { Card, Loading, Metric, PageHeader } from "@/components/ui";
import { useJobCounters, useJobs } from "@/hooks/queries";
import { formatNumber } from "@/lib/format";
import type { JobStatus } from "@/lib/types";

type Filter = JobStatus | "ALL";

const FILTERS: Array<{ id: Filter; label: string }> = [
  { id: "ALL", label: "All" },
  { id: "QUEUED", label: "Queued" },
  { id: "RUNNING", label: "Running" },
  { id: "SUCCEEDED", label: "Succeeded" },
  { id: "FAILED", label: "Failed" },
  { id: "CANCELLED", label: "Cancelled" },
];

/**
 * The durable job ledger, with outcome filters.
 *
 * Jobs are the only thing in RepoPilot with a lifecycle, so this screen is the
 * operations view: what ran, how long it took, whether it retried, and what it
 * produced. Nothing is synthesised — an empty list means nothing has run.
 */
export function AnalysisHistoryPage() {
  const [filter, setFilter] = useState<Filter>("ALL");
  const jobs = useJobs(filter === "ALL" ? undefined : filter);
  const counters = useJobCounters();

  const countFor = (id: Filter): number | null => {
    const data = counters.data;
    if (data === undefined) {
      return null;
    }
    switch (id) {
      case "ALL":
        return data.queued + data.running + data.succeeded + data.failed + data.cancelled;
      case "QUEUED":
        return data.queued;
      case "RUNNING":
        return data.running;
      case "SUCCEEDED":
        return data.succeeded;
      case "FAILED":
        return data.failed;
      case "CANCELLED":
        return data.cancelled;
    }
  };

  return (
    <>
      <PageHeader
        title="Analysis History"
        description="Every job RepoPilot has run, with its outcome, attempts and duration."
      />

      <div className="grid grid-4" style={{ marginBottom: "var(--space-5)" }}>
        <Metric label="Queued" value={formatNumber(counters.data?.queued ?? 0)} foot="Waiting for a worker" />
        <Metric label="Running" value={formatNumber(counters.data?.running ?? 0)} foot="In flight now" accent={(counters.data?.running ?? 0) > 0} />
        <Metric label="Succeeded" value={formatNumber(counters.data?.succeeded ?? 0)} foot="Completed runs" />
        <Metric label="Failed" value={formatNumber(counters.data?.failed ?? 0)} foot="Terminal failures" />
      </div>

      <Card
        title="Jobs"
        flush
        actions={
          <div className="chip-row">
            {FILTERS.map((option) => (
              <button
                key={option.id}
                type="button"
                className="chip-btn"
                aria-pressed={filter === option.id}
                onClick={() => setFilter(option.id)}
              >
                {option.label}
                {countFor(option.id) !== null ? <span className="chip-count">{formatNumber(countFor(option.id))}</span> : null}
              </button>
            ))}
          </div>
        }
      >
        {jobs.isLoading ? (
          <Loading label="Loading jobs" />
        ) : (
          <JobTable
            jobs={jobs.data?.items ?? []}
            emptyMessage={
              filter === "ALL"
                ? "No jobs have been submitted yet. Index a repository from the Overview screen."
                : `No jobs are in the ${filter.toLowerCase()} state.`
            }
          />
        )}
      </Card>
    </>
  );
}
