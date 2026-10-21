package dev.repopilot.investigation;

import dev.repopilot.analysis.AnalysisOutcome;
import dev.repopilot.analysis.AnalysisRunner;
import dev.repopilot.api.PageResponse;
import dev.repopilot.api.v1.JobView;
import dev.repopilot.codeindex.index.IndexOutcome;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
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
 * End-to-end investigation: index a repository, ask a question, let the job run, read the answer and
 * the verdict on it.
 *
 * <p>Runs against the deterministic extractor, which quotes retrieved evidence and calls no model.
 * That makes the assertions about <em>RepoPilot's</em> half of the pipeline exact: the evidence keys
 * are the ones in the prompt, every excerpt is re-read from disk, every claim resolves, and the
 * grounding verdict is true only because each of those held.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "repopilot.security.token=integration-test-bearer-token-0007",
        "repopilot.ai.enabled=true",
        "repopilot.ai.provider=deterministic",
        "repopilot.jobs.dispatch-interval-millis=3600000",
        "repopilot.jobs.dispatch-initial-delay-millis=3600000"
})
@AutoConfigureWebTestClient
class InvestigationIntegrationTest {

    private static final String BEARER = "Bearer integration-test-bearer-token-0007";
    private static final String SERVICE_PATH =
            "com/example/payments/PaymentService.java";

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
    private RepositoryIndexer indexer;
    @Autowired
    private AnalysisRunner analysis;
    @Autowired
    private JobDispatcher dispatcher;
    @Autowired
    private AnalysisJobRepository jobs;
    @Autowired
    private InvestigationRepository investigations;
    @Autowired
    private InvestigationEvidenceRepository evidence;

    @Test
    @DisplayName("answers from retrieved evidence and marks the answer grounded")
    void answersWithGroundedCitations() throws IOException {
        Fixture fixture = indexedRepository();
        UUID repositoryId = fixture.repositoryId();

        Submission submitted = ask(repositoryId,
                "Why does the empty catch in PaymentService hide failures?");
        assertThat(submitted.status()).isEqualTo("QUEUED");

        // The investigation exists before the job finishes, in a state a UI can poll.
        InvestigationViews.Detail queued = detail(repositoryId, submitted.investigationId());
        assertThat(queued.status()).isIn("QUEUED", "RUNNING");
        assertThat(queued.answer()).isNull();

        AnalysisJob job = awaitTerminal(submitted.jobId());
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);

        InvestigationViews.Detail answer = detail(repositoryId, submitted.investigationId());
        assertThat(answer.status()).isEqualTo("SUCCEEDED");
        assertThat(answer.revisionId()).isEqualTo(fixture.revisionId());
        assertThat(answer.question()).contains("PaymentService");
        assertThat(answer.modelProvider()).isEqualTo("deterministic");
        assertThat(answer.modelName()).contains("no model called");
        assertThat(answer.promptSha256()).hasSize(64);
        assertThat(answer.promptChars()).isPositive();
        assertThat(answer.latencyMs()).isNotNull();
        assertThat(answer.completedAt()).isNotNull();

        // The answer is grounded, and it is grounded because every claim cites evidence that was
        // in the prompt and every cited excerpt hashed the same when it was re-read.
        assertThat(answer.grounded()).isTrue();
        assertThat(answer.claims()).isNotEmpty();
        assertThat(answer.unsupportedClaims()).isZero();
        assertThat(answer.partialClaims()).isZero();
        assertThat(answer.verifiedClaims()).isEqualTo(answer.claims().size());
        assertThat(answer.claims()).allSatisfy(claim -> {
            assertThat(claim.verificationStatus()).isEqualTo("VERIFIED");
            assertThat(claim.citedKeys()).hasSize(1);
            assertThat(claim.notes()).isNotBlank();
        });

        // The summary is read by a person, so it has to state the count it counted. Building a
        // StringBuilder with an int sets its capacity, not its text; that mistake once produced a
        // sentence starting with " item(s) of evidence".
        assertThat(answer.retrievalSummary())
                .startsWith(answer.evidenceCount() + " item(s) of evidence");

        // Evidence: numbered, ranked, and every item the extractor cited is marked cited.
        assertThat(answer.evidence()).isNotEmpty().hasSize(answer.evidenceCount());
        assertThat(answer.evidence()).allSatisfy(item -> {
            assertThat(item.key()).matches("E\\d+");
            assertThat(item.rank()).isPositive();
            assertThat(item.cited()).isTrue();
            assertThat(item.retrievalMethod()).isNotBlank();
        });
        assertThat(answer.evidence()).extracting(InvestigationViews.Evidence::key)
                .containsExactlyElementsOf(answer.evidence().stream()
                        .map(item -> "E" + item.rank()).toList());

