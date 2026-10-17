import { Fragment, useState } from "react";
import { Badge, EmptyState, KeyValue, ProgressBar } from "@/components/ui";
import { useCancelJob } from "@/hooks/queries";
import {
  formatDuration,
  formatInstant,
  formatRelative,
  hasValue,
  jobStatusTone,
  titleCase,
} from "@/lib/format";
import type { JobView, Uuid } from "@/lib/types";

function shortId(id: Uuid | null | undefined): string {
  return id === null || id === undefined ? "—" : id.slice(0, 8);
}

/**
 * The durable job ledger.
 *
 * Every row is a real record from the job table — there are no optimistic rows and no
 * simulated progress. A job that is `RUNNING` shows the progress the worker last
 * reported, which may lag; the honest reading is "as of its last heartbeat".
 */
export function JobTable({
  jobs,
  emptyMessage = "No jobs have been submitted yet.",
}: {
  jobs: JobView[];
  emptyMessage?: string;
}) {
  const cancel = useCancelJob();
  const [expanded, setExpanded] = useState<Uuid | null>(null);
  const [cancelMessage, setCancelMessage] = useState<string | null>(null);

  if (jobs.length === 0) {
    return <EmptyState icon="history" title="No runs" message={emptyMessage} />;
  }

  return (
    <div className="table-wrap">
      {cancelMessage !== null ? (
        <div style={{ padding: "var(--space-3) var(--space-4)" }} className="rp-faint">
          {cancelMessage}
        </div>
      ) : null}
      <table className="data">
        <thead>
          <tr>
            <th>Type</th>
            <th>Status</th>
            <th>Repository</th>
            <th style={{ width: 140 }}>Progress</th>
            <th className="table-num">Attempt</th>
            <th>Started</th>
            <th>Duration</th>
            <th>Outcome</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {jobs.map((job) => {
            const active = job.status === "QUEUED" || job.status === "RUNNING";
            const isOpen = expanded === job.id;
            return (
              <Fragment key={job.id}>
                <tr
                  className="clickable"
                  onClick={() => setExpanded(isOpen ? null : job.id)}
                >
                  <td className="nowrap">{titleCase(job.jobType)}</td>
                  <td>
                    <Badge tone={jobStatusTone(job.status)} dot>
                      {job.status}
                    </Badge>
                  </td>
                  <td className="rp-mono">{shortId(job.repositoryId)}</td>
                  <td>
                    <ProgressBar value={job.progress} indeterminate={job.status === "RUNNING" && job.progress === 0} />
                  </td>
                  <td className="table-num">
                    {job.attempt}/{job.maxAttempts}
                  </td>
                  <td className="nowrap">{hasValue(job.startedAt) ? formatRelative(job.startedAt) : "—"}</td>
                  <td className="nowrap">
                    {hasValue(job.startedAt) ? formatDuration(job.startedAt, job.finishedAt ?? null) : "—"}
                  </td>
                  <td className="rp-truncate" style={{ maxWidth: 320 }} title={job.resultSummary ?? job.errorMessage ?? ""}>
                    {job.errorMessage ?? job.resultSummary ?? "—"}
                  </td>
                  <td onClick={(event) => event.stopPropagation()}>
                    {active ? (
                      <button
                        type="button"
                        className="btn btn-sm btn-danger"
                        disabled={cancel.isPending}
                        onClick={() => {
                          setCancelMessage(null);
                          cancel.mutate(job.id, {
                            onSuccess: (outcome) =>
                              setCancelMessage(
                                outcome.accepted
                                  ? `Cancellation requested for ${job.id.slice(0, 8)}.`
                                  : `Cancellation was not accepted (job is ${outcome.statusWhenRequested}).`,
                              ),
                            onError: () => setCancelMessage("Cancellation request failed."),
                          });
                        }}
                      >
                        Cancel
                      </button>
                    ) : null}
                  </td>
                </tr>
                {isOpen ? (
                  <tr>
                    <td colSpan={9} style={{ backgroundColor: "var(--bg-canvas)" }}>
                      <KeyValue
                        items={[
                          { label: "Job id", value: <span className="rp-mono">{job.id}</span> },
                          { label: "Workspace", value: <span className="rp-mono">{job.workspaceId ?? "—"}</span> },
                          { label: "Repository", value: <span className="rp-mono">{job.repositoryId ?? "—"}</span> },
                          { label: "Stage", value: job.stage ?? "—" },
                          { label: "Message", value: job.message ?? "—" },
                          { label: "Created", value: formatInstant(job.createdAt) },
                          { label: "Started", value: formatInstant(job.startedAt) },
                          { label: "Finished", value: formatInstant(job.finishedAt) },
                          { label: "Last heartbeat", value: formatInstant(job.heartbeatAt) },
                          { label: "Cancel requested", value: job.cancelRequested ? "yes" : "no" },
                          { label: "Error code", value: job.errorCode ?? "—" },
                        ]}
                      />
                    </td>
                  </tr>
                ) : null}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
