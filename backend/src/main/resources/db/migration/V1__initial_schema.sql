-- RepoPilot initial schema.
--
-- Portability note: this migration is written to run on PostgreSQL and, for the
-- zero-dependency `local` profile, on H2 in PostgreSQL compatibility mode.
-- That constraint rules out jsonb, arrays, partial indexes and pgvector columns.
-- Portable VARCHAR (no explicit length in DDL is avoided here for clarity) and
-- TIMESTAMP WITH TIME ZONE are used instead. See docs/adr/0004-database-portability.md.

-- ===========================================================================
-- workspace
-- ===========================================================================
CREATE TABLE workspace (
    id            UUID                     NOT NULL,
    name          VARCHAR(200)             NOT NULL,
    slug          VARCHAR(200)             NOT NULL,
    description   VARCHAR(2000),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    version       BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_workspace PRIMARY KEY (id),
    CONSTRAINT uq_workspace_slug UNIQUE (slug)
);

-- ===========================================================================
-- repository
-- ===========================================================================
CREATE TABLE repository (
    id                   UUID                     NOT NULL,
    workspace_id         UUID                     NOT NULL,
    name                 VARCHAR(300)             NOT NULL,
    source_type          VARCHAR(32)              NOT NULL,
    canonical_location   VARCHAR(2000)            NOT NULL,
    display_location     VARCHAR(2000)            NOT NULL,
    default_branch       VARCHAR(300),
    status               VARCHAR(32)              NOT NULL,
    size_bytes           BIGINT,
    analyzed_file_count  INTEGER                  NOT NULL DEFAULT 0,
    indexing_error       VARCHAR(2000),
    last_indexed_at      TIMESTAMP WITH TIME ZONE,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    version              BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_repository PRIMARY KEY (id),
    CONSTRAINT fk_repository_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id) ON DELETE CASCADE,
    CONSTRAINT uq_repository_workspace_name UNIQUE (workspace_id, name)
);
CREATE INDEX ix_repository_workspace ON repository (workspace_id);
CREATE INDEX ix_repository_status ON repository (status);

-- ===========================================================================
-- repository_revision  (immutable snapshot identity used by every analysis)
-- ===========================================================================
CREATE TABLE repository_revision (
    id            UUID                     NOT NULL,
    repository_id UUID                     NOT NULL,
    revision      VARCHAR(200)             NOT NULL,
    branch        VARCHAR(300),
    commit_author VARCHAR(300),
    committed_at  TIMESTAMP WITH TIME ZONE,
    fingerprint   VARCHAR(128)             NOT NULL,
    file_count    INTEGER                  NOT NULL,
    indexed_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_repository_revision PRIMARY KEY (id),
    CONSTRAINT fk_revision_repository FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
    CONSTRAINT uq_revision_repository_rev UNIQUE (repository_id, revision)
);
CREATE INDEX ix_revision_repository ON repository_revision (repository_id, indexed_at);

-- ===========================================================================
-- source_file
-- ===========================================================================
CREATE TABLE source_file (
    id            UUID                     NOT NULL,
    revision_id   UUID                     NOT NULL,
    relative_path VARCHAR(1500)            NOT NULL,
    language      VARCHAR(32)              NOT NULL,
    size_bytes    BIGINT                   NOT NULL,
    content_hash  VARCHAR(128)             NOT NULL,
    line_count    INTEGER,
    parse_status  VARCHAR(32)              NOT NULL,
    parse_error   VARCHAR(2000),
    package_name  VARCHAR(500),
    indexed_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_source_file PRIMARY KEY (id),
    CONSTRAINT fk_source_file_revision FOREIGN KEY (revision_id) REFERENCES repository_revision (id) ON DELETE CASCADE,
    CONSTRAINT uq_source_file_revision_path UNIQUE (revision_id, relative_path)
);
CREATE INDEX ix_source_file_revision ON source_file (revision_id);
CREATE INDEX ix_source_file_package ON source_file (revision_id, package_name);
CREATE INDEX ix_source_file_hash ON source_file (content_hash);

