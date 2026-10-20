package dev.repopilot.runtime.retention;

import dev.repopilot.config.RepoPilotProperties;
import dev.repopilot.runtime.ArtifactStore;
import dev.repopilot.runtime.RuntimeArtifact;
import dev.repopilot.runtime.RuntimeArtifactRepository;
import dev.repopilot.runtime.RuntimeArtifactType;
import dev.repopilot.runtime.RuntimeMeasurementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

/**
 * Retention for uploaded runtime artefacts: which files are past the window, and removing them.
 *
 * <h2>What is purged, and what is kept</h2>
 * Only the file. An uploaded artefact is two things — bytes in the artefact store and the rows that
 * describe them — and the rows are the half worth keeping. The artefact row names the file, the run
 * names the reader that read it, and the measurements are what the file <em>said</em>. Once the bytes
 * are gone those measurements are the only copy of that evidence, and re-deriving them means asking the
 * operator to reproduce the run that produced the file. So retention removes the copy and keeps the
 * record: a purged heap dump's object census stays readable, with its row saying the file it came from
 * is no longer stored.
 *
 * <p>What retention deliberately does <em>not</em> do is delete the run. The configuration property
 * once promised to "delete artefacts and runs older than this many days", and that promise was wrong:
 * deleting a run cascades to its measurements, so the pass would reclaim the bytes and then destroy the
 * only remaining statement of what they contained. Disk pressure is a reason to drop a copy, not a
 * reason to forget an answer. See docs/adr/0012.
 *
 * <h2>Order of operations, and what a crash leaves behind</h2>
 * Files first, marks second. If the process dies in between, the database describes a file that is no
 * longer there: the next pass finds it, finds nothing to delete, and marks it purged — the row heals
 * towards the truth. The other order would leave bytes in the store that no row points at, which is
 * invisible to every query and therefore unreclaimable. Deleting first is the ordering whose failure
 * mode is detectable.
 *
 * <h2>Transactions</h2>
 * The filesystem work happens between short database transactions, never inside one: see docs/adr/0006.
 * One short read decides the candidate set and which of it is safe to touch, and one short write marks
 * the result. Nothing holds a connection open while files are being deleted, and the two decisions are
 * made by {@link TransactionTemplate} rather than by an annotated method, because a purge is a
 * sequence of steps rather than one transaction and self-invocation would silently do nothing.
 */
