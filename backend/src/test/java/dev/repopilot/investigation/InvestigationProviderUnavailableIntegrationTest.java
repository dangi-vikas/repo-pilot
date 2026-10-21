package dev.repopilot.investigation;

import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.RepositorySummary;
import dev.repopilot.workspace.WorkspaceService;
import dev.repopilot.workspace.WorkspaceSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With AI switched off, asking a question is refused before anything is queued.
 *
 * <p>The distinction matters: a job that fails a second later is a worse answer than a refusal that
 * names the setting, and it leaves a failed row in the record for something that was never attempted.
 * The repository is not even indexed here — the refusal happens before evidence is considered.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "repopilot.security.token=integration-test-bearer-token-0009",
        "repopilot.ai.enabled=false",
        "repopilot.jobs.dispatch-interval-millis=3600000",
        "repopilot.jobs.dispatch-initial-delay-millis=3600000"
})
@AutoConfigureWebTestClient
class InvestigationProviderUnavailableIntegrationTest {

    private static final String BEARER = "Bearer integration-test-bearer-token-0009";

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
    private InvestigationRepository investigations;

    @Test
    @DisplayName("refuses with 503 and the reason, and creates no investigation")
    void refusesWhenAiIsDisabled() throws IOException {
        Path repository = workspaceRoot.resolve("payments");
        Files.createDirectories(repository);
        Files.writeString(repository.resolve("README.md"), "# payments\n");

        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);
        RepositorySummary registered = repositories.register(workspace.id(), "payments",
                repository.toAbsolutePath().toString());

        client.post().uri("/api/v1/repositories/{id}/investigations", registered.id())
                .header("Authorization", BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("question", "Why does PaymentService hide the failure?"))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("DEPENDENCY_UNAVAILABLE")
                .jsonPath("$.message").value(message -> assertThat(message.toString())
                        .contains("disabled"));

        assertThat(investigations.count()).isZero();
    }
}