-- ===========================================================================
-- code_symbol
-- ===========================================================================
CREATE TABLE code_symbol (
    id               UUID                     NOT NULL,
    source_file_id   UUID                     NOT NULL,
    revision_id      UUID                     NOT NULL,
    parent_symbol_id UUID,
    kind             VARCHAR(32)              NOT NULL,
    name             VARCHAR(500)             NOT NULL,
    qualified_name   VARCHAR(1500),
    signature        VARCHAR(2000),
    declared_type    VARCHAR(500),
    visibility       VARCHAR(32),
    is_static        BOOLEAN                  NOT NULL DEFAULT FALSE,
    is_abstract      BOOLEAN                  NOT NULL DEFAULT FALSE,
    start_line       INTEGER,
    start_column     INTEGER,
    end_line         INTEGER,
    end_column       INTEGER,
    annotation_names VARCHAR(2000),
    javadoc_summary  VARCHAR(2000),
    complexity       INTEGER,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_code_symbol PRIMARY KEY (id),
    CONSTRAINT fk_symbol_file FOREIGN KEY (source_file_id) REFERENCES source_file (id) ON DELETE CASCADE,
    CONSTRAINT fk_symbol_revision FOREIGN KEY (revision_id) REFERENCES repository_revision (id) ON DELETE CASCADE,
    CONSTRAINT fk_symbol_parent FOREIGN KEY (parent_symbol_id) REFERENCES code_symbol (id) ON DELETE CASCADE
);
CREATE INDEX ix_symbol_revision ON code_symbol (revision_id);
CREATE INDEX ix_symbol_file ON code_symbol (source_file_id);
CREATE INDEX ix_symbol_kind ON code_symbol (revision_id, kind);
CREATE INDEX ix_symbol_name ON code_symbol (name);
CREATE INDEX ix_symbol_qname ON code_symbol (qualified_name);

-- ===========================================================================
-- dependency_edge
-- ===========================================================================
CREATE TABLE dependency_edge (
    id               UUID                     NOT NULL,
    revision_id      UUID                     NOT NULL,
    source_symbol_id UUID,
    target_symbol_id UUID,
    source_file_id   UUID,
    edge_type        VARCHAR(48)              NOT NULL,
    target_name      VARCHAR(1500)            NOT NULL,
    resolution       VARCHAR(32)              NOT NULL,
    source_line      INTEGER,
    detail           VARCHAR(2000),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_dependency_edge PRIMARY KEY (id),
    CONSTRAINT fk_edge_revision FOREIGN KEY (revision_id) REFERENCES repository_revision (id) ON DELETE CASCADE,
    CONSTRAINT fk_edge_source_symbol FOREIGN KEY (source_symbol_id) REFERENCES code_symbol (id) ON DELETE CASCADE,
    CONSTRAINT fk_edge_target_symbol FOREIGN KEY (target_symbol_id) REFERENCES code_symbol (id) ON DELETE CASCADE,
    CONSTRAINT fk_edge_source_file FOREIGN KEY (source_file_id) REFERENCES source_file (id) ON DELETE CASCADE
);
CREATE INDEX ix_edge_revision ON dependency_edge (revision_id);
CREATE INDEX ix_edge_source_symbol ON dependency_edge (source_symbol_id);
CREATE INDEX ix_edge_target_symbol ON dependency_edge (target_symbol_id);
CREATE INDEX ix_edge_type ON dependency_edge (revision_id, edge_type);
CREATE INDEX ix_edge_target_name ON dependency_edge (revision_id, target_name);

