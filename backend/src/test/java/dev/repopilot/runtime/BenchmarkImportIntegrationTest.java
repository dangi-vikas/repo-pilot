package dev.repopilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import dev.repopilot.analysis.AnalysisRun;
import dev.repopilot.analysis.AnalysisRunRepository;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * End-to-end benchmark evidence: upload a real JMH result over HTTP, queue its import, let the
 * dispatcher run it, and read the quoted figures back through the API.
 *
 * <p>The file is the two-fork run recorded in {@code src/test/resources/benchmarks/} — see
 * {@code corpus/benchmarks/README.md} for the command and for the warning that it is a format fixture
 * and not a performance claim. Nothing on the path is stubbed: the same multipart ingest, the same shape
 * check, the same artefact store, the same queue and the same handler as production.
 *
 * <p>Three properties are asserted as hard as the numbers are. The rows are quoted
 * ({@code AS_REPORTED}) rather than reduced; nothing is compared with anything; and the JVM's command
 * line and binary path, both of which the file carries, are not stored anywhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "repopilot.security.token=integration-test-bearer-token-0006",
        "repopilot.ai.enabled=false",
        "repopilot.jobs.dispatch-interval-millis=3600000"
})
@AutoConfigureWebTestClient
class BenchmarkImportIntegrationTest {

    private static final String BEARER = "Bearer integration-test-bearer-token-0006";

