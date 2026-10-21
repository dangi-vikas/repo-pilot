package dev.repopilot.investigation;

/**
 * One piece of evidence, as handed to the model and as stored for the reader.
 *
 * <p>The excerpt is the only field that contains source text. It is bounded before it is
 * put in a prompt, and only its hash is persisted — the text lives in the prompt and in the
 * file, never in a database column, so this table cannot become a prompt log.
 *
 * @param key             citation handle, {@code E1}… in final rank order
 * @param kind            what this is
 * @param label           one-line description shown in the prompt and the UI
 * @param detail          metadata the evidence itself states (a rule id, a metric value);
 *                        never source text
 * @param path            repository-relative path, or null when there is no location
 * @param startLine       1-based first line of the excerpt, or null
 * @param endLine         1-based last line of the excerpt, or null
 * @param symbolName      qualified symbol name, when the evidence is a symbol
 * @param symbolKind      symbol kind as the index recorded it
 * @param excerpt         the exact lines shown to the model, or null
 * @param retrievalScore  rank input, documented per method
 * @param retrievalMethod how it was found, e.g. {@code symbol-name}, {@code finding-text}
 */
public record EvidenceItem(
        String key,
        EvidenceKind kind,
        String label,
        String detail,
        String path,
        Integer startLine,
        Integer endLine,
        String symbolName,
        String symbolKind,
        String excerpt,
        double retrievalScore,
        String retrievalMethod
) {

    /** SHA-256 of the excerpt, or null when there is none. Security rule: the text is not stored. */
    public String excerptSha256() {
        return excerpt == null ? null : Hashing.sha256(excerpt);
    }

    public boolean hasLocation() {
        return path != null && startLine != null && endLine != null;
    }

    /** The same item with its citation handle assigned. */
    public EvidenceItem withKey(String newKey) {
        return new EvidenceItem(newKey, kind, label, detail, path, startLine, endLine,
                symbolName, symbolKind, excerpt, retrievalScore, retrievalMethod);
    }

    /** The same item without its excerpt, for storage and for views. */
    public EvidenceItem withoutExcerpt() {
        return new EvidenceItem(key, kind, label, detail, path, startLine, endLine,
                symbolName, symbolKind, null, retrievalScore, retrievalMethod);
    }
}
