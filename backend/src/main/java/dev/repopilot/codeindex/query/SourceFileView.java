package dev.repopilot.codeindex.query;

import dev.repopilot.codeindex.parse.ParseStatus;
import dev.repopilot.repository.scan.DetectedLanguage;

import java.util.UUID;

/** A source file as returned by the code explorer list. Never includes file content. */
public record SourceFileView(
        UUID id,
        String relativePath,
        DetectedLanguage language,
        long sizeBytes,
        Integer lineCount,
        ParseStatus parseStatus,
        String parseError,
        String packageName
) {
}
