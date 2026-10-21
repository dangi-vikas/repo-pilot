package dev.repopilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import dev.repopilot.analysis.AnalysisRun;
import dev.repopilot.analysis.AnalysisRunRepository;
import dev.repopilot.analysis.AnalysisRunStatus;
import dev.repopilot.analysis.AnalysisRunType;
import dev.repopilot.analysis.query.AnalysisQueryService;
import dev.repopilot.api.PageResponse;
import dev.repopilot.api.v1.JobView;
import dev.repopilot.jobs.AnalysisJob;
import dev.repopilot.jobs.AnalysisJobRepository;
import dev.repopilot.jobs.JobDispatcher;
import dev.repopilot.jobs.JobStatus;
import dev.repopilot.jobs.JobType;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.RepositorySummary;
import dev.repopilot.runtime.query.RuntimeArtifactView;
import dev.repopilot.runtime.query.RuntimeMeasurementFilter;
import dev.repopilot.runtime.query.RuntimeMeasurementView;
import dev.repopilot.runtime.query.RuntimeQueryService;
import dev.repopilot.runtime.query.RuntimeSummaryView;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end runtime evidence: upload a recording over HTTP, queue its analysis, let the dispatcher
 * run it, and read the measurements back through the API.
 *
 * <p>The recording is produced by this test rather than checked in, for the reason given in
 * {@code JfrReaderTest}: a checked-in file would be a recording of whatever JDK wrote it, and the
 * assertions would drift away from the JDK in use.
 *
 * <p>Dispatch is driven explicitly rather than waited for: the scheduled pass is configured with a
 * one-hour interval, and each step calls {@link JobDispatcher#dispatchPass()} itself and then waits
 * for the job to reach a terminal state. Nothing about the path is bypassed &mdash; the same queue,
 * the same claim, the same handler and the same completion transition are used &mdash; but the
 * outcome does not depend on when a scheduler happens to wake up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "repopilot.security.token=integration-test-bearer-token-0003",
        "repopilot.ai.enabled=false",
        // The dispatcher's first pass runs at startup. A long interval afterwards keeps every later
        // pass the test's own, so the order of events below is fixed.
        "repopilot.jobs.dispatch-interval-millis=3600000",
        "repopilot.jobs.dispatch-initial-delay-millis=3600000"
})
@AutoConfigureWebTestClient
class RuntimeAnalysisIntegrationTest {

    private static final String BEARER = "Bearer integration-test-bearer-token-0003";

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
    private AnalysisJobRepository jobRepository;
    @Autowired
    private AnalysisRunRepository runs;
    @Autowired
    private RuntimeArtifactRepository artifacts;
    @Autowired
    private RuntimeMeasurementRepository measurements;
    @Autowired
    private RuntimeQueryService runtimeQueries;
    @Autowired
    private AnalysisQueryService analysisQueries;
    @Autowired
    private ArtifactStore store;

