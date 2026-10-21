package dev.repopilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import dev.repopilot.analysis.AnalysisRunRepository;
import dev.repopilot.analysis.AnalysisRunStatus;
import dev.repopilot.analysis.AnalysisRunType;
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
import dev.repopilot.runtime.query.RuntimeMeasurementView;
import dev.repopilot.runtime.query.RuntimeQueryService;
import dev.repopilot.runtime.query.RuntimeSummaryView;
import dev.repopilot.runtime.threaddump.RealThreadDump;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end thread-dump evidence: upload a dump over HTTP, queue its analysis, let the dispatcher
 * run it, and read the measurements back through the API.
 *
 * <p>The dump is of the JVM running the tests, taken through {@code jcmd} exactly as an operator
 * would take one. Nothing about the path is stubbed: the same multipart ingest, the same artefact
 * store, the same queue, the same handler and the same completion transition as production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "repopilot.security.token=integration-test-bearer-token-0004",
        "repopilot.ai.enabled=false",
        "repopilot.jobs.dispatch-interval-millis=3600000",
        "repopilot.jobs.dispatch-initial-delay-millis=3600000"
})
@AutoConfigureWebTestClient
class ThreadDumpAnalysisIntegrationTest {

    private static final String BEARER = "Bearer integration-test-bearer-token-0004";

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

