package dev.repopilot.codeindex.query;

import dev.repopilot.repository.scan.DetectedLanguage;

/**
 * Source content for the code viewer.
 *
 * @param relativePath repository-relative path
 * @param language     detected language
 * @param lineCount    number of lines in the returned content
 * @param content      source text
 * @param truncated    true when the file exceeded the viewer limit and was cut
 */
public record SourceContentView(
        String relativePath,
        DetectedLanguage language,
        int lineCount,
        String content,
        boolean truncated
) {
}
