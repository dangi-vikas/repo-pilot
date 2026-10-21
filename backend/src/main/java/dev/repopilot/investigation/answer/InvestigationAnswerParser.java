package dev.repopilot.investigation.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repopilot.investigation.ClaimKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the model's response into the answer contract, tolerating cosmetic deviations and
 * refusing to invent substance.
 *
 * <h2>What is tolerated</h2>
 * A fenced code block, prose before or after the object, an evidence key written as a
 * comma-separated string, a lowercase kind, a missing optional field. Models do these things,
 * and none of them changes what was claimed.
 *
 * <h2>What is not tolerated</h2>
 * A response that is not an object at all becomes a {@link ParsedAnswer} with
 * {@code answer == null}: the raw text is kept so the user can read it, and the result is
 * reported as unparseable rather than silently discarded. Unknown claim kinds become
 * {@link ClaimKind#INFERENCE} — the honest default — with a note. A blank claim is dropped
 * with a note rather than stored as an empty row.
 */
public final class InvestigationAnswerParser {

    private static final int MAX_ANSWER_CHARS = 20_000;
    private static final int MAX_CLAIM_CHARS = 4_000;
    private static final int MAX_CLAIMS = 60;

    private InvestigationAnswerParser() {
    }

    /**
     * @param answer null when the response did not contain a JSON object
     * @param raw    the response as received, bounded
     * @param notes  everything that was normalised or dropped, for the diagnostics block
     */
    public record ParsedAnswer(InvestigationAnswer answer, String raw, List<String> notes) {

        public ParsedAnswer {
            notes = List.copyOf(notes);
        }

        public boolean parsed() {
            return answer != null;
        }
    }

    public static ParsedAnswer parse(String content, ObjectMapper json) {
        List<String> notes = new ArrayList<>();
        String raw = content == null ? "" : content.strip();
        if (raw.startsWith("\uFEFF")) {
            raw = raw.substring(1).strip();
        }
        if (raw.isEmpty()) {
            return new ParsedAnswer(null, "", List.of("The model returned an empty response."));
        }

        String candidate = stripFences(raw, notes);
        JsonNode node = tryParse(candidate, json);
        if (node == null) {
            node = tryParse(extractObject(raw), json);
            if (node != null) {
                notes.add("The model wrote text outside the JSON object; that text was ignored.");
            }
        }
        if (node == null || !node.isObject()) {
            return new ParsedAnswer(null, truncate(raw, MAX_ANSWER_CHARS),
                    List.of("The model did not return the JSON object this build requires."));
        }

        String answerText = text(node, "answer");
        if (answerText == null || answerText.isBlank()) {
            notes.add("The answer field was missing or empty.");
            answerText = "";
        }

        List<InvestigationAnswer.AnswerClaim> claims = new ArrayList<>();
        JsonNode claimsNode = node.get("claims");
        if (claimsNode != null && claimsNode.isArray()) {
            for (JsonNode claimNode : claimsNode) {
                if (claims.size() >= MAX_CLAIMS) {
                    notes.add(MAX_CLAIMS + " claims kept; the model wrote more.");
                    break;
                }
                String text = text(claimNode, "text");
                if (text == null || text.isBlank()) {
                    notes.add("A claim with no text was dropped.");
                    continue;
                }
                ClaimKind kind = kind(text(claimNode, "kind"), notes);
                List<String> evidence = keys(claimNode.get("evidence"), notes);
                claims.add(new InvestigationAnswer.AnswerClaim(
                        truncate(text, MAX_CLAIM_CHARS), kind, evidence));
            }
        } else {
            notes.add("The response had no claims array, so nothing could be checked.");
        }

        String confidence = text(node, "confidence");
        if (confidence != null) {
            String normalised = confidence.trim().toUpperCase(Locale.ROOT);
            if (normalised.equals("LOW") || normalised.equals("MEDIUM") || normalised.equals("HIGH")) {
                confidence = normalised;
            } else {
                notes.add("The model's confidence label '" + truncate(confidence, 40)
                        + "' is not LOW, MEDIUM or HIGH; it was not stored.");
                confidence = null;
            }
        }

        InvestigationAnswer answer = new InvestigationAnswer(
                truncate(answerText, MAX_ANSWER_CHARS), claims, confidence,
                strings(node.get("unanswered")), text(node, "limitations"));
        return new ParsedAnswer(answer, truncate(raw, MAX_ANSWER_CHARS), notes);
    }

    private static String stripFences(String raw, List<String> notes) {
        if (!raw.startsWith("```")) {
            return raw;
        }
        int firstNewline = raw.indexOf('\n');
        int closing = raw.lastIndexOf("```");
        if (firstNewline > 0 && closing > firstNewline) {
            notes.add("The model wrapped its answer in a code fence; the fence was removed.");
            return raw.substring(firstNewline + 1, closing).strip();
        }
        return raw;
    }

    private static String extractObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : null;
    }

    private static JsonNode tryParse(String candidate, ObjectMapper json) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(candidate);
            return node != null && node.isObject() ? node : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static ClaimKind kind(String value, List<String> notes) {
        if (value == null) {
            notes.add("A claim had no kind; it was recorded as an inference.");
            return ClaimKind.INFERENCE;
        }
        try {
            return ClaimKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            notes.add("A claim's kind '" + truncate(value, 40)
                    + "' is not a known kind; it was recorded as an inference.");
            return ClaimKind.INFERENCE;
        }
    }

    private static List<String> keys(JsonNode node, List<String> notes) {
        List<String> keys = new ArrayList<>();
        if (node == null || node.isNull()) {
            return keys;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    keys.add(item.asText().trim());
                }
            }
        } else if (node.isTextual()) {
            notes.add("A claim's evidence was a string rather than an array; it was split on commas.");
            for (String part : node.asText().split(",")) {
                if (!part.isBlank()) {
                    keys.add(part.trim());
                }
            }
        }
        return List.copyOf(keys.stream().distinct().toList());
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    values.add(truncate(item.asText(), 500));
                }
            }
        }
        return List.copyOf(values);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
