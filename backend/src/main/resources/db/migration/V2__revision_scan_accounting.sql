-- ===========================================================================
-- Scan accounting for a revision.
--
-- Why this exists
-- ---------------
-- The enumerator knows exactly what it deliberately did not read: credential
-- files, unsupported languages, binary files, files over the size limit, and the
-- directories it refused to walk at all. Before these columns existed that
-- knowledge died with the job, and the coverage view could only count the files
-- that produced rows. The result was a report that said "0 excluded" for a scan
-- that had in fact skipped three credential files. An unstated limitation of that
-- kind is exactly what makes a code-intelligence tool untrustworthy, so the
-- numbers are persisted here with the revision they describe.
--
-- Invariants the coverage view now states
-- ---------------------------------------
--   enumerated_file_count = file_count + excluded_file_count
--     every file inspected was either read or deliberately skipped. Note that
--     file_count is the row count (see the `file_count` column), which is the
--     analysable set that reached pass one.
--   pruned_directory_count is kept separate on purpose: a pruned directory hides
--     an unknown number of unseen files, which is not the same claim as "N files
--     were inspected and skipped".
--
-- Portability: no jsonb, no arrays (see docs/adr/0004-database-portability.md).
-- The per-reason breakdown is a compact deterministic string,
-- e.g. 'SECRET_BEARING=3,UNSUPPORTED_LANGUAGE=2'. It is deliberately readable:
-- an operator can read the value straight out of the database or the API.
-- ===========================================================================

ALTER TABLE repository_revision ADD COLUMN enumerated_file_count  BIGINT      NOT NULL DEFAULT 0;
ALTER TABLE repository_revision ADD COLUMN excluded_file_count    BIGINT      NOT NULL DEFAULT 0;
ALTER TABLE repository_revision ADD COLUMN pruned_directory_count BIGINT      NOT NULL DEFAULT 0;
ALTER TABLE repository_revision ADD COLUMN excluded_by_reason     VARCHAR(600);
ALTER TABLE repository_revision ADD COLUMN pruned_by_reason       VARCHAR(600);

-- Precondition for bytecode-based analysis (SpotBugs). Persisted for the same
-- reason as the counts above: "SpotBugs was not applicable" has to be checkable
-- against the revision rather than asserted by the tool that skipped it.
ALTER TABLE repository_revision ADD COLUMN compiled_classes_present BOOLEAN   NOT NULL DEFAULT FALSE;
