package dev.repopilot.runtime;

import dev.repopilot.analysis.AnalysisRunStatus;
import dev.repopilot.api.PageResponse;
import dev.repopilot.api.v1.JobView;
import dev.repopilot.jobs.AnalysisJob;
import dev.repopilot.jobs.AnalysisJobRepository;
import dev.repopilot.jobs.JobDispatcher;
import dev.repopilot.jobs.JobService;
import dev.repopilot.jobs.JobStatus;
import dev.repopilot.jobs.JobType;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.RepositorySummary;
import dev.repopilot.runtime.query.RuntimeArtifactView;
import dev.repopilot.runtime.query.RuntimeMeasurementView;
import dev.repopilot.runtime.query.RuntimeQueryService;
import dev.repopilot.runtime.query.RuntimeSummaryView;
import dev.repopilot.runtime.retention.ArtifactRetentionService;
import dev.repopilot.shared.error.RepoPilotException;
import dev.repopilot.workspace.WorkspaceService;
import dev.repopilot.workspace.WorkspaceSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;

/**
 * Artefact retention end to end, with automatic pruning deliberately switched off.
 *
 * <p>The window is {@code 0} for this class, which is the configuration an operator gets when they do
 * not want files disappearing on their behalf. Three things follow from it, and all three are asserted
 * here rather than described: the plan reports the repository's state and names no candidate, a purge
 * with no window is refused, and a purge that names a window still works. The explicit-window path is
 * the one a user takes from the screen, and it has to behave identically whether or not the scheduler
 * is enabled.
 *
 * <p>Age is forced by writing {@code created_at} directly, because nothing in the product backdates an
 * upload and the alternative would be to wait thirty days. Everything else — the upload, the analysis,
 * the file deletion, the mark, the re-upload — goes through production code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "repopilot.security.token=integration-test-bearer-token-0007",
        "repopilot.ai.enabled=false",
        "repopilot.jobs.dispatch-interval-millis=3600000",
        "repopilot.jobs.dispatch-initial-delay-millis=3600000",
        "repopilot.storage.retention-days=0",
        "repopilot.storage.retention-sweep-interval-millis=3600000",
        "repopilot.storage.retention-initial-delay-millis=3600000"
})
@AutoConfigureWebTestClient
class ArtifactRetentionIntegrationTest {

    private static final String BEARER = "Bearer integration-test-bearer-token-0007";

    @TempDir
    static Path workspaceRoot;

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("repopilot.workspace.roots", () -> workspaceRoot.toAbsolutePath().toString());
        registry.add("repopilot.storage.data-dir", () -> dataDir.toAbsolutePath().toString());
    }

    @Autowired
    private WebTestClient client;
    @Autowired
    private WorkspaceService workspaces;
    @Autowired
    private RepositoryService repositories;
    @Autowired
    private JobDispatcher dispatcher;
    @Autowired
    private JobService jobs;
    @Autowired
    private AnalysisJobRepository jobRepository;
    @Autowired
    private RuntimeQueryService runtimeQueries;
    @Autowired
    private RuntimeArtifactRepository artifacts;
    @Autowired
    private ArtifactStore store;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("purges the bytes, keeps every measurement, refuses re-analysis, and restores on re-upload")
    void purgesBytesAndKeepsEvidence() throws Exception {
        RepositorySummary repository = register("retention");
        byte[] result = Files.readAllBytes(fixture("jmh-result.json"));
        RuntimeArtifactView uploaded = upload(repository.id(), result, "jmh-result.json")
                .expectStatus().isCreated()
                .expectBody(RuntimeArtifactView.class)
                .returnResult()
                .getResponseBody();
        assertThat(uploaded).isNotNull();
        AnalysisJob imported = awaitTerminal(submit(repository.id(), uploaded.id()).id());
        assertThat(imported.getStatus()).isEqualTo(JobStatus.SUCCEEDED);

        RuntimeSummaryView before = runtimeQueries.summary(repository.id(), null);
        assertThat(before.status()).isEqualTo(AnalysisRunStatus.SUCCEEDED);
        long measurements = before.measurementCount();
        assertThat(measurements).isPositive();

        backdate(uploaded.id(), 40);

        // 1. With automatic pruning off, the plan states the repository and names no candidate: a screen
        // that showed "1 file due" while the setting says "do not prune" would be lying about the policy.
        client.get().uri("/api/v1/repositories/{id}/runtime/retention", repository.id())
                .header("Authorization", BEARER)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.automatic").isEqualTo(false)
                .jsonPath("$.retentionDays").isEqualTo(0)
                .jsonPath("$.storedArtefacts").isEqualTo(1)
                .jsonPath("$.purgedArtefacts").isEqualTo(0)
                .jsonPath("$.candidates.length()").isEqualTo(0);

        // 2. A purge that names no window is refused, in words that say which setting is off.
        client.post().uri("/api/v1/repositories/{id}/runtime/retention/purge", repository.id())
                .header("Authorization", BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.message").value(containsString("retention-days"))
                .jsonPath("$.message").value(containsString("by omission"));

        // 3. A purge that names one runs, and states what it reclaimed and what it kept.
        client.post().uri("/api/v1/repositories/{id}/runtime/retention/purge", repository.id())
                .header("Authorization", BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("olderThanDays", 30))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(JobView.class)
                .value(job -> assertThat(job.jobType()).isEqualTo(JobType.ARTIFACT_RETENTION));

        AnalysisJob purge = awaitTerminal(latestRetentionJob(repository.id()));
        assertThat(purge.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        // A maintenance pass produces no analysis run: nothing was read, so there is nothing to report.
        assertThat(purge.getAnalysisRunId()).isNull();
        assertThat(purge.getResultSummary())
                .contains("Purged 1 stored file(s) older than 30 day(s)")
                .contains(measurements + " measurement(s) were kept");

        // 4. The file is gone and the row says when it went; the evidence is untouched.
        String storageRef = artifacts.findById(uploaded.id()).orElseThrow().getStorageRef();
        assertThatThrownBy(() -> store.resolve(storageRef)).isInstanceOf(RepoPilotException.class);
        assertThat(runtimeQueries.artifact(repository.id(), uploaded.id()).purgedAt()).isNotNull();
        assertThat(runtimeQueries.artifact(repository.id(), uploaded.id()).sizeBytes()).isEqualTo(result.length);

        RuntimeSummaryView after = runtimeQueries.summary(repository.id(), null);
        assertThat(after.runId()).isEqualTo(before.runId());
        assertThat(after.status()).isEqualTo(AnalysisRunStatus.SUCCEEDED);
        assertThat(after.measurementCount()).isEqualTo(measurements);
        assertThat(rows(repository.id(), "benchmark.").totalItems()).isEqualTo(measurements);

        client.get().uri("/api/v1/repositories/{id}/runtime/retention", repository.id())
                .header("Authorization", BEARER)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.storedArtefacts").isEqualTo(0)
                .jsonPath("$.purgedArtefacts").isEqualTo(1)
                .jsonPath("$.purgedBytes").isEqualTo(result.length)
                .jsonPath("$.candidates.length()").isEqualTo(0);

        // 5. Re-analysis is refused rather than queued against a file that is not there.
        client.post().uri("/api/v1/repositories/{id}/runtime/artifacts/{artifactId}/analyze",
                        repository.id(), uploaded.id())
                .header("Authorization", BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("CONFLICT")
                .jsonPath("$.message").value(containsString("removed by retention"));

        // 6. Uploading the same bytes again restores the artefact: the same row, readable again, with no
        // second copy and no second run.
        RuntimeArtifactView restored = upload(repository.id(), result, "jmh-result.json")
                .expectStatus().isCreated()
                .expectBody(RuntimeArtifactView.class)
                .returnResult()
                .getResponseBody();
        assertThat(restored).isNotNull();
        assertThat(restored.id()).isEqualTo(uploaded.id());
        assertThat(restored.purgedAt()).isNull();
        assertThat(store.resolve(storageRef)).exists();

        client.get().uri("/api/v1/repositories/{id}/runtime/retention", repository.id())
                .header("Authorization", BEARER)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.storedArtefacts").isEqualTo(1)
                .jsonPath("$.purgedArtefacts").isEqualTo(0);
    }

    @Test
    @DisplayName("names what a purge would remove, and leaves a file inside the window alone")
    void previewsAndLeavesRecentFilesAlone() throws Exception {
        RepositorySummary repository = register("windows");
        RuntimeArtifactView old = upload(repository.id(), Files.readAllBytes(fixture("jmh-result.json")),
                "old.json").expectBody(RuntimeArtifactView.class).returnResult().getResponseBody();
        RuntimeArtifactView recent = upload(repository.id(), Files.readAllBytes(fixture("jmh-result-2-forks.json")),
                "recent.json").expectBody(RuntimeArtifactView.class).returnResult().getResponseBody();
        assertThat(old).isNotNull();
        assertThat(recent).isNotNull();

        backdate(old.id(), 40);

        // The preview is computed against the clock on every read, and states per file what would
        // survive it. That number is the point of the preview: it is what "purge" does not delete.
        client.get().uri("/api/v1/repositories/{id}/runtime/retention?days=30", repository.id())
                .header("Authorization", BEARER)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.candidates.length()").isEqualTo(1)
                .jsonPath("$.candidates[0].artifactId").isEqualTo(old.id().toString())
                .jsonPath("$.candidates[0].measurementCount").isEqualTo(0)
                .jsonPath("$.candidates[0].sizeBytes").isEqualTo((int) old.sizeBytes());

        purge(repository.id(), 30);

        assertThat(runtimeQueries.artifact(repository.id(), old.id()).purgedAt()).isNotNull();
        assertThat(runtimeQueries.artifact(repository.id(), recent.id()).purgedAt()).isNull();
        assertThat(store.resolve(artifacts.findById(recent.id()).orElseThrow().getStorageRef())).exists();

        client.get().uri("/api/v1/repositories/{id}/runtime/retention", repository.id())
                .header("Authorization", BEARER)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.storedArtefacts").isEqualTo(1)
                .jsonPath("$.purgedArtefacts").isEqualTo(1);
    }

    @Test
    @DisplayName("a retention job submitted with no window fails and says why, rather than reporting a purge of nothing")
    void aJobWithNoWindowFails() throws Exception {
        RepositorySummary repository = register("no-window");

        JobService.Submission submission = jobs.submit(JobType.ARTIFACT_RETENTION, repository.workspaceId(),
                repository.id(), null, null);
        AnalysisJob job = awaitTerminal(submission.job().getId());

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorCode()).isEqualTo("JOB_FAILED");
        assertThat(job.getErrorMessage())
                .contains("switched off")
                .contains("olderThanDays")
                .contains("will not treat");
    }

    // ------------------------------------------------------------------ helpers

    private static Path fixture(String name) throws Exception {
        return Path.of(ArtifactRetentionIntegrationTest.class.getResource("/benchmarks/" + name).toURI());
    }

    private void backdate(UUID artifactId, int days) {
        jdbc.update("update runtime_artifact set created_at = ? where id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(days), artifactId);
    }

    /** Counts rows through the API, which is the only place a client can count them. */
    private PageResponse<RuntimeMeasurementView> rows(UUID repositoryId, String metric) {
        PageResponse<RuntimeMeasurementView> page = client.get()
                .uri("/api/v1/repositories/{id}/runtime/measurements?metric=" + metric + "&size=1", repositoryId)
                .header("Authorization", BEARER)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<PageResponse<RuntimeMeasurementView>>() {
                })
                .returnResult()
                .getResponseBody();
        assertThat(page).isNotNull();
        return page;
    }

    private UUID latestRetentionJob(UUID repositoryId) {
        return jobs.listForRepository(repositoryId, org.springframework.data.domain.PageRequest.of(0, 50))
                .getContent().stream()
                .filter(job -> job.getJobType() == JobType.ARTIFACT_RETENTION)
                .map(AnalysisJob::getId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no retention job for " + repositoryId));
    }

    private void purge(UUID repositoryId, int days) {
        client.post().uri("/api/v1/repositories/{id}/runtime/retention/purge", repositoryId)
                .header("Authorization", BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("olderThanDays", days))
                .exchange()
                .expectStatus().isAccepted();
        awaitTerminal(latestRetentionJob(repositoryId));
    }

    private RepositorySummary register(String name) throws IOException {
        Path root = workspaceRoot.resolve(name);
        Files.createDirectories(root);
        Files.writeString(root.resolve("README.txt"), "retention host\n", StandardCharsets.UTF_8);
        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);
        return repositories.register(workspace.id(), name, root.toAbsolutePath().toString());
    }

    private WebTestClient.ResponseSpec upload(UUID repositoryId, byte[] bytes, String filename) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(bytes)).filename(filename);
        return client.post().uri("/api/v1/repositories/{id}/runtime/artifacts", repositoryId)
                .header("Authorization", BEARER)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange();
    }

    private JobView submit(UUID repositoryId, UUID artifactId) {
        JobView job = client.post().uri("/api/v1/repositories/{id}/runtime/artifacts/{artifactId}/analyze",
                        repositoryId, artifactId)
                .header("Authorization", BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(JobView.class)
                .returnResult()
                .getResponseBody();
        assertThat(job).isNotNull();
        return job;
    }

    private AnalysisJob awaitTerminal(UUID jobId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
        while (Instant.now().isBefore(deadline)) {
            dispatcher.dispatchPass();
            AnalysisJob job = jobRepository.findById(jobId).orElseThrow();
            if (job.getStatus().isTerminal()) {
                return job;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for job " + jobId, e);
            }
        }
        throw new AssertionError("job " + jobId + " did not finish within the deadline");
    }
}
