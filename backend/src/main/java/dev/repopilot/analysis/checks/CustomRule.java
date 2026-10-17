package dev.repopilot.analysis.checks;

import dev.repopilot.analysis.FindingCategory;
import dev.repopilot.analysis.FindingConfidence;
import dev.repopilot.analysis.FindingSeverity;

/**
 * The built-in rules.
 *
 * <p>Each constant carries its own claim boundary: what it costs if real, what to do, and
 * — the part most rule sets omit — what it cannot see. The limitations are not decoration.
 * They are what lets a reader decide whether a finding applies to their code instead of
 * trusting or ignoring the whole list.
 *
 * <h2>Why hand-write rules at all, when PMD is on the classpath</h2>
 * These rules exist for the cases where the cost of a mistake is highest and a generic
 * ruleset's wording is too vague to act on — a swallowed exception, a credential in a
 * literal, a non-thread-safe formatter in a field. PMD runs alongside them and is reported
 * separately, so the two families of claims stay distinguishable by {@code analyzerId}.
 *
 * <h2>Severity and confidence are chosen per rule, not per pattern</h2>
 * A syntax match is {@link FindingConfidence#CERTAIN} about the pattern's presence and
 * nothing at all about whether it is wrong here; a name-based match is {@link
 * FindingConfidence#MEDIUM} for the same reason. Severity reflects the cost when the claim
 * turns out to be true.
 */
public enum CustomRule {

    /** A {@code catch} block with no statements: the failure is discarded, and the caller is told it succeeded. */
    EMPTY_CATCH_BLOCK(
            "RP001",
            "EMPTY_CATCH_BLOCK",
            "Empty catch block",
            FindingSeverity.MEDIUM,
            FindingConfidence.CERTAIN,
            FindingCategory.CORRECTNESS,
            "The failure is silently absorbed. Execution continues on state that was never updated, and the caller "
                    + "sees success, so the first visible symptom appears far from the cause.",
            "Handle the exception, rethrow it wrapped with context, or log it together with the caught exception. "
                    + "If the failure is genuinely ignorable, say so in a comment and name the variable to show intent.",
            "Matches a textually empty block, including one that contains only comments. A block that calls a no-op "
                    + "helper is not matched, and a single statement that discards the exception silently is not "
                    + "an empty block — see RP002 for that case. A caught parameter named ignored or expected is "
                    + "treated as a deliberate decision and is skipped."),

    /** A {@code catch} block with a body that never reads the caught exception and never rethrows. */
    SWALLOWED_EXCEPTION(
            "RP002",
            "SWALLOWED_EXCEPTION",
            "Caught exception is ignored",
            FindingSeverity.MEDIUM,
            FindingConfidence.HIGH,
            FindingCategory.CORRECTNESS,
            "The handler runs but the failure's cause is dropped. Diagnosis then depends on reproducing the failure, "
                    + "because the information needed to explain it was discarded at the point it was available.",
            "Log the caught exception, rethrow it, or add it as the cause of a new exception. Returning a default "
                    + "value is defensible, but only with the cause recorded alongside it.",
            "Does not follow data flow into called methods, so a handler that passes the exception to a helper is "
                    + "reported only if it names the variable directly. Cannot tell whether the caller records the "
                    + "failure elsewhere. A caught parameter named ignored or expected is treated as a deliberate "
                    + "decision and is skipped."),

    /** {@code printStackTrace()} sends the trace to {@code stderr} instead of the logging pipeline. */
    PRINT_STACK_TRACE(
            "RP003",
            "PRINT_STACK_TRACE",
            "Exception printed to stderr",
            FindingSeverity.LOW,
            FindingConfidence.CERTAIN,
            FindingCategory.MAINTAINABILITY,
            "The trace bypasses the logging framework: it is missing from structured logs, carries no request or "
                    + "trace identifier, ignores the configured level, and is usually written to a stream no one "
                    + "collects.",
            "Replace with a logger call that passes the exception as an argument, so the stack trace is formatted by "
                    + "the logging backend and correlated with the surrounding context.",
            "Reports the call site only. It cannot see where the process's standard error is redirected, so on a "
                    + "developer workstation the practical difference may be small."),

    /** A direct write to {@code System.out} or {@code System.err}. */
    CONSOLE_OUTPUT(
            "RP004",
            "CONSOLE_OUTPUT",
            "Direct console output",
            FindingSeverity.LOW,
            FindingConfidence.CERTAIN,
            FindingCategory.STYLE,
            "Output goes to a shared, unsynchronised, untimestamped stream at a level no one can configure. In a "
                    + "service it interleaves with other writers, and in a library it pollutes the host's output.",
            "Use the logging framework, or a returned value if the output was standing in for one.",
            "Cannot distinguish a deliberate command-line tool from a stray debug statement, because the rule reads "
                    + "one file and does not know how the class is used."),

