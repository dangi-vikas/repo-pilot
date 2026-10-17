# ADR 0004 — Database portability: H2 for local, PostgreSQL for deployment

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** RepoPilot maintainers

## Context

RepoPilot is local-first (ADR 0002): a developer should be able to clone the repo, run one
command, and have a working application. Requiring an external database to be installed
and running before the app will start is a real barrier — it is the most common reason a
"just run it" experience fails.

At the same time, the target deployment story includes Docker Compose, where PostgreSQL is
the natural, production-grade choice: it is what a user would run for real, and it is what
integrates cleanly with future needs (full-text search, `pgvector` if embeddings ever earn
their keep).

The risk is **schema drift**: SQL that works on one database and silently differs on the
other. A tool that analyses code for correctness must not itself have two divergent schema
definitions.

## Decision

Ship **one Flyway migration set** written to the **portable subset** of SQL that both
targets accept, and select the database by profile:

- **`local` profile (default):** H2 in **PostgreSQL compatibility mode**, file-backed under
  the data directory. Zero external dependencies; first run works offline.
- **`postgres` profile:** the same migrations against PostgreSQL, used by
  `docker compose up --build`.

Portability rules the migrations follow:

1. **`TIMESTAMP WITH TIME ZONE` (mapped to `Instant`) for all time columns** — never
   `TIMESTAMP`, whose semantics differ between engines. All timestamps are recorded in UTC.
2. **`VARCHAR(n)` for text with a known bound, `CLOB`/`TEXT` avoided where a bound exists.**
3. **No engine-specific defaults** (`NOW()`, `gen_random_uuid()`, sequences tied to a
   dialect). Identifiers are application-generated UUIDs (see the entity base classes).
4. **Boolean, integer and UUID types only from the common subset.** H2's PostgreSQL mode
   and PostgreSQL both accept `BOOLEAN`, `INTEGER`/`BIGINT`, and `UUID`.
5. **Foreign keys and unique constraints are declared explicitly**, since both engines
   enforce them and their error behaviour (which the API maps to 409/400) is relied upon.

The `local` profile is a **convenience**, not the reference. PostgreSQL is the database the
schema is validated against; H2 compatibility mode is the thing expected to keep up.

## Consequences

**Positive**

- First-run experience is a single command with no external services.
- One migration set means no drift by construction; the portability rules are checkable in
  review and are stated at the top of `V1__initial_schema.sql`.
- When the app runs under Docker Compose, it is running the same schema on the same engine
  a user would choose for real.

**Negative / risks**

- H2's PostgreSQL compatibility mode is close but not identical. A migration that uses a
  PostgreSQL-only feature would work in Compose and break locally, or vice versa. The
  portability rules above are the mitigation; this is a live risk, not a solved one.
- Some capabilities (advanced full-text search, `pgvector`) are PostgreSQL-only and must be
  feature-gated rather than assumed, if they are ever added.

## Alternatives considered

- **PostgreSQL only.** Rejected: turns "run it" into "install a database first", directly
  against the local-first goal.
- **H2 only, everywhere.** Rejected: not the engine a user would run for real, and it
  forecloses the PostgreSQL-specific features that deployment users may want.
- **SQLite.** Rejected: weaker type system and concurrency model for the multi-writer job
  workload, and no migration path to the deployment target.
