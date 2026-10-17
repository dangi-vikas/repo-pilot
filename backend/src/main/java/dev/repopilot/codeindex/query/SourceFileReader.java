package dev.repopilot.codeindex.query;

import dev.repopilot.codeindex.persistence.SourceFile;
import dev.repopilot.codeindex.persistence.SourceFileRepository;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.sandbox.RepositorySandbox;
import dev.repopilot.shared.error.RepoPilotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Reads source content for the code viewer.
 *
 * <h2>Security</h2>
 * Content is only served for a path that is <em>recorded</em> in the requested
 * revision, and the resolved filesystem path is re-validated through the repository
 * sandbox on every read. A caller cannot ask for an arbitrary path: the path must
 * first exist as an indexed source file, and the sandbox then confirms the file still
 * resolves inside the permitted root. Both checks are required — the database check
 * stops enumeration of unmapped paths, the sandbox check stops traversal and symlink
 * escape.
 */
@Service
public class SourceFileReader {

    private static final Logger log = LoggerFactory.getLogger(SourceFileReader.class);

    /** Content served to the viewer is capped; larger files are returned truncated with a flag. */
    private static final long VIEWER_MAX_BYTES = 2L * 1024 * 1024;

    private final SourceFileRepository sourceFiles;
    private final RepositoryService repositories;
    private final RepositorySandbox sandbox;

    public SourceFileReader(SourceFileRepository sourceFiles,
                            RepositoryService repositories,
                            RepositorySandbox sandbox) {
        this.sourceFiles = sourceFiles;
        this.repositories = repositories;
        this.sandbox = sandbox;
    }

    @Transactional(readOnly = true)
    public SourceContentView read(UUID repositoryId, UUID revisionId, String relativePath) {
        String canonicalRelative = RepositorySandbox.validateRelativePath(relativePath);

        SourceFile recorded = sourceFiles.findByRevisionIdAndRelativePath(revisionId, canonicalRelative)
                .orElseThrow(() -> RepoPilotException.notFound(
                        "Indexed file '" + canonicalRelative + "'", revisionId));

        if (recorded.getSizeBytes() > VIEWER_MAX_BYTES) {
            log.debug("file {} exceeds the viewer limit; returning truncated content", canonicalRelative);
        }

        Path root = repositories.resolveRoot(repositoryId);
        Path resolved = sandbox.resolveForRead(root, canonicalRelative);

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(resolved);
        } catch (IOException e) {
            throw new RepoPilotException(dev.repopilot.shared.error.ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "The file could not be read from disk. It may have been removed since indexing.");
        }

        boolean truncated = bytes.length > VIEWER_MAX_BYTES;
        String content = new String(truncated ? java.util.Arrays.copyOf(bytes, (int) VIEWER_MAX_BYTES) : bytes,
                StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        return new SourceContentView(canonicalRelative, recorded.getLanguage(),
                countLines(content), content, truncated);
    }

    private static int countLines(String content) {
        if (content.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
