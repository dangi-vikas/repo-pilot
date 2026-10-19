package dev.repopilot.analysis;

import java.util.List;

/**
 * What produced a set of findings.
 *
 * <p>Stored on {@code analysis_run.run_type} so a run's findings can be read back with
 * the pipeline that produced them.
 *
 * <p>{@link #producesRuntimeMeasurements()} is what decides which runs count as a repository's
 * runtime evidence. It lives here, next to the constants, because the alternative was a query that
 * asked for {@code JFR_ANALYSIS} by name — and a query like that silently stops finding a thread
 * dump the moment a second reader is added. Adding a reader is then a change in one enum rather than
 * a hunt for every place that spelled out the pipeline it cared about.
 */
public enum AnalysisRunType {

    /** Source analyzers: the built-in checks, PMD, and SpotBugs when bytecode is present. */
    STATIC_ANALYSIS(false),

    /** A JFR recording parsed into runtime measurements. */
    JFR_ANALYSIS(true),

    /** A heap dump ingested. */
    HEAP_ANALYSIS(true),

    /** A thread dump parsed into thread state observations. */
    THREAD_DUMP_ANALYSIS(true),

    /** A benchmark result imported. */
    BENCHMARK_IMPORT(true);

    private final boolean producesRuntimeMeasurements;

    AnalysisRunType(boolean producesRuntimeMeasurements) {
        this.producesRuntimeMeasurements = producesRuntimeMeasurements;
    }

    /**
     * Whether this run reads an uploaded runtime artefact rather than a source revision.
     *
     * <p>Runtime evidence is a fact about a process; static analysis is a claim about code. They are
     * read through different screens and filtered differently, so the distinction is part of the
     * enum rather than a list repeated wherever it is needed.
     */
    public boolean producesRuntimeMeasurements() {
        return producesRuntimeMeasurements;
    }

    /** Every run type that reads a runtime artefact, for "the newest runtime evidence" queries. */
    public static List<AnalysisRunType> runtimeMeasurementTypes() {
        return List.of(values()).stream().filter(AnalysisRunType::producesRuntimeMeasurements).toList();
    }
}