    @Test
    @DisplayName("uploads a thread dump, analyses it, and reads the measurements back")
    void uploadsAnalysesAndReadsBack() throws Exception {
        assumeTrue(RealThreadDump.isAvailable(), "no jcmd in this JDK, so no dump can be taken");
        RepositorySummary repository = register("concurrency");
        byte[] dump = RealThreadDump.captureBytes(dataDir);
        String expectedHash = sha256(dump);

        RuntimeSummaryView empty = runtimeQueries.summary(repository.id(), null);
        assertThat(empty.runId()).isNull();

        // 1. Upload. A thread dump is supported, and it is not process memory: it names threads and
        // frames, which reveals structure without carrying the contents of the heap.
        RuntimeArtifactView uploaded = upload(repository.id(), dump, "thread-dump.txt", "THREAD_DUMP")
                .expectStatus().isCreated()
                .expectBody(RuntimeArtifactView.class)
                .returnResult()
                .getResponseBody();
        assertThat(uploaded).isNotNull();
        assertThat(uploaded.artifactType()).isEqualTo(RuntimeArtifactType.THREAD_DUMP);
        assertThat(uploaded.supported()).isTrue();
        assertThat(uploaded.unsupportedReason()).isNull();
        assertThat(uploaded.containsProcessMemory()).isFalse();
        assertThat(uploaded.contentHash()).isEqualTo(expectedHash);
        assertThat(uploaded.analysisRunId()).isNull();

        // 2. Queue it and let the dispatcher run it. The job type is the dump's, not the recording's.
        JobView submitted = submit(repository.id(), uploaded.id());
        assertThat(submitted.jobType()).isEqualTo(JobType.THREAD_DUMP_ANALYSIS);
        assertThat(submitted.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(jobRepository.findById(submitted.id()).orElseThrow().getParameters())
                .contains(uploaded.id().toString());

        AnalysisJob completed = awaitTerminal(submitted.id());
        assertThat(completed.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        UUID runId = completed.getAnalysisRunId();
        assertThat(runId).isNotNull();

        // 3. The run reports measurements and zero findings, and says so in its own words.
        RuntimeSummaryView summary = runtimeQueries.summary(repository.id(), null);
        assertThat(summary.runId()).isEqualTo(runId);
        assertThat(runs.findById(runId).orElseThrow().getRunType())
                .isEqualTo(AnalysisRunType.THREAD_DUMP_ANALYSIS);
        assertThat(summary.status()).isEqualTo(AnalysisRunStatus.SUCCEEDED);
        assertThat(summary.measurementCount()).isPositive();
        assertThat(summary.summary())
                .contains("thread dump")
                .contains("no threshold applied")
                .contains("no severity assigned")
                .contains("one instant");
        assertThat(summary.analyzerVersions()).contains("thread-dump-reader");
        assertThat(runs.findById(runId).orElseThrow().getFindingCount()).isZero();

        // The families are the segment after the namespace, and they are the dump's own, not a
        // recording's: a thread-dump run must not report GC in it.
        assertThat(summary.byMetricFamily()).containsKeys("threads", "monitors");
        assertThat(summary.byMetricFamily()).doesNotContainKey("gc");

        // 4. Measurements over HTTP. Every state a live thread can be in is reported, including the
        // empty ones, so an idle JVM is distinguishable from a dump this build cannot read.
        PageResponse<RuntimeMeasurementView> states = get(
                "/api/v1/repositories/{id}/runtime/measurements?metric=threaddump.threads.state&size=50",
                repository.id());
        assertThat(states.totalItems()).isEqualTo(4);
        assertThat(states.items()).extracting(RuntimeMeasurementView::scope)
                .containsExactlyInAnyOrder("RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING");
        assertThat(states.items()).allSatisfy(row -> {
            assertThat(row.analysisRunId()).isEqualTo(runId);
            assertThat(row.artifactId()).isEqualTo(uploaded.id());
            assertThat(row.unit()).isEqualTo("count");
            assertThat(row.metricKind()).isEqualTo(MetricKind.COUNTER);
        });
        // The JVM that took the dump always has its own threads, and the report says how many
        // threads it named without a Java state rather than leaving them out.
        PageResponse<RuntimeMeasurementView> census = get(
                "/api/v1/repositories/{id}/runtime/measurements?metric=threaddump.threads&size=50",
                repository.id());
        assertThat(census.items()).extracting(RuntimeMeasurementView::metricKey)
                .contains("threaddump.threads.count", "threaddump.threads.java.count",
                        "threaddump.threads.nonjava.count");
        assertThat(census.items()).anySatisfy(row -> {
            assertThat(row.metricKey()).isEqualTo("threaddump.threads.count");
            // A measurement that does not explain its basis is a number a reader has to trust.
            assertThat(row.attributesJson()).contains("basis");
        });

        // The filter is a prefix, so one namespace cannot reach into the other's rows.
        assertThat(get("/api/v1/repositories/{id}/runtime/measurements?metric=jfr&size=50", repository.id())
                .totalItems()).isZero();

        // 5. Provenance: the header and the counts, and never the stacks.
        RuntimeArtifact analysed = artifacts.findById(uploaded.id()).orElseThrow();
        assertThat(analysed.getAnalysisRunId()).isEqualTo(runId);
        JsonNode provenance = (JsonNode) summary.provenanceJson();
        assertThat(provenance).isNotNull();
        assertThat(provenance.at("/environment/jvmName").asText()).isNotBlank();
        assertThat(provenance.at("/environment/jvmVersion").asText()).isNotBlank();
        assertThat(provenance.at("/dump/takenAt").asText()).isNotBlank();
        assertThat(provenance.at("/dump/threads").asLong()).isPositive();
        assertThat(provenance.at("/dump/threadsWithoutJavaState").asLong()).isPositive();
        assertThat(provenance.at("/produced/thresholdsApplied").asBoolean()).isFalse();
        assertThat(provenance.at("/produced/findings").asInt()).isZero();
        // The dump's own histogram and its stacks stay in the file the operator uploaded. A thread
        // name from a deadlock cycle is the one exception, and it travels with the measurement it
        // belongs to.
        assertThat(analysed.getMetadataJson())
                .doesNotContain("java.lang.Thread.run")
                .doesNotContain("\tat ")
                .doesNotContain("waitForInterruptibly");

        // 6. The artefact reports what was read from it.
        RuntimeArtifactView readBack = runtimeQueries.artifact(repository.id(), uploaded.id());
        assertThat(readBack.measurementCount()).isEqualTo(summary.measurementCount());
        assertThat(readBack.analysisRunId()).isEqualTo(runId);
        assertThat(measurements.countByAnalysisRunId(runId)).isEqualTo(summary.measurementCount());
    }

    @Test
    @DisplayName("accepts a dump a Windows shell re-encoded as UTF-16, BOM and all")
    void acceptsAWindowsCapturedDump() throws Exception {
        assumeTrue(RealThreadDump.isAvailable(), "no jcmd in this JDK, so no dump can be taken");
        RepositorySummary repository = register("windows-shell");
        // `jcmd <pid> Thread.print > dump.txt` in PowerShell writes UTF-16LE with a byte-order mark
        // because the shell transcodes the tool's output on the way to the file. This is the whole
        // defect the test guards: read as UTF-8, every line becomes every other byte, the header stops
        // being "Full thread dump ", and a good dump is refused at upload as "not a thread dump" —
        // refused because of the operator's terminal, which is not a property of the evidence.
        String text = new String(RealThreadDump.captureBytes(dataDir), StandardCharsets.UTF_8);
        byte[] utf16 = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] marked = new byte[utf16.length + 2];
        marked[0] = (byte) 0xFF;
        marked[1] = (byte) 0xFE;
        System.arraycopy(utf16, 0, marked, 2, utf16.length);

        RuntimeArtifactView uploaded = upload(repository.id(), marked, "dump-from-powershell.txt",
                "THREAD_DUMP")
                .expectStatus().isCreated()
                .expectBody(RuntimeArtifactView.class)
                .returnResult()
                .getResponseBody();
        assertThat(uploaded).isNotNull();
        assertThat(uploaded.supported()).isTrue();

        AnalysisJob completed = awaitTerminal(submit(repository.id(), uploaded.id()).id());
        assertThat(completed.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(completed.getErrorCode()).isNull();

        RuntimeSummaryView summary = runtimeQueries.summary(repository.id(), null);
        assertThat(summary.summary()).contains("thread(s)");
        assertThat(summary.provenanceJson()).isNotNull();
        String provenanceText = String.valueOf(summary.provenanceJson());
        // A BOM left on the first line would land in the provenance as a stray character, and the
        // first thing the reader reads is the process id.
        assertThat(provenanceText).doesNotContain("\uFEFF");
        // The JVM's own threads are counted, not silently dropped along with the encoding.
        assertThat(measurements.countByAnalysisRunId(completed.getAnalysisRunId())).isPositive();
    }

    @Test
    @DisplayName("refuses the dump shapes that carry no thread states, before storing them")
    void refusesDumpsWithNoStatesAtUpload() throws Exception {
        RepositorySummary repository = register("thin");

        // The plain and JSON `Thread.dump_to_file` shapes are thread dumps to a reader looking at the
        // filename, and useless to a concurrency inspection: no states, no monitors, no deadlocks.
        // They are refused by name, with the command that produces a readable dump.
        String thinPlain = String.join("\n", List.of(
                "9568",
                "2026-10-19T04:34:15.300714Z",
                "21.0.5+9-LTS-239",
                "",
                "#9 \"Reference Handler\"",
                "      java.base/java.lang.ref.Reference.waitForReferencePendingList(Native Method)"));

        for (String body : List.of(thinPlain, "{\n  \"threadDump\": {\n    \"threadId\": 1\n  }\n}")) {
            upload(repository.id(), body.getBytes(StandardCharsets.UTF_8), "dump.txt", "THREAD_DUMP")
                    .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                    .expectBody()
                    .jsonPath("$.errorCode").isEqualTo("UNSUPPORTED_INPUT")
                    .jsonPath("$.message").value(containsString("Thread.dump_to_file"))
                    .jsonPath("$.message").value(containsString("Thread.print"));
        }

        upload(repository.id(), "not a dump at all\n".getBytes(StandardCharsets.UTF_8), "notes.txt",
                "THREAD_DUMP")
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody().jsonPath("$.errorCode").isEqualTo("UNSUPPORTED_INPUT");

        // Nothing was stored: the artefact list never holds a file that cannot be analysed, so a
        // refused upload leaves no row the operator has to clean up.
        assertThat(runtimeQueries.artifacts(repository.id(), PageRequest.of(0, 10)).getTotalElements())
                .isZero();
        assertThat(runtimeQueries.summary(repository.id(), null).runId()).isNull();
    }

    @Test
    @DisplayName("reports a failed run rather than silence when the dump cannot be read")
    void reportsAFailedRun() throws Exception {
        RepositorySummary repository = register("stateless");

        // A dump whose header is right and whose threads state nothing. This is not hypothetical: a
        // JVM that does not print HotSpot's state line produces exactly this, and reading it would be
        // a page of zeros that looks like a healthy process. It passes ingest — the header is there —
        // and the run then fails with a reason, which is the same contract a truncated recording gets.
        String stateless = String.join("\n", List.of(
                "Full thread dump Some Other VM (1.0 mixed mode):",
                "",
                "\"main\" prio=6 tid=0x1 nid=0x1 runnable [0x1]",
                "\tat A.b(A.java:1)",
                "",
                "JNI global refs: 1, weak refs: 0"));

        RuntimeArtifactView uploaded = upload(repository.id(), stateless.getBytes(StandardCharsets.UTF_8),
                "other-vm.txt", "THREAD_DUMP")
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
        assertThat(runs.findById(summary.runId()).orElseThrow().getRunType())
                .isEqualTo(AnalysisRunType.THREAD_DUMP_ANALYSIS);
        assertThat(summary.status()).isEqualTo(AnalysisRunStatus.FAILED);
        assertThat(summary.summary())
                .contains("java.lang.Thread.State")
                .contains("zeros");
        assertThat(summary.measurementCount()).isZero();
        assertThat(summary.artifactName()).isEqualTo("other-vm.txt");
        assertThat(artifacts.findById(uploaded.id()).orElseThrow().getAnalysisRunId())
                .isEqualTo(summary.runId());
    }

    @Test
    @DisplayName("refuses a file uploaded as a heap dump that is not an HPROF dump")
    void refusesAFileThatIsNotAHeapDump() throws Exception {
        RepositorySummary repository = register("mismatch");
        // Now that heap dumps are read, a file that is not one is refused at upload rather than stored
        // as an artefact no reader can use. The refusal names the header it looked for; the message
        // itself is asserted where it is written, in HeapDumpReaderTest.
        upload(repository.id(), "not really a heap dump".getBytes(StandardCharsets.UTF_8),
                "sample.hprof", "HEAP_DUMP")
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody().jsonPath("$.errorCode").isEqualTo("UNSUPPORTED_INPUT");
    }

    // ------------------------------------------------------------------ helpers

    private RepositorySummary register(String name) throws IOException {
        Path root = workspaceRoot.resolve(name);
        Files.createDirectories(root);
        Files.writeString(root.resolve("README.txt"), "thread dump host\n", StandardCharsets.UTF_8);
        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);
        return repositories.register(workspace.id(), name, root.toAbsolutePath().toString());
    }

    private PageResponse<RuntimeMeasurementView> get(String uri, UUID repositoryId) {
        PageResponse<RuntimeMeasurementView> page = client.get().uri(uri, repositoryId)
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
        throw new AssertionError("job " + jobId + " did not finish within the deadline");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