    @Test
    @DisplayName("uploads a recording over HTTP, analyses it, and reads the measurements back")
    void uploadsAnalysesAndReadsBack() throws Exception {
        RepositorySummary repository = register("runtime");
        byte[] recording = recording();
        String expectedHash = sha256(recording);

        // Nothing has been recorded yet. That is an answer, not an error: a client must be able to
        // tell "no recording has been analysed" from "a recording was analysed and measured nothing".
        RuntimeSummaryView empty = runtimeQueries.summary(repository.id(), null);
        assertThat(empty.runId()).isNull();
        assertThat(empty.measurementCount()).isZero();
        assertThat(empty.byMetricFamily()).isEmpty();
        assertThat(empty.provenanceJson()).isNull();

        // 1. Upload. The artefact is stored by content, not by name.
        RuntimeArtifactView uploaded = upload(repository.id(), recording, "sample.jfr", "JFR_RECORDING")
                .expectStatus().isCreated()
                .expectBody(RuntimeArtifactView.class)
                .returnResult()
                .getResponseBody();
        assertThat(uploaded).isNotNull();
        assertThat(uploaded.artifactType()).isEqualTo(RuntimeArtifactType.JFR_RECORDING);
        assertThat(uploaded.originalFilename()).isEqualTo("sample.jfr");
        assertThat(uploaded.contentHash()).isEqualTo(expectedHash);
        assertThat(uploaded.sizeBytes()).isEqualTo(recording.length);
        assertThat(uploaded.supported()).isTrue();
        assertThat(uploaded.unsupportedReason()).isNull();
        // Stored, but not yet read: no run and therefore no measurements.
        assertThat(uploaded.analysisRunId()).isNull();
        assertThat(uploaded.measurementCount()).isZero();

        // The same bytes are the same artefact: a second upload does not create a second identity, and
        // its name does not replace the first one's, because the existing row already describes these
        // bytes with the name they arrived under.
        RuntimeArtifactView again = upload(repository.id(), recording, "../../escape.jfr", "JFR_RECORDING")
                .expectStatus().isCreated()
                .expectBody(RuntimeArtifactView.class)
                .returnResult()
                .getResponseBody();
        assertThat(again).isNotNull();
        assertThat(again.id()).isEqualTo(uploaded.id());
        assertThat(again.originalFilename()).isEqualTo("sample.jfr");

        // Different bytes with a hostile name: the name is reduced to a label that is stored for
        // display, while the content hash decides where the file actually goes.
        byte[] tampered = recording.clone();
        tampered[tampered.length - 1] ^= 0x01;
        RuntimeArtifactView smuggled = upload(repository.id(), tampered, "../../etc/passwd.jfr",
                        "JFR_RECORDING")
                .expectStatus().isCreated()
                .expectBody(RuntimeArtifactView.class)
                .returnResult()
                .getResponseBody();
        assertThat(smuggled).isNotNull();
        assertThat(smuggled.originalFilename()).isEqualTo("passwd.jfr");
        assertThat(smuggled.id()).isNotEqualTo(uploaded.id());
        assertThat(artifacts.findById(uploaded.id()).orElseThrow().getStorageRef())
                .doesNotContain("escape")
                .doesNotContain("passwd")
                .doesNotContain("..");

        // 2. Refusals. A file that is not a recording is rejected before any job is queued, and the
        // message says what was found rather than failing a moment later inside the reader.
        upload(repository.id(), "this is a text file\n".getBytes(StandardCharsets.UTF_8), "notes.jfr",
                "JFR_RECORDING")
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody().jsonPath("$.errorCode").isEqualTo("UNSUPPORTED_INPUT");
        upload(repository.id(), "FLR\u0000pretend".getBytes(StandardCharsets.UTF_8), "mystery.bin", null)
                .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
                .expectBody().jsonPath("$.errorCode").isEqualTo("VALIDATION_FAILED");
        upload(repository.id(), "FLR\u0000pretend".getBytes(StandardCharsets.UTF_8), "mystery.bin", "NOT_A_TYPE")
                .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
                .expectBody().jsonPath("$.errorCode").isEqualTo("VALIDATION_FAILED");

        // 3. A file uploaded as a heap dump that is not one is refused at upload, naming the shape that
        // was expected. Heap dumps are read by this build now, so the refusal comes before a job rather
        // than after it; reading one is covered by HeapDumpAnalysisIntegrationTest.
        upload(repository.id(), "not really a heap dump".getBytes(StandardCharsets.UTF_8),
                "sample.hprof", "HEAP_DUMP")
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody().jsonPath("$.errorCode").isEqualTo("UNSUPPORTED_INPUT");

        // 4. Queue the analysis and let the dispatcher run it.
        JobView submitted = submit(repository.id(), uploaded.id());
        assertThat(submitted.jobType()).isEqualTo(JobType.JFR_ANALYSIS);
        assertThat(submitted.repositoryId()).isEqualTo(repository.id());
        assertThat(submitted.status()).isEqualTo(JobStatus.QUEUED);
        // The job carries the artefact to read as a parameter.
        assertThat(jobRepository.findById(submitted.id()).orElseThrow().getParameters())
                .contains(uploaded.id().toString());

        AnalysisJob completed = awaitTerminal(submitted.id());
        assertThat(completed.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        UUID runId = completed.getAnalysisRunId();
        assertThat(runId).isNotNull();

        // 5. The run. A JFR run reports measurements, and reports zero findings: the two counts are
        // separate columns precisely so a runtime run does not read as "0 findings, all clear".
        RuntimeSummaryView summary = runtimeQueries.summary(repository.id(), null);
        assertThat(summary.runId()).isEqualTo(runId);
        assertThat(summary.status()).isEqualTo(AnalysisRunStatus.SUCCEEDED);
        assertThat(summary.measurementCount()).isPositive();
        assertThat(summary.artifactName()).isEqualTo("sample.jfr");
        assertThat(summary.artifactHash()).isEqualTo(expectedHash);
        assertThat(summary.artifactSizeBytes()).isEqualTo(recording.length);
        assertThat(summary.byMetricFamily()).containsKeys("events", "execution", "gc", "recording");
        assertThat(summary.summary()).contains("no threshold applied").contains("no severity assigned");
        assertThat(summary.analyzerVersions()).contains("jfr-reader");

        AnalysisRun run = runs.findById(runId).orElseThrow();
        assertThat(run.getRunType()).isEqualTo(AnalysisRunType.JFR_ANALYSIS);
        assertThat(run.getFindingCount()).isZero();
        assertThat(run.getMeasurementCount()).isEqualTo(summary.measurementCount());

        // Provenance is stored on the artefact: the JVM and the host, never the command line.
        RuntimeArtifact analysed = artifacts.findById(uploaded.id()).orElseThrow();
        assertThat(analysed.getAnalysisRunId()).isEqualTo(runId);
        assertThat(analysed.getMetadataJson()).isNotNull();
        JsonNode provenance = (JsonNode) summary.provenanceJson();
        assertThat(provenance).isNotNull();
        assertThat(provenance.at("/environment/jvmName").asText()).isNotBlank();
        assertThat(provenance.at("/environment/jvmVersion").asText()).isNotBlank();
        assertThat(provenance.at("/environment/physicalMemoryBytes").asLong()).isPositive();
        assertThat(provenance.at("/recording/events").asLong()).isPositive();
        assertThat(provenance.at("/produced/thresholdsApplied").asBoolean()).isFalse();
        assertThat(analysed.getMetadataJson())
                .doesNotContain("java.class.path")
                .doesNotContain("java.home")
                .doesNotContain("CLASSPATH");

        // 6. Measurements over HTTP, filtered by key prefix, every row self-describing.
        PageResponse<RuntimeMeasurementView> executionRows = client.get()
                .uri("/api/v1/repositories/{id}/runtime/measurements?metric=jfr.execution&size=200",
                        repository.id())
                .header("Authorization", BEARER)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<PageResponse<RuntimeMeasurementView>>() {
                })
                .returnResult()
                .getResponseBody();
        assertThat(executionRows).isNotNull();
        assertThat(executionRows.items()).isNotEmpty();
        assertThat(executionRows.totalItems()).isEqualTo(executionRows.items().size());
        assertThat(executionRows.items()).allSatisfy(row -> {
            assertThat(row.metricKey()).startsWith("jfr.execution");
            assertThat(row.analysisRunId()).isEqualTo(runId);
            assertThat(row.artifactId()).isEqualTo(uploaded.id());
            assertThat(row.unit()).isNotBlank();
            assertThat(row.sampleCount()).isNotNegative();
        });
        assertThat(executionRows.items()).anySatisfy(row -> {
            assertThat(row.metricKey()).isEqualTo("jfr.execution.hotFrame");
            assertThat(row.scope()).isNotBlank();
            assertThat(row.attributesJson()).contains("share");
        });

        // Emission shares are per sample: they cannot add up to more samples than were taken.
        long samples = (long) executionRows.items().stream()
                .filter(row -> "jfr.execution.samples".equals(row.metricKey()))
                .findFirst()
                .orElseThrow()
                .value();
        double hotFrameShares = executionRows.items().stream()
                .filter(row -> "jfr.execution.hotFrame".equals(row.metricKey()))
                .mapToDouble(RuntimeMeasurementView::value)
                .sum();
        assertThat(samples).isPositive();
        assertThat(hotFrameShares).isLessThanOrEqualTo(samples);
        assertThat(executionRows.items()).anySatisfy(row ->
                assertThat(row.metricKey()).isEqualTo("jfr.execution.sample.interval"));

        // GC pause and wall duration are different quantities, and are stored as different keys with
        // the same unit and kind, so neither can be mistaken for the other.
        PageResponse<RuntimeMeasurementView> gcRows = client.get()
                .uri("/api/v1/repositories/{id}/runtime/measurements?metric=jfr.gc&kind=DURATION&size=100",
                        repository.id())
                .header("Authorization", BEARER)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<PageResponse<RuntimeMeasurementView>>() {
                })
                .returnResult()
                .getResponseBody();
        assertThat(gcRows).isNotNull();
        assertThat(gcRows.items()).isNotEmpty();
        assertThat(gcRows.items()).allSatisfy(row -> {
            assertThat(row.metricKind()).isEqualTo(MetricKind.DURATION);
            assertThat(row.unit()).isEqualTo("ms");
        });
        assertThat(gcRows.items()).anySatisfy(row ->
                assertThat(row.metricKey()).isEqualTo("jfr.gc.pause.max"));