    /** A string literal assigned to a name that reads as a credential. */
    HARDCODED_CREDENTIAL(
            "RP005",
            "HARDCODED_CREDENTIAL",
            "Credential in a string literal",
            FindingSeverity.HIGH,
            FindingConfidence.MEDIUM,
            FindingCategory.SECURITY,
            "A credential committed to source is in every clone, every developer machine, every backup and every "
                    + "review of the repository. It cannot be rotated by changing configuration, and removing it "
                    + "later does not remove it from history.",
            "Move the value to configuration or a secret store and inject it. Rotate the value, because it must be "
                    + "assumed compromised the moment it was committed.",
            "Name-based: it matches a credential-looking identifier initialised with a literal, and cannot see secrets "
                    + "held in configuration files, generated code, or a differently named variable. Placeholder values "
                    + "and template expressions are skipped, so test fixtures may still be reported. The finding never "
                    + "records the value itself."),

    /** {@code sleep} called inside a loop body. */
    SLEEP_IN_LOOP(
            "RP006",
            "SLEEP_IN_LOOP",
            "Sleep inside a loop",
            FindingSeverity.MEDIUM,
            FindingConfidence.HIGH,
            FindingCategory.PERFORMANCE,
            "Each iteration holds its thread for the whole sleep. In request-handling code this consumes a worker for "
                    + "the duration, and in a retry loop the total wait grows with the number of attempts.",
            "Use a scheduled executor, a bounded backoff with an interruptible wait, or an event-driven retry, so the "
                    + "thread is not held while waiting.",
            "Matches any call named sleep — {@code Thread.sleep(millis)} above all — inside a syntactic loop, "
                    + "including legitimate bounded polling in tests and background tasks. It does not identify "
                    + "loops that run once, and it treats a sleep inside a lambda written in a loop body as inside "
                    + "the loop even when the lambda is handed to an asynchronous executor."),

    /** A {@code SimpleDateFormat} held in a field, where two threads can format at once. */
    SHARED_DATE_FORMAT(
            "RP007",
            "SHARED_DATE_FORMAT",
            "Non-thread-safe formatter in a field",
            FindingSeverity.MEDIUM,
            FindingConfidence.HIGH,
            FindingCategory.CONCURRENCY,
            "SimpleDateFormat keeps its partial parse and format state in the instance. Two threads using one field at "
                    + "the same time produce corrupted output, wrong dates, or a thrown exception, and the failure is "
                    + "intermittent and load-dependent.",
            "Use DateTimeFormatter, which is immutable and thread-safe. If the legacy API is required, create the "
                    + "formatter per call or guard it with its own lock.",
            "Reports the declaration. It does not prove the field is reachable from more than one thread, and it does "
                    + "not cover other mutable non-thread-safe types held in fields."),

    /** A handler that catches {@code Throwable}, and therefore {@code Error} as well. */
    CATCHES_THROWABLE(
            "RP008",
            "CATCHES_THROWABLE",
            "Catches Throwable",
            FindingSeverity.MEDIUM,
            FindingConfidence.HIGH,
            FindingCategory.CORRECTNESS,
            "Throwable includes Error: out-of-memory, stack overflow, and linkage failures. These signal that the "
                    + "process is no longer reliable, and a handler that absorbs them converts an unrecoverable "
                    + "condition into continued execution on an unknown JVM state.",
            "Catch the specific exceptions that can actually occur. If a last-resort boundary is needed, catch "
                    + "Exception, record the failure, and rethrow the Error.",
            "Reports the clause. Whether the handler rethrows is considered only for the ignored-exception rule, so a "
                    + "catch-and-rethrow of Throwable is still reported as a style issue.");

    private final String id;
    private final String findingType;
    private final String title;
    private final FindingSeverity severity;
    private final FindingConfidence confidence;
    private final FindingCategory category;
    private final String impact;
    private final String recommendedAction;
    private final String limitations;

    CustomRule(String id,
               String findingType,
               String title,
               FindingSeverity severity,
               FindingConfidence confidence,
               FindingCategory category,
               String impact,
               String recommendedAction,
               String limitations) {
        this.id = id;
        this.findingType = findingType;
        this.title = title;
        this.severity = severity;
        this.confidence = confidence;
        this.category = category;
        this.impact = impact;
        this.recommendedAction = recommendedAction;
        this.limitations = limitations;
    }

    public String id() {
        return id;
    }

    public String findingType() {
        return findingType;
    }

    public String title() {
        return title;
    }

    public FindingSeverity severity() {
        return severity;
    }

    public FindingConfidence confidence() {
        return confidence;
    }

    public FindingCategory category() {
        return category;
    }

    public String impact() {
        return impact;
    }

    public String recommendedAction() {
        return recommendedAction;
    }

    public String limitations() {
        return limitations;
    }
}