        // At least one item is a real location that was re-read and matched, and at least one is
        // a static finding: retrieval reached the analysis, not just the symbol table.
        assertThat(answer.evidence()).anySatisfy(item -> {
            assertThat(item.sourceStatus()).isEqualTo("CONFIRMED");
            assertThat(item.path()).isNotBlank();
            assertThat(item.excerptChars()).isPositive();
        });
        assertThat(answer.evidence()).anySatisfy(item -> {
            assertThat(item.kind()).isEqualTo("FINDING");
            assertThat(item.path()).isEqualTo(SERVICE_PATH);
        });

        // The excerpt itself never reaches the database or the API: only its hash and length.
        var stored = evidence.findByInvestigationIdOrderByRankAsc(submitted.investigationId());
        assertThat(stored).isNotEmpty().allSatisfy(row -> {
            if (row.getSourcePath() != null && row.getExcerptChars() != null
                    && row.getExcerptChars() > 0) {
                assertThat(row.getExcerptSha256()).hasSize(64);
            }
        });

        // The extractor states, in its own words, that no model was called.
        assertThat(answer.limitations()).contains("No language model was called");
        assertThat(answer.diagnostics().unanswered()).isNotEmpty();

        // The list endpoint agrees with the detail endpoint.
        PageResponse<InvestigationViews.Summary> list = client.get()
                .uri("/api/v1/repositories/{id}/investigations", repositoryId)
                .header("Authorization", BEARER)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<PageResponse<InvestigationViews.Summary>>() {
                })
                .returnResult().getResponseBody();
        assertThat(list).isNotNull();
        assertThat(list.totalItems()).isEqualTo(1);
        assertThat(list.items().get(0).grounded()).isTrue();
        assertThat(list.items().get(0).evidenceCount()).isEqualTo(answer.evidenceCount());
    }

    @Test
    @DisplayName("refuses a question whose every word is a stop word, before queuing anything")
    void refusesAQuestionWithNothingToSearchFor() throws IOException {
        UUID repositoryId = indexedRepository().repositoryId();

        client.post().uri("/api/v1/repositories/{id}/investigations", repositoryId)
                .header("Authorization", BEARER)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("question", "how does the code work"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.message").value(message -> assertThat(message.toString())
                        .contains("names nothing to search for"));

        assertThat(investigations.findByRepositoryIdOrderByCreatedAtDesc(repositoryId,
                org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("does not let one repository's identifier read another repository's investigation")
    void scopesInvestigationsToTheirRepository() throws IOException {
        UUID first = indexedRepository().repositoryId();
        Submission submitted = ask(first, "Where is the PaymentService empty catch?");
        awaitTerminal(submitted.jobId());

        UUID second = indexedRepository().repositoryId();
        client.get().uri("/api/v1/repositories/{id}/investigations/{investigationId}",
                        second, submitted.investigationId())
                .header("Authorization", BEARER)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("cancels a queued investigation without pretending it has an answer")
    void cancelsAQueuedInvestigation() throws IOException {
        UUID repositoryId = indexedRepository().repositoryId();
        Submission submitted = ask(repositoryId,
                "Why does PaymentService swallow the exception?");

        client.post().uri("/api/v1/repositories/{id}/investigations/{investigationId}/cancel",
                        repositoryId, submitted.investigationId())
                .header("Authorization", BEARER)
                .exchange()
                .expectStatus().isAccepted();

        InvestigationViews.Detail cancelled = detail(repositoryId, submitted.investigationId());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.answer()).isNull();

        // A finished investigation is a record: cancelling it afterwards is a conflict, not a
        // silent success.
        client.post().uri("/api/v1/repositories/{id}/investigations/{investigationId}/cancel",
                        repositoryId, submitted.investigationId())
                .header("Authorization", BEARER)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    private record Fixture(UUID repositoryId, UUID revisionId) {
    }

    /** One repository holding the question's subject, the job, and what the answer will cite. */
    private record Submission(UUID investigationId, UUID jobId, String status) {
    }

    private Fixture indexedRepository() throws IOException {
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
        Files.writeString(repository.resolve("com/example/payments/Payment.java"), """
                package com.example.payments;

                public record Payment(String id, long amountMinor) {
                }
                """, StandardCharsets.UTF_8);

        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);
        RepositorySummary registered = repositories.register(workspace.id(),
                "payments-" + UUID.randomUUID(), repository.toAbsolutePath().toString());
        IndexOutcome index = indexer.index(registered.id(), null);
        AnalysisOutcome outcome = analysis.analyse(registered.id(), null, null);
        assertThat(outcome.status()).isEqualTo(dev.repopilot.analysis.AnalysisRunStatus.SUCCEEDED);
        return new Fixture(registered.id(), index.revisionId());
    }

    private Submission ask(UUID repositoryId, String question) {
        Submission submission = client.post()
                .uri("/api/v1/repositories/{id}/investigations", repositoryId)
                .header("Authorization", BEARER)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("question", question))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(Submission.class)
                .returnResult().getResponseBody();
        assertThat(submission).isNotNull();
        return submission;
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
