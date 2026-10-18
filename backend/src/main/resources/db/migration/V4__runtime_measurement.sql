-- ===========================================================================
-- V4 — runtime measurements
--
-- Static analysis produces findings: a claim about code, carrying a rule id and a
-- citation. Runtime analysis produces measurements: a quantity observed while a
-- process was running. A measurement has no line to cite, and its meaning depends
-- entirely on the run it came from — a GC pause figure is a fact about one JVM on
-- one machine for one recording, not a property of the source.
--
-- Storing a measurement in the finding table would force it into a severity, a
-- category and a file location it does not have. It gets its own table, and the
-- numbers keep their unit, their reduction and their sample count attached, so a
-- reader can tell a total from a maximum from a mean without asking.
-- ===========================================================================

CREATE TABLE runtime_measurement (
    id              UUID                     NOT NULL,
    repository_id   UUID                     NOT NULL,
    analysis_run_id UUID                     NOT NULL,
    artifact_id     UUID,
    metric_key      VARCHAR(120)             NOT NULL,
    metric_kind     VARCHAR(24)              NOT NULL,
    aggregation     VARCHAR(24)              NOT NULL,
    -- Named metric_value rather than value: VALUE is a reserved word in H2 and in
    -- recent SQL standards, and a column that needs quoting to be readable is a trap.
    metric_value    DOUBLE PRECISION         NOT NULL,
    unit            VARCHAR(24)              NOT NULL,
    sample_count    INTEGER                  NOT NULL DEFAULT 0,
    scope           VARCHAR(300),
    attributes_json VARCHAR(2000),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_runtime_measurement PRIMARY KEY (id),
    CONSTRAINT fk_measurement_repository FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
    CONSTRAINT fk_measurement_run FOREIGN KEY (analysis_run_id) REFERENCES analysis_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_measurement_artifact FOREIGN KEY (artifact_id) REFERENCES runtime_artifact (id) ON DELETE SET NULL
);
CREATE INDEX ix_measurement_run ON runtime_measurement (analysis_run_id, metric_key);
CREATE INDEX ix_measurement_repository ON runtime_measurement (repository_id, created_at);
CREATE INDEX ix_measurement_artifact ON runtime_measurement (artifact_id);

-- A run reports how much it produced. For a static run that is a finding count; for
-- a runtime run it is a measurement count. Keeping the two separate means a JFR run
-- does not read as "0 findings" (which would look like a clean result rather than a
-- different kind of result).
ALTER TABLE analysis_run ADD COLUMN measurement_count INTEGER NOT NULL DEFAULT 0;
