package dev.repopilot.investigation;

import dev.repopilot.analysis.AnalysisRunner;
import dev.repopilot.api.v1.InvestigationController.SubmissionView;
import dev.repopilot.codeindex.index.RepositoryIndexer;
import dev.repopilot.investigation.query.InvestigationViews;
import dev.repopilot.jobs.AnalysisJob;
import dev.repopilot.jobs.AnalysisJobRepository;
import dev.repopilot.jobs.JobDispatcher;
import dev.repopilot.jobs.JobStatus;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.RepositorySummary;
import dev.repopilot.workspace.WorkspaceService;
import dev.repopilot.workspace.WorkspaceSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when a model's answer does not hold up.
 *
 * <p>The provider here returns a fixed response on purpose. The scenarios are the ones a real model
 * produces regularly and that RepoPilot must never smooth over: a key that was never in the prompt, a
 * confident sentence with no citation, and a line number the evidence does not cover. The assertions
 * are about the record the user reads, not about the model.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "repopilot.security.token=integration-test-bearer-token-0008",
        "repopilot.ai.enabled=true",
        "repopilot.ai.provider=scripted",
        "repopilot.jobs.dispatch-interval-millis=3600000"
})
@AutoConfigureWebTestClient
@Import(InvestigationCitationIntegrationTest.ScriptedConfiguration.class)
class InvestigationCitationIntegrationTest {

    private static final String BEARER = "Bearer integration-test-bearer-token-0008";
    private static final String SERVICE_PATH = "com/example/payments/PaymentService.java";

