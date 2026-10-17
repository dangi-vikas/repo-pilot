package dev.repopilot.shared.persistence;

import dev.repopilot.jobs.AnalysisJob;
import dev.repopilot.jobs.JobService;
import dev.repopilot.jobs.JobStatus;
import dev.repopilot.jobs.JobType;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.RepositorySummary;
import dev.repopilot.workspace.WorkspaceService;
import dev.repopilot.workspace.WorkspaceSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins down the identity contract every create path depends on: a newly persisted
 * entity comes back fully materialised, and the instance the caller holds is the one
 * the persistence context manages.
 *
 * <p>This exists because the API serialises the entity it just created straight into
 * the response without re-reading it. If {@code save} silently routing through
 * {@code merge} — see the explanation on {@link AbstractAuditedEntity} — the response
 * would carry a null id and the client would have no resource to address. Each
 * assertion below corresponds to a field a client needs in a {@code 201}/{@code 202}
 * body.
 */
@SpringBootTest(properties = {
        "repopilot.security.token=integration-test-bearer-token-0001",
        "repopilot.ai.enabled=false"
})
class EntityIdentityIntegrationTest {

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
    private WorkspaceService workspaces;
    @Autowired
    private RepositoryService repositories;
    @Autowired
    private JobService jobs;

    @Test
    @DisplayName("creating a workspace returns the generated id and audit timestamps")
    void workspaceCreationIsMaterialised() {
        WorkspaceSummary created = workspaces.create("Identity probe", "identity test");

        assertThat(created.id()).isNotNull();
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.slug()).isNotBlank();
        // Round-trips, which also proves the row is actually addressable by that id.
        assertThat(workspaces.require(created.id()).id()).isEqualTo(created.id());
    }

    @Test
    @DisplayName("registering a repository returns the generated id and audit timestamps")
    void repositoryRegistrationIsMaterialised() throws IOException {
        WorkspaceSummary workspace = workspaces.create("Repo identity probe", null);
        Path project = workspaceRoot.resolve("identity-probe");
        Files.createDirectories(project);
        Files.writeString(project.resolve("Main.java"),
                "package probe; public class Main { }", StandardCharsets.UTF_8);

        RepositorySummary registered = repositories.register(workspace.id(), "identity-probe",
                project.toAbsolutePath().toString());

        assertThat(registered.id()).isNotNull();
        assertThat(registered.createdAt()).isNotNull();
        assertThat(registered.updatedAt()).isNotNull();
        assertThat(repositories.require(registered.id()).id()).isEqualTo(registered.id());
    }

    @Test
    @DisplayName("submitting a job returns the generated id and creation timestamp")
    void jobSubmissionIsMaterialised() {
        WorkspaceSummary workspace = workspaces.create("Job identity probe", null);

        JobService.Submission submission = jobs.submit(
                JobType.REPOSITORY_INDEX, workspace.id(), null, null, null);

        AnalysisJob job = submission.job();
        assertThat(submission.created()).isTrue();
        assertThat(job.getId()).isNotNull();
        assertThat(job.getCreatedAt()).isNotNull();
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(jobs.require(job.getId()).getId()).isEqualTo(job.getId());
    }

    @Test
    @DisplayName("an idempotency key returns the existing job rather than starting a second run")
    void idempotentSubmissionReturnsSameJob() {
        WorkspaceSummary workspace = workspaces.create("Idempotency probe", null);
        String key = "dedupe-" + java.util.UUID.randomUUID();

        JobService.Submission first = jobs.submit(
                JobType.REPOSITORY_INDEX, workspace.id(), null, null, key);
        JobService.Submission second = jobs.submit(
                JobType.REPOSITORY_INDEX, workspace.id(), null, null, key);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.job().getId()).isEqualTo(first.job().getId());
    }
}
