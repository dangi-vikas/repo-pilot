package dev.repopilot.runtime.retention;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repopilot.config.RepoPilotProperties;
import dev.repopilot.jobs.JobService;
import dev.repopilot.jobs.JobType;
import dev.repopilot.repository.Repository;
import dev.repopilot.repository.RepositoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The sweep that decides whether a retention pass is due.
 *
 * <p>What is worth testing here is not that a job is submitted but the three decisions around it: with
 * pruning off nothing is queued at all, with work in progress the pass waits, and one repository that
 * cannot be queued does not stop the sweep for the rest. The idempotency key is asserted verbatim
 * because it is the whole mechanism that turns an hourly sweep into a daily pass — a key without the
 * date would either queue nothing after the first day or queue a new pass every hour.
 */
class RetentionSchedulerTest {

    private static final UUID WORKSPACE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REPOSITORY_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID REPOSITORY_B = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-10-20T03:04:05Z");

    private final RepositoryRepository repositories = mock(RepositoryRepository.class);
    private final JobService jobs = mock(JobService.class);

    @BeforeEach
    void setUp() {
        when(jobs.submit(any(), any(), any(), any(), any()))
                .thenReturn(new JobService.Submission(null, true));
    }

    @Test
    @DisplayName("queues one pass per idle repository, with the window and the date in the job itself")
    void queuesOnePassPerRepository() {
        Repository repositoryA = repository(REPOSITORY_A);
        Repository repositoryB = repository(REPOSITORY_B);
        when(repositories.findAll()).thenReturn(List.of(repositoryA, repositoryB));

        scheduler(30).enqueueRetention();

        verify(jobs).submit(eq(JobType.ARTIFACT_RETENTION), eq(WORKSPACE), eq(REPOSITORY_A),
                eq("{\"olderThanDays\":30}"), eq("retention:" + REPOSITORY_A + ":2026-10-20"));
        verify(jobs).submit(eq(JobType.ARTIFACT_RETENTION), eq(WORKSPACE), eq(REPOSITORY_B),
                eq("{\"olderThanDays\":30}"), eq("retention:" + REPOSITORY_B + ":2026-10-20"));
    }

    @Test
    @DisplayName("queues nothing when automatic pruning is switched off")
    void queuesNothingWhenDisabled() {
        scheduler(0).enqueueRetention();

        // Not even a read of the repository table: there is nothing to decide, so the sweep does not ask.
        verifyNoInteractions(repositories, jobs);
    }

    @Test
    @DisplayName("defers a repository that has work in progress")
    void defersBusyRepositories() {
        Repository repositoryA = repository(REPOSITORY_A);
        Repository repositoryB = repository(REPOSITORY_B);
        when(repositories.findAll()).thenReturn(List.of(repositoryA, repositoryB));
        when(jobs.countActiveForRepository(REPOSITORY_A)).thenReturn(1L);

        scheduler(30).enqueueRetention();

        verify(jobs, never()).submit(any(), any(), eq(REPOSITORY_A), any(), any());
        verify(jobs).submit(any(), any(), eq(REPOSITORY_B), any(), any());
    }

    @Test
    @DisplayName("one repository that cannot be queued does not stop the sweep for the rest")
    void keepsGoingAfterOneFailure() {
        Repository repositoryA = repository(REPOSITORY_A);
        Repository repositoryB = repository(REPOSITORY_B);
        when(repositories.findAll()).thenReturn(List.of(repositoryA, repositoryB));
        when(jobs.submit(any(), any(), eq(REPOSITORY_A), any(), any()))
                .thenThrow(new IllegalStateException("queue is full"));

        scheduler(30).enqueueRetention();

        verify(jobs).submit(any(), any(), eq(REPOSITORY_B), any(), any());
    }

    private RetentionScheduler scheduler(int retentionDays) {
        return new RetentionScheduler(repositories, jobs, properties(retentionDays), new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Repository repository(UUID id) {
        Repository repository = mock(Repository.class);
        when(repository.getId()).thenReturn(id);
        when(repository.getWorkspaceId()).thenReturn(WORKSPACE);
        return repository;
    }

    /**
     * The real configuration record, bound from the property keys Spring would bind it from.
     *
     * <p>Bound rather than mocked so the defaults under test are the ones the application uses; a mock
     * would let the test agree with itself about a number that only exists in the test.
     */
    private static RepoPilotProperties properties(int retentionDays) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(
                Map.of("repopilot.storage.retention-days", String.valueOf(retentionDays)));
        return new Binder(source).bind("repopilot", Bindable.of(RepoPilotProperties.class))
                .orElseThrow(() -> new AssertionError("could not bind the test configuration"));
    }
}