-- ===========================================================================
-- java_mapping  (framework surface detected from source: REST, Kafka, scheduling)
-- ===========================================================================
CREATE TABLE java_mapping (
    id             UUID                     NOT NULL,
    revision_id    UUID                     NOT NULL,
    source_file_id UUID                     NOT NULL,
    symbol_id      UUID,
    mapping_type   VARCHAR(48)              NOT NULL,
    http_method    VARCHAR(16),
    path           VARCHAR(1000),
    topic          VARCHAR(500),
    consumer_group VARCHAR(500),
    annotation_names VARCHAR(2000),
    start_line     INTEGER,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_java_mapping PRIMARY KEY (id),
    CONSTRAINT fk_mapping_revision FOREIGN KEY (revision_id) REFERENCES repository_revision (id) ON DELETE CASCADE,
    CONSTRAINT fk_mapping_file FOREIGN KEY (source_file_id) REFERENCES source_file (id) ON DELETE CASCADE,
    CONSTRAINT fk_mapping_symbol FOREIGN KEY (symbol_id) REFERENCES code_symbol (id) ON DELETE CASCADE
);
CREATE INDEX ix_mapping_revision_type ON java_mapping (revision_id, mapping_type);
CREATE INDEX ix_mapping_path ON java_mapping (revision_id, path);

-- ===========================================================================
-- analysis_job  (durable job lifecycle)
-- ===========================================================================
CREATE TABLE analysis_job (
    id                UUID                     NOT NULL,
    workspace_id      UUID,
    repository_id     UUID,
    job_type          VARCHAR(48)              NOT NULL,
    status            VARCHAR(24)              NOT NULL,
    priority          INTEGER                  NOT NULL DEFAULT 100,
    progress          INTEGER                  NOT NULL DEFAULT 0,
    stage             VARCHAR(200),
    message           VARCHAR(2000),
    parameters        VARCHAR(4000),
    idempotency_key   VARCHAR(200),
    attempt           INTEGER                  NOT NULL DEFAULT 0,
    max_attempts      INTEGER                  NOT NULL DEFAULT 1,
    /** Earliest time this job may be dispatched. Used for backoff after a retryable failure. */
    available_at      TIMESTAMP WITH TIME ZONE,
    cancel_requested  BOOLEAN                  NOT NULL DEFAULT FALSE,
    analysis_run_id   UUID,
    error_code        VARCHAR(64),
    error_message     VARCHAR(2000),
    result_summary    VARCHAR(2000),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at        TIMESTAMP WITH TIME ZONE,
    finished_at       TIMESTAMP WITH TIME ZONE,
    heartbeat_at      TIMESTAMP WITH TIME ZONE,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_analysis_job PRIMARY KEY (id),
    CONSTRAINT fk_job_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_repository FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
    CONSTRAINT uq_job_idempotency_key UNIQUE (idempotency_key)
);
CREATE INDEX ix_job_status ON analysis_job (status, priority, created_at);
CREATE INDEX ix_job_available ON analysis_job (status, available_at);
CREATE INDEX ix_job_repository ON analysis_job (repository_id, created_at);
CREATE INDEX ix_job_type ON analysis_job (job_type);

-- ===========================================================================
-- analysis_run
-- ===========================================================================
CREATE TABLE analysis_run (
    id                UUID                     NOT NULL,
    repository_id     UUID                     NOT NULL,
    revision_id       UUID,
    job_id            UUID,
    run_type          VARCHAR(48)              NOT NULL,
    status            VARCHAR(24)              NOT NULL,
    analyzer_versions VARCHAR(4000),
    artifact_name     VARCHAR(500),
    artifact_hash     VARCHAR(128),
    artifact_size_bytes BIGINT,
    artifact_ref      VARCHAR(1000),
    summary           VARCHAR(4000),
    finding_count     INTEGER                  NOT NULL DEFAULT 0,
    started_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_analysis_run PRIMARY KEY (id),
    CONSTRAINT fk_run_repository FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
    CONSTRAINT fk_run_revision FOREIGN KEY (revision_id) REFERENCES repository_revision (id) ON DELETE CASCADE
);
CREATE INDEX ix_run_repository ON analysis_run (repository_id, started_at);
CREATE INDEX ix_run_type ON analysis_run (repository_id, run_type);

