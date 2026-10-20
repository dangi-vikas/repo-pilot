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
 *
 * <p>{@link #AS_REPORTED} is the one value that is not a reduction, and it exists because
 * RepoPilot reads two different kinds of file. A recording is <em>reduced</em> here: RepoPilot
 * counts the events and computes the totals. A benchmark result file is <em>quoted</em>: the
 * mean, the standard error and the percentiles in it were computed by the harness that ran the
 * benchmark, and RepoPilot's only part is to repeat them accurately. Calling such a value
 * {@code MEAN} would claim RepoPilot had averaged something it never saw, and calling a quoted
 * percentile {@code MAX} would be straightforwardly false. So a quoted value says so.
 *
 * <p>This is also why the rule above survives contact with a file that contains percentiles.
 * RepoPilot still computes none: a percentile in a benchmark result is stored under the
 * harness's own key, with {@code AS_REPORTED} beside it, and is never relabelled as a figure
 * RepoPilot derived from samples it holds.
 */
public enum MeasurementAggregation {

    /** Number of observations. */
    COUNT,

    /** Sum over all observations. */
    SUM,

    /** Mean of all observations. */
    MEAN,

    /** Largest observation. */
    MAX,

    /**
     * Quoted from the file: not reduced by RepoPilot.
     *
     * <p>Used by readers that ingest a summary another tool produced — a benchmark harness writes
     * the mean, the error and the percentiles itself — where the arithmetic happened outside this
     * process and the reader's only job is to repeat what the file says without dressing it up as
     * its own work.
     */
    AS_REPORTED
}
