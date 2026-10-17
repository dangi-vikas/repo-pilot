package dev.repopilot.analysis;

import dev.repopilot.repository.scan.DetectedLanguage;

import java.util.List;
import java.util.Set;

/**
 * One deterministic analyzer over source text.
 *
 * <p>An analyzer is a pure function of the files it is given: no database access, no network,
 * no clock, no randomness, no state kept between calls. That is what makes its output
 * reproducible, and reproducibility is what makes a finding worth recording. An analyzer that
 * needs to know what else is in the repository is told by the framework, not by querying
 * behind its back.
 *
 * <p>Rules an implementation must follow:
 *
 * <ol>
 *   <li>Never read a file itself. Paths come from the runner, already checked by the sandbox
 *       and the read policy.</li>
 *   <li>Never throw on malformed input. Files that do not parse are ordinary input here:
 *       report nothing for them and move on. An analyzer that can fail a whole run because one
 *       file is unusual makes the run's result a lie about the other files.</li>
 *   <li>Treat a batch as a scheduling unit. Do not assume it is the whole revision, do not
 *       assume its files are related, and do not carry state from one call to the next.</li>
 *   <li>Always state the rule's limitations. Confidence without a stated boundary is how a
 *       heuristic gets read as a fact.</li>
 *   <li>Cite line numbers that exist, or cite none. The runner re-checks every citation
 *       against the file and records one outside the file as a rule defect.</li>
 * </ol>
 */
public interface SourceAnalyzer {

    /** Stable identifier stored on every finding and on the run. */
    String id();

    /**
     * Version of the rules, not of the framework. Stored on the run so a change in findings can
     * be attributed to a change in rules rather than in the code.
     */
    String version();

    /** Languages this analyzer can read. It is never handed anything else. */
    Set<DetectedLanguage> languages();

    /**
     * Analyzes a batch of files, all of which this analyzer claims to support.
     *
     * @param sources one or more files, in repository-path order
     * @return findings per path plus notes about the run; never {@code null}
     */
    AnalysisBatch analyze(List<AnalysisSource> sources);
}
