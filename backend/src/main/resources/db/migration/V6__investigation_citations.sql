-- ===========================================================================
-- V6: citation-checked AI investigations
--
-- The investigation, investigation_evidence and investigation_message tables were
-- created in V1 for this phase. What was missing is the part that makes an answer
-- auditable: the key the model cited, the hash of the exact excerpt it was shown, the
-- verdict reached by re-reading that excerpt from disk, and one row per claim.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- investigation: what was sent, and what could not be answered
-- ---------------------------------------------------------------------------
ALTER TABLE investigation ADD COLUMN prompt_sha256 VARCHAR(64);

-- Structured diagnostics rather than prose: unanswered questions, markers in the answer
-- that matched no evidence, and which evidence the retrieval dropped for budget. Kept as
-- JSON so the read path does not have to parse a message.
ALTER TABLE investigation ADD COLUMN diagnostics_json VARCHAR(4000);

-- ---------------------------------------------------------------------------
-- investigation_evidence: the citation handle, and the excerpt's identity
-- ---------------------------------------------------------------------------
-- The key the model sees and cites ("E3"). Stable for the life of the investigation.
ALTER TABLE investigation_evidence ADD COLUMN evidence_key VARCHAR(12);

-- SHA-256 of the exact excerpt handed to the model, not of the file. The check re-reads
-- the same line range and compares this. Storing the excerpt itself would make this table
-- a prompt log; storing its hash makes the claim checkable without keeping the code.
ALTER TABLE investigation_evidence ADD COLUMN excerpt_sha256 VARCHAR(64);
ALTER TABLE investigation_evidence ADD COLUMN excerpt_chars INTEGER;

-- CONFIRMED / CHANGED / MISSING / NOT_LOCATED, decided when the answer was verified.
ALTER TABLE investigation_evidence ADD COLUMN source_status VARCHAR(32);
ALTER TABLE investigation_evidence ADD COLUMN verified_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX ix_inv_evidence_key ON investigation_evidence (investigation_id, evidence_key);

-- ---------------------------------------------------------------------------
-- investigation_claim: one row per statement the model made
-- ---------------------------------------------------------------------------
-- The claim text is stored as the model wrote it. The verdict is RepoPilot's, and the
-- two are never merged: rewriting a model's sentence to match the evidence would destroy
-- the record of what it actually said.
CREATE TABLE investigation_claim (
    id                  UUID                     NOT NULL,
    investigation_id    UUID                     NOT NULL,
    claim_index         INTEGER                  NOT NULL,
    claim_text          VARCHAR(4000)            NOT NULL,
    claim_kind          VARCHAR(24)              NOT NULL,
    cited_keys          VARCHAR(500),
    verification_status VARCHAR(32)              NOT NULL,
    verification_notes  VARCHAR(2000),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_investigation_claim PRIMARY KEY (id),
    CONSTRAINT fk_inv_claim_investigation FOREIGN KEY (investigation_id)
        REFERENCES investigation (id) ON DELETE CASCADE
);
CREATE INDEX ix_inv_claim_investigation ON investigation_claim (investigation_id, claim_index);
