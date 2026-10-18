package dev.repopilot.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repopilot.config.RepoPilotProperties;
import dev.repopilot.jobs.JobService;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.RepositorySummary;
import dev.repopilot.runtime.query.RuntimeArtifactView;
import dev.repopilot.runtime.jfr.JfrReader;
import dev.repopilot.shared.error.ErrorCode;
import dev.repopilot.shared.error.RepoPilotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Writes: ingesting a runtime artefact and queueing its analysis.
 *
 * <p>Ingest is deliberately split in two. {@link #stage} validates the request and hands back a
 * temporary file for the caller to stream the upload into; {@link #adopt} hashes what actually
 * arrived and puts it in content-addressed storage. The HTTP layer needs that split because
 * WebFlux streams a multipart part to disk without buffering it, and a heap dump is not something
 * to hold in memory.
 *
 * <p>What is checked, and why each check earns its place:
 * <ul>
 *   <li><b>The filename never becomes a path.</b> Only its base name is kept, and only to show it
 *       in the UI. Where the bytes go is decided by the repository id and the content hash.</li>
 *   <li><b>The artefact must be plausible before a job is spent on it.</b> A JFR recording
 *       starts with the {@code FLR} header; a gzip stream starts with {@code 1F 8B}. The second
 *       case gets its own message, because "this is a compressed recording, decompress it first"
 *       is the answer that helps, and "not a JFR file" is the answer that wastes an afternoon.</li>
 *   <li><b>Size is bounded, twice.</b> The outer bound is WebFlux's
 *       {@code spring.webflux.multipart.max-disk-usage-per-part}, enforced while the part is being
 *       written, so an oversized stream stops at a bounded number of bytes on disk instead of at
 *       whatever the disk happens to hold. The inner bound is
 *       {@code repopilot.analysis.max-artifact-size-bytes}, checked here on the landed file so the
 *       refusal can name the exact size and the exact limit — which the framework's message cannot.
 *       A partial upload is deleted rather than stored.</li>
 * </ul>
 *
 * <p>An artefact whose type this build cannot parse is still accepted and stored, with
 * {@code supported=false} reported to the client. Refusing it would lose evidence the operator
 * has already collected; pretending to analyse it would be worse. It is stored and labelled.
 */
@Service
public class RuntimeService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeService.class);

    private static final int MAX_FILENAME = 500;

    private final RepositoryService repositories;
    private final RuntimeArtifactRepository artifacts;
    private final RuntimeMeasurementRepository measurements;
    private final ArtifactStore store;
    private final JobService jobs;
    private final ObjectMapper json;

    /**
     * Largest artefact accepted, in bytes, checked on the landed file.
     *
     * <p>Taken from {@code repopilot.analysis.max-artifact-size-bytes} rather than fixed here. A
     * limit that is documented but not applied is worse than no limit, because the operator raises
     * a number, watches nothing change, and concludes the check is broken rather than absent. What
     * is configured is what is enforced, including a value too small to be useful: "the limit is 0
     * bytes" is a clear pointer at the configuration, whereas silently substituting a different
     * number would hide the mistake.
     */
    private final long maxArtifactBytes;

    public RuntimeService(RepositoryService repositories,
                          RuntimeArtifactRepository artifacts,
                          RuntimeMeasurementRepository measurements,
                          ArtifactStore store,
                          JobService jobs,
                          ObjectMapper json,
                          RepoPilotProperties properties) {
        this.repositories = repositories;
        this.artifacts = artifacts;
        this.measurements = measurements;
        this.store = store;
        this.jobs = jobs;
        this.json = json;
        this.maxArtifactBytes = properties.analysis().maxArtifactSizeBytes();
    }

    /** The limit this instance applies, so a caller can state it rather than restate it. */
    public long maxArtifactBytes() {
        return maxArtifactBytes;
    }

    /** An upload that has been validated and is waiting for its bytes. */
    public record StagedUpload(UUID repositoryId,
                               UUID workspaceId,
                               RuntimeArtifactType type,
                               String filename,
                               Path temporaryFile) {
    }

    /**
     * Validates an upload request and reserves a temporary file for it.
     *
     * <p>No bytes are read here, and no row is written: a request that streams nothing leaves
     * nothing behind beyond a deleted temporary file.
     */
    public StagedUpload stage(UUID repositoryId, String rawFilename, String rawType) throws IOException {
        RepositorySummary repository = repositories.require(repositoryId);
        String filename = baseName(rawFilename);
        RuntimeArtifactType type = resolveType(rawType, filename);
        Path temporary = store.newIncomingFile();
        return new StagedUpload(repositoryId, repository.workspaceId(), type, filename, temporary);
    }

    /**
     * Hashes the uploaded bytes, stores them and records the artefact.
     *
     * <p>Identity is the content hash. Uploading the same recording twice returns the artefact
     * already on file instead of creating a second row pointing at the same bytes, so "which run
     * read this" keeps one answer. The returned view carries the measurement count, so a re-upload
     * of a recording that has already been analysed does not look like a fresh, unread file.
     */
    public RuntimeArtifactView adopt(StagedUpload staged) throws IOException {
        Path temporary = staged.temporaryFile();
        try {
            long size = Files.size(temporary);
            if (size == 0) {
                throw RepoPilotException.validation("The uploaded file is empty.");
            }
            if (size > maxArtifactBytes) {
                throw RepoPilotException.limitExceeded("The uploaded file is " + size + " bytes; the limit is "
                        + maxArtifactBytes + " bytes.");
            }
            verifyLooksLike(temporary, staged.type());

            ArtifactStore.StoredArtifact stored = store.adopt(staged.repositoryId(), staged.type(), temporary);

            RuntimeArtifact artifact = artifacts.findFirstByRepositoryIdAndContentHashOrderByCreatedAtAsc(
                            staged.repositoryId(), stored.contentHash())
                    .orElseGet(() -> artifacts.save(new RuntimeArtifact(staged.repositoryId(), staged.type(),
                            staged.filename(), stored.contentHash(), stored.sizeBytes(), stored.storageRef())));
            return RuntimeArtifactView.from(artifact, measurements.countByArtifactId(artifact.getId()));
        } catch (IOException | RuntimeException e) {
            // Nothing partial is ever stored, and the scratch file is not left for the next
            // upload to trip over.
            deleteQuietly(temporary);
            throw e;
        }
    }

    /**
     * Queues the job that analyses an artefact.
     *
     * <p>Refuses unsupported artefact types here rather than letting a job fail a moment later,
     * because the caller can act on "this build cannot read heap dumps" and cannot act on a job
     * that failed several seconds after it was queued.
     */
    public JobService.Submission submitAnalysis(UUID repositoryId, UUID artifactId, String idempotencyKey) {
        RepositorySummary repository = repositories.require(repositoryId);
        RuntimeArtifact artifact = artifacts.findByIdAndRepositoryId(artifactId, repositoryId)
                .orElseThrow(() -> RepoPilotException.notFound("Runtime artefact", artifactId));

        RuntimeArtifactType type = artifact.getArtifactType();
        if (!type.supported()) {
            throw RepoPilotException.unsupported(type.unsupportedReason());
        }
        if (jobs.countActiveForRepository(repositoryId) > 0) {
            throw RepoPilotException.conflict(
                    "This repository already has a job in progress. Wait for it to finish before analysing.");
        }
        return jobs.submit(type.jobType(), repository.workspaceId(), repositoryId, parameters(artifactId), idempotencyKey);
    }

    private String parameters(UUID artifactId) {
        try {
            return json.writeValueAsString(java.util.Map.of("artifactId", artifactId.toString()));
        } catch (JsonProcessingException e) {
            throw new RepoPilotException(ErrorCode.INTERNAL_ERROR, "Could not serialise job parameters", e);
        }
    }

    /**
     * Removes the stored bytes of every artefact a repository owned.
     *
     * <p>Called after the repository's records are gone, never before: deleting the bytes while the
     * rows still point at them would leave the database describing files that no longer exist.
     *
     * @return the number of files removed
     */
    public int discardArtifacts(UUID repositoryId) {
        return store.discardRepository(repositoryId);
    }

    /**
     * Keeps only the base name, strips control characters and bounds the length.
     *
     * <p>The result is displayed and stored, never resolved. A client-supplied path is therefore
     * reduced to a label: {@code ../../etc/passwd.jfr} becomes {@code passwd.jfr}, which is a
     * truthful name for the file it actually uploaded.
     */
    private static String baseName(String rawFilename) {
        if (rawFilename == null || rawFilename.isBlank()) {
            throw RepoPilotException.validation("The upload must have a filename.");
        }
        String base;
        try {
            Path path = Path.of(rawFilename);
            Path name = path.getFileName();
            base = name == null ? rawFilename : name.toString();
        } catch (InvalidPathException e) {
            base = rawFilename;
        }
        base = base.replaceAll("[\\p{Cntrl}]", "").trim();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            throw RepoPilotException.validation("The upload must have a usable filename.");
        }
        return base.length() <= MAX_FILENAME ? base : base.substring(0, MAX_FILENAME);
    }

    /** An explicit type wins; otherwise the extension decides; otherwise the request is refused. */
    private static RuntimeArtifactType resolveType(String rawType, String filename) {
        if (rawType != null && !rawType.isBlank()) {
            try {
                return RuntimeArtifactType.parse(rawType);
            } catch (IllegalArgumentException e) {
                throw RepoPilotException.validation(e.getMessage()
                        + " Expected one of: JFR_RECORDING, HEAP_DUMP, THREAD_DUMP, BENCHMARK_RESULT.");
            }
        }
        return RuntimeArtifactType.fromFilename(filename)
                .orElseThrow(() -> RepoPilotException.validation(
                        "Could not tell what kind of artefact '" + filename + "' is. State the artifactType"
                                + " (JFR_RECORDING, HEAP_DUMP, THREAD_DUMP or BENCHMARK_RESULT)."));
    }

    /** A cheap header check, so an obvious mismatch is refused before a job is spent on it. */
    private static void verifyLooksLike(Path file, RuntimeArtifactType type) throws IOException {
        if (type != RuntimeArtifactType.JFR_RECORDING) {
            return;
        }
        switch (JfrReader.detectKind(file)) {
            case GZIP -> throw RepoPilotException.unsupported("This is a gzip-compressed file, not a raw JDK "
                    + "Flight Recorder recording. Decompress it first (the JDK writes one when a recording is "
                    + "dumped with gzip enabled) and upload the .jfr file.");
            case UNKNOWN -> throw RepoPilotException.unsupported("This file does not begin with the JDK Flight "
                    + "Recorder header ('FLR'), so it is not a JFR recording. Compressed or converted recordings "
                    + "must be decompressed or re-dumped before upload.");
            case RECORDING -> {
                // Nothing to do: it is what it says it is.
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("could not delete scratch upload {}: {}", path, e.getMessage());
        }
    }

    /**
     * Deletes a staged scratch file whose bytes never arrived.
     *
     * <p>{@link #stage} reserves the file before anything is streamed into it, so every failure
     * between the two calls &mdash; a client that disconnects, a framework limit that aborts the
     * part mid-stream &mdash; has to be able to reclaim it. Without this the scratch directory
     * grows by one dead file per failed upload, which is precisely the sort of leak that is
     * invisible until a disk fills.
     */
    public void discard(StagedUpload staged) {
        if (staged != null) {
            deleteQuietly(staged.temporaryFile());
        }
    }
}
