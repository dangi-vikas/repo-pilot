package dev.repopilot.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * What an analyzer reports, before any of it is persisted.
 *
 * <p>An analyzer produces findings; it does not write rows, and it is never trusted to
 * describe its own provenance. The analyzer id, version, evidence category and
 * verification status are supplied by the framework around it, so a check cannot claim
 * to be a runtime measurement or mark its own citation as confirmed.
 *
 * @param analyzerId         which analyzer produced this, e.g. {@code custom-checks}
 * @param analyzerVersion    the analyzer's version, stored on the run
 * @param ruleId             the rule that fired, e.g. {@code RP001}
 * @param findingType        stable machine identifier of the kind of problem
 * @param title              one line, shown as the finding's heading
 * @param summary            what was found, in the code that was read
 * @param severity           how much it matters if real
 * @param confidence         how sure the analyzer is of the claim, not of the impact
 * @param category           correctness, security, performance, ...
 * @param relativePath       repository-relative path of the file the finding is in
 * @param startLine          1-based line, or {@code null} for a file-level claim
 * @param endLine            1-based last line, or {@code null}
 * @param impact             what goes wrong if it is real
 * @param recommendedAction  what to do about it
 * @param limitations        what this rule cannot see. Required to be non-blank so every
 *                           finding carries the boundary of its own claim.
 */
public record StaticFinding(
        String analyzerId,
        String analyzerVersion,
        String ruleId,
        String findingType,
        String title,
        String summary,
        FindingSeverity severity,
        FindingConfidence confidence,
        FindingCategory category,
        String relativePath,
        Integer startLine,
        Integer endLine,
        String impact,
        String recommendedAction,
        String limitations
) {

    public StaticFinding {
        requireText(analyzerId, "analyzerId");
        requireText(ruleId, "ruleId");
        requireText(findingType, "findingType");
        requireText(title, "title");
        requireText(relativePath, "relativePath");
        if (severity == null || confidence == null || category == null) {
            throw new IllegalArgumentException("severity, confidence and category are required");
        }
        if (limitations == null || limitations.isBlank()) {
            // Enforced rather than documented: a finding that does not state what its rule
            // cannot see is the kind of claim this product is built to avoid.
            throw new IllegalArgumentException("limitations is required for every finding");
        }
    }

    /**
     * Stable identity of this finding within a run: the same rule at the same file and
     * line is the same finding, however many times an analyzer walks the tree.
     *
     * <p>Used as the deduplication key and stored in {@code finding.fingerprint}, whose
     * unique constraint is {@code (analysis_run_id, fingerprint)}. Two rules firing on
     * the same line are two findings; the analyzer id and rule id are therefore part of
     * the hash.
     */
    public String fingerprint() {
        return sha256Hex(analyzerId + "|" + ruleId + "|" + relativePath + "|" + startLine + "|" + endLine);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JVM.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