@Service
public class ArtifactRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactRetentionService.class);

    /** How often progress is reported while files are being removed. */
    private static final int PROGRESS_STEP = 25;

    private final RuntimeArtifactRepository artifacts;
    private final RuntimeMeasurementRepository measurements;
    private final ArtifactStore store;
    private final RepoPilotProperties properties;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public ArtifactRetentionService(RuntimeArtifactRepository artifacts,
                                    RuntimeMeasurementRepository measurements,
                                    ArtifactStore store,
                                    RepoPilotProperties properties,
                                    Clock clock,
                                    PlatformTransactionManager transactionManager) {
        this.artifacts = artifacts;
        this.measurements = measurements;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
        this.writes = new TransactionTemplate(transactionManager);
    }

    /**
     * What a repository holds and what retention would remove from it.
     *
     * <p>Computed live rather than stored, because it is a function of the clock: a plan made an hour
     * ago describes an hour ago, and a preview that can be stale is worse than no preview at all. This
     * is also why the purge job has no "dry run" flag: reading the plan <em>is</em> the dry run, and it
     * costs a query rather than a job.
     *
     * @param candidates the artefacts a purge would remove now, newest first
     */
    public record RetentionPlan(UUID repositoryId,
                                int retentionDays,
                                boolean automatic,
                                Instant cutoff,
                                long storedArtefacts,
                                long storedBytes,
                                long purgedArtefacts,
                                long purgedBytes,
                                Instant oldestStoredAt,
                                List<Candidate> candidates) {

        /** Bytes a purge would reclaim now. */
        public long removableBytes() {
            return candidates.stream().mapToLong(Candidate::sizeBytes).sum();
        }

        /** Measurements that would survive the purge, because they are not the bytes. */
        public long measurementsKept() {
            return candidates.stream().mapToLong(Candidate::measurementCount).sum();
        }
    }

    /**
     * One artefact a purge would remove.
     *
     * @param measurementCount how many measurement rows were derived from it, which is what the purge
     *                         keeps: the figure exists to make "purging does not lose evidence" a number
     *                         the operator can see before they agree to it
     */
    public record Candidate(UUID artifactId,
                            String originalFilename,
                            RuntimeArtifactType artifactType,
                            long sizeBytes,
                            Instant createdAt,
                            UUID analysisRunId,
                            long measurementCount) {
    }

    /** What a purge actually did. */
    public record PurgeOutcome(int olderThanDays,
                               Instant cutoff,
                               int purged,
                               long bytesPurged,
                               int alreadyGone,
                               int skippedShared,
                               long measurementsKept) {
    }

    /**
     * A candidate as the purge needs it, including the stored location.
     *
     * <p>Private, because the stored location is an internal layout — a repository id, two hash
     * characters and a content hash — and neither the API nor the UI has anything to do with it. It
     * travels with the candidate so the delete step does not have to re-read a row to learn where its
     * file is, which would put a query inside a loop over files.
     */
    private record PlannedFile(UUID id,
                               String storageRef,
                               long sizeBytes,
                               long measurementCount,
                               RuntimeArtifactType type,
                               String filename,
                               Instant createdAt,
                               UUID analysisRunId) {
    }

    /**
     * The plan under the configured window.
     *
     * <p>The one-argument form exists so that the read path does not have to know what the policy is:
     * the service that applies retention is also the service that says what the window currently is, and
     * a controller that passed it in would be a second place that knows.
     */
    public RetentionPlan plan(UUID repositoryId) {
        return plan(repositoryId, properties.storage().retentionDays());
    }

    /**
     * The plan for a repository, as of now.
     *
     * <p>A retention window of zero disables pruning, so the plan reports the repository's state and an
     * empty candidate list: the screen that shows it should say "automatic pruning is off" rather than
     * pretend everything is either recent or gone.
     */
    public RetentionPlan plan(UUID repositoryId, int retentionDays) {
        Instant cutoff = cutoff(retentionDays);
        return reads.execute(status -> {
            List<RuntimeArtifact> stored =
                    artifacts.findByRepositoryIdAndPurgedAtIsNullOrderByCreatedAtDesc(repositoryId);
            List<PlannedFile> planned = retentionDays > 0 ? pastWindow(stored, cutoff) : List.of();
            return new RetentionPlan(repositoryId,
                    retentionDays,
                    retentionDays > 0,
                    cutoff,
                    stored.size(),
                    artifacts.sumStoredBytes(repositoryId),
                    artifacts.countByRepositoryIdAndPurgedAtIsNotNull(repositoryId),
                    artifacts.sumPurgedBytes(repositoryId),
                    oldest(stored),
                    planned.stream().map(ArtifactRetentionService::candidate).toList());
        });
    }

    /**
     * Removes every stored file older than the window and marks its row purged.
     *
     * @param olderThanDays how old a file must be; must be positive, because "purge everything" is a
     *                      different intention from "apply the retention window" and is not this call
     * @param progress      reported as a percentage of the candidate set, for a job's progress column
     * @param checkpoint    called between files, so a purge can be cancelled part-way
     */
    public PurgeOutcome purge(UUID repositoryId, int olderThanDays, IntConsumer progress, Runnable checkpoint) {
        if (olderThanDays <= 0) {
            throw new IllegalArgumentException("retention needs a positive window in days, not " + olderThanDays);
        }
        Instant cutoff = cutoff(olderThanDays);

        // Decide, in one read transaction, and keep only plain values from here on.
        List<PlannedFile> planned = reads.execute(status -> {
            List<RuntimeArtifact> stored =
                    artifacts.findByRepositoryIdAndPurgedAtIsNullOrderByCreatedAtDesc(repositoryId);
            return pastWindow(stored, cutoff);
        });
        if (planned.isEmpty()) {
            progress.accept(100);
            return new PurgeOutcome(olderThanDays, cutoff, 0, 0, 0, 0, 0);
        }

        // Which of them share a stored file with a row that is staying. Unreachable while uploads
        // deduplicate by content hash, and asked in one query per candidate before anything is deleted,
        // because the cost of asking is a lookup and the cost of being wrong is a file another row
        // still describes.
        Set<UUID> shared = reads.execute(status -> planned.stream()
                .filter(file -> artifacts.existsByStorageRefAndIdNot(file.storageRef(), file.id()))
                .map(PlannedFile::id)
                .collect(Collectors.toSet()));

        // Delete the files, with no database transaction open.
        List<UUID> removed = new ArrayList<>(planned.size());
        List<UUID> gone = new ArrayList<>();
        long bytes = 0;
        long measurementsKept = 0;
        int skippedShared = 0;
        int done = 0;
        for (PlannedFile file : planned) {
            checkpoint.run();
            if (shared.contains(file.id())) {
                skippedShared++;
                log.warn("not purging artefact {}: another row references the same stored file", file.id());
                continue;
            }
            if (file.storageRef() == null || file.storageRef().isBlank()) {
                // A row with no stored location has no file to remove. Marking it purged is the honest
                // outcome: the bytes are not there, and the record of what they said stays.
                gone.add(file.id());
                log.warn("artefact {} has no stored location; marking it purged", file.id());
            } else if (store.discard(file.storageRef())) {
                removed.add(file.id());
                bytes += file.sizeBytes();
            } else {
                gone.add(file.id());
            }
            measurementsKept += file.measurementCount();
            done++;
            if (done % PROGRESS_STEP == 0) {
                progress.accept(Math.min(95, 95 * done / planned.size()));
            }
        }

        // Mark what is now gone, in one short write, after the bytes are gone.
        List<UUID> marked = new ArrayList<>(removed);
        marked.addAll(gone);
        int markedRows = marked.isEmpty() ? 0
                : writes.execute(status -> artifacts.markPurged(marked, clock.instant()));
        progress.accept(100);
        log.info("retention purged {} of {} candidate file(s) for repository {} ({} recorded bytes, {} already "
                        + "gone, {} skipped): {} measurement(s) kept",
                markedRows, planned.size(), repositoryId, bytes, gone.size(), skippedShared, measurementsKept);
        return new PurgeOutcome(olderThanDays, cutoff, markedRows, bytes, gone.size(), skippedShared,
                measurementsKept);
    }

    /** The stored artefacts older than the cutoff, newest first, with what a purge would need. */
    private List<PlannedFile> pastWindow(List<RuntimeArtifact> stored, Instant cutoff) {
        List<PlannedFile> planned = new ArrayList<>();
        for (RuntimeArtifact artifact : stored) {
            if (artifact.getCreatedAt().isBefore(cutoff)) {
                planned.add(new PlannedFile(artifact.getId(), artifact.getStorageRef(), artifact.getSizeBytes(),
                        measurements.countByArtifactId(artifact.getId()), artifact.getArtifactType(),
                        artifact.getOriginalFilename(), artifact.getCreatedAt(), artifact.getAnalysisRunId()));
            }
        }
        return List.copyOf(planned);
    }

    /** The oldest stored artefact's upload time, from a list ordered newest first. */
    private static Instant oldest(List<RuntimeArtifact> stored) {
        return stored.isEmpty() ? null : stored.get(stored.size() - 1).getCreatedAt();
    }

    private static Candidate candidate(PlannedFile file) {
        return new Candidate(file.id(), file.filename(), file.type(), file.sizeBytes(), file.createdAt(),
                file.analysisRunId(), file.measurementCount());
    }

    /** The instant a file must predate to be purgeable. */
    private Instant cutoff(int retentionDays) {
        return clock.instant().minus(Math.max(0, retentionDays), ChronoUnit.DAYS);
    }
}
