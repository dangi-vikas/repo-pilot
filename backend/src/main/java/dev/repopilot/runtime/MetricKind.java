package dev.repopilot.runtime;

/**
 * What kind of quantity a runtime measurement is.
 *
 * <p>The kind exists so that a reader (and the UI) can tell what the number <em>means</em>
 * without knowing the metric's name. A duration is not a byte count is not a count of
 * events, and a total is not a maximum. Storing the reduction and the unit alongside the
 * value is what stops a figure like {@code 42} from being repeated as if it explained
 * itself.
 */
public enum MetricKind {

    /** A count of occurrences: collections, samples, events, threads. Unit is always {@code count}. */
    COUNTER,

    /** A span of time. Stored in milliseconds; the reader keeps nanoseconds internally. */
    DURATION,

    /** An instantaneous value observed periodically (CPU load, heap in use) or a sampled weight. */
    GAUGE,

    /** An amount of memory. */
    SIZE
}
