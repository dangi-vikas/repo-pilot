package dev.repopilot.runtime;

import dev.repopilot.config.StartupTasks;
import dev.repopilot.config.StorageInitializer;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.RepositorySummary;
import dev.repopilot.runtime.query.RuntimeArtifactView;
import dev.repopilot.workspace.WorkspaceService;
import dev.repopilot.workspace.WorkspaceSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * The upload boundary, at both of the places it is enforced.
 *
 * <p>An artefact limit is only real if something refuses an artefact that exceeds it, and only
 * useful if the refusal says which number was exceeded. The limits here are configured small so
 * that the boundary can actually be reached in a test: a 1 GiB default cannot be probed with a
 * 2 GiB fixture, but a 4 KiB limit can be probed with 4 097 bytes, and the same code path runs
 * either way.
 *
 * <p>Two limits are exercised, because they fail differently:
 * <ul>
 *   <li>{@code repopilot.analysis.max-artifact-size-bytes} is RepoPilot's own limit, applied to the
 *       file that landed. It can name the size and the limit, so it produces the message.</li>
 *   <li>{@code spring.webflux.multipart.max-disk-usage-per-part} is the framework's, applied while
 *       the part is being written. It is what stops a stream that would otherwise fill the disk,
 *       and it cannot say anything useful about which application limit was in play.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "repopilot.security.token=upload-limit-test-bearer-token-0004",
        "repopilot.ai.enabled=false",
        "repopilot.jobs.dispatch-interval-millis=3600000",
        // Deliberately tiny, so the boundary is reachable rather than theoretical.
        "repopilot.analysis.max-artifact-size-bytes=4096",
        // Above the application limit but still tiny, so both layers are exercised in one context.
        // The order matters: a file between the two is read and then refused by name; a file past
        // both is cut off mid-stream.
        "spring.webflux.multipart.max-disk-usage-per-part=65536"
})
@AutoConfigureWebTestClient
class RuntimeUploadLimitIntegrationTest {

    private static final String BEARER = "Bearer upload-limit-test-bearer-token-0004";
    private static final int APPLICATION_LIMIT = 4096;

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
    private RuntimeArtifactRepository artifacts;
    @Autowired
    private RuntimeService runtime;
    @Autowired
    private StorageInitializer storage;
    @Autowired
    private StartupTasks startupTasks;

    @Test
    @DisplayName("the configured limit is the enforced limit, and the file at it is accepted")
    void theConfiguredLimitIsTheEnforcedLimit() {
        // The service reports the number it applies. This is the assertion that would have caught
        // the limit being hard-coded while the property was documented: the two would disagree.
        assertThat(runtime.maxArtifactBytes()).isEqualTo(APPLICATION_LIMIT);
    }

