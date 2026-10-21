package dev.repopilot.investigation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Reads the claims of one answer, in the order the model wrote them. */
public interface InvestigationClaimRepository extends JpaRepository<InvestigationClaim, UUID> {

    List<InvestigationClaim> findByInvestigationIdOrderByClaimIndexAsc(UUID investigationId);
}
