package dev.repopilot.runtime;

/**
 * How a set of observations was reduced to one number.
 *
 * <p>Every measurement states its own reduction. A GC pause total and a GC pause maximum
 * come from the same events and mean completely different things; without this column a
 * dashboard showing "128 ms" could be showing either, and the reader would have no way to
 * tell which claim they were looking at.
 *
 * <p>Deliberately no percentiles. A percentile of a few hundred samples is a number with
 * no meaningful precision, and a percentile of a million samples would have to be computed
 * over a reservoir rather than the whole set &mdash; which is a different claim that would
 * need its own caveat. Totals, maxima, means and counts are exact over every event the
 * recording contains, so they are what this increment reports.
 */
public enum MeasurementAggregation {

    /** Number of observations. */
    COUNT,

    /** Sum over all observations. */
    SUM,

    /** Mean of all observations. */
    MEAN,

    /** Largest observation. */
    MAX
}
