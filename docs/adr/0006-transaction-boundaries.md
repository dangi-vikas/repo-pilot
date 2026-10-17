# ADR 0006 — Transaction boundaries

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** RepoPilot maintainers

## Context

RepoPilot mixes operations with wildly different durations:

- **Microseconds–milliseconds:** read one repository summary; insert a queued job.
- **Seconds:** parse a batch of source files; resolve references against the symbol index.
- **Seconds–minutes:** a full repository scan, a JFR recording analysis, a model call to
  investigate a hotspot.

Holding a database transaction open across the slow category is a classic and expensive
mistake. Under H2/PostgreSQL it means a connection is pinned for the whole duration, a
connection pool of N can then serve only N concurrent slow operations, and any lock taken
early is held late — turning a read-mostly tool into one that serialises behind its
slowest work. It also makes cancellation unsatisfying, because aborting work still has to
wait for the transaction to end.

The durable job system (see `dev.repopilot.jobs`) already exists to keep long work off the
request path. This ADR fixes the *transactional* half of the same problem.

## Decision

**No database transaction is ever held open across a slow operation** — a filesystem
scan, a parser pass, a profiler read, or a model call. Transactions are short, explicit,
and contain only database work.

Concretely:

1. **Job submission is a short transaction.** `JobService.submit` validates, inserts a
   `QUEUED` row, and returns. It never executes the work. HTTP latency is therefore
   independent of analysis cost, and an accepted job survives a restart because it is
   already durable.

2. **State transitions are isolated and self-contained.** `JobClaimService` performs
   `QUEUED → RUNNING`, progress, `→ SUCCEEDED/FAILED/CANCELLED` transitions as separate
   `REQUIRES_NEW` transactions. It is a *different bean* from `JobService`/`JobDispatcher`
   on purpose: Spring's transaction advice is proxy-based, so a `REQUIRES_NEW` method
   called from another method of the same class would silently join the caller's
   transaction. Separate beans make each transition a genuinely independent, short
   transaction that commits or rolls back on its own — which is what makes the claim step
   safe against two dispatchers racing.

3. **The indexing pipeline commits in short batches, between which it does no database
   work.** `RepositoryIndexer` reads and hashes files, then parses, using a
   `TransactionTemplate` only for the tiny windows that delete prior derived rows or write
   a batch of parsed results. Parsing and file I/O happen *between* those windows, holding
   no connection. A batch size bounds how much is written per transaction.

4. **Long jobs are resumable and cancellable at checkpoints, not inside transactions.**
   `JobContext.checkpoint()` is called from the pipeline's loops, outside any transaction,
   so observing a cancellation flag never waits on a connection.

5. **Progress and heartbeats are throttled.** Updating progress on every parsed file would
   turn a parse loop into a write loop; progress is persisted on its own schedule in its
   own transaction.

## Consequences

**Positive**

- Connection-pool utilisation is proportional to *database work*, not to *analysis work*,
  so a long scan cannot starve the API of connections.
- Locks are held for the smallest possible window; the tool stays responsive while
  indexing.
- Cancellation is observed promptly at checkpoints rather than after a transaction drains.
- Each transition commits independently, which is what makes crash recovery
  (`StartupTasks` reconciling `RUNNING` jobs) and multi-dispatcher safety tractable.

**Negative / risks**

- The pipeline is *not* atomic across its whole run. A crash mid-index leaves a
  partially-populated revision. This is handled by making indexing idempotent: the
  derived rows for a revision are deleted and rewritten, so a retry converges. The
  trade-off is deliberate — atomicity of a multi-minute scan would mean holding a
  transaction longer than is acceptable.
- More, smaller transactions mean more commit round-trips. For a bounded batch size this is
  cheap relative to parsing, and it is the price of not pinning connections.
- Developers must remember the proxy/self-invocation rule when adding transitions; the
  separate-bean structure in `JobClaimService` is the standing reminder.

## Alternatives considered

- **One transaction per job, spanning the whole analysis.** Rejected: pins a connection
  for the job's duration, starves the pool, and holds locks too long.
- **No transactions; autocommit everywhere.** Rejected: loses atomicity for the multi-row
  batch writes (a source file and its symbols must land together) and for status
  transitions.
- **`REQUIRES_NEW` methods on the same service.** Rejected: silently ineffective due to
  proxy self-invocation; see point 2.
