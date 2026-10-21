package dev.repopilot.investigation;

import dev.repopilot.shared.persistence.AbstractCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One statement the model made, kept exactly as it wrote it, with RepoPilot's verdict beside it.
 *
 * <p>Claims are rows rather than a blob inside the answer because the verdict has to be
 * quotable on its own: "claim 3 is unsupported because it cites E9, which was never in the
 * prompt" is a fact about the record, not a note about a paragraph.
 */
@Entity
@Table(name = "investigation_claim")
public class InvestigationClaim extends AbstractCreatedEntity {

    @Column(name = "investigation_id", nullable = false, updatable = false)
    private UUID investigationId;

    @Column(name = "claim_index", nullable = false, updatable = false)
    private int claimIndex;

    @Column(name = "claim_text", nullable = false, length = 4000)
    private String claimText;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_kind", nullable = false, length = 24)
    private ClaimKind claimKind;

    /** The keys as the model wrote them, e.g. {@code E1,E4}; kept even when they do not resolve. */
    @Column(name = "cited_keys", length = 500)
    private String citedKeys;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 32)
    private ClaimVerification verificationStatus;

    @Column(name = "verification_notes", length = 2000)
    private String verificationNotes;

    protected InvestigationClaim() {
    }

    public InvestigationClaim(UUID investigationId, int claimIndex, String claimText,
                              ClaimKind claimKind, String citedKeys,
                              ClaimVerification verificationStatus, String verificationNotes) {
        this.investigationId = investigationId;
        this.claimIndex = claimIndex;
        this.claimText = claimText;
        this.claimKind = claimKind;
        this.citedKeys = citedKeys;
        this.verificationStatus = verificationStatus;
        this.verificationNotes = verificationNotes;
    }

    public UUID getInvestigationId() {
        return investigationId;
    }

    public int getClaimIndex() {
        return claimIndex;
    }

    public String getClaimText() {
        return claimText;
    }

    public ClaimKind getClaimKind() {
        return claimKind;
    }

    public String getCitedKeys() {
        return citedKeys;
    }

    public ClaimVerification getVerificationStatus() {
        return verificationStatus;
    }

    public String getVerificationNotes() {
        return verificationNotes;
    }
}
