package dev.repopilot.analysis;

import dev.repopilot.repository.scan.DetectedLanguage;

/**
 * One file handed to an analyzer: its repository-relative path, its language, and its
 * decoded text.
 *
 * <p>Content is passed in rather than the analyzer opening the file itself, because the
 * analysis boundary is a security boundary. The runner reads through the repository
 * sandbox and the read policy; an analyzer gets bytes that already passed those checks
 * and has no way to reach a path the scan refused. An analyzer that could open arbitrary
 * files would make "credentials are never opened" a statement about the indexer only.
 *
 * @param relativePath repository-relative path, used for identity and reporting
 * @param language     detected language; an analyzer is never handed one it cannot read
 * @param lineCount    number of lines in {@code content}, used to verify cited lines
 * @param content      decoded file text. Never logged and never persisted.
 */
public record AnalysisSource(String relativePath, DetectedLanguage language, int lineCount, String content) {

    public AnalysisSource {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath is required");
        }
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
    }
}
