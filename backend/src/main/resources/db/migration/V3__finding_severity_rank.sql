-- Severity ordering for findings.
--
-- `finding.severity` stores the enum name, which is the right thing to read and the wrong
-- thing to sort by: alphabetically it orders CRITICAL, HIGH, INFO, LOW, MEDIUM, so a
-- triage list would be sorted by spelling rather than by urgency. The alternative was a
-- CASE expression in every ordering query; a stored rank is cheaper to read, indexable,
-- and states the order once.
--
-- The default of 0 is deliberate for pre-existing rows: an unmapped severity must sort
-- last rather than be presented as an emergency.

ALTER TABLE finding ADD COLUMN severity_rank SMALLINT NOT NULL DEFAULT 0;

CREATE INDEX ix_finding_severity_rank ON finding (repository_id, severity_rank);
