package dev.repopilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import dev.repopilot.analysis.AnalysisRun;
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
import dev.repopilot.runtime.heapdump.TestHeapDump;
import dev.repopilot.runtime.query.RuntimeArtifactView;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end heap-dump evidence: upload a real dump over HTTP, queue its analysis, let the dispatcher
 * run it, and read the measurements back through the API.
 *
 * <p>The dump is a real one, taken with {@code jcmd GC.heap_dump} from a JVM built for the purpose
 * with a known object population ({@link HeapDumpSubject}), so the numbers that come back out of the
 * API can be checked against the numbers that went in. Nothing on the path is stubbed: the same
 * multipart ingest, the same header check, the same artefact store, the same queue and the same
 * handler as production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "repopilot.security.token=integration-test-bearer-token-0005",
        "repopilot.ai.enabled=false",
        "repopilot.jobs.dispatch-interval-millis=3600000"
})
@AutoConfigureWebTestClient
class HeapDumpAnalysisIntegrationTest {

    private static final String BEARER = "Bearer integration-test-bearer-token-0005";

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
    private RuntimeQueryService runtimeQueries;

    @Test
    @DisplayName("uploads a heap dump, analyses it, and reads the census back")
    void uploadsAnalysesAndReadsBack() throws Exception {
        assumeTrue(TestHeapDump.isAvailable(), "no jcmd in this JDK, so no dump can be taken");
        RepositorySummary repository = register("memory");
        byte[] dump = Files.readAllBytes(TestHeapDump.capture(dataDir));

        assertThat(runtimeQueries.summary(repository.id(), null).runId()).isNull();

        // 1. Upload. A heap dump is supported, and it is process memory: its whole subject is what the
        // application was holding, which is what the flag exists to say.
        RuntimeArtifactView uploaded = upload(repository.id(), dump, "heap.hprof", "HEAP_DUMP")
                .expectStatus().isCreated()
                .expectBody(RuntimeArtifactView.class)
                .returnResult()
                .getResponseBody();
        assertThat(uploaded).isNotNull();
        assertThat(uploaded.artifactType()).isEqualTo(RuntimeArtifactType.HEAP_DUMP);
        assertThat(uploaded.supported()).isTrue();
        assertThat(uploaded.unsupportedReason()).isNull();
        assertThat(uploaded.containsProcessMemory()).isTrue();

        // The same bytes uploaded again resolve to the same artefact: a dump is content-addressed, and
        // an operator who uploads the file twice does not get two copies of it.
        RuntimeArtifactView again = upload(repository.id(), dump, "heap-again.hprof", "HEAP_DUMP")
                .expectStatus().isCreated()
                .expectBody(RuntimeArtifactView.class)
                .returnResult()
                .getResponseBody();
        assertThat(again).isNotNull();
        assertThat(again.id()).isEqualTo(uploaded.id());
        assertThat(runtimeQueries.artifacts(repository.id(), PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(1);

        // 2. Queue the analysis and let the dispatcher run it.
        JobView submitted = submit(repository.id(), uploaded.id());
        assertThat(submitted.jobType()).isEqualTo(JobType.HEAP_ANALYSIS);
        assertThat(submitted.status()).isEqualTo(JobStatus.QUEUED);

        AnalysisJob completed = awaitTerminal(submitted.id());
        assertThat(completed.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        UUID runId = completed.getAnalysisRunId();
        assertThat(runId).isNotNull();

        AnalysisRun run = runs.findById(runId).orElseThrow();
        assertThat(run.getRunType()).isEqualTo(AnalysisRunType.HEAP_ANALYSIS);
        // A runtime run reports measurements and no findings, and the two counts are separate columns
        // so that a dump cannot read as "nothing found, all clear".
        assertThat(run.getFindingCount()).isZero();
        assertThat(run.getMeasurementCount()).isPositive();

        // 3. The summary, which is what an operator sees first.
        RuntimeSummaryView summary = runtimeQueries.summary(repository.id(), null);
        assertThat(summary.runId()).isEqualTo(runId);
        assertThat(summary.status()).isEqualTo(AnalysisRunStatus.SUCCEEDED);
        assertThat(summary.artifactName()).isEqualTo("heap.hprof");
        assertThat(summary.measurementCount()).isEqualTo(run.getMeasurementCount());
        assertThat(summary.byMetricFamily()).containsKeys("objects", "types", "arrays", "instances", "roots");
        assertThat(summary.summary())
                .contains("recorded byte(s)")
                .contains("still reachable")
                .contains("no threshold applied")
                .contains("no severity assigned")
                .contains("no retained size computed");
        assertThat(summary.analyzerVersions()).contains("heap-dump-reader");

        // 4. Provenance: the header, the file's size and the census, and nothing of the heap's contents
        // beyond it. In particular no type names, because those belong next to the numbers derived from
        // them rather than in a blob that gets copied around.
        JsonNode provenance = (JsonNode) summary.provenanceJson();
        assertThat(provenance).isNotNull();
        assertThat(provenance.at("/environment/formatVersion").asText()).startsWith("1.0.");
        assertThat(provenance.at("/environment/identifierSize").asInt()).isIn(4, 8);
        assertThat(provenance.at("/dump/takenAt").asText()).isNotBlank();
        assertThat(provenance.at("/dump/fileBytes").asLong()).isEqualTo(dump.length);
        assertThat(provenance.at("/heap/objects").asLong()).isPositive();
        assertThat(provenance.at("/heap/types").asLong()).isPositive();
        assertThat(provenance.at("/produced/findings").asInt()).isZero();
        assertThat(provenance.at("/produced/thresholdsApplied").asBoolean()).isFalse();
        assertThat(provenance.at("/produced/retainedSizesComputed").asBoolean()).isFalse();
        assertThat(summary.provenanceJson().toString()).doesNotContain("$Node").doesNotContain("$Bucket");

        assertThat(runtimeQueries.artifact(repository.id(), uploaded.id()).analysisRunId()).isEqualTo(runId);

        // 5. Measurements over HTTP, filtered by key prefix, every row self-describing.
        PageResponse<RuntimeMeasurementView> totals = rows(repository.id(), "heapdump.objects", 200);
        assertThat(totals.totalItems()).isGreaterThanOrEqualTo(2);
        assertThat(value(totals, "heapdump.objects.count")).isGreaterThan(1000);
        assertThat(value(totals, "heapdump.objects.bytes")).isPositive();
        assertThat(totals.items()).allSatisfy(row -> {
            assertThat(row.analysisRunId()).isEqualTo(runId);
            assertThat(row.artifactId()).isEqualTo(uploaded.id());
            assertThat(row.unit()).isNotBlank();
        });

        // The type tables: cut at twenty, with the number that was cut stated, and a basis on every row
        // so a byte figure cannot be read as a retained size.
        PageResponse<RuntimeMeasurementView> typeRows = rows(repository.id(), "heapdump.types.bytes", 200);
        assertThat(value(typeRows, "heapdump.types.bytes.reported")).isEqualTo(20);
        assertThat(value(typeRows, "heapdump.types.bytes.observed")).isGreaterThan(100);
        assertThat(typeRows.items())
                .filteredOn(row -> row.metricKey().equals("heapdump.types.bytes"))
                .hasSize(20)
                .allSatisfy(row -> {
                    assertThat(row.scope()).isNotBlank();
                    assertThat(row.unit()).isEqualTo("bytes");
                    assertThat(row.metricKind()).isEqualTo(MetricKind.SIZE);
                    assertThat(row.attributesJson())
                            .contains("basis")
                            .contains("share")
                            .contains("kind");
                });

        // The largest type by recorded bytes is a real type name, in source form.
        RuntimeMeasurementView largest = typeRows.items().stream()
                .filter(row -> row.metricKey().equals("heapdump.types.bytes"))
                .findFirst()
                .orElseThrow();
        assertThat(largest.scope()).doesNotContain("/").doesNotStartWith("[");
        assertThat(largest.value()).isPositive();

        // The census is internally consistent: the parts add up to the whole, which is checked here
        // rather than trusted, because both figures come from the same walk.
        PageResponse<RuntimeMeasurementView> census = rows(repository.id(), "heapdump.", 200);
        assertThat(value(census, "heapdump.instances.count") + value(census, "heapdump.arrays.count"))
                .isEqualTo(value(census, "heapdump.objects.count"));
        assertThat(value(census, "heapdump.instances.bytes") + value(census, "heapdump.arrays.bytes"))
                .isEqualTo(value(census, "heapdump.objects.bytes"));

        // The run is the repository's current runtime run, and asking for it by id returns it.
        assertThat(runtimeQueries.summary(repository.id(), runId).runId()).isEqualTo(runId);
    }

    @Test
    @DisplayName("refuses a class histogram uploaded as a heap dump, by name")
    void refusesAClassHistogram() throws Exception {
        RepositorySummary repository = register("histogram");
        // The file an operator is most likely to have collected by mistake. It is refused before a job
        // is spent, and the message names the tool that produces a real dump instead.
        String histogram = "6128:\n num     #instances         #bytes  class name (module)\n"
                + "-------------------------------------------------------\n"
                + "   1:         13521        2046144  [B (java.base@21.0.5)\n"
                + "   2:         12312         172368  java.lang.String (java.base@21.0.5)\n"
                + "Total        25833        2218512\n";

        upload(repository.id(), histogram.getBytes(StandardCharsets.UTF_8), "histo.hprof", "HEAP_DUMP")
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("UNSUPPORTED_INPUT")
                .jsonPath("$.message").value(org.hamcrest.Matchers.containsString("jmap -histo"))
                .jsonPath("$.message").value(org.hamcrest.Matchers.containsString("GC.heap_dump"));
    }

    // ------------------------------------------------------------------ helpers

    private RepositorySummary register(String name) throws IOException {
        Path root = workspaceRoot.resolve(name);
        Files.createDirectories(root);
        Files.writeString(root.resolve("README.txt"), "heap dump host\n", StandardCharsets.UTF_8);
        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);
        return repositories.register(workspace.id(), name, root.toAbsolutePath().toString());
    }

    private PageResponse<RuntimeMeasurementView> rows(UUID repositoryId, String metric, int size) {
        PageResponse<RuntimeMeasurementView> page = client.get()
                .uri("/api/v1/repositories/{id}/runtime/measurements?metric=" + metric + "&size=" + size,
                        repositoryId)
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

    private static double value(PageResponse<RuntimeMeasurementView> page, String key) {
        return page.items().stream()
                .filter(row -> row.metricKey().equals(key))
                .mapToDouble(RuntimeMeasurementView::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no measurement " + key));
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
