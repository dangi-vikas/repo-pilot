# ADR 0012 — Artefact retention: the file goes, the evidence stays

- **Status:** Accepted
- **Date:** 2026-10-20
- **Deciders:** RepoPilot maintainers

## Context

Uploaded runtime artefacts are the largest thing RepoPilot stores. A heap dump of a modest service is
hundreds of megabytes, a JFR recording with allocation sampling can be a gigabyte, and an operator who
collects one a week fills a disk inside a year. Until this phase the only way to reclaim that space was
to delete the repository, which also deletes the index, the findings, the runs and every measurement —
throwing away the evidence to get rid of the input it was derived from.

The evidence and the input are not the same thing. A measurement row is a number, a unit, a reduction,
a scope and an optional set of caveats, produced by a reader that no longer needs the file. The file is
bulk. A retention policy is therefore a policy about **bytes**, and its central design question is not
"when may we delete" but "what exactly is deleted".

There is a second question, and it is the one that makes this feature worth an ADR: what does a server
do when the policy says nothing? A default of "keep forever" fills disks; a default of "delete anything
older than the configured age" with an age of zero would delete everything on the first sweep. Neither
is an acceptable reading of an unset value, so the build treats **zero as "pruning is off"** and
**absence of a window on an explicit purge as a validation failure**.

## Decision

**Retention removes the stored file of an artefact past its window, marks the row `purged_at`, and
deletes nothing else. Runs and measurements are never deleted by age.**

1. **A purge removes bytes, not evidence.** The `runtime_artifact` row stays (with `purged_at` set), the
   `analysis_run` stays, and every `runtime_measurement` stays. The artefact list keeps showing the
   file's name, size and hash and the number of measurements derived from it; the file is simply no
   longer there. `ArtifactStore.resolve()` reports a missing file as `NOT_FOUND`, and the artifact view
   says `purgedAt`, so "the bytes are gone" is a state a client can render rather than an error it has
   to interpret.

2. **Runs are never deleted by a retention pass.** A run is the record of what a reader did, and a
   measurement without its run is an orphan with no provenance. The window applies to uploads, not to
   analyses.

3. **The plan is computed live, and reading it *is* the dry run.** `GET /runtime/retention` recomputes
   candidates against the current clock on every call, per file, with the number of measurements that
   would survive it. There is no `dryRun` parameter on the purge job, because a dry run that could be
   stale is worse than none, and a job that only pretends to delete is a second code path through the
   dangerous operation.

4. **A window of zero means "off", and a purge with no window is refused.** `repopilot.storage.
   retention-days = 0` disables the scheduler; the plan then reports the repository's state and names
   **no candidate**, and the screen says "pruning is off" rather than showing an empty list that would
   read as "nothing is old". `POST /retention/purge` with `olderThanDays` absent or ≤ 0 is a
   `400 VALIDATION_FAILED` whose message says it will not treat a missing window as everything. The
   same refusal is implemented in the service, so a job submitted around the HTTP layer cannot
   inherit it either.

5. **The order is: delete files, then mark rows.** A failure between the two steps leaves a row whose
   file is missing — visible, recoverable by re-upload, and reported honestly by `resolve()`. The
   reverse order would leave bytes with no owner, which only the startup orphan sweep could find. The
   pass is a maintenance job rather than an inline request, so it is visible in the job list with the
   window it ran under; it produces **no analysis run**, because nothing was read.

6. **Two passes may overlap, and the second finds nothing.** There is no global lock. A file that is
   already gone is counted as `alreadyGone` and its row is still marked, so a pass interrupted between
   the steps is completed by the next one. A file still referenced by another artefact row is left in
   place and counted as `skippedShared`; content addressing makes that impossible in the current
   layout, and the guard exists so a future layout that shares files cannot silently delete one from
   under the other.

7. **Re-uploading the same bytes restores the artefact.** Storage is content-addressed and unique per
   `(repositoryId, contentHash)`, so the second upload finds the existing row, writes the file again
   and clears `purged_at`. Measurements are not duplicated and no second run is created. Analysing a
   purged artefact before that is refused with `409 CONFLICT` and a sentence saying the measurements
   are still here and the file must be uploaded again — a queued job that would fail on a missing file
   is a worse answer than a refusal.

8. **Maintenance jobs bypass the per-repository "one job at a time" guard.** A scheduled purge must
   never be blocked by, or block, an analysis the user started; `JobService` excludes
   `ARTIFACT_RETENTION` from the guard by job type rather than by a flag on the caller.

9. **The scheduler queues, it never deletes inline.** `RetentionScheduler` runs hourly (initial delay
   120 s, both configurable) and, for each repository with no active job, submits one
   `ARTIFACT_RETENTION` job with the window written into its parameters — `{"olderThanDays": N}` — so
   the job records the policy it ran under even if the configuration changes afterwards. Its
   idempotency key is `retention:<repositoryId>:<yyyy-MM-dd>`: an hourly sweep becomes a daily pass, and
   a manual purge on the same day does not create a second one. One repository that cannot be queued
   does not stop the sweep for the rest.

10. **Deleting a repository still deletes everything it had.** The two paths are different on purpose:
    repository deletion is the operator saying "forget this project" and removes rows and bytes;
    retention is the operator saying "keep the findings, reclaim the disk" and removes bytes only. Both
    leave another repository's bytes untouched.

11. **Nothing in this build deletes a single artefact on demand.** There is no per-artefact `DELETE`.
    The only ways bytes leave the store are retention, repository deletion, and the startup orphan
    sweep. An artefact the operator wants gone now is an artefact they can purge by naming a window of
    one day once it is a day old, or remove with its repository — and that is a deliberate limit on the
    number of paths that can destroy evidence.

## Consequences

- **The disk can be reclaimed without losing a single number.** The run summary and the plan both state
  measurements kept alongside bytes released, and the confirmation dialog repeats it before the pass is
  queued: the number that makes the decision safe is on screen before the click.
- **A purged artefact is a first-class, visible state.** It is not an error, not a missing row, and not
  a silently broken link: the list marks it, the Analyse button explains why it is disabled, and the
  re-upload path is one upload away.
- **The default is conservative.** Pruning is on with a 30-day window in the shipped configuration and
  can be switched off; an operator who has not thought about retention keeps their files rather than
  losing them, and a server whose window is zero never queues a pass.
- **One migration, one column.** `V5__artifact_retention.sql` adds `runtime_artifact.purged_at` and
  nothing else; the purge queries are two `UPDATE`s and three aggregates over existing indexes.
- **Not claimed:** deduplication across repositories, compression of stored artefacts, per-artefact
  pinning ("never purge this one"), or a retention policy per artefact type. The window is one number
  per repository and the plan says so.
