package dev.repopilot.investigation.prompt;

import dev.repopilot.investigation.EvidenceItem;

import java.util.List;

/**
 * The exact text handed to a model, with the accounting that makes it auditable.
 *
 * @param system       the standing instructions
 * @param user         the question and the numbered evidence
 * @param items        the evidence that was numbered in {@code user}, in order
 * @param charCount    total characters of both messages
 * @param contextChars characters of retrieved evidence, as opposed to instructions
 * @param sha256       SHA-256 of the prompt. Stored instead of the prompt: the prompt contains
 *                     source excerpts, and a stored prompt is a prompt log
 */
public record InvestigationPrompt(
        String system,
        String user,
        List<EvidenceItem> items,
        int charCount,
        int contextChars,
        String sha256
) {

    public InvestigationPrompt {
        items = List.copyOf(items);
    }
}