        // A filter that matches nothing is an empty page; the summary is what reports that the run
        // itself existed.
        assertThat(runtimeQueries.measurements(repository.id(),
                new RuntimeMeasurementFilter(null, "jfr.no.such.metric", null, null),
                PageRequest.of(0, 10)).getTotalElements()).isZero();

        // 7. The artefact list reports what has been read from each file.
        RuntimeArtifactView readBack = runtimeQueries.artifact(repository.id(), uploaded.id());
        assertThat(readBack.measurementCount()).isEqualTo(summary.measurementCount());
        assertThat(readBack.analysisRunId()).isEqualTo(runId);

        // 8. A static-analysis summary for the same repository is still empty: run types do not read
        // each other's rows, so a runtime run cannot make static analysis look analysed.
        assertThat(analysisQueries.summary(repository.id(), null, AnalysisRunType.STATIC_ANALYSIS).total())
                .isZero();

        // 9. A second run appends. Both runs keep their own measurements, so "what changed since last
        // time" stays answerable.
        long firstRunRows = measurements.countByAnalysisRunId(runId);
        AnalysisJob second = awaitTerminal(submit(repository.id(), uploaded.id()).id());
        assertThat(second.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(second.getAnalysisRunId()).isNotEqualTo(runId);
        assertThat(measurements.countByAnalysisRunId(runId)).isEqualTo(firstRunRows);
        assertThat(measurements.countByAnalysisRunId(second.getAnalysisRunId())).isEqualTo(firstRunRows);
        assertThat(runtimeQueries.summary(repository.id(), null).runId()).isEqualTo(second.getAnalysisRunId());
        assertThat(runtimeQueries.summary(repository.id(), runId).runId()).isEqualTo(runId);

        // A run id from another repository is not readable through this one.
        RepositorySummary other = register("other");
        assertThat(runtimeQueries.summary(other.id(), second.getAnalysisRunId()).runId()).isNull();
    }

