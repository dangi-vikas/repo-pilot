package dev.repopilot.runtime;

import dev.repopilot.config.StorageInitializer;
import dev.repopilot.shared.error.RepoPilotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Content-addressed storage for uploaded runtime artefacts.
 *
 * <p>Bytes are written outside every repository, under a path derived from the repository
 * id and the SHA-256 of the content. Nothing about the stored location comes from the
 * client's filename: a file called {@code ../../../etc/passwd.jfr} is stored as
 * {@code <repo>/<aa>/<sha256>.jfr}, so a hostile name cannot become a path.
 *
 * <p>Ingest is a two-step move rather than a copy: the caller streams the upload to a
 * temporary file, this class hashes it, then renames it into place. That keeps peak memory
 * flat regardless of artefact size (a heap dump can be larger than the JVM's heap, so
 * buffering it would be its own out-of-memory story) and makes a partially uploaded file
 * impossible to observe at the final path.
 *
 * <p>Reads re-check containment. A {@code storage_ref} is a path from the database, and a
 * path from the database is input like any other: it is resolved, normalised, and required
 * to stay inside the artefacts directory &mdash; both lexically and after symlink
 * resolution &mdash; before anything is opened.
 */
@Component
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);
    private static final int HASH_BUFFER = 64 * 1024;

    private final Path artifactsDirectory;
    private final Path temporaryDirectory;

    public ArtifactStore(StorageInitializer storage) {
        this.artifactsDirectory = storage.artifactsDirectory();
        this.temporaryDirectory = storage.temporaryDirectory();
    }

    /**
     * Hashes a file the caller has already written and moves it into content-addressed
     * storage.
     *
     * @param repositoryId the repository the artefact belongs to
     * @param type         what the file is; decides the stored extension
     * @param incoming     a regular file that this method may move (and will consume)
     * @throws IOException when the file cannot be read, hashed or moved
     */
    public StoredArtifact adopt(UUID repositoryId, RuntimeArtifactType type, Path incoming) throws IOException {
        if (!Files.isRegularFile(incoming, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("incoming artefact is not a regular file");
        }
        long size = Files.size(incoming);
        String hash = sha256(incoming);
        Path target = pathFor(repositoryId, hash, type.extension());
        Files.createDirectories(target.getParent());

        if (Files.exists(target)) {
            // The same bytes are already stored. Drop the duplicate rather than overwrite:
            // the existing file is what an existing run read, and it must not change under it.
            Files.deleteIfExists(incoming);
            log.debug("artefact {} already stored for repository {}", hash, repositoryId);
        } else {
            try {
                Files.move(incoming, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(incoming, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return new StoredArtifact(hash, size, storageRef(repositoryId, hash, type.extension()));
    }

    /** Creates a scratch file for an in-flight upload. */
    public Path newIncomingFile() throws IOException {
        Files.createDirectories(temporaryDirectory);
        return Files.createTempFile(temporaryDirectory, "upload-", ".part");
    }

    /**
     * Repository ids that currently own something in the artefact store.
     *
     * <p>Taken from the directory names rather than from the database, because the question this
     * answers is the reverse of the one the database answers: "what is on disk" rather than "what
     * is recorded". A directory whose name is not a repository id is reported and skipped rather
     * than guessed at.
     */
    public Set<UUID> storedRepositoryIds() {
        if (!Files.isDirectory(artifactsDirectory)) {
            return Set.of();
        }
        try (Stream<Path> entries = Files.list(artifactsDirectory)) {
            return entries
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> parseUuid(path.getFileName().toString()))
                    .flatMap(Optional::stream)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            log.warn("could not list the artefact store: {}", e.getMessage());
            return Set.of();
        }
    }

    /**
     * Removes every stored artefact belonging to a repository.
     *
     * <p>Only for a repository that is gone. The bytes are addressed by repository id, so a
     * repository that no longer exists can own nothing here, and leaving them would keep a heap
     * dump &mdash; which contains process memory &mdash; on disk after the operator asked for the
     * repository to be deleted.
     *
     * <p>Best effort, and logged: by the time this runs the database has already forgotten the
     * records, so there is nothing to roll back. A file that cannot be deleted is reported, and the
     * next startup sweep will find it again.
     *
     * @return the number of files removed
     */
    public int discardRepository(UUID repositoryId) {
        Path directory = artifactsDirectory.resolve(repositoryId.toString()).normalize();
        if (directory.equals(artifactsDirectory) || !directory.startsWith(artifactsDirectory)) {
            // The id comes from a database row, but this method deletes recursively and the check
            // costs nothing.
            throw RepoPilotException.pathRejected(
                    "Refusing to remove artefacts outside the artefact directory.");
        }
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> walk = Files.walk(directory)) {
            // Deepest first, so a directory is empty by the time it is removed. Links are not
            // followed: an artefact directory could contain one, and deleting through it would
            // reach outside the store.
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    if (Files.deleteIfExists(path)) {
                        removed++;
                    }
                } catch (IOException e) {
                    log.warn("could not delete artefact file {}: {}", path, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("could not walk artefacts for repository {}: {}", repositoryId, e.getMessage());
        }
        return removed;
    }

    /**
     * Removes one stored artefact's bytes.
     *
     * <p>Different from {@link #discardRepository} in the two ways that matter to a retention pass.
     * It is scoped to a single content-addressed file rather than to everything a repository owns,
     * because purging an old artefact must not touch the ones the operator is still working with. And
     * a file that is already gone is not an error: a pass that stopped because a previous pass had
     * already removed one file would be unrunnable, and the row is marked purged either way, so the
     * absence is self-healing rather than a failure.
     *
     * <p>The containment checks are the same ones {@link #resolve} makes, and for the same reason: a
     * {@code storage_ref} comes from a database row, and a path from a database row is input like any
     * other. The difference is that a reference which resolves outside the artefact directory is still
     * a hard refusal here rather than a not-found, because this method deletes.
     *
     * @return whether a file was actually removed
     */
    public boolean discard(String storageRef) {
        if (storageRef == null || storageRef.isBlank()) {
            throw RepoPilotException.validation("The artefact has no stored location.");
        }
        Path candidate = artifactsDirectory.resolve(storageRef).normalize();
        if (!candidate.startsWith(artifactsDirectory)) {
            throw RepoPilotException.pathRejected(
                    "Artefact reference " + storageRef + " resolves outside the artefact directory.");
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            Path realRoot = artifactsDirectory.toRealPath();
            Path realParent = candidate.toRealPath().getParent();
            if (realParent == null || !realParent.startsWith(realRoot)) {
                throw RepoPilotException.pathRejected(
                        "Artefact reference " + storageRef + " resolves to a link outside the artefact directory.");
            }
        } catch (IOException vanished) {
            // The file disappeared between the existence check and the resolution. Nothing to do.
            return false;
        }
        try {
            return Files.deleteIfExists(candidate);
        } catch (IOException e) {
            log.warn("could not delete artefact file {}: {}", candidate, e.getMessage());
            return false;
        }
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException notAnId) {
            log.warn("ignoring unrecognised entry in the artefact store: {}", value);
            return Optional.empty();
        }
    }

    /** Resolves a stored reference, refusing anything that leaves the artefacts directory. */
    public Path resolve(String storageRef) {
        if (storageRef == null || storageRef.isBlank()) {
            throw RepoPilotException.validation("The artefact has no stored location.");
        }
        Path candidate = artifactsDirectory.resolve(storageRef).normalize();
        if (!candidate.startsWith(artifactsDirectory)) {
            throw RepoPilotException.pathRejected(
                    "Artefact reference " + storageRef + " resolves outside the artefact directory.");
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw RepoPilotException.notFound("Runtime artefact file", storageRef);
        }
        try {
            // Lexical containment is not enough: the stored path could be a symlink whose
            // target is elsewhere. The real parent must still be inside the real root.
            Path realRoot = artifactsDirectory.toRealPath();
            Path realParent = candidate.toRealPath().getParent();
            if (realParent == null || !realParent.startsWith(realRoot)) {
                throw RepoPilotException.pathRejected(
                        "Artefact reference " + storageRef + " resolves to a link outside the artefact directory.");
            }
        } catch (IOException e) {
            throw RepoPilotException.notFound("Runtime artefact file", storageRef);
        }
        return candidate;
    }

    public Path artifactsDirectory() {
        return artifactsDirectory;
    }

    private Path pathFor(UUID repositoryId, String hash, String extension) {
        return artifactsDirectory
                .resolve(repositoryId.toString())
                .resolve(hash.substring(0, 2))
                .resolve(hash + "." + extension);
    }

    private String storageRef(UUID repositoryId, String hash, String extension) {
        return repositoryId + "/" + hash.substring(0, 2) + "/" + hash + "." + extension;
    }

    /** Streaming SHA-256; never reads more than one buffer of the file into memory. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable in this JVM", e);
        }
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digesting = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[HASH_BUFFER];
            while (digesting.read(buffer) != -1) {
                // Reading is the work; the digest is updated as a side effect.
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** What the store recorded about an adopted file. */
    public record StoredArtifact(String contentHash, long sizeBytes, String storageRef) {
    }
}
