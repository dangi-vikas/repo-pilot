package dev.repopilot.investigation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Reads the evidence that was handed to the model, in the order it was numbered. */
public interface InvestigationEvidenceRepository extends JpaRepository<InvestigationEvidence, UUID> {

    List<InvestigationEvidence> findByInvestigationIdOrderByRankAsc(UUID investigationId);
}
