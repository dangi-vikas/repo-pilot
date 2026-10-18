package dev.repopilot.config;

import dev.repopilot.jobs.AnalysisJob;
import dev.repopilot.jobs.AnalysisJobRepository;
import dev.repopilot.jobs.JobClaimService;
import dev.repopilot.repository.RepositoryRepository;
import dev.repopilot.runtime.ArtifactStore;
import dev.repopilot.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Startup reconciliation.
 *
 * <p>RepoPilot is a local application that will be closed while work is in flight —
 * that is normal, not exceptional. This runner makes the consequences explicit and
 * deterministic:
 *
 * <ol>
 *   <li><b>Workspace.</b> Guarantees at least one workspace so the UI is usable on
 *       first start.</li>
 *   <li><b>Interrupted jobs.</b> A job still marked RUNNING with a stale heartbeat
 *       cannot be running, because this process just started. It is either re-queued
 *       (idempotent type, attempts remaining) or failed with an explicit
 *       {@code RUNNER_INTERRUPTED} reason. Nothing is silently forgotten and nothing
 *       is silently retried.</li>
 *   <li><b>Temporary files.</b> Scratch directories left by a previous run are removed,
 *       so disk is not consumed by abandoned intermediate state. Files are removed
 *       only inside RepoPilot's own temp directory, and only after a containment check.</li>
 *   <li><b>Orphaned artefacts.</b> A stored recording or dump whose repository is no longer in
 *       the database is removed. This is the second half of a repository deletion: the rows go
 *       when the user asks, the bytes go after that, and a crash in between would otherwise strand
 *       them. Only directories named after a repository id the database does not know are
 *       touched; nothing is pruned by age, because nothing in this build prunes by age yet
 *       (see docs/security.md, "Artefacts and retention").</li>
 * </ol>
 */
@Component
@Order(10)
public class StartupTasks implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupTasks.class);

    private final WorkspaceService workspaces;
    private final AnalysisJobRepository jobs;
    private final JobClaimService claimService;
    private final StorageInitializer storage;
    private final RepositoryRepository repositories;
    private final ArtifactStore artifacts;
    private final RepoPilotProperties properties;
    private final Clock clock;

    public StartupTasks(WorkspaceService workspaces,
                        AnalysisJobRepository jobs,
                        JobClaimService claimService,
                        StorageInitializer storage,
                        RepositoryRepository repositories,
                        ArtifactStore artifacts,
                        RepoPilotProperties properties,
                        Clock clock) {
        this.workspaces = workspaces;
        this.jobs = jobs;
        this.claimService = claimService;
        this.storage = storage;
        this.repositories = repositories;
        this.artifacts = artifacts;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        workspaces.ensureDefaultWorkspace();
        reconcileInterruptedJobs();
        if (properties.storage().cleanupTempOnStartup()) {
            cleanTemporaryFiles();
        }
        sweepOrphanedArtifacts();
    }

    /**
     * Removes stored artefacts whose repository no longer exists.
     *
     * <p>Asks the store what it holds rather than the database what it recorded, because the
     * interesting case is the one the database cannot answer: a directory that no record points
     * at. A repository deleted a moment before a crash, or through a path that does not clean up
     * after itself, is found here.
     */
    private void sweepOrphanedArtifacts() {
        Set<UUID> stored = artifacts.storedRepositoryIds();
        if (stored.isEmpty()) {
            return;
        }
        int removed = 0;
        for (UUID repositoryId : stored) {
            if (repositories.existsById(repositoryId)) {
                continue;
            }
            int files = artifacts.discardRepository(repositoryId);
            removed += files;
            log.info("removed {} orphaned artefact file(s) for repository {}, which no longer exists",
                    files, repositoryId);
        }
        if (removed > 0) {
            log.info("artefact sweep removed {} orphaned file(s) in total", removed);
        }
    }

    private void reconcileInterruptedJobs() {
        Instant staleBefore = clock.instant()
                .minusSeconds(Math.max(30, properties.jobs().staleJobThresholdSeconds()));
        List<AnalysisJob> interrupted = jobs.findStaleRunning(staleBefore);
        if (interrupted.isEmpty()) {
            log.info("no interrupted jobs found at startup");
            return;
        }
        log.warn("found {} job(s) left RUNNING by a previous process; reconciling", interrupted.size());
        for (AnalysisJob job : interrupted) {
            claimService.recoverInterrupted(job.getId());
        }
    }

    private void cleanTemporaryFiles() {
        Path temp = storage.temporaryDirectory().toAbsolutePath().normalize();
        Path dataRoot = storage.dataDirectory().toAbsolutePath().normalize();

        // Containment check before a recursive delete. The temp directory is derived from
        // configuration, so a misconfiguration must not be able to delete arbitrary paths.
        if (!temp.startsWith(dataRoot) || temp.equals(dataRoot)) {
            log.error("refusing to clean temporary files: {} is not a subdirectory of {}", temp, dataRoot);
            return;
        }
        if (!Files.isDirectory(temp)) {
            return;
        }

        int removed = 0;
        try (Stream<Path> entries = Files.walk(temp)) {
            List<Path> ordered = entries
                    .filter(path -> !path.equals(temp))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path path : ordered) {
                if (!path.toAbsolutePath().normalize().startsWith(temp)) {
                    log.warn("skipping path outside the temporary directory during cleanup");
                    continue;
                }
                try {
                    Files.deleteIfExists(path);
                    removed++;
                } catch (IOException e) {
                    log.debug("could not delete {}: {}", path.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("temporary file cleanup could not complete: {}", e.getMessage());
            return;
        }
        log.info("removed {} abandoned temporary file(s) from {}", removed, temp);
    }
}
