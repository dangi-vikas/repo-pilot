package dev.repopilot.shared.reactive;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Bridges blocking infrastructure (JDBC/JPA, file I/O, in-process analyzers) into
 * the reactive web layer.
 *
 * <p>Why this exists: RepoPilot's API is WebFlux but its persistence is
 * Spring Data JPA, which is blocking. Rather than pretend otherwise, every
 * blocking call is explicitly moved onto a bounded elastic scheduler. That makes
 * the blocking boundary visible in code review, keeps request-handling threads
 * (Netty event loops) free, and prevents the classic WebFlux+JPA mistake of
 * running queries on an event loop thread.
 *
 * <p>See docs/adr/0003-webflux-with-blocking-jpa.md for the trade-off analysis
 * and the conditions under which this should be replaced with R2DBC.
 */
public final class Blocking {

    private Blocking() {
    }

    /**
     * Runs a blocking supplier off the event loop.
     *
     * <p>Use for JDBC/JPA queries, file reads and CPU work that is short enough
     * not to deserve a dedicated job. Long-running work belongs in the job system
     * instead, not here.
     */
    public static <T> Mono<T> offload(Supplier<T> supplier) {
        return Mono.fromCallable(supplier::get).subscribeOn(Schedulers.boundedElastic());
    }

    /** Deferred variant: the supplier runs once per subscription, after subscription. */
    public static <T> Mono<T> deferOffload(Callable<T> callable) {
        return Mono.defer(() -> Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic()));
    }

    /** Runs a blocking generator off the event loop. */
    public static <T> Flux<T> offloadMany(Supplier<? extends Iterable<T>> supplier) {
        return Flux.defer(() -> Flux.fromIterable(supplier.get()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Runs a blocking {@link Void} action off the event loop and completes empty. */
    public static Mono<Void> offloadVoid(Runnable runnable) {
        return Mono.fromRunnable(runnable).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /** A {@link Mono} that completes after the given duration, used for polling fallbacks. */
    public static Mono<Long> tick(Duration interval) {
        return Mono.delay(interval);
    }
}
