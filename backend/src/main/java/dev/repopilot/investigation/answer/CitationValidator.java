package dev.repopilot.investigation.answer;

import dev.repopilot.investigation.ClaimKind;
import dev.repopilot.investigation.ClaimVerification;
import dev.repopilot.investigation.EvidenceItem;
import dev.repopilot.investigation.SourceStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides what an answer is worth, claim by claim, and never edits it.
 *
 * <h2>The four checks</h2>
 * <ol>
 *   <li><b>The keys exist.</b> A claim citing {@code E9} when the prompt had eight items is
 *       unsupported, and the note names the key.</li>
 *   <li><b>Something is cited at all.</b> A claim with no evidence is unsupported. This is the
 *       rule that stops a fluent paragraph from borrowing authority from the evidence beside it.</li>
 *   <li><b>The citation still resolves.</b> A cited excerpt that no longer hashes to what was
 *       sent — or whose file has gone — makes the claim partial, whatever else it cites.</li>
 *   <li><b>A named location is covered.</b> A sentence that says "see Foo.java:88" must cite
 *       evidence that actually covers line 88; otherwise it is a location the evidence does not
 *       support, and it is marked partial with that location quoted back.</li>
 * </ol>
 *
 * <p>{@code grounded} is true only when there is at least one claim, every claim is
 * {@link ClaimVerification#VERIFIED}, and no marker in the answer text refers to a key that was
 * not sent. A model that answers a question the evidence cannot answer therefore produces a
 * successful, readable, ungrounded answer — which is the outcome the user needs to see.
 */
public final class CitationValidator {

    /** {@code Foo.java:41} or {@code src/main/java/Foo.java:41}. */
    private static final Pattern LOCATION =
            Pattern.compile("([A-Za-z0-9_][\\w./\\\\-]*\\.(?:java|kt|groovy)):(\\d+)");
    private static final Pattern MARKER = Pattern.compile("\\[(E\\d+)]");
    private static final int MAX_NOTES_CHARS = 2_000;

    private CitationValidator() {
    }

    /** One claim with the verdict and the sentence that explains it. */
    public record ValidatedClaim(int index, String text, ClaimKind kind, List<String> citedKeys,
                                 ClaimVerification status, String notes) {

        public ValidatedClaim {
            citedKeys = List.copyOf(citedKeys);
        }
    }

    /** The whole verdict. */
    public record ValidationResult(List<ValidatedClaim> claims, boolean grounded,
                                   List<String> danglingMarkers, List<String> notes) {

        public ValidationResult {
            claims = List.copyOf(claims);
            danglingMarkers = List.copyOf(danglingMarkers);
            notes = List.copyOf(notes);
        }

        public int count(ClaimVerification status) {
            return (int) claims.stream().filter(claim -> claim.status() == status).count();
        }

        /** All keys that any claim cited, including ones that do not resolve. */
        public Set<String> citedKeys() {
            Set<String> keys = new LinkedHashSet<>();
            for (ValidatedClaim claim : claims) {
                for (String key : claim.citedKeys()) {
                    keys.add(key);
                }
            }
            return keys;
        }
    }

    public static ValidationResult validate(InvestigationAnswer answer,
                                            List<EvidenceItem> items,
                                            Map<String, SourceStatus> sourceStatus) {
        Map<String, EvidenceItem> byKey = new java.util.LinkedHashMap<>();
        for (EvidenceItem item : items) {
            byKey.put(item.key(), item);
        }

        List<ValidatedClaim> validated = new ArrayList<>();
        int index = 0;
        for (InvestigationAnswer.AnswerClaim claim : answer.claims()) {
            validated.add(validateClaim(index++, claim, byKey, sourceStatus));
        }

        List<String> dangling = new ArrayList<>();
        Matcher markers = MARKER.matcher(answer.answer() == null ? "" : answer.answer());
        while (markers.find()) {
            if (!byKey.containsKey(markers.group(1)) && !dangling.contains(markers.group(1))) {
                dangling.add(markers.group(1));
            }
        }

        List<String> notes = new ArrayList<>();
        if (validated.isEmpty()) {
            notes.add("The answer contained no claims, so nothing could be checked against the evidence.");
        }
        if (!dangling.isEmpty()) {
            notes.add("The answer text cites " + String.join(", ", dangling)
                    + ", which was not in the prompt.");
        }

        boolean grounded = !validated.isEmpty()
                && validated.stream().allMatch(claim -> claim.status() == ClaimVerification.VERIFIED)
                && dangling.isEmpty();
        return new ValidationResult(validated, grounded, dangling, notes);
    }

    private static ValidatedClaim validateClaim(int index, InvestigationAnswer.AnswerClaim claim,
                                                Map<String, EvidenceItem> byKey,
                                                Map<String, SourceStatus> sourceStatus) {
        if (claim.evidence().isEmpty()) {
            return new ValidatedClaim(index, claim.text(), claim.kind(), List.of(),
                    ClaimVerification.UNSUPPORTED,
                    "The claim cites no evidence, so nothing supports it.");
        }

        List<String> unknown = claim.evidence().stream().filter(key -> !byKey.containsKey(key)).toList();
        if (!unknown.isEmpty()) {
            return new ValidatedClaim(index, claim.text(), claim.kind(), claim.evidence(),
                    ClaimVerification.UNSUPPORTED,
                    "The claim cites " + String.join(", ", unknown)
                            + ", which " + (unknown.size() == 1 ? "was" : "were")
                            + " not in the prompt.");
        }

        List<String> problems = new ArrayList<>();
        SourceStatus worst = SourceStatus.CONFIRMED;
        for (String key : claim.evidence()) {
            SourceStatus status = sourceStatus.getOrDefault(key, SourceStatus.NOT_LOCATED);
            if (status == SourceStatus.MISSING) {
                worst = SourceStatus.MISSING;
                problems.add("the source cited by " + key + " can no longer be read");
            } else if (status == SourceStatus.CHANGED && worst != SourceStatus.MISSING) {
                worst = SourceStatus.CHANGED;
                problems.add("the excerpt cited by " + key + " no longer matches the file");
            }
        }

        Matcher locations = LOCATION.matcher(claim.text());
        while (locations.find()) {
            String path = locations.group(1).replace('\\', '/');
            int line = Integer.parseInt(locations.group(2));
            if (!covered(claim.evidence(), byKey, path, line)) {
                problems.add("the claim names " + path + ":" + line
                        + ", which its citations do not cover");
            }
        }

        if (problems.isEmpty()) {
            return new ValidatedClaim(index, claim.text(), claim.kind(), claim.evidence(),
                    ClaimVerification.VERIFIED, verifiedNote(claim, byKey, sourceStatus));
        }
        return new ValidatedClaim(index, claim.text(), claim.kind(), claim.evidence(),
                ClaimVerification.PARTIAL, truncate(String.join("; ", problems), MAX_NOTES_CHARS));
    }

    private static String verifiedNote(InvestigationAnswer.AnswerClaim claim,
                                       Map<String, EvidenceItem> byKey,
                                       Map<String, SourceStatus> sourceStatus) {
        boolean anyLocated = claim.evidence().stream()
                .map(byKey::get)
                .anyMatch(item -> item != null && item.hasLocation());
        if (!anyLocated) {
            return "The cited evidence has no file location to re-read.";
        }
        boolean anyConfirmed = claim.evidence().stream()
                .allMatch(key -> sourceStatus.getOrDefault(key, SourceStatus.NOT_LOCATED)
                        != SourceStatus.CHANGED);
        return anyConfirmed
                ? "Every cited key was in the prompt and each cited excerpt was re-read from disk "
                  + "and matched."
                : "The citation resolves, but no cited excerpt could be re-read.";
    }

    private static boolean covered(List<String> keys, Map<String, EvidenceItem> byKey,
                                   String path, int line) {
        for (String key : keys) {
            EvidenceItem item = byKey.get(key);
            if (item == null || item.path() == null) {
                continue;
            }
            String evidencePath = item.path().replace('\\', '/');
            if (!evidencePath.equals(path) && !evidencePath.endsWith("/" + path)) {
                continue;
            }
            int start = item.startLine() == null ? 1 : item.startLine();
            int end = item.endLine() == null ? start : item.endLine();
            if (line >= start && line <= end) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
