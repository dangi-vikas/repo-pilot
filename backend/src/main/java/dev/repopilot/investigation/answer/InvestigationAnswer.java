package dev.repopilot.investigation.answer;

import dev.repopilot.investigation.ClaimKind;

import java.util.List;

/**
 * The answer contract, as the model returned it.
 *
 * <p>Nothing here is trusted. The text is kept verbatim so the user can read what the model
 * actually said; the claim list is what the backend checks. An answer with no claims is a
 * valid parse and an ungrounded result, which is a materially different thing from a parse
 * failure — the first is "the model answered but cited nothing", the second is "the model did
 * not follow the contract".
 */
public record InvestigationAnswer(
        String answer,
        List<AnswerClaim> claims,
        String confidence,
        List<String> unanswered,
        String limitations
) {

    public InvestigationAnswer {
        claims = List.copyOf(claims);
        unanswered = List.copyOf(unanswered);
    }

    /** One statement, with the keys the model says support it. */
    public record AnswerClaim(String text, ClaimKind kind, List<String> evidence) {

        public AnswerClaim {
            evidence = List.copyOf(evidence);
        }
    }
}
