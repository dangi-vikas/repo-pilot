# ADR 0003 — WebFlux API over blocking JPA persistence

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** RepoPilot maintainers

## Context

RepoPilot's HTTP layer is Spring WebFlux, but its persistence is Spring Data JPA over
JDBC — which is blocking by nature. These two choices sit in a well-known tension. The
classic WebFlux mistake is to call blocking JDBC from a Netty event-loop thread; under
load the event loop stalls and the whole server becomes unresponsive, all while the
application *looks* reactive.

The two "clean" alternatives are:

- **Spring MVC + JPA (all blocking).** Simplest and entirely consistent: one thread per
  request, blocking all the way down. No reactive machinery to get wrong.
- **WebFlux + R2DBC (all non-blocking).** Internally consistent too, but R2DBC means
  giving up JPA, Hibernate, Flyway's JPA integration, and the large ecosystem of
  JPA-based code — a significant cost for a single-node, local-first tool.

RepoPilot needs neither the throughput of R2DBC nor the async repackaging of a full
reactive stack. What it does need is a deliberate, visible boundary between the reactive
web tier and the blocking persistence tier, so the awkwardness is handled once rather than
sprinkled across every controller.

## Decision

Keep WebFlux for the HTTP layer and JPA for persistence, and make the boundary explicit
through a single bridge type, `dev.repopilot.shared.reactive.Blocking`:

```java
public static <T> Mono<T> offload(Supplier<T> supplier) {
    return Mono.fromCallable(supplier::get).subscribeOn(Schedulers.boundedElastic());
}

public static <T> Mono<T> deferOffload(Callable<T> callable) {
    return Mono.defer(() -> Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic()));
}
```

Rules that follow from this:

1. **Every handler that touches JPA, the filesystem, or a CPU-bound analyser must go
   through `Blocking`.** Nothing blocking runs on a Netty event loop.
2. **`deferOffload` is used when the work must run once per subscription**, `offload` when
   the supplier is cheap and safe to invoke eagerly. Controllers that read a path variable
   and then query use `deferOffload` so the query happens inside the offloaded body.
3. **Long work never runs on the request thread at all.** Anything expensive is handed to
   the durable job system (ADR 0006); the HTTP call only *submits* a job and returns.
4. **The bounded elastic scheduler is the backpressure valve.** It is a small, fixed pool,
   which is exactly the point: JDBC connections are also a small fixed pool, and the two
   limits should be in the same order of magnitude so the API cannot oversubscribe the
   database.

## Consequences

**Positive**

- WebFlux's request handling (streaming, backpressure on the HTTP side) is retained.
- The blocking boundary is *grep-able*: every JDBC call site is inside a `Blocking.*`
  lambda, which makes review and future migration tractable.
- No rewrite of the persistence layer; Hibernate, Flyway and JPA repositories all stay.

**Negative / risks**

- Two concurrency models coexist, which is genuinely harder to reason about than a
  uniformly blocking stack.
- A developer can bypass the bridge and run a blocking call on the event loop; only code
  review prevents it. (A `BlockHound`-style runtime guard would make this mechanical and
  is a candidate follow-up.)
- Thread hand-off has a small latency cost versus a pure blocking stack.

## Conditions to revisit

Replace JPA with R2DBC and delete the bridge if any of the following becomes true:

- RepoPilot needs to serve many concurrent users from one process, and the bounded
  elastic pool becomes a measured bottleneck.
- JPA/blocking work begins to dominate latency after the job system has already absorbed
  all long-running work.
- A streaming endpoint (for example, tailing logs) needs to interleave with database
  reads on the same request in a way the bridge makes awkward.

Until then, the bridge is the correct trade-off: it buys a consistent, well-understood
persistence layer at the cost of one clearly-marked boundary.
