package dev.repopilot.investigation.answer;

import dev.repopilot.investigation.EvidenceItem;
import dev.repopilot.investigation.Hashing;
import dev.repopilot.investigation.SourceExcerptReader;
import dev.repopilot.investigation.SourceStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Re-reads, from disk, every excerpt that was sent to the model.
 *
 * <p>This is the check that makes a citation mean something. The evidence table stores the
 * SHA-256 of the exact lines that went into the prompt, not the lines themselves; this service
 * reads the same range through the sandbox and compares. A match is
 * {@link SourceStatus#CONFIRMED}, a mismatch is {@link SourceStatus#CHANGED} — the file moved
 * under the answer, and the user is told rather than the mismatch being smoothed over.
 *
 * <p>Evidence with no location (a repository summary, a runtime run summary) is
 * {@link SourceStatus#NOT_LOCATED}. That is not a failure and is not reported as one: there is
 * simply nothing to re-read.
 */
@Service
public class SourceVerifier {

    private final SourceExcerptReader excerpts;

    public SourceVerifier(SourceExcerptReader excerpts) {
        this.excerpts = excerpts;
    }

    /** @return source status per evidence key, for the keys that have one */
    public Map<String, SourceStatus> verify(UUID repositoryId, UUID revisionId, List<EvidenceItem> items) {
        Map<String, SourceStatus> statuses = new LinkedHashMap<>();
        for (EvidenceItem item : items) {
            statuses.put(item.key(), verifyOne(repositoryId, revisionId, item));
        }
        return statuses;
    }

    private SourceStatus verifyOne(UUID repositoryId, UUID revisionId, EvidenceItem item) {
        if (item.excerpt() == null || item.excerpt().isEmpty()) {
            // Nothing was quoted, so there is no claim about those lines to check.
            return SourceStatus.NOT_LOCATED;
        }
        if (!item.hasLocation()) {
            return SourceStatus.NOT_LOCATED;
        }
        int lines = item.endLine() - item.startLine() + 1;
        SourceExcerptReader.Excerpt again = excerpts.read(repositoryId, revisionId,
                item.path(), item.startLine(), lines);
        if (again == null) {
            return SourceStatus.MISSING;
        }
        return Hashing.sha256(again.text()).equals(Hashing.sha256(item.excerpt()))
                ? SourceStatus.CONFIRMED
                : SourceStatus.CHANGED;
    }
}