    /** Derived from the two-fork fixture: 8 rate entries with a stated error (19 rows each), 6 sample entries (20 each) and 5 file-level rows. */
    private static final int TWO_FORK_MEASUREMENTS = 277;

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
    @DisplayName("imports a real result file, and every score comes back quoted rather than reduced")
    void importsARealResultFile() throws Exception {
        RepositorySummary repository = register("benchmarks");
        byte[] result = Files.readAllBytes(fixture("jmh-result-2-forks.json"));

        assertThat(runtimeQueries.summary(repository.id(), null).runId()).isNull();

        // 1. Upload. The type is inferred from the extension, the file is supported, and it is not
        // process memory: it is a table of numbers about a build.
        RuntimeArtifactView uploaded = upload(repository.id(), result, "jmh-result-2-forks.json", null)
                .expectStatus().isCreated()
                .expectBody(RuntimeArtifactView.class)
                .returnResult()
                .getResponseBody();
        assertThat(uploaded).isNotNull();
        assertThat(uploaded.artifactType()).isEqualTo(RuntimeArtifactType.BENCHMARK_RESULT);
        assertThat(uploaded.supported()).isTrue();
        assertThat(uploaded.unsupportedReason()).isNull();
        assertThat(uploaded.containsProcessMemory()).isFalse();
        assertThat(uploaded.purgedAt()).isNull();

        // 2. Queue the import and let the dispatcher run it.
        JobView submitted = submit(repository.id(), uploaded.id());
        assertThat(submitted.jobType()).isEqualTo(JobType.BENCHMARK_IMPORT);
        assertThat(submitted.status()).isEqualTo(JobStatus.QUEUED);

        AnalysisJob completed = awaitTerminal(submitted.id());
        assertThat(completed.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        UUID runId = completed.getAnalysisRunId();
        assertThat(runId).isNotNull();

        AnalysisRun run = runs.findById(runId).orElseThrow();
        assertThat(run.getRunType()).isEqualTo(AnalysisRunType.BENCHMARK_IMPORT);
        assertThat(run.getFindingCount()).isZero();
        assertThat(run.getMeasurementCount()).isEqualTo(TWO_FORK_MEASUREMENTS);

        // 3. The summary: what was read, and what a single file cannot support.
        RuntimeSummaryView summary = runtimeQueries.summary(repository.id(), null);
        assertThat(summary.runId()).isEqualTo(runId);
        assertThat(summary.artifactName()).isEqualTo("jmh-result-2-forks.json");
        assertThat(summary.measurementCount()).isEqualTo(TWO_FORK_MEASUREMENTS);
        assertThat(summary.summary())
                .contains("277 measurement(s) from 14 benchmark result entries")
                .contains("JMH 1.37")
                .contains("4 in throughput mode")
                .contains("4 in average-time mode")
                .contains("6 in sample mode")
                .contains("6 of 14 record no per-iteration values")
                .contains("no threshold applied")
                .contains("no severity assigned")
                .contains("nothing compared with anything");
        assertThat(summary.analyzerVersions()).contains("benchmark-reader=1.0.0");

        // 4. Provenance: the harness and the file's own facts, and explicitly not a comparison.
        JsonNode provenance = (JsonNode) summary.provenanceJson();
        assertThat(provenance.at("/environment/jmhVersion").asText()).isEqualTo("1.37");
        assertThat(provenance.at("/environment/jdkVersion").asText()).isEqualTo("21.0.5");
        assertThat(provenance.at("/benchmark/entries").asInt()).isEqualTo(14);
        assertThat(provenance.at("/benchmark/modes").size()).isEqualTo(3);
        assertThat(provenance.at("/benchmark/parameters").get(0).asText()).isEqualTo("size");
        assertThat(provenance.at("/benchmark/jvmArgumentsRecorded").asInt()).isZero();
        assertThat(provenance.at("/benchmark/entriesWithoutIterations").asInt()).isEqualTo(6);
        assertThat(provenance.at("/benchmark/entriesWithoutErrorEstimate").asInt()).isZero();
        assertThat(provenance.at("/produced/measurements").asInt()).isEqualTo(TWO_FORK_MEASUREMENTS);
        assertThat(provenance.at("/produced/thresholdsApplied").asBoolean()).isFalse();
        assertThat(provenance.at("/produced/comparisonsMade").asBoolean()).isFalse();
        assertThat(provenance.at("/produced/findings").asInt()).isZero();

        // 5. One score, exactly as the file states it: two forks of three iterations, so a real error
        // and a real confidence interval, in the harness's own unit.
        PageResponse<RuntimeMeasurementView> throughput = rows(repository.id(), "benchmark.throughput", 300);
        RuntimeMeasurementView score = only(throughput, "benchmark.throughput.score", "mapLookup", "size=16");
        assertThat(score.value()).isEqualTo(10.359395201829896);
        assertThat(score.unit()).isEqualTo("ops/us");
        assertThat(score.metricKind()).isEqualTo(MetricKind.GAUGE);
        assertThat(score.aggregation()).isEqualTo(MeasurementAggregation.AS_REPORTED);
        assertThat(score.sampleCount()).isEqualTo(6);
        assertThat(score.attributesJson())
                .contains("\"mode\":\"thrpt\"")
                .contains("checked against the file it came from")
                .contains("No threshold was applied");

        assertThat(only(throughput, "benchmark.throughput.scoreError", "mapLookup", "size=16").value())
                .isEqualTo(3.259704679281219);
        assertThat(only(throughput, "benchmark.throughput.confidence.lower", "mapLookup", "size=16").value())
                .isEqualTo(7.099690522548677);

        // The declared iteration count is what the benchmark was configured with; the recorded one is
        // what the file holds across both forks. They are different numbers and are stored as such.
        assertThat(only(throughput, "benchmark.throughput.measurement.iterations", "mapLookup", "size=16").value())
                .isEqualTo(3);
        assertThat(only(throughput, "benchmark.throughput.iterations", "mapLookup", "size=16").value())
                .isEqualTo(6);

        // Sample mode has no per-iteration values to check against, and says so.
        PageResponse<RuntimeMeasurementView> sample = rows(repository.id(), "benchmark.sampleTime.score", 100);
        assertThat(sample.items()).filteredOn(row -> row.metricKey().equals("benchmark.sampleTime.score"))
                .hasSize(6)
                .allSatisfy(row -> {
                    assertThat(row.attributesJson()).contains("not checkable");
                    // Sample mode records a histogram rather than per-iteration values, so the score is
                    // not a reduction over samples RepoPilot holds and carries no sample count.
                    assertThat(row.sampleCount()).isZero();
                });

        // The file-level counts are rows, not provenance only: a reader who sees 14 scores must also be
        // able to see how many of them could be checked.
        PageResponse<RuntimeMeasurementView> file = rows(repository.id(), "benchmark.file", 100);
        assertThat(value(file, "benchmark.file.entries")).isEqualTo(14);
        assertThat(value(file, "benchmark.file.entries.withoutIterations")).isEqualTo(6);
        assertThat(value(file, "benchmark.file.jvmArguments")).isZero();

        assertThat(runtimeQueries.artifact(repository.id(), uploaded.id()).analysisRunId()).isEqualTo(runId);
    }

    @Test
    @DisplayName("refuses the other shapes JMH writes, by name, before a job is spent")
    void refusesTheOtherShapes() throws Exception {
        RepositorySummary repository = register("shapes");

        upload(repository.id(), csv().getBytes(StandardCharsets.UTF_8), "results.csv", "BENCHMARK_RESULT")
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("UNSUPPORTED_INPUT")
                .jsonPath("$.message").value(containsString("-rf json"))
                .jsonPath("$.message").value(containsString("per-iteration"));

        upload(repository.id(), text().getBytes(StandardCharsets.UTF_8), "results.txt", "BENCHMARK_RESULT")
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("UNSUPPORTED_INPUT")
                .jsonPath("$.message").value(containsString("rounded"))
                .jsonPath("$.message").value(containsString("-rf json"));

        upload(repository.id(), gzip("[{\"benchmark\" : \"dev.repopilot.Work.run\"}]"), "results.json.gz",
                "BENCHMARK_RESULT")
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("UNSUPPORTED_INPUT")
                .jsonPath("$.message").value(containsString("gzip -d"));

        upload(repository.id(), "{\"benchmark\" : \"dev.repopilot.Work.run\"}".getBytes(StandardCharsets.UTF_8),
                "one-result.json", "BENCHMARK_RESULT")
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("UNSUPPORTED_INPUT")
                .jsonPath("$.message").value(containsString("array"));

        // A refused upload leaves nothing behind: no artefact, no job, no run.
        assertThat(runtimeQueries.artifacts(repository.id(), org.springframework.data.domain.PageRequest.of(0, 10))
                .getTotalElements()).isZero();
    }

    @Test
    @DisplayName("a file array that is not a result file fails the run rather than storing nothing quietly")
    void failsAnArrayThatIsNotAResultFile() throws Exception {
        RepositorySummary repository = register("not-a-result");

        RuntimeArtifactView uploaded = upload(repository.id(), "[{\"hello\" : \"world\"}]"
                        .getBytes(StandardCharsets.UTF_8), "not-results.json", "BENCHMARK_RESULT")
                .expectStatus().isCreated()
                .expectBody(RuntimeArtifactView.class)
                .returnResult()
                .getResponseBody();
        assertThat(uploaded).isNotNull();

        AnalysisJob completed = awaitTerminal(submit(repository.id(), uploaded.id()).id());

        assertThat(completed.getStatus()).isEqualTo(JobStatus.FAILED);
        // The job's own code is the lifecycle outcome; the domain reason travels in the message, which is
        // the one a user reads.
        assertThat(completed.getErrorCode()).isEqualTo("JOB_FAILED");
        assertThat(completed.getErrorMessage())
                .contains("no `benchmark` field")
                .contains("-rf json");
        assertThat(runtimeQueries.summary(repository.id(), null).measurementCount()).isZero();
    }

    @Test
    @DisplayName("the JVM's command line and binary path are counted, never stored")
    void keepsTheCommandLineOutOfWhatItStores() throws Exception {
        RepositorySummary repository = register("secrets");
        String json = """
                [{
                  "jmhVersion" : "1.37",
                  "benchmark" : "dev.repopilot.fixture.Work.run",
                  "mode" : "thrpt",
                  "threads" : 1,
                  "forks" : 1,
                  "jvm" : "C:\\\\Users\\\\secret-operator\\\\jdk-21\\\\bin\\\\java.exe",
                  "jvmArgs" : ["-Ddb.password=hunter2", "-Dapi.token=do-not-store-me"],
                  "jdkVersion" : "21.0.5",
                  "vmName" : "Java HotSpot(TM) 64-Bit Server VM",
                  "vmVersion" : "21.0.5+9-LTS-239",
                  "warmupIterations" : 1,
                  "warmupTime" : "500 ms",
                  "measurementIterations" : 2,
                  "measurementTime" : "500 ms",
                  "primaryMetric" : {
                    "score" : 1.5, "scoreError" : 0.25, "scoreUnit" : "ops/us",
                    "scoreConfidence" : [1.0, 2.0],
                    "scorePercentiles" : {"99.0" : 2.0},
                    "rawData" : [[1.0, 2.0]]
                  }
                }]
                """;

        RuntimeArtifactView uploaded = upload(repository.id(), json.getBytes(StandardCharsets.UTF_8),
                "with-secrets.json", "BENCHMARK_RESULT")
                .expectStatus().isCreated()
                .expectBody(RuntimeArtifactView.class)
                .returnResult()
                .getResponseBody();
        assertThat(uploaded).isNotNull();
        AnalysisJob completed = awaitTerminal(submit(repository.id(), uploaded.id()).id());
        assertThat(completed.getStatus()).isEqualTo(JobStatus.SUCCEEDED);

        RuntimeSummaryView summary = runtimeQueries.summary(repository.id(), null);
        JsonNode provenance = (JsonNode) summary.provenanceJson();
        assertThat(provenance.at("/benchmark/jvmArgumentsRecorded").asInt()).isEqualTo(2);

        // The count is kept; not one character of the values is. The check runs over everything the API
        // can return for this repository, not only over the provenance block.
        String stored = provenance.toString()
                + summary.summary()
                + rows(repository.id(), "benchmark.", 100).items().toString()
                + runtimeQueries.artifact(repository.id(), uploaded.id()).toString();
        assertThat(stored)
                .doesNotContain("hunter2")
                .doesNotContain("do-not-store-me")
                .doesNotContain("secret-operator");
    }

    // ------------------------------------------------------------------ helpers

    private static Path fixture(String name) throws Exception {
        return Path.of(BenchmarkImportIntegrationTest.class.getResource("/benchmarks/" + name).toURI());
    }

    private static String csv() {
        return "\"Benchmark\",\"Mode\",\"Threads\",\"Score\",\"Score Error (99.9%)\",\"Unit\"\n"
                + "\"dev.repopilot.fixture.RepoPilotFixtureBenchmarks.mapLookup\",\"thrpt\",1,9.861384,NaN,\"ops/us\"\n";
    }

    private static String text() {
        return "# JMH version: 1.37\n"
                + "# VM version: JDK 21.0.5, Java HotSpot(TM) 64-Bit Server VM, 21.0.5+9-LTS-239\n"
                + "# Benchmark mode: Throughput, ops/time\n"
                + "\n"
                + "Benchmark                                Mode  Cnt  Score   Error  Units\n"
                + "RepoPilotFixtureBenchmarks.mapLookup    thrpt    2  9.861         ops/us\n";
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private RepositorySummary register(String name) throws IOException {
        Path root = workspaceRoot.resolve(name);
        Files.createDirectories(root);
        Files.writeString(root.resolve("README.txt"), "benchmark host\n", StandardCharsets.UTF_8);
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

    private static RuntimeMeasurementView only(PageResponse<RuntimeMeasurementView> page, String key,
                                               String scopeA, String scopeB) {
        return page.items().stream()
                .filter(row -> row.metricKey().equals(key))
                .filter(row -> row.scope() != null && row.scope().contains(scopeA) && row.scope().contains(scopeB))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no measurement " + key + " in scope " + scopeA));
    }

    private static double value(PageResponse<RuntimeMeasurementView> page, String key) {
        return page.items().stream()
                .filter(row -> row.metricKey().equals(key))
                .mapToDouble(RuntimeMeasurementView::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no measurement " + key));
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
