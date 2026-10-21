package dev.repopilot.investigation.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repopilot.investigation.Investigation;
import dev.repopilot.investigation.InvestigationClaimRepository;
import dev.repopilot.investigation.InvestigationEvidence;
import dev.repopilot.investigation.InvestigationEvidenceRepository;
import dev.repopilot.investigation.InvestigationRepository;
import dev.repopilot.shared.error.RepoPilotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads investigations for the API.
 *
 * <p>Every read is scoped by repository, so an identifier from one repository cannot be used
 * to read another's answer. The detail read assembles the evidence, the claims and the
 * diagnostics into one response because a claim without the evidence it cites is not
 * reviewable, and a verdict without the note that explains it is not actionable.
 */
@Service
public class InvestigationQueryService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationQueryService.class);

    private final InvestigationRepository investigations;
    private final InvestigationEvidenceRepository evidence;
    private final InvestigationClaimRepository claims;
    private final ObjectMapper json;

    public InvestigationQueryService(InvestigationRepository investigations,
                                     InvestigationEvidenceRepository evidence,
                                     InvestigationClaimRepository claims,
                                     ObjectMapper json) {
        this.investigations = investigations;
        this.evidence = evidence;
        this.claims = claims;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Page<InvestigationViews.Summary> list(UUID repositoryId, Pageable pageable) {
        return investigations.findByRepositoryIdOrderByCreatedAtDesc(repositoryId, pageable)
                .map(this::summary);
    }

    @Transactional(readOnly = true)
    public InvestigationViews.Detail detail(UUID repositoryId, UUID investigationId) {
        Investigation investigation = investigations.findByIdAndRepositoryId(investigationId, repositoryId)
                .orElseThrow(() -> RepoPilotException.notFound("Investigation", investigationId));

        List<InvestigationViews.Evidence> evidenceViews = evidence
                .findByInvestigationIdOrderByRankAsc(investigationId).stream()
                .map(this::evidenceView)
                .toList();
        List<InvestigationViews.Claim> claimViews = claims
                .findByInvestigationIdOrderByClaimIndexAsc(investigationId).stream()
                .map(claim -> new InvestigationViews.Claim(claim.getClaimIndex(), claim.getClaimText(),
                        claim.getClaimKind().name(), splitKeys(claim.getCitedKeys()),
                        claim.getVerificationStatus().name(), claim.getVerificationNotes()))
                .toList();

        int verified = (int) claimViews.stream()
                .filter(claim -> "VERIFIED".equals(claim.verificationStatus())).count();
        int partial = (int) claimViews.stream()
                .filter(claim -> "PARTIAL".equals(claim.verificationStatus())).count();
        int unsupported = (int) claimViews.stream()
                .filter(claim -> "UNSUPPORTED".equals(claim.verificationStatus())).count();

        return new InvestigationViews.Detail(
                investigation.getId(), investigation.getRepositoryId(), investigation.getRevisionId(),
                investigation.getJobId(), investigation.getTitle(), investigation.getQuestion(),
                investigation.getScope(), investigation.getStatus().name(), investigation.getAnswer(),
                investigation.getConfidence(), investigation.isGrounded(),
                investigation.getRetrievalSummary(), investigation.getLimitations(),
                investigation.getModelProvider(), investigation.getModelName(),
                investigation.getPromptChars(), investigation.getContextChars(),
                investigation.getEvidenceCount(), investigation.getLatencyMs(),
                investigation.getPromptSha256(), investigation.getCreatedAt(),
                investigation.getCompletedAt(), investigation.getErrorMessage(),
                evidenceViews, claimViews, diagnostics(investigation.getDiagnosticsJson()),
                verified, partial, unsupported);
    }

    private InvestigationViews.Summary summary(Investigation investigation) {
        return new InvestigationViews.Summary(investigation.getId(), investigation.getTitle(),
                investigation.getQuestion(), investigation.getStatus().name(),
                investigation.isGrounded(), investigation.getConfidence(),
                investigation.getEvidenceCount(), investigation.getModelProvider(),
                investigation.getJobId(), investigation.getCreatedAt(),
                investigation.getCompletedAt(), investigation.getErrorMessage());
    }

    private InvestigationViews.Evidence evidenceView(InvestigationEvidence item) {
        return new InvestigationViews.Evidence(item.getEvidenceKey(),
                item.getEvidenceType().name(), item.getLabel(), item.getDetail(),
                item.getSourcePath(), item.getStartLine(), item.getEndLine(),
                item.getSymbolName(), item.getSymbolKind(),
                item.getRetrievalScore() == null ? 0 : item.getRetrievalScore(),
                item.getRetrievalMethod(), item.getRank(), item.isCited(),
                item.getSourceStatus() == null ? null : item.getSourceStatus().name(),
                item.getVerifiedAt(), item.getExcerptChars());
    }

    private static List<String> splitKeys(String keys) {
        if (keys == null || keys.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : keys.split(",")) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
        return values;
    }

    private InvestigationViews.Diagnostics diagnostics(String diagnosticsJson) {
        if (diagnosticsJson == null || diagnosticsJson.isBlank()) {
            return InvestigationViews.Diagnostics.empty();
        }
        try {
            JsonNode node = json.readTree(diagnosticsJson);
            return new InvestigationViews.Diagnostics(
                    strings(node, "unanswered"),
                    strings(node, "danglingMarkers"),
                    strings(node, "retrievalNotes"),
                    strings(node, "parserNotes"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // The diagnostics are an aid; a row written by an older build without them is not an error.
            log.debug("investigation diagnostics could not be parsed: {}", e.getMessage());
            return InvestigationViews.Diagnostics.empty();
        }
    }

    private static List<String> strings(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : value) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return List.copyOf(values);
    }
}
