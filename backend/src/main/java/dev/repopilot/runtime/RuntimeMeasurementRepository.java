package dev.repopilot.runtime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

/**
 * Reads runtime measurements.
 *
 * <p>Reads are always scoped to a run. A measurement without its run is a number with no
 * method: "a maximum GC pause of 128 ms" means nothing until you know whose recording it
 * came from, on what machine, over what window.
 */
public interface RuntimeMeasurementRepository extends JpaRepository<RuntimeMeasurement, UUID>,
        JpaSpecificationExecutor<RuntimeMeasurement> {

    Page<RuntimeMeasurement> findByAnalysisRunId(UUID analysisRunId, Pageable pageable);

    List<RuntimeMeasurement> findByAnalysisRunId(UUID analysisRunId);

    long countByAnalysisRunId(UUID analysisRunId);

    long countByArtifactId(UUID artifactId);

    List<RuntimeMeasurement> findByAnalysisRunIdAndMetricKeyStartingWith(UUID analysisRunId, String prefix);
}