-- ===========================================================================
-- runtime_artifact  (JFR recordings, heap dumps, thread dumps, benchmark files)
-- ===========================================================================
CREATE TABLE runtime_artifact (
    id                UUID                     NOT NULL,
    repository_id     UUID,
    analysis_run_id   UUID,
    artifact_type     VARCHAR(32)              NOT NULL,
    original_filename VARCHAR(500)             NOT NULL,
    content_hash      VARCHAR(128)             NOT NULL,
    size_bytes        BIGINT                   NOT NULL,
    storage_ref       VARCHAR(1000)            NOT NULL,
    metadata_json     VARCHAR(8000),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_runtime_artifact PRIMARY KEY (id),
    CONSTRAINT fk_artifact_repository FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
    CONSTRAINT fk_artifact_run FOREIGN KEY (analysis_run_id) REFERENCES analysis_run (id) ON DELETE SET NULL
);
CREATE INDEX ix_artifact_repository ON runtime_artifact (repository_id, created_at);
CREATE INDEX ix_artifact_type ON runtime_artifact (artifact_type);
CREATE INDEX ix_artifact_hash ON runtime_artifact (content_hash);

-- ===========================================================================
-- finding  (normalized across every analyzer. Evidence category is mandatory.)
-- ===========================================================================
CREATE TABLE finding (
    id                  UUID                     NOT NULL,
    repository_id       UUID                     NOT NULL,
    revision_id         UUID,
    analysis_run_id     UUID,
    source_file_id      UUID,
    symbol_id           UUID,
    fingerprint         VARCHAR(200)             NOT NULL,
    finding_type        VARCHAR(64)              NOT NULL,
    title               VARCHAR(500)             NOT NULL,
    summary             VARCHAR(4000),
    severity            VARCHAR(16)              NOT NULL,
    evidence_category   VARCHAR(32)              NOT NULL,
    confidence          VARCHAR(16)              NOT NULL,
    analyzer_id         VARCHAR(64)              NOT NULL,
    rule_id             VARCHAR(200),
    category            VARCHAR(64)              NOT NULL,
    source_path         VARCHAR(1500),
    start_line          INTEGER,
    end_line            INTEGER,
    impact              VARCHAR(2000),
    recommended_action  VARCHAR(4000),
    limitations         VARCHAR(2000),
    verification_status VARCHAR(32)              NOT NULL,
    attributes_json     VARCHAR(8000),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_finding PRIMARY KEY (id),
    CONSTRAINT fk_finding_repository FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
    CONSTRAINT fk_finding_revision FOREIGN KEY (revision_id) REFERENCES repository_revision (id) ON DELETE CASCADE,
    CONSTRAINT fk_finding_run FOREIGN KEY (analysis_run_id) REFERENCES analysis_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_finding_file FOREIGN KEY (source_file_id) REFERENCES source_file (id) ON DELETE SET NULL,
    CONSTRAINT fk_finding_symbol FOREIGN KEY (symbol_id) REFERENCES code_symbol (id) ON DELETE SET NULL,
    CONSTRAINT uq_finding_run_fingerprint UNIQUE (analysis_run_id, fingerprint)
);
CREATE INDEX ix_finding_repository ON finding (repository_id, created_at);
CREATE INDEX ix_finding_severity ON finding (repository_id, severity);
CREATE INDEX ix_finding_category ON finding (repository_id, category);
CREATE INDEX ix_finding_evidence ON finding (repository_id, evidence_category);
CREATE INDEX ix_finding_type ON finding (finding_type);
CREATE INDEX ix_finding_fingerprint ON finding (fingerprint);

-- ===========================================================================
-- investigation
-- ===========================================================================
CREATE TABLE investigation (
    id                UUID                     NOT NULL,
    repository_id     UUID                     NOT NULL,
    revision_id       UUID,
    job_id            UUID,
    title             VARCHAR(500)             NOT NULL,
    question          VARCHAR(4000)            NOT NULL,
    scope             VARCHAR(1000),
    status            VARCHAR(24)              NOT NULL,
    answer            VARCHAR(20000),
    confidence        VARCHAR(16),
    retrieval_summary VARCHAR(4000),
    limitations       VARCHAR(2000),
    model_provider    VARCHAR(64),
    model_name        VARCHAR(200),
    prompt_chars      INTEGER,
    context_chars     INTEGER,
    evidence_count    INTEGER,
    latency_ms        BIGINT,
    grounded          BOOLEAN                  NOT NULL DEFAULT FALSE,
    error_message     VARCHAR(2000),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at      TIMESTAMP WITH TIME ZONE,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_investigation PRIMARY KEY (id),
    CONSTRAINT fk_investigation_repository FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
    CONSTRAINT fk_investigation_revision FOREIGN KEY (revision_id) REFERENCES repository_revision (id) ON DELETE CASCADE
);
CREATE INDEX ix_investigation_repository ON investigation (repository_id, created_at);
CREATE INDEX ix_investigation_status ON investigation (status);

