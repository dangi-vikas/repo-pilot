package dev.repopilot.repository.scan;

import java.nio.file.Path;

/**
 * A file selected for analysis.
 *
 * <p>{@link #absolutePath} is process-internal. This record is never serialised to a
 * client; API responses carry {@link #relativePath} only.
 */
public record ScannedFile(
        String relativePath,
        Path absolutePath,
        long sizeBytes,
        DetectedLanguage language
) {
    public boolean isJava() {
        return language == DetectedLanguage.JAVA;
    }
}