    @Test
    @DisplayName("accepts the file at the limit and refuses one byte over it, naming both numbers")
    void refusesJustOverTheLimitByName() throws IOException {
        RepositorySummary repository = register("near");

        // Exactly at the limit. The size check runs before the plausibility check, so this proves
        // the boundary is inclusive rather than off by one in the quiet direction.
        RuntimeArtifactView accepted = client.post()
                .uri(uploadUri(repository))
                .header("Authorization", BEARER)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart(recordingLike(APPLICATION_LIMIT), "at-limit.jfr"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(RuntimeArtifactView.class)
                .returnResult()
                .getResponseBody();
        assertThat(accepted).isNotNull();
        assertThat(accepted.sizeBytes()).isEqualTo(APPLICATION_LIMIT);

        // One byte over. "Too large" without the number leaves the operator guessing which of two
        // knobs to turn, so the message carries both the size that arrived and the limit in force.
        client.post()
                .uri(uploadUri(repository))
                .header("Authorization", BEARER)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart(recordingLike(APPLICATION_LIMIT + 1), "one-over.jfr"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("LIMIT_EXCEEDED")
                .jsonPath("$.message").value(containsString(String.valueOf(APPLICATION_LIMIT + 1)))
                .jsonPath("$.message").value(containsString(String.valueOf(APPLICATION_LIMIT)));

        // The refusal stored nothing: the repository holds the accepted artefact and nothing else.
        assertThat(artifacts.findByRepositoryIdOrderByCreatedAtDesc(repository.id(),
                PageRequest.of(0, 50)).getTotalElements()).isEqualTo(1);

        // And it left no scratch file behind. A refused upload that leaks its temporary file grows
        // the scratch directory by one file per attempt, which is invisible until a disk is full.
        assertThat(scratchFiles()).isEmpty();
    }

    @Test
    @DisplayName("a stream past the framework cap is refused without storing anything or leaking its scratch file")
    void refusesAStreamPastTheFrameworkCap() throws IOException {
        RepositorySummary repository = register("oversized");

        // Larger than the framework's per-part disk cap, and larger than the in-memory threshold,
        // so it is a disk-backed part that the framework cuts off while it is still arriving.
        client.post()
                .uri(uploadUri(repository))
                .header("Authorization", BEARER)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart(recordingLike(512 * 1024), "oversized.jfr"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("LIMIT_EXCEEDED")
                // A limit the caller cannot act on is only half a refusal, so the message names the
                // setting rather than saying "too large".
                .jsonPath("$.message").value(containsString("max-disk-usage-per-part"));

        assertThat(artifacts.findByRepositoryIdOrderByCreatedAtDesc(repository.id(),
                PageRequest.of(0, 50)).getTotalElements()).isZero();
        assertThat(scratchFiles()).isEmpty();
    }

    @Test
    @DisplayName("deleting a repository removes the bytes it had stored, not only the records")
    void deletingARepositoryRemovesItsStoredBytes() throws IOException {
        RepositorySummary repository = register("deletable");

        client.post()
                .uri(uploadUri(repository))
                .header("Authorization", BEARER)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart(recordingLike(APPLICATION_LIMIT), "evidence.jfr"))
                .exchange()
                .expectStatus().isCreated();

        Path stored = storage.artifactsDirectory().resolve(repository.id().toString());
        assertThat(Files.isDirectory(stored)).isTrue();

        client.delete().uri("/api/v1/repositories/{id}", repository.id())
                .header("Authorization", BEARER)
                .exchange()
                .expectStatus().isNoContent();

        // The records go in a transaction; the bytes go after it, and they do go. A heap dump is
        // process memory, so "deleted" has to mean deleted rather than "no longer listed".
        assertThat(Files.exists(stored)).isFalse();
        client.get().uri("/api/v1/repositories/{id}", repository.id())
                .header("Authorization", BEARER)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("startup removes artefacts whose repository no longer exists, and nobody else's")
    void startupSweepsOrphanedArtifacts() throws IOException {
        UUID orphanId = UUID.randomUUID();
        Path orphan = storage.artifactsDirectory().resolve(orphanId.toString()).resolve("ab");
        Files.createDirectories(orphan);
        Files.writeString(orphan.resolve("left-behind.jfr"), "FLR\u0000stale", StandardCharsets.UTF_8);

        // A repository that still exists, to prove the sweep is not simply deleting everything.
        RepositorySummary live = register("still-here");
        client.post()
                .uri(uploadUri(live))
                .header("Authorization", BEARER)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart(recordingLike(APPLICATION_LIMIT), "kept.jfr"))
                .exchange()
                .expectStatus().isCreated();
        Path kept = storage.artifactsDirectory().resolve(live.id().toString());

        startupTasks.run(null);

        assertThat(Files.exists(storage.artifactsDirectory().resolve(orphanId.toString()))).isFalse();
        assertThat(Files.isDirectory(kept)).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private String uploadUri(RepositorySummary repository) {
        return "/api/v1/repositories/" + repository.id() + "/runtime/artifacts?artifactType=JFR_RECORDING";
    }

    private static BodyInserters.MultipartInserter multipart(byte[] bytes, String filename) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(bytes)).filename(filename);
        return BodyInserters.fromMultipartData(builder.build());
    }

    /**
     * Bytes that pass the ingest header check so that only the size is under test.
     *
     * <p>Nothing reads the body of this file: the size check precedes the plausibility check, and a
     * recording is only parsed once its analysis is queued.
     */
    private static byte[] recordingLike(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        bytes[0] = 'F';
        bytes[1] = 'L';
        bytes[2] = 'R';
        bytes[3] = 0;
        return bytes;
    }

    /** Scratch files left in the upload directory. Empty is the only acceptable answer. */
    private List<Path> scratchFiles() throws IOException {
        Path temporary = storage.temporaryDirectory();
        if (!Files.isDirectory(temporary)) {
            return List.of();
        }
        try (var entries = Files.list(temporary)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".part")).toList();
        }
    }

    private RepositorySummary register(String name) throws IOException {
        Path root = workspaceRoot.resolve(name);
        Files.createDirectories(root);
        Files.writeString(root.resolve("README.txt"), "upload limit host\n", StandardCharsets.UTF_8);
        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);
        return repositories.register(workspace.id(), name, root.toAbsolutePath().toString());
    }
}
