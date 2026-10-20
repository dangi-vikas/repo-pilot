package dev.repopilot.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.repopilot.config.RepoPilotProperties;
import dev.repopilot.jobs.JobService;
import dev.repopilot.jobs.JobType;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.RepositorySummary;
import dev.repopilot.runtime.query.RuntimeArtifactView;
import dev.repopilot.runtime.benchmark.BenchmarkFormat;
import dev.repopilot.runtime.benchmark.BenchmarkReader;
import dev.repopilot.runtime.heapdump.HeapDumpFormat;
import dev.repopilot.runtime.heapdump.HeapDumpReader;
import dev.repopilot.runtime.jfr.JfrReader;
import dev.repopilot.runtime.retention.ArtifactRetentionJobHandler;
import dev.repopilot.runtime.threaddump.ThreadDumpFormat;
import dev.repopilot.runtime.threaddump.ThreadDumpReader;
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
 *       is the answer that helps, and "not a JFR file" is the answer that wastes an afternoon. A
 *       thread dump likewise has to be the rich plain-text shape: the thinner
 *       {@code Thread.dump_to_file} shapes are refused by name, because a dump with no thread
 *       states in it would produce a report that looks like a healthy JVM. A heap dump has to begin
 *       with the {@code HPROF} header, and the file that is refused most usefully is the one an
 *       operator is most likely to have collected by mistake: {@code jmap -histo} output, which is
 *       a summary the JVM printed rather than a dump of anything. A benchmark result has no header to
 *       check at all, because JSON has none: that check is weaker by necessity — the file has to open
 *       with an array — and {@code BenchmarkReader} is where the structural check that can name the
 *       wrong entry lives. What is refused here is the shape decidable from the first byte, which
 *       includes the two other output formats JMH can write and a gzip archive.</li>
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

    /** Largest benchmark result accepted, in bytes, checked at upload before any parsing happens. */
    private final long maxBenchmarkBytes;

    /** The configuration, kept because the retention endpoints read the configured window from it. */
    private final RepoPilotProperties properties;

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
        this.properties = properties;
        this.maxArtifactBytes = properties.analysis().maxArtifactSizeBytes();
        this.maxBenchmarkBytes = properties.analysis().maxBenchmarkResultBytes();
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
            if (artifact.isPurged()) {
                // The same bytes, uploaded again. Storage is content-addressed, so the file is back at
                // exactly the path this row names, and the row must stop claiming it is gone rather than
                // accumulate a second row for one file.
                artifact.markStored();
                artifact = artifacts.save(artifact);
            }
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
        if (artifact.isPurged()) {
            // The bytes are gone but the row, the run it produced and its measurements are not. Saying
            // so is the only honest answer: re-queuing the job would fail deeper in with a missing file,
            // and the operator can fix this by uploading the same file again.
            throw RepoPilotException.conflict("The stored file for this artefact was removed by retention, so "
                    + "there is nothing left to read. Its measurements are still here. Upload the file again to "
                    + "analyse it.");
        }
        if (jobs.countActiveAnalysisForRepository(repositoryId) > 0) {
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
     * Queues a retention pass for a repository.
     *
     * <p>The window is the configured one unless the caller names another, and it is written into the
     * job's parameters either way: a job that says what it was asked to do is worth more than one that
     * has to be read against configuration that may have changed since.
     *
     * <p>Nothing is refused for lack of a window when a pass is already queued. Two passes are harmless
     * — the second finds the files gone and says so — and refusing one would mean an operator who
     * uploaded an old file and asked to purge it again gets an error instead of a no-op. What is
     * refused is a pass that would have no window at all, because "purge everything" is not a policy
     * this endpoint is allowed to infer.
     */
    public JobService.Submission submitRetention(UUID repositoryId, Integer olderThanDays, String idempotencyKey) {
        RepositorySummary repository = repositories.require(repositoryId);
        int days = olderThanDays == null ? properties.storage().retentionDays() : olderThanDays;
        if (days <= 0) {
            throw RepoPilotException.validation("Retention cannot run with a window of " + days + " day(s). "
                    + "Automatic pruning is switched off (repopilot.storage.retention-days=0), so this "
                    + "request has to name the window it wants, and a pass that would remove every stored "
                    + "file is not something this endpoint will do by omission.");
        }
        ObjectNode parameters = json.createObjectNode();
        parameters.put(ArtifactRetentionJobHandler.OLDER_THAN_DAYS, days);
        try {
            return jobs.submit(JobType.ARTIFACT_RETENTION, repository.workspaceId(), repositoryId,
                    json.writeValueAsString(parameters), idempotencyKey);
        } catch (JsonProcessingException e) {
            throw new RepoPilotException(ErrorCode.INTERNAL_ERROR,
                    "Could not serialise retention job parameters", e);
        }
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
    private void verifyLooksLike(Path file, RuntimeArtifactType type) throws IOException {
        switch (type) {
            case JFR_RECORDING -> verifyRecording(file);
            case HEAP_DUMP -> verifyHeapDump(file);
            case THREAD_DUMP -> verifyThreadDump(file);
            case BENCHMARK_RESULT -> verifyBenchmarkResult(file);
        }
    }

    /**
     * Refuses a benchmark result this build cannot import, at upload rather than at analysis.
     *
     * <p>Two checks, and the weaker one is the format. JMH's CSV and text outputs both contain the
     * score, which is exactly what makes them dangerous: a score rounded by a display formatter looks
     * like a score, and neither carries the per-iteration values a score can be checked against. They
     * are refused by name with the flag that produces the readable form.
     *
     * <p>The size check is here rather than only in the analysis job because a result file is parsed
     * into memory, entry by entry, whereas a dump is streamed. A file over the limit is refused with
     * both numbers stated, so the operator can see which configuration to change.
     */
    private void verifyBenchmarkResult(Path file) throws IOException {
        long size = Files.size(file);
        if (size > maxBenchmarkBytes) {
            throw RepoPilotException.limitExceeded("The uploaded benchmark result is " + size
                    + " bytes; the limit is " + maxBenchmarkBytes + " bytes"
                    + " (repopilot.analysis.max-benchmark-result-bytes).");
        }
        BenchmarkFormat format = BenchmarkReader.detect(file);
        if (!format.readable()) {
            throw RepoPilotException.unsupported(BenchmarkFormat.explain(format));
        }
    }

    /**
     * Refuses a heap dump this build cannot read, at upload rather than at analysis.
     *
     * <p>The refusal that earns its keep here is the class histogram: {@code jmap -histo} output is
     * the file an operator is most likely to believe is a heap dump, and it is not one — it has no
     * object graph behind it, so nothing in it can be checked. A gzipped dump is refused by name too,
     * with the command that decompresses it, because expanding an untrusted archive inside the
     * analysis job is work the upload's size limit cannot bound.
     */
    private static void verifyHeapDump(Path file) throws IOException {
        HeapDumpReader.Detection detection = HeapDumpReader.detect(file);
        if (!detection.format().readable()) {
            throw RepoPilotException.unsupported(
                    HeapDumpFormat.explain(detection.format(), detection.head()));
        }
    }

    private static void verifyRecording(Path file) throws IOException {
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

    /**
     * Refuses a thread dump this build cannot read, at upload rather than at analysis.
     *
     * <p>The two thin {@code Thread.dump_to_file} shapes — plain and JSON — are accepted by the file
     * picker's extension and rejected here, because they record no thread states and no monitor
     * ownership: a report derived from one would be a page of zeros that looks exactly like a
     * healthy JVM. The refusal names the command that produces a readable dump instead, and it
     * happens before the bytes are stored, so the artefact list never contains a file that cannot be
     * analysed.
     */
    private static void verifyThreadDump(Path file) throws IOException {
        ThreadDumpFormat format = ThreadDumpReader.detect(file);
        if (!format.readable()) {
            throw RepoPilotException.unsupported(ThreadDumpFormat.explain(format));
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