    @TempDir
    static Path workspaceRoot;

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("repopilot.workspace.roots", () -> workspaceRoot.toAbsolutePath().toString());
        registry.add("repopilot.storage.data-dir", () -> dataDir.toAbsolutePath().toString());
    }

    @TestConfiguration
    static class ScriptedConfiguration {

        @Bean
        ScriptedAiProvider scriptedAiProvider() {
            return new ScriptedAiProvider();
        }
    }

    @Autowired
    private WebTestClient client;
    @Autowired
    private WorkspaceService workspaces;
    @Autowired
    private RepositoryService repositories;
    @Autowired
    private RepositoryIndexer indexer;
    @Autowired
    private AnalysisRunner analysis;
    @Autowired
    private JobDispatcher dispatcher;
    @Autowired
    private AnalysisJobRepository jobs;

    @BeforeEach
    void resetScript() {
        ScriptedAiProvider.respondWith("{}");
    }

    @Test
    @DisplayName("keeps the model's words and marks each claim's citation problem separately")
    void marksUnsupportedAndPartialClaims() throws IOException {
        UUID repositoryId = repositoryWithAnEmptyCatch();

        String answerText = "The catch block is empty [E1], and the gateway retries [E99].";
        ScriptedAiProvider.respondWith("""
                {"answer":"%s","claims":[
                  {"text":"The empty catch block swallows the exception.","kind":"OBSERVATION","evidence":["E1"]},
                  {"text":"The gateway retries three times.","kind":"OBSERVATION","evidence":["E99"]},
                  {"text":"This is probably a defect.","kind":"INFERENCE","evidence":[]},
                  {"text":"See PaymentService.java:999 for the detail.","kind":"OBSERVATION","evidence":["E1"]}],
                 "confidence":"HIGH",
                 "unanswered":["Whether the gateway retries is not in the evidence."],
                 "limitations":"Only one file was retrieved."}
                """.formatted(answerText));

        SubmissionView job = ask(repositoryId, "Why does the empty catch in PaymentService hide failures?");
        assertThat(awaitTerminal(job.jobId()).getStatus()).isEqualTo(JobStatus.SUCCEEDED);

        InvestigationViews.Detail detail = detail(repositoryId, job.investigationId());

        // The answer is stored as the model wrote it. A validator that rewrote it would destroy
        // the only record of what was actually claimed.
        assertThat(detail.answer()).isEqualTo(answerText);
        assertThat(detail.status()).isEqualTo("SUCCEEDED");
        assertThat(detail.grounded()).isFalse();

        assertThat(detail.claims()).hasSize(4);
        assertThat(detail.claims().get(0).verificationStatus()).isEqualTo("VERIFIED");
        assertThat(detail.claims().get(1).verificationStatus()).isEqualTo("UNSUPPORTED");
        assertThat(detail.claims().get(1).notes()).contains("E99").contains("not in the prompt");
        assertThat(detail.claims().get(2).verificationStatus()).isEqualTo("UNSUPPORTED");
        assertThat(detail.claims().get(2).notes()).contains("cites no evidence");
        assertThat(detail.claims().get(3).verificationStatus()).isEqualTo("PARTIAL");
        assertThat(detail.claims().get(3).notes()).contains("PaymentService.java:999");

        assertThat(detail.verifiedClaims()).isEqualTo(1);
        assertThat(detail.partialClaims()).isEqualTo(1);
        assertThat(detail.unsupportedClaims()).isEqualTo(2);

        // E99 was never sent, so the marker in the prose is dangling and the answer cannot be
        // grounded even though one claim resolved.
        assertThat(detail.diagnostics().danglingMarkers()).contains("E99");
        assertThat(detail.diagnostics().unanswered())
                .contains("Whether the gateway retries is not in the evidence.");

        // The model's own confidence is stored and shown as the model's, never as the verdict.
        assertThat(detail.confidence()).isEqualTo("HIGH");
        assertThat(detail.limitations()).isEqualTo("Only one file was retrieved.");
        assertThat(detail.modelProvider()).isEqualTo("scripted");
    }

    @Test
    @DisplayName("records a provider failure on the investigation, not only on the job")
    void recordsAProviderFailure() throws IOException {
        UUID repositoryId = repositoryWithAnEmptyCatch();
        ScriptedAiProvider.failWith(ScriptedAiProvider.unavailable());

        SubmissionView job = ask(repositoryId, "Why does the empty catch in PaymentService hide failures?");
        AnalysisJob failed = awaitTerminal(job.jobId());
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);

        InvestigationViews.Detail detail = detail(repositoryId, job.investigationId());
        assertThat(detail.status()).isEqualTo("FAILED");
        assertThat(detail.answer()).isNull();
        assertThat(detail.errorMessage()).contains("did not answer").contains("unreachable");
        assertThat(detail.completedAt()).isNotNull();
    }

    private UUID repositoryWithAnEmptyCatch() throws IOException {
        Path repository = workspaceRoot.resolve("payments-" + UUID.randomUUID());
        Files.createDirectories(repository.resolve("com/example/payments"));
        Files.writeString(repository.resolve(SERVICE_PATH), """
                package com.example.payments;

                public class PaymentService {

                    private final PaymentGateway gateway;

                    public PaymentService(PaymentGateway gateway) {
                        this.gateway = gateway;
                    }

                    public boolean charge(Payment payment) {
                        try {
                            return gateway.charge(payment);
                        } catch (RuntimeException e) {
                        }
                        return false;
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("com/example/payments/PaymentGateway.java"), """
                package com.example.payments;

                public interface PaymentGateway {
                    boolean charge(Payment payment);
                }
                """, StandardCharsets.UTF_8);

        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);
        RepositorySummary registered = repositories.register(workspace.id(),
                "payments-" + UUID.randomUUID(), repository.toAbsolutePath().toString());
        indexer.index(registered.id(), null);
        analysis.analyse(registered.id(), null, null);
        return registered.id();
    }

    private SubmissionView ask(UUID repositoryId, String question) {
        SubmissionView job = client.post().uri("/api/v1/repositories/{id}/investigations", repositoryId)
                .header("Authorization", BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("question", question))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(SubmissionView.class)
                .returnResult().getResponseBody();
        assertThat(job).isNotNull();
        return job;
    }

    private InvestigationViews.Detail detail(UUID repositoryId, UUID investigationId) {
        InvestigationViews.Detail detail = client.get()
                .uri("/api/v1/repositories/{id}/investigations/{investigationId}",
                        repositoryId, investigationId)
                .header("Authorization", BEARER)
                .exchange()
                .expectStatus().isOk()
                .expectBody(InvestigationViews.Detail.class)
                .returnResult().getResponseBody();
        assertThat(detail).isNotNull();
        return detail;
    }

    private AnalysisJob awaitTerminal(UUID jobId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            dispatcher.dispatchPass();
            AnalysisJob job = jobs.findById(jobId).orElseThrow();
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

