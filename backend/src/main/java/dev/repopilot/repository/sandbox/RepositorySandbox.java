package dev.repopilot.repository.sandbox;

import dev.repopilot.config.RepoPilotProperties;
import dev.repopilot.config.StorageInitializer;
import dev.repopilot.shared.error.RepoPilotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The repository filesystem boundary.
 *
 * <h2>Why a dedicated component</h2>
 * RepoPilot reads arbitrary directories chosen by a user and then serves their
 * contents back over HTTP. That makes every path that reaches the filesystem a
 * potential directory-traversal or symlink-escape primitive. Centralising path
 * validation in one audited class is the only way to reason about it; scattering
 * {@code resolve()} calls across services guarantees a missed case.
 *
 * <h2>Rules enforced</h2>
 * <ol>
 *   <li><b>Allow-list roots.</b> A repository may only live under a configured
 *       workspace root. Nothing outside is readable, ever.</li>
 *   <li><b>Real-path containment.</b> Validation is performed on
 *       {@link Path#toRealPath} output, i.e. after symlinks are resolved. A symlink
 *       inside the workspace pointing at {@code /etc} resolves outside the root and
 *       is rejected. Checking the un-resolved path instead is the classic bypass.</li>
 *   <li><b>Relative paths for reads.</b> API callers identify files by
 *       repository-relative path. Absolute paths, drive letters, {@code ..}
 *       segments and NUL bytes are rejected before touching the filesystem.</li>
 *   <li><b>Re-validation on every read.</b> The recorded canonical root is re-checked
 *       per request rather than trusted, so a root that is later replaced by a
 *       symlink cannot widen access for an already-registered repository.</li>
 *   <li><b>Self-exclusion.</b> RepoPilot refuses to import its own data directory or
 *       any directory containing it, so analysis artefacts can never be indexed as
 *       source or re-served as repository content.</li>
 * </ol>
 *
 * <h2>Known limitations (documented, not hidden)</h2>
 * <ul>
 *   <li>Containment is checked on canonical paths, which does not defend against a
 *       concurrent rename between validation and read (TOCTOU) on a filesystem the
 *       attacker can already write to. An attacker with that access already has
 *       equivalent capability.</li>
 *   <li>On Windows, junctions and reparse points are resolved by
 *       {@code toRealPath}; permission semantics differ from POSIX and are not
 *       modelled here.</li>
 *   <li>Case-insensitive filesystems mean containment comparisons could accept a
 *       differently-cased sibling on some platforms. Paths are compared using
 *       {@link Path#startsWith}, which is provider-aware.</li>
 * </ul>
 */
@Component
public class RepositorySandbox {

    private static final Logger log = LoggerFactory.getLogger(RepositorySandbox.class);

    /** Characters that must never appear in a caller-supplied path. */
    private static final String NUL = "\0";
    private static final int MAX_PATH_LENGTH = 4096;

    private final List<Path> allowedRoots;
    private final Path dataDirectory;

    public RepositorySandbox(RepoPilotProperties properties, StorageInitializer storage) {
        this.dataDirectory = storage.dataDirectory();
        this.allowedRoots = resolveAllowedRoots(properties);
        log.info("repository sandbox roots: {}", allowedRoots);
    }

    /** The allow-listed roots, as canonical absolute paths. */
    public List<Path> allowedRoots() {
        return allowedRoots;
    }

    /**
     * Validates an import target and returns its canonical path plus a display form.
     *
     * @param userSuppliedPath absolute path, or a path relative to the first allowed root
     * @throws RepoPilotException with {@code PATH_REJECTED} or {@code VALIDATION_FAILED}
     */
    public ImportTarget validateImportTarget(String userSuppliedPath) {
        Path candidate = toAbsoluteCandidatePath(userSuppliedPath, "repository path");

        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw RepoPilotException.validation(
                    "The directory does not exist: " + redactHome(candidate));
        }
        Path real = realPath(candidate);
        if (!Files.isDirectory(real)) {
            throw RepoPilotException.validation(
                    "The path is not a directory: " + redactHome(real));
        }
        if (!Files.isReadable(real)) {
            throw RepoPilotException.validation(
                    "The directory is not readable by this process: " + redactHome(real));
        }

        requireInsideAllowedRoot(real);
        requireNotRepoPilotData(real);

        return new ImportTarget(real, displayPath(real));
    }

    /**
     * Confirms a previously-registered repository root is still valid and still
     * inside the sandbox. Called before every read so that a root which has been
     * replaced, deleted or symlinked away is caught at use time.
     */
    public Path requireValidRoot(String recordedCanonicalRoot) {
        Path real = realPath(Path.of(recordedCanonicalRoot));
        requireInsideAllowedRoot(real);
        requireNotRepoPilotData(real);
        if (!Files.isDirectory(real)) {
            throw RepoPilotException.pathRejected(
                    "The repository is no longer a readable directory. Re-import it if it was moved.");
        }
        return real;
    }

    /**
     * Resolves a repository-relative path to an absolute, contained, real path.
     *
     * @param repositoryRoot canonical root previously validated by {@link #requireValidRoot}
     * @param relativePath   caller-supplied path relative to the root, using {@code /} or {@code \}
     */
    public Path resolveForRead(Path repositoryRoot, String relativePath) {
        String segment = validateRelativePath(relativePath);

        Path resolved = repositoryRoot.resolve(segment).normalize();
        if (!resolved.startsWith(repositoryRoot)) {
            // Reached only if validation above were bypassed; kept as defence in depth
            // because this is the check that actually stops '..' traversal.
            throw RepoPilotException.pathRejected("Path escapes the repository root");
        }
        if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw RepoPilotException.notFound("File", segment);
        }

        // Resolve symlinks and re-check: a symlinked file inside the repository that
        // points outside would pass the string check above and must not be read.
        Path real = realPath(resolved);
        if (!real.startsWith(repositoryRoot)) {
            throw RepoPilotException.pathRejected(
                    "The selected path resolves outside the repository through a symbolic link. "
                            + "RepoPilot does not follow symlinks out of the repository.");
        }
        if (!Files.isRegularFile(real)) {
            throw RepoPilotException.validation("The path is not a regular file");
        }
        return real;
    }

    /**
     * Validates the syntactic shape of a caller-supplied relative path without
     * touching the filesystem. Exposed separately so tests and callers can assert
     * rejection reasons directly.
     */
    public static String validateRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw RepoPilotException.validation("A file path is required");
        }
        if (relativePath.contains(NUL)) {
            throw RepoPilotException.pathRejected("Path contains a NUL byte");
        }
        if (relativePath.length() > MAX_PATH_LENGTH) {
            throw RepoPilotException.pathRejected("Path exceeds the maximum supported length");
        }

        // Accept both separators so Windows clients work, then validate segment by
        // segment on a canonical separator.
        String normalised = relativePath.replace('\\', '/');

        if (normalised.startsWith("/")) {
            throw RepoPilotException.pathRejected("Absolute paths are not accepted; use a repository-relative path");
        }
        // 'C:/...' or 'C:...' style drive-qualified paths.
        if (normalised.length() >= 2 && normalised.charAt(1) == ':') {
            throw RepoPilotException.pathRejected("Drive-qualified paths are not accepted");
        }
        // UNC paths \\server\share
        if (relativePath.startsWith("\\\\")) {
            throw RepoPilotException.pathRejected("UNC paths are not accepted");
        }

        List<String> segments = new ArrayList<>();
        for (String raw : normalised.split("/")) {
            String segment = raw.strip();
            if (segment.isEmpty() || ".".equals(segment)) {
                continue; // tolerate './a/b' and 'a//b'
            }
            if ("..".equals(segment)) {
                throw RepoPilotException.pathRejected("Path traversal ('..') is not accepted");
            }
            if (segment.contains(":") || segment.contains("*") || segment.contains("?")
                    || segment.contains("\"") || segment.contains("<") || segment.contains(">")
                    || segment.contains("|")) {
                throw RepoPilotException.pathRejected("Path contains a character that is not permitted");
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            throw RepoPilotException.validation("A file path is required");
        }
        return String.join("/", segments);
    }

    /** A display path that never reveals the machine's directory layout. */
    public String displayPath(Path realPath) {
        for (Path root : allowedRoots) {
            if (realPath.startsWith(root)) {
                Path relative = root.relativize(realPath);
                if (relative.getNameCount() == 0) {
                    // The root itself (or a configured root nested under another root).
                    return root.getFileName() == null ? "workspace" : root.getFileName().toString();
                }
                return relative.toString().replace('\\', '/');
            }
        }
        // Defensive: validateImportTarget rejects anything outside the roots, so this
        // branch is unreachable in normal operation. Returning the final segment keeps
        // an unexpected path out of API responses rather than leaking it.
        return realPath.getFileName() == null ? "repository" : realPath.getFileName().toString();
    }

    /** Replaces the user's home directory prefix with '~' for human-facing messages. */
    public String redactHome(Path path) {
        String home = System.getProperty("user.home");
        String value = path.toString();
        if (home != null && !home.isBlank() && value.startsWith(home)) {
            return "~" + value.substring(home.length());
        }
        return value;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private Path toAbsoluteCandidatePath(String userSupplied, String what) {
        if (userSupplied == null || userSupplied.isBlank()) {
            throw RepoPilotException.validation("A " + what + " is required");
        }
        if (userSupplied.contains(NUL)) {
            throw RepoPilotException.pathRejected("The " + what + " contains a NUL byte");
        }
        if (userSupplied.length() > MAX_PATH_LENGTH) {
            throw RepoPilotException.pathRejected("The " + what + " exceeds the maximum supported length");
        }

        Path raw;
        try {
            raw = Path.of(userSupplied.strip());
        } catch (InvalidPathException e) {
            throw RepoPilotException.pathRejected("The " + what + " is not a valid filesystem path");
        }

        if (raw.isAbsolute()) {
            return raw.normalize();
        }
        // Relative input is interpreted against the primary workspace root, never
        // against the process working directory, which would be unpredictable.
        return allowedRoots.get(0).resolve(raw).normalize();
    }

    private void requireInsideAllowedRoot(Path realPath) {
        for (Path root : allowedRoots) {
            if (realPath.startsWith(root)) {
                return;
            }
        }
        throw RepoPilotException.pathRejected(
                "Repository paths must be inside a configured RepoPilot workspace root. "
                        + "Configure REPOPILOT_WORKSPACE_ROOTS to add another location. "
                        + "Allowed roots are kept server-side and are not disclosed by the API.");
    }

    private void requireNotRepoPilotData(Path realPath) {
        if (realPath.startsWith(dataDirectory) || dataDirectory.startsWith(realPath)) {
            throw RepoPilotException.pathRejected(
                    "The path overlaps the RepoPilot data directory. RepoPilot refuses to index its own "
                            + "artefacts or their parent directory.");
        }
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw RepoPilotException.validation(
                    "The path could not be resolved to a real location on disk: " + e.getMessage());
        }
    }

    private List<Path> resolveAllowedRoots(RepoPilotProperties properties) {
        List<String> configured = properties.workspace().roots().stream()
                .filter(root -> root != null && !root.isBlank())
                .toList();

        List<Path> roots = new ArrayList<>();
        if (!configured.isEmpty()) {
            for (String root : configured) {
                Path path = createIfMissing(Path.of(root.strip()), false);
                roots.add(path);
            }
            log.info("using {} explicitly configured workspace root(s)", roots.size());
            return List.copyOf(roots);
        }

        String configuredDefault = properties.workspace().defaultRootName();
        String defaultRootName = (configuredDefault == null || configuredDefault.isBlank())
                ? "repo-pilot-workspace" : configuredDefault.strip();
        Path home = Path.of(System.getProperty("user.home", "."));
        Path defaultRoot = home.resolve(defaultRootName);
        createIfMissing(defaultRoot, true);
        roots.add(realPath(defaultRoot));
        log.info("no REPOPILOT_WORKSPACE_ROOTS configured; using the managed workspace at {}", defaultRoot);
        return List.copyOf(roots);
    }

    private Path createIfMissing(Path path, boolean create) {
        Path absolute = path.isAbsolute() ? path : path.toAbsolutePath();
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) {
                throw new IllegalStateException(
                        "Configured workspace root does not exist: " + absolute
                                + ". Create it or remove it from REPOPILOT_WORKSPACE_ROOTS.");
            }
            try {
                Files.createDirectories(absolute);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Unable to create the managed workspace root at " + absolute, e);
            }
        }
        Path real = realPath(absolute);
        if (!Files.isDirectory(real)) {
            throw new IllegalStateException("Workspace root is not a directory: " + real);
        }
        if (!Files.isReadable(real)) {
            throw new IllegalStateException("Workspace root is not readable: " + real);
        }
        return real;
    }

    /** A validated import target: canonical server-side path plus a safe display form. */
    public record ImportTarget(Path canonicalPath, String displayPath) {
        @Override
        public String toString() {
            return displayPath;
        }
    }

    /** Normalises a repository name derived from a filesystem path. */
    public static String deriveRepositoryName(Path canonicalPath) {
        Path fileName = canonicalPath.getFileName();
        if (fileName == null) {
            return "repository";
        }
        String name = fileName.toString()
                .replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("(^-+)|(-+$)", "")
                .toLowerCase(Locale.ROOT);
        return name.isEmpty() ? "repository" : name;
    }
}