-- ===========================================================================
-- investigation_evidence  (exact evidence handed to, or cited by, the model)
-- ===========================================================================
CREATE TABLE investigation_evidence (
    id               UUID                     NOT NULL,
    investigation_id UUID                     NOT NULL,
    finding_id       UUID,
    evidence_role    VARCHAR(32)              NOT NULL,
    evidence_type    VARCHAR(48)              NOT NULL,
    label            VARCHAR(500)             NOT NULL,
    detail           VARCHAR(4000),
    source_path      VARCHAR(1500),
    start_line       INTEGER,
    end_line         INTEGER,
    symbol_name      VARCHAR(1000),
    symbol_kind      VARCHAR(32),
    retrieval_score  DOUBLE PRECISION,
    retrieval_method VARCHAR(32),
    rank             INTEGER,
    cited            BOOLEAN                  NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_investigation_evidence PRIMARY KEY (id),
    CONSTRAINT fk_inv_evidence_investigation FOREIGN KEY (investigation_id) REFERENCES investigation (id) ON DELETE CASCADE,
    CONSTRAINT fk_inv_evidence_finding FOREIGN KEY (finding_id) REFERENCES finding (id) ON DELETE SET NULL
);
CREATE INDEX ix_inv_evidence_investigation ON investigation_evidence (investigation_id, evidence_role, rank);

-- ===========================================================================
-- investigation_message  (follow-up turns in an investigation)
-- ===========================================================================
CREATE TABLE investigation_message (
    id               UUID                     NOT NULL,
    investigation_id UUID                     NOT NULL,
    turn_index       INTEGER                  NOT NULL,
    role             VARCHAR(16)              NOT NULL,
    content          VARCHAR(20000)           NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_investigation_message PRIMARY KEY (id),
    CONSTRAINT fk_inv_message_investigation FOREIGN KEY (investigation_id) REFERENCES investigation (id) ON DELETE CASCADE
);
CREATE INDEX ix_inv_message_investigation ON investigation_message (investigation_id, turn_index);

-- ===========================================================================
-- recommendation  (proposed fix, never applied automatically)
-- ===========================================================================
CREATE TABLE recommendation (
    id                  UUID                     NOT NULL,
    repository_id       UUID                     NOT NULL,
    finding_id          UUID,
    investigation_id    UUID,
    title               VARCHAR(500)             NOT NULL,
    rationale           VARCHAR(6000),
    patch_diff          VARCHAR(60000),
    target_path         VARCHAR(1500),
    status              VARCHAR(40)              NOT NULL,
    evidence_summary    VARCHAR(4000),
    limitations         VARCHAR(2000),
    generated_by        VARCHAR(64)              NOT NULL,
    model_name          VARCHAR(200),
    applied             BOOLEAN                  NOT NULL DEFAULT FALSE,
    verification_status VARCHAR(40)              NOT NULL,
    verification_notes  VARCHAR(4000),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_recommendation PRIMARY KEY (id),
    CONSTRAINT fk_recommendation_repository FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
    CONSTRAINT fk_recommendation_finding FOREIGN KEY (finding_id) REFERENCES finding (id) ON DELETE CASCADE
);
CREATE INDEX ix_recommendation_finding ON recommendation (finding_id);
CREATE INDEX ix_recommendation_repository ON recommendation (repository_id, created_at);

