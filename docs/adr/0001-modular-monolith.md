# ADR 0001 — Modular monolith with package-by-feature boundaries

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** RepoPilot maintainers

## Context

RepoPilot spans several capabilities that could each be their own service: repository
ingestion and code indexing, static analysis, runtime performance analysis (JFR, heap,
thread dumps), AI investigation, and a web API with a single-page UI. A naive structure
would either scatter a capability across `controller`/`service`/`repository` packages, or
split the system into microservices from day one.

Two facts shape the decision:

1. **This is a local-first, single-node tool.** Its default deployment is one process on
   a developer's laptop, talking to an embedded database and a local model runtime. There
   is no independent scaling axis to exploit, and no operational team to run a fleet.
2. **The capabilities share a great deal.** They all read the same repository revisions,
   the same source files, the same symbol index, and the same job infrastructure. The
   value of the product comes from *correlating* those signals (a static finding next to
   the runtime hotspot in the same method), which means the boundaries must be cheap to
   cross.

## Decision

Build RepoPilot as a **modular monolith**: one deployable Spring Boot application, with
every capability isolated in its own **package by feature** under `dev.repopilot`:

```
dev.repopilot
├── workspace        # workspaces, tenancy anchor
├── repository       # registration, sandbox, scanning, revisions
├── codeindex        # parse, resolve, persist, query the symbol graph
├── analysis         # (static / runtime / ai) — added in later phases
├── jobs             # durable job lifecycle and dispatch
├── ai               # provider abstraction (Ollama default)
├── security
├── api              # HTTP layer; the only outbound boundary
├── config
└── shared           # cross-cutting primitives (persistence, errors, reactive bridges)
```

Boundaries are enforced by convention and code review, plus two mechanical rules:

- **No JPA associations across modules.** A module stores foreign keys as raw `UUID`
  columns, never as `@ManyToOne`. A query in `codeindex` cannot accidentally traverse
  into `repository` and defeat the boundary.
- **Cross-module reads go through a module's service or a purpose-built query
  interface** (`RepositoryService`, `CodeIndexQueryService`), not through another
  module's repository.

Packages, not Maven modules, are used deliberately. Maven modules would give compile-time
enforcement, but they also add build ceremony and make the common case — a small
refactor crossing two capabilities — disproportionately expensive. If a boundary proves
load-bearing and needs hard enforcement, it can be promoted to a Maven module later, and
this ADR superseded.

## Consequences

**Positive**

- One build, one process, one database. `docker compose up --build` is a single service
  plus PostgreSQL, not an orchestration exercise.
- Correlating signals across capabilities is an in-memory call, not a network hop, so the
  product's core value (cross-linking findings to code) stays cheap.
- The job system, transaction conventions, and error model are shared rather than
  re-implemented per capability.

**Negative / risks**

- A package boundary can be violated silently; only review and convention stop it. The
  "no cross-module JPA associations" rule is the main mechanical defence.
- A single process means a runaway analysis can affect the whole app. This is mitigated
  by the bounded worker pool and per-job cancellation rather than by process isolation.
- Scaling out beyond one node is not a supported path without revisiting this ADR.

## Alternatives considered

- **Layered packages (controller/service/repository).** Rejected: it makes a capability
  impossible to reason about in isolation and invites cross-capability coupling.
- **Microservices per capability.** Rejected: no scaling need, high operational cost,
  and it would put the most valuable operation (cross-capability correlation) behind a
  network boundary.
- **Multi-module Maven build.** Deferred, not rejected outright; see above.
