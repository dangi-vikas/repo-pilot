package dev.repopilot.investigation;

import dev.repopilot.codeindex.persistence.SourceFile;
import dev.repopilot.codeindex.persistence.SourceFileRepository;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.sandbox.RepositorySandbox;
import dev.repopilot.shared.error.RepoPilotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Reads a bounded excerpt of one indexed file, for a prompt and for the check that follows it.
 *
 * <h2>Why not reuse the code viewer</h2>
 * {@link dev.repopilot.codeindex.query.SourceFileReader} serves up to two megabytes to a
 * human who asked for one file. A prompt has a budget and a dozen files, so this reader
 * takes a line window and stops. It applies the same two guards in the same order — the
 * path must be recorded in the revision, and the sandbox must still resolve it inside the
 * permitted root — because an investigation must not become the one place that reads a path
 * the index never recorded.
 *
 * <p>The excerpt is capped by lines <em>and</em> characters: a generated file with one
 * 400 KB line must not be able to fill the context window on its own.
 */
@Service
public class SourceExcerptReader {

    private static final Logger log = LoggerFactory.getLogger(SourceExcerptReader.class);

    /** Lines offered per evidence item. A method body is usually enough to see a shape. */
    public static final int MAX_LINES = 24;
    /** Characters offered per evidence item, after line capping. */
    public static final int MAX_CHARS = 1200;

    private final SourceFileRepository sourceFiles;
    private final RepositoryService repositories;
    private final RepositorySandbox sandbox;

    public SourceExcerptReader(SourceFileRepository sourceFiles,
                               RepositoryService repositories,
                               RepositorySandbox sandbox) {
        this.sourceFiles = sourceFiles;
        this.repositories = repositories;
        this.sandbox = sandbox;
    }

    /**
     * Reads at most {@code maxLines} lines starting at {@code startLine}.
     *
     * @return the excerpt, or null when the file is not recorded in this revision, no longer
     *         resolves inside the sandbox, or cannot be read
     */
    public Excerpt read(UUID repositoryId, UUID revisionId, String relativePath,
                        int startLine, int maxLines) {
        if (relativePath == null || startLine < 1) {
            return null;
        }
        int lineBudget = Math.max(1, Math.min(maxLines, MAX_LINES));

        String canonical;
        try {
            canonical = RepositorySandbox.validateRelativePath(relativePath);
        } catch (RepoPilotException e) {
            log.warn("refusing to read an evidence path the sandbox rejected: {}", e.getMessage());
            return null;
        }
        SourceFile recorded = sourceFiles.findByRevisionIdAndRelativePath(revisionId, canonical)
                .orElse(null);
        if (recorded == null) {
            log.warn("refusing to read '{}' as evidence: it is not a source file of revision {}",
                    canonical, revisionId);
            return null;
        }

        Path root = repositories.resolveRoot(repositoryId);
        Path resolved = sandbox.resolveForRead(root, canonical);

        List<String> lines;
        try (var stream = Files.lines(resolved, StandardCharsets.UTF_8)) {
            lines = stream.skip(startLine - 1L).limit(lineBudget).toList();
        } catch (IOException | java.io.UncheckedIOException e) {
            // UncheckedIOException is what a mid-stream decoding failure surfaces as.
            log.debug("excerpt of '{}:{}' could not be read: {}", canonical, startLine, e.getMessage());
            return null;
        }
        if (lines.isEmpty()) {
            return null;
        }

        StringBuilder text = new StringBuilder();
        int consumed = 0;
        for (String line : lines) {
            int projected = text.length() + line.length() + (consumed == 0 ? 0 : 1);
            if (consumed > 0 && projected > MAX_CHARS) {
                break;
            }
            if (consumed > 0) {
                text.append('\n');
            }
            text.append(line);
            consumed++;
            if (text.length() >= MAX_CHARS) {
                break;
            }
        }
        String excerpt = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text.toString();
        if (excerpt.isBlank()) {
            return null;
        }
        return new Excerpt(excerpt, startLine, startLine + consumed - 1);
    }

    /** A read excerpt and the line range it actually covers. */
    public record Excerpt(String text, int startLine, int endLine) {
    }
}