-- ===========================================================================
-- benchmark_run
-- ===========================================================================
CREATE TABLE benchmark_run (
    id                    UUID                     NOT NULL,
    repository_id         UUID                     NOT NULL,
    analysis_run_id       UUID,
    name                  VARCHAR(300)             NOT NULL,
    workload              VARCHAR(2000),
    dataset               VARCHAR(1000),
    environment           VARCHAR(2000),
    revision_label        VARCHAR(200)             NOT NULL,
    warmup_iterations     INTEGER,
    measurement_iterations INTEGER,
    sample_count          INTEGER                  NOT NULL DEFAULT 0,
    p50_micros            BIGINT,
    p95_micros            BIGINT,
    p99_micros            BIGINT,
    mean_micros           BIGINT,
    min_micros            BIGINT,
    max_micros            BIGINT,
    throughput_per_sec    DOUBLE PRECISION,
    error_count           INTEGER                  NOT NULL DEFAULT 0,
    status                VARCHAR(24)              NOT NULL,
    notes                 VARCHAR(4000),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_benchmark_run PRIMARY KEY (id),
    CONSTRAINT fk_benchmark_repository FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
);
CREATE INDEX ix_benchmark_repository ON benchmark_run (repository_id, name, created_at);

-- ===========================================================================
-- code_embedding (optional semantic retrieval; lexical retrieval is the default)
-- ===========================================================================
CREATE TABLE code_embedding (
    id             UUID                     NOT NULL,
    revision_id    UUID                     NOT NULL,
    source_file_id UUID,
    symbol_id      UUID,
    chunk_index    INTEGER                  NOT NULL,
    start_line     INTEGER,
    end_line       INTEGER,
    content        VARCHAR(20000)           NOT NULL,
    model          VARCHAR(200)             NOT NULL,
    dimensions     INTEGER                  NOT NULL,
    vector_text    VARCHAR(60000)           NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_code_embedding PRIMARY KEY (id),
    CONSTRAINT fk_embedding_revision FOREIGN KEY (revision_id) REFERENCES repository_revision (id) ON DELETE CASCADE,
    CONSTRAINT fk_embedding_file FOREIGN KEY (source_file_id) REFERENCES source_file (id) ON DELETE CASCADE,
    CONSTRAINT uq_embedding_chunk UNIQUE (revision_id, source_file_id, chunk_index, model)
);
CREATE INDEX ix_embedding_revision ON code_embedding (revision_id, model);

-- ===========================================================================
-- model_configuration
-- ===========================================================================
CREATE TABLE model_configuration (
    id                UUID                     NOT NULL,
    provider          VARCHAR(64)              NOT NULL,
    chat_model        VARCHAR(200)             NOT NULL,
    embedding_model   VARCHAR(200),
    base_url          VARCHAR(500)             NOT NULL,
    enabled           BOOLEAN                  NOT NULL DEFAULT TRUE,
    default_provider  BOOLEAN                  NOT NULL DEFAULT FALSE,
    request_timeout_seconds INTEGER            NOT NULL DEFAULT 120,
    max_concurrency   INTEGER                  NOT NULL DEFAULT 2,
    context_char_budget INTEGER                NOT NULL DEFAULT 24000,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_model_configuration PRIMARY KEY (id),
    CONSTRAINT uq_model_provider_chat UNIQUE (provider, chat_model)
);

-- ===========================================================================
-- audit_event
-- ===========================================================================
CREATE TABLE audit_event (
    id             UUID                     NOT NULL,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    actor          VARCHAR(200)             NOT NULL,
    event_type     VARCHAR(64)              NOT NULL,
    outcome        VARCHAR(24)              NOT NULL,
    target_type    VARCHAR(64),
    target_id      VARCHAR(64),
    correlation_id VARCHAR(64),
    detail         VARCHAR(2000),
    CONSTRAINT pk_audit_event PRIMARY KEY (id)
);
CREATE INDEX ix_audit_occurred ON audit_event (occurred_at);
CREATE INDEX ix_audit_event_type ON audit_event (event_type, occurred_at);
