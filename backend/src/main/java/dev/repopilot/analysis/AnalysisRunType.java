package dev.repopilot.analysis;

/**
 * What produced a set of findings.
 *
 * <p>Stored on {@code analysis_run.run_type} so a run's findings can be read back with
 * the pipeline that produced them. Only {@link #STATIC_ANALYSIS} is implemented; the
 * runtime types are declared because the schema and the job types already name them, and
 * a run row that says {@code JFR_ANALYSIS} is more useful later than a migration.
 */
public enum AnalysisRunType {

    /** Source analyzers: the built-in checks, PMD, and SpotBugs when bytecode is present. */
    STATIC_ANALYSIS,

    /** A JFR recording parsed into runtime measurements. */
    JFR_ANALYSIS,

    /** A heap dump ingested. */
    HEAP_ANALYSIS,

    /** A thread dump parsed into thread state observations. */
    THREAD_DUMP_ANALYSIS,

    /** A benchmark result imported. */
    BENCHMARK_IMPORT
}