    @Test
    @DisplayName("reports a failed run rather than silence when the recording cannot be read")
    void reportsAFailedRun() throws Exception {
        RepositorySummary repository = register("broken");
        // A file with the recording header and rubbish after it: it passes ingest, then the reader
        // fails. The run must exist and be FAILED, because "I uploaded a recording and nothing
        // appeared" is not an acceptable answer.
        RuntimeArtifactView uploaded = upload(repository.id(),
                "FLR\u0000garbage".getBytes(StandardCharsets.UTF_8), "truncated.jfr", "JFR_RECORDING")
                .expectStatus().isCreated()
                .expectBody(RuntimeArtifactView.class)
                .returnResult()
                .getResponseBody();
        assertThat(uploaded).isNotNull();

        AnalysisJob failed = awaitTerminal(submit(repository.id(), uploaded.id()).id());

        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo("JOB_FAILED");

        RuntimeSummaryView summary = runtimeQueries.summary(repository.id(), null);
        assertThat(summary.runId()).isNotNull();
        assertThat(summary.status()).isEqualTo(AnalysisRunStatus.FAILED);
        assertThat(summary.summary()).contains("could not be read");
        assertThat(summary.measurementCount()).isZero();
        assertThat(summary.artifactName()).isEqualTo("truncated.jfr");
        // The failed attempt is still the recorded history of the artefact.
        assertThat(artifacts.findById(uploaded.id()).orElseThrow().getAnalysisRunId())
                .isEqualTo(summary.runId());

        purgeStoredFile(uploaded.id());
    }

