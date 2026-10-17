package dev.repopilot.repository.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Enumerates a repository without reading file contents.
 *
 * <p>Properties that matter for an untrusted-input boundary:
 * <ul>
 *   <li>Symbolic links are never followed. They are the cheapest way to make a
 *       scanner read outside its sandbox.</li>
 *   <li>Permission and I/O errors are recorded per entry and never abort the scan.
 *       An unreadable subdirectory must not invalidate an entire repository.</li>
 *   <li>Hard limits (file count, total bytes, single file size, depth) stop the walk
 *       deterministically and are reported in the manifest.</li>
 *   <li>Secret-bearing files are identified by name and skipped before any stat or
 *       read, so their bytes never enter the process.</li>
 *   <li>The manifest reports unresolvable absolute paths only internally; the
 *       {@link ScannedFile#relativePath} is what the rest of the system uses.</li>
 * </ul>
 */
@Component
public class SourceFileEnumerator {

    private static final Logger log = LoggerFactory.getLogger(SourceFileEnumerator.class);

    /** Guards against pathologically deep trees; deeper paths are also inside the sandbox. */
    private static final int MAX_DEPTH = 40;

    /** Extensions that are treated as binary without opening the file. */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "svgz", "ico", "icns", "tiff",
            "jar", "war", "ear", "class", "aar", "apk", "dex",
            "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "tar", "zst",
            "exe", "dll", "so", "dylib", "o", "a", "lib", "bin", "dat", "dmg", "msi",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt",
            "mp3", "mp4", "avi", "mov", "mkv", "wav", "flac", "ogg", "webm",
            "woff", "woff2", "ttf", "otf", "eot",
            "db", "sqlite", "sqlite3", "mdb", "idx", "pack", "hprof", "jfr", "jks", "p12");

    private final FileExclusionPolicy policy;

    public SourceFileEnumerator(FileExclusionPolicy policy) {
        this.policy = policy;
    }

    /**
     * Walks {@code root} and produces a manifest.
     *
     * @param cancelled polled between entries so a cancelled indexing job stops promptly
     */
    public ScanManifest enumerate(Path root, BooleanSupplier cancelled) {
        var accumulator = new Accumulator();
        long maxFiles = policy.maxFilesPerRepository();
        long maxBytes = policy.maxRepositorySizeBytes();
        long maxFileSize = policy.maxFileSizeBytes();
        boolean followSymlinks = policy.followSymlinks();

        try {
            // An empty option set means symbolic links are not followed. That is the
            // behaviour RepoPilot requires, so no FileVisitOption.FOLLOW_LINKS is added.
            Files.walkFileTree(root, Set.of(), MAX_DEPTH, new EnumerationVisitor(
                    root, policy, accumulator, cancelled, followSymlinks,
                    maxFiles, maxBytes, maxFileSize));
        } catch (IOException e) {
            // A failure to even start the walk is fatal for the scan; individual entry
            // failures inside the visitor are already tolerated.
            throw new IllegalStateException("Unable to enumerate the repository at a permitted root: "
                    + e.getMessage(), e);
        } catch (CancellationException e) {
            // Cancellation is a normal outcome. The manifest built so far is returned so
            // the caller can report how far it got.
            accumulator.truncate("Indexing was cancelled");
        }

        boolean compiled = hasCompiledClasses(root);
        ScanManifest manifest = accumulator.build(root, compiled);
        log.info("enumerated {} entries under a permitted root: {} analysable file(s), {} skipped{}",
                manifest.enumeratedFiles(), manifest.analysable().size(), manifest.totalSkipped(),
                manifest.truncated() ? " (truncated: " + manifest.truncationReason() + ")" : "");
        return manifest;
    }

    /** Mutable accumulation state, kept out of the visitor to keep the visitor readable. */
    private static final class Accumulator {
        private final List<ScannedFile> analysable = new ArrayList<>();
        private final Map<SkipReason, Long> skipped = new EnumMap<>(SkipReason.class);
        private final Map<SkipReason, Long> prunedDirectories = new EnumMap<>(SkipReason.class);
        private long enumeratedFiles;
        private long enumeratedBytes;
        private boolean truncated;
        private String truncationReason;

        void count(SkipReason reason) {
            skipped.merge(reason, 1L, Long::sum);
        }

        void prune(SkipReason reason) {
            prunedDirectories.merge(reason, 1L, Long::sum);
        }

        void include(ScannedFile file) {
            analysable.add(file);
            enumeratedBytes += file.sizeBytes();
        }

        void truncate(String reason) {
            if (!truncated) {
                truncated = true;
                truncationReason = reason;
            }
        }

        boolean isTruncated() {
            return truncated;
        }

        ScanManifest build(Path root, boolean compiledClassesFound) {
            return new ScanManifest(root, List.copyOf(analysable), Map.copyOf(skipped),
                    Map.copyOf(prunedDirectories), enumeratedFiles, enumeratedBytes,
                    truncated, truncationReason, compiledClassesFound);
        }
    }

    private static final class EnumerationVisitor implements FileVisitor<Path> {

        private final Path root;
        private final FileExclusionPolicy policy;
        private final Accumulator accumulator;
        private final BooleanSupplier cancelled;
        private final boolean followSymlinks;
        private final long maxFiles;
        private final long maxBytes;
        private final long maxFileSize;

        private EnumerationVisitor(Path root, FileExclusionPolicy policy, Accumulator accumulator,
                                   BooleanSupplier cancelled, boolean followSymlinks,
                                   long maxFiles, long maxBytes, long maxFileSize) {
            this.root = root;
            this.policy = policy;
            this.accumulator = accumulator;
            this.cancelled = cancelled;
            this.followSymlinks = followSymlinks;
            this.maxFiles = maxFiles;
            this.maxBytes = maxBytes;
            this.maxFileSize = maxFileSize;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            checkCancelled();
            if (dir.equals(root)) {
                return FileVisitResult.CONTINUE;
            }
            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
            if (!followSymlinks && Files.isSymbolicLink(dir)) {
                // Never followed: a symlink is the cheapest sandbox escape. Counted as a
                // pruned directory rather than a skipped file, because its contents were
                // never inspected -- counting it as a file would overstate what was seen
                // and break the "inspected = read + skipped" partition.
                accumulator.prune(SkipReason.SYMLINK);
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (policyExcluded(name)) {
                // Directory pruning is reported separately from file skips: it hides an
                // unknown number of unseen files rather than a counted number of inspected ones.
                accumulator.prune(policy.directorySkipReason(name));
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            checkCancelled();
            accumulator.enumeratedFiles++;

            if (accumulator.enumeratedFiles > maxFiles) {
                accumulator.truncate("Repository file limit of " + maxFiles
                        + " entries was reached. Raise repopilot.workspace.max-files-per-repository to scan more.");
                return FileVisitResult.TERMINATE;
            }
            if (accumulator.enumeratedBytes > maxBytes) {
                accumulator.truncate("Repository size limit of " + maxBytes
                        + " bytes was reached. Raise repopilot.workspace.max-repository-size-bytes to scan more.");
                return FileVisitResult.TERMINATE;
            }

            String relativePath = toRelative(file);
            String fileName = file.getFileName() == null ? relativePath : file.getFileName().toString();

            // Checked before any stat or read: credential material must never be opened.
            if (policy.isSecretBearing(fileName, relativePath)) {
                accumulator.count(SkipReason.SECRET_BEARING);
                return FileVisitResult.CONTINUE;
            }
            if (attrs.isSymbolicLink() || Files.isSymbolicLink(file)) {
                accumulator.count(SkipReason.SYMLINK);
                return FileVisitResult.CONTINUE;
            }
            if (policy.isGeneratedPath(relativePath)) {
                accumulator.count(SkipReason.GENERATED);
                return FileVisitResult.CONTINUE;
            }

            long size = attrs.size();
            if (size > maxFileSize) {
                accumulator.count(SkipReason.SIZE_LIMIT);
                return FileVisitResult.CONTINUE;
            }

            DetectedLanguage language = DetectedLanguage.fromFileName(relativePath);
            if (language == DetectedLanguage.UNKNOWN) {
                if (looksBinary(fileName)) {
                    accumulator.count(SkipReason.BINARY);
                } else {
                    accumulator.count(SkipReason.UNSUPPORTED_LANGUAGE);
                }
                return FileVisitResult.CONTINUE;
            }
            if (!language.requiresRead()) {
                accumulator.count(SkipReason.UNSUPPORTED_LANGUAGE);
                return FileVisitResult.CONTINUE;
            }

            accumulator.include(new ScannedFile(relativePath, file, size, language));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) {
            // A single unreadable file or directory must not fail the scan. Reported as a
            // skip so the user can see that coverage was incomplete.
            accumulator.enumeratedFiles++;
            accumulator.count(SkipReason.UNREADABLE);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exception) {
            if (exception != null) {
                accumulator.count(SkipReason.UNREADABLE);
            }
            return FileVisitResult.CONTINUE;
        }

        private void checkCancelled() {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Indexing cancelled");
            }
        }

        private boolean policyExcluded(String directoryName) {
            return policy.isExcludedDirectory(directoryName);
        }

        private String toRelative(Path file) {
            return root.relativize(file).toString().replace('\\', '/');
        }

        private boolean looksBinary(String fileName) {
            int dot = fileName.lastIndexOf('.');
            if (dot < 0 || dot == fileName.length() - 1) {
                return false;
            }
            return BINARY_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Detects whether compiled classes exist in a conventional build output directory.
     *
     * <p>This is an existence probe only: it never compiles anything and never reads
     * bytecode. Its purpose is to let the analysis layer answer honestly when
     * bytecode-dependent analysis cannot run.
     */
    boolean hasCompiledClasses(Path root) {
        List<Path> candidates = List.of(
                root.resolve("target/classes"),
                root.resolve("build/classes/java/main"),
                root.resolve("out/production/classes"),
                root.resolve("build/classes"));
        for (Path candidate : candidates) {
            if (!Files.isDirectory(candidate)) {
                continue;
            }
            try (var stream = Files.walk(candidate, 6)) {
                boolean found = stream
                        .filter(Files::isRegularFile)
                        .anyMatch(path -> path.getFileName().toString().endsWith(".class"));
                if (found) {
                    return true;
                }
            } catch (IOException e) {
                log.debug("unable to probe {} for compiled classes: {}", candidate, e.getMessage());
            }
        }
        return false;
    }
}
