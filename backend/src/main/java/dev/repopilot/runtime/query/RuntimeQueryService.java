package dev.repopilot.runtime.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repopilot.analysis.AnalysisRun;
import dev.repopilot.analysis.AnalysisRunRepository;
import dev.repopilot.analysis.AnalysisRunType;
import dev.repopilot.runtime.MetricKind;
import dev.repopilot.runtime.RuntimeArtifact;
import dev.repopilot.runtime.RuntimeArtifactRepository;
import dev.repopilot.runtime.RuntimeMeasurement;
import dev.repopilot.runtime.RuntimeMeasurementRepository;
import dev.repopilot.shared.error.RepoPilotException;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read path for runtime artefacts and measurements.
 *
 * <p>Structurally the same promise as {@code AnalysisQueryService}: every read is scoped to one
 * analysis run, resolved here rather than left to the caller. A measurement list with no run
 * behind it cannot say which recording it describes, on which machine, over which window &mdash;
 * and a profiling number without that context is a number that invites the wrong conclusion.
 *
 * <p>Reads also never merge runs. Two recordings of the same repository are two measurements of
 * two processes, and averaging them would produce a figure that describes neither.
 */
@Service
public class RuntimeQueryService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeQueryService.class);

    /** Reading order: the key groups a family, then the scope, then the reduction. */
    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.asc("metricKey"),
            Sort.Order.asc("scope"),
            Sort.Order.asc("aggregation"));

    private static final int MAX_PAGE_SIZE = 1_000;

    private final AnalysisRunRepository runs;
    private final RuntimeMeasurementRepository measurements;
    private final RuntimeArtifactRepository artifacts;
    private final ObjectMapper json;

    public RuntimeQueryService(AnalysisRunRepository runs,
                               RuntimeMeasurementRepository measurements,
                               RuntimeArtifactRepository artifacts,
                               ObjectMapper json) {
        this.runs = runs;
        this.measurements = measurements;
        this.artifacts = artifacts;
        this.json = json;
    }

    /**
     * The newest runtime-evidence run for a repository, whichever reader produced it.
     *
     * <p>"Runtime evidence" is every run type that reads an uploaded artefact — a recording or a
     * thread dump — and it is asked for as a set rather than by naming one pipeline. Naming one is
     * how a repository with a freshly analysed thread dump comes to report that nothing has been
     * recorded.
     */
    @Transactional(readOnly = true)
    public Optional<AnalysisRun> currentRun(UUID repositoryId) {
        return runs.findFirstByRepositoryIdAndRunTypeInOrderByStartedAtDesc(repositoryId,
                AnalysisRunType.runtimeMeasurementTypes());
    }

    /** Artefacts uploaded for a repository, newest first. */
    @Transactional(readOnly = true)
    public Page<RuntimeArtifactView> artifacts(UUID repositoryId, Pageable pageable) {
        return artifacts.findByRepositoryIdOrderByCreatedAtDesc(repositoryId, pageable)
                .map(artifact -> RuntimeArtifactView.from(artifact, measurements.countByArtifactId(artifact.getId())));
    }

    @Transactional(readOnly = true)
    public RuntimeArtifactView artifact(UUID repositoryId, UUID artifactId) {
        RuntimeArtifact artifact = artifacts.findByIdAndRepositoryId(artifactId, repositoryId)
                .orElseThrow(() -> RepoPilotException.notFound("Runtime artefact", artifactId));
        return RuntimeArtifactView.from(artifact, measurements.countByArtifactId(artifactId));
    }

    /**
     * A page of measurements.
     *
     * <p>An empty page can mean two different things: nothing has been recorded, or the recording
     * produced no measurement matching the filter. {@link #summary} is what distinguishes them,
     * and the UI calls both.
     */
    @Transactional(readOnly = true)
    public Page<RuntimeMeasurementView> measurements(UUID repositoryId, RuntimeMeasurementFilter filter,
                                                     Pageable pageable) {
        UUID runId = filter.runId();
        if (runId == null) {
            runId = currentRun(repositoryId).map(AnalysisRun::getId).orElse(null);
        }
        if (runId == null) {
            return Page.empty(pageable);
        }
        UUID scoped = runId;
        Pageable effective = pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE), DEFAULT_SORT)
                : PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                        pageable.getSort());
        return measurements.findAll(specification(scoped, filter), effective).map(RuntimeMeasurementView::from);
    }

    /**
     * The header for a repository's runtime evidence: which run, which recording, and how the
     * measurements break down.
     *
     * <p>The count returned is the number of rows actually loaded rather than the number the run
     * claims, so the header and the list beneath it cannot disagree.
     */
    @Transactional(readOnly = true)
    public RuntimeSummaryView summary(UUID repositoryId, UUID runId) {
        Optional<AnalysisRun> resolved = runId == null ? currentRun(repositoryId) : runs.findById(runId);
        if (resolved.isEmpty() || !repositoryId.equals(resolved.get().getRepositoryId())) {
            return RuntimeSummaryView.empty(repositoryId);
        }
        AnalysisRun run = resolved.get();
        List<RuntimeMeasurement> rows = measurements.findByAnalysisRunId(run.getId());

        RuntimeArtifact artifact = artifacts.findByAnalysisRunId(run.getId()).stream().findFirst().orElse(null);
        Map<MetricKind, Long> byKind = new EnumMap<>(MetricKind.class);
        Map<String, Long> byFamily = new LinkedHashMap<>();
        for (RuntimeMeasurement row : rows) {
            byKind.merge(row.getMetricKind(), 1L, Long::sum);
            byFamily.merge(family(row.getMetricKey()), 1L, Long::sum);
        }

        return new RuntimeSummaryView(
                repositoryId,
                run.getId(),
                artifact == null ? null : artifact.getId(),
                run.getArtifactName(),
                artifact == null ? null : artifact.getArtifactType(),
                run.getArtifactHash(),
                run.getArtifactSizeBytes(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getAnalyzerVersions(),
                run.getSummary(),
                rows.size(),
                Map.copyOf(byKind),
                Map.copyOf(byFamily),
                provenance(artifact));
    }

    /**
     * Parses the artefact's stored provenance for the client.
     *
     * <p>Returned as a tree rather than a flattened map because it is a nested fact: the JVM, the
     * host and the recording window are three different subjects. A parse failure yields null
     * rather than an error: the file's provenance being unreadable is not a reason to refuse to
     * show the measurements derived from it.
     */
    private Object provenance(RuntimeArtifact artifact) {
        if (artifact == null || artifact.getMetadataJson() == null) {
            return null;
        }
        try {
            JsonNode node = json.readTree(artifact.getMetadataJson());
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("artefact {} has unreadable provenance metadata: {}", artifact.getId(), e.getMessage());
            return null;
        }
    }

    private static String family(String metricKey) {
        String[] segments = metricKey.split("\\.");
        return segments.length > 1 ? segments[1] : metricKey;
    }

    private static Specification<RuntimeMeasurement> specification(UUID runId, RuntimeMeasurementFilter filter) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("analysisRunId"), runId));
            if (filter.metricKeyPrefix() != null && !filter.metricKeyPrefix().isBlank()) {
                predicates.add(builder.like(builder.lower(root.get("metricKey")),
                        filter.metricKeyPrefix().strip().toLowerCase(Locale.ROOT) + "%"));
            }
            if (filter.metricKind() != null) {
                predicates.add(builder.equal(root.get("metricKind"), filter.metricKind()));
            }
            if (filter.scopeContains() != null && !filter.scopeContains().isBlank()) {
                predicates.add(builder.like(builder.lower(root.get("scope")),
                        "%" + filter.scopeContains().strip().toLowerCase(Locale.ROOT) + "%"));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