    // ------------------------------------------------------------------ helpers

    private RepositorySummary register(String name) throws IOException {
        Path root = workspaceRoot.resolve(name);
        Files.createDirectories(root);
        Files.writeString(root.resolve("README.txt"), "runtime artefact host\n", StandardCharsets.UTF_8);
        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);
        return repositories.register(workspace.id(), name, root.toAbsolutePath().toString());
    }

    private WebTestClient.ResponseSpec upload(UUID repositoryId, byte[] bytes, String filename, String type) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(bytes)).filename(filename);
        String uri = "/api/v1/repositories/" + repositoryId + "/runtime/artifacts"
                + (type == null ? "" : "?artifactType=" + type);
        return client.post().uri(uri)
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

    /** Drives dispatch passes until the job reaches a terminal state, and fails if it does not. */
    private AnalysisJob awaitTerminal(UUID jobId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
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
        throw new AssertionError("job " + jobId + " did not finish within the deadline; status was "
                + jobRepository.findById(jobId).map(job -> job.getStatus().name()).orElse("missing"));
    }

    /** A recording made here, with events enabled so the assertions have something to be sure of. */
    private static byte[] recording() throws Exception {
        Path file = Files.createTempFile(dataDir, "integration-", ".jfr");
        Path payload = dataDir.resolve("integration-payload.bin");
        byte[] bytes = new byte[512_000];
        new Random(7).nextBytes(bytes);

        try (jdk.jfr.Recording recording = new jdk.jfr.Recording(
                jdk.jfr.Configuration.getConfiguration("profile"))) {
            recording.setName("repopilot-integration-test");
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            recording.enable("jdk.ThreadSleep").with("threshold", "0 ns");
            recording.enable("jdk.FileRead").with("threshold", "0 ns");
            recording.enable("jdk.ObjectAllocationSample");
            recording.start();

            Files.write(payload, bytes);
            Files.readAllBytes(payload);
            Thread.sleep(30);
            burnCpu(Duration.ofMillis(500));
            allocate();
            System.gc();
            Thread.sleep(30);

            recording.stop();
            recording.dump(file);
        }
        Files.deleteIfExists(payload);
        byte[] recording = Files.readAllBytes(file);
        Files.deleteIfExists(file);
        return recording;
    }

    private static void burnCpu(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        long accumulator = 1;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 1_000; i++) {
                accumulator = accumulator * 31 + i;
            }
        }
        sink = accumulator;
    }

    private static void allocate() {
        Object[] blocks = new Object[64];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = new byte[256 * 1024];
        }
        sink = blocks.length;
    }

    /** Keeps the workload's result observable so the compiler cannot elide the work. */
    private static volatile long sink;

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Removes a stored file, nudging a collection first.
     *
     * <p>The JDK's {@code RecordingFile} keeps its channel open when it fails to parse a file, and
     * there is no way to close it from outside, so the file stays locked until the next collection
     * releases it. That is a real limitation of the platform &mdash; recorded in
     * docs/adr/0008-runtime-evidence.md &mdash; and this test tidies up after it rather than leaving a
     * locked file for the temporary-directory cleanup to trip over.
     */
    private void purgeStoredFile(UUID artifactId) {
        Path file = store.resolve(artifacts.findById(artifactId).orElseThrow().getStorageRef());
        for (int attempt = 0; attempt < 20 && Files.exists(file); attempt++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException stillLocked) {
                // Retried after the next collection.
            }
        }
    }
}
