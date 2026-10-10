-- =====================================================================================
-- V3 — Documents (spec §6, §9, §19, §22)
--
-- Design notes
--  * Binaries live in the object store; PostgreSQL keeps metadata, links and the
--    extracted text only (§6.2 "Не дублируй большие бинарные файлы в PostgreSQL").
--  * Every value extracted by rules or AI carries its provenance: page, snippet and
--    extraction method. A field without a source must not exist (§9.4).
--  * document_types + document_type_fields make the taxonomy editable by an
--    administrator without a code change (§9.3).
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Editable document taxonomy (§9.3)
-- -------------------------------------------------------------------------------------
CREATE TABLE document_types (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code            varchar(80) NOT NULL,
    category        varchar(40) NOT NULL,
    name            jsonb NOT NULL,                 -- {"de": "Rechnung", "en": "Invoice", ...}
    description     jsonb NOT NULL DEFAULT '{}'::jsonb,
    parent_id       uuid REFERENCES document_types (id) ON DELETE SET NULL,
    -- Type-specific behavioural flags; business rules stay data, not code (§31).
    is_active       boolean NOT NULL DEFAULT true,
    requires_review boolean NOT NULL DEFAULT false,
    sort_order      integer NOT NULL DEFAULT 100,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_types_code_key UNIQUE (code),
    CONSTRAINT document_types_category_chk CHECK (category IN (
        'FINANCE', 'CONTRACTS', 'GOVERNMENT', 'HOUSING', 'EMPLOYMENT',
        'FAMILY_EDUCATION_HEALTH', 'LEGAL', 'OTHER'
    ))
);

CREATE INDEX document_types_category_idx ON document_types (category, sort_order)
    WHERE is_active;

CREATE TRIGGER document_types_set_updated_at BEFORE UPDATE ON document_types
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Field catalogue per document type: which fields are applicable, mandatory and critical.
CREATE TABLE document_type_fields (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_type_id uuid NOT NULL REFERENCES document_types (id) ON DELETE CASCADE,
    field_key       varchar(80) NOT NULL,
    label           jsonb NOT NULL,
    value_type      varchar(30) NOT NULL,
    is_required     boolean NOT NULL DEFAULT false,
    -- Critical fields must be verified by a human before automation proceeds (§9.2).
    is_critical     boolean NOT NULL DEFAULT false,
    extraction_hint text,
    sort_order      integer NOT NULL DEFAULT 100,
    CONSTRAINT document_type_fields_key_uq UNIQUE (document_type_id, field_key),
    CONSTRAINT document_type_fields_value_type_chk CHECK (value_type IN (
        'TEXT', 'NUMBER', 'AMOUNT', 'CURRENCY', 'DATE', 'PERIOD', 'BOOLEAN',
        'IBAN', 'BIC', 'TAX_ID', 'REFERENCE', 'LIST'
    ))
);

-- -------------------------------------------------------------------------------------
-- document_blobs — one row per stored binary. Shared by document versions and
-- generated artefacts; a blob is never referenced without a tenant scope.
-- -------------------------------------------------------------------------------------
CREATE TABLE document_blobs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    storage_key     varchar(500) NOT NULL,
    storage_backend varchar(30) NOT NULL DEFAULT 'local-fs',
    bucket          varchar(200),
    size_bytes      bigint NOT NULL,
    content_hash    char(64) NOT NULL,               -- SHA-256 hex
    content_type    varchar(150) NOT NULL,
    -- Security scan result; a blob may not be processed before it is CLEAN (§21.2).
    scan_status     varchar(30) NOT NULL DEFAULT 'PENDING',
    scan_details    jsonb NOT NULL DEFAULT '{}'::jsonb,
    quarantined     boolean NOT NULL DEFAULT true,
    encryption      varchar(30) NOT NULL DEFAULT 'none',
    created_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,
    CONSTRAINT document_blobs_hash_format_chk CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT document_blobs_size_chk CHECK (size_bytes >= 0),
    CONSTRAINT document_blobs_scan_status_chk CHECK (scan_status IN (
        'PENDING', 'CLEAN', 'INFECTED', 'FAILED', 'SKIPPED'
    )),
    CONSTRAINT document_blobs_storage_key_uq UNIQUE (storage_backend, storage_key)
);

CREATE INDEX document_blobs_tenant_hash_idx ON document_blobs (tenant_id, content_hash);
CREATE INDEX document_blobs_pending_scan_idx ON document_blobs (scan_status)
    WHERE scan_status = 'PENDING';

-- -------------------------------------------------------------------------------------
-- documents — the central entity (§6.2)
-- -------------------------------------------------------------------------------------
CREATE TABLE documents (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    workspace_id            uuid REFERENCES workspaces (id) ON DELETE SET NULL,
    organization_id         uuid REFERENCES organizations (id) ON DELETE SET NULL,

    original_filename       varchar(400) NOT NULL,
    internal_name           varchar(400) NOT NULL,
    mime_type               varchar(150) NOT NULL,
    size_bytes              bigint NOT NULL,
    content_hash            char(64) NOT NULL,
    current_version         integer NOT NULL DEFAULT 1,

    source                  varchar(30) NOT NULL DEFAULT 'UPLOAD',
    sender                  varchar(400),
    recipient               varchar(400),
    document_date           date,
    document_number         varchar(120),
    document_type_id        uuid REFERENCES document_types (id) ON DELETE SET NULL,
    language                varchar(10),
    summary                 text,

    extracted_text          text,
    structured_data         jsonb NOT NULL DEFAULT '{}'::jsonb,

    processing_status       varchar(30) NOT NULL DEFAULT 'RECEIVED',
    classification_confidence numeric(5, 4),
    review_status           varchar(30) NOT NULL DEFAULT 'NOT_REQUIRED',

    confidentiality         varchar(30) NOT NULL DEFAULT 'INTERNAL',
    legal_hold              boolean NOT NULL DEFAULT false,
    retention_policy_id     uuid,                    -- FK added below (forward reference)

    duplicate_of_document_id uuid REFERENCES documents (id) ON DELETE SET NULL,
    received_at             timestamptz NOT NULL DEFAULT now(),
    archived_at             timestamptz,
    -- Search vector is maintained by PostgreSQL itself from the extracted text.
    search_vector           tsvector GENERATED ALWAYS AS (
                                to_tsvector('german',
                                    coalesce(document_number, '') || ' ' ||
                                    coalesce(original_filename, '') || ' ' ||
                                    coalesce(summary, '') || ' ' ||
                                    coalesce(extracted_text, ''))
                            ) STORED,

    created_by              uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    deleted_at              timestamptz,

    CONSTRAINT documents_hash_format_chk CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT documents_size_chk CHECK (size_bytes >= 0),
    CONSTRAINT documents_version_chk CHECK (current_version >= 1),
    CONSTRAINT documents_confidence_chk CHECK (
        classification_confidence IS NULL
        OR (classification_confidence >= 0 AND classification_confidence <= 1)
    ),
    CONSTRAINT documents_source_chk CHECK (source IN (
        'UPLOAD', 'EMAIL', 'SCANNER', 'API', 'FOLDER_IMPORT', 'MOBILE', 'GENERATED', 'MIGRATION'
    )),
    -- Statuses of the processing pipeline (§6.3). Transitions are enforced in the
    -- service layer; the CHECK only guarantees that no unknown status can be stored.
    CONSTRAINT documents_status_chk CHECK (processing_status IN (
        'RECEIVED', 'QUARANTINED', 'OCR_PENDING', 'EXTRACTING', 'CLASSIFYING', 'MATCHING',
        'REVIEW_REQUIRED', 'READY', 'ACTION_PENDING', 'COMPLETED',
        'FAILED', 'DUPLICATE', 'UNSUPPORTED', 'CANCELLED', 'ARCHIVED', 'LEGAL_HOLD'
    )),
    CONSTRAINT documents_review_status_chk CHECK (review_status IN (
        'NOT_REQUIRED', 'PENDING', 'IN_REVIEW', 'APPROVED', 'REJECTED'
    )),
    CONSTRAINT documents_confidentiality_chk CHECK (confidentiality IN (
        'PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'STRICT'
    ))
);

CREATE INDEX documents_tenant_status_idx ON documents (tenant_id, processing_status)
    WHERE deleted_at IS NULL;
CREATE INDEX documents_tenant_received_idx ON documents (tenant_id, received_at DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX documents_tenant_type_idx ON documents (tenant_id, document_type_id)
    WHERE deleted_at IS NULL;
CREATE INDEX documents_tenant_date_idx ON documents (tenant_id, document_date DESC);
CREATE INDEX documents_workspace_idx ON documents (workspace_id) WHERE deleted_at IS NULL;
CREATE INDEX documents_search_idx ON documents USING gin (search_vector);
CREATE INDEX documents_number_trgm_idx ON documents USING gin (document_number gin_trgm_ops);
-- Duplicate protection: the same content cannot be stored twice per tenant (§6.2, §9.1).
CREATE UNIQUE INDEX documents_tenant_hash_key ON documents (tenant_id, content_hash)
    WHERE deleted_at IS NULL AND processing_status <> 'DUPLICATE';
CREATE INDEX documents_legal_hold_idx ON documents (tenant_id) WHERE legal_hold;

CREATE TRIGGER documents_set_updated_at BEFORE UPDATE ON documents
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- document_retention_policies (§22) — versioned, never one universal period.
-- -------------------------------------------------------------------------------------
CREATE TABLE document_retention_policies (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid REFERENCES tenants (id) ON DELETE CASCADE,  -- NULL = system default
    code                varchar(80) NOT NULL,
    name                jsonb NOT NULL,
    document_type_id    uuid REFERENCES document_types (id) ON DELETE CASCADE,
    -- NULL period = "not yet specified"; the system must then ask for verification
    -- instead of inventing a legal retention period (§22, §31).
    retention_period_months integer,
    retention_trigger   varchar(40) NOT NULL DEFAULT 'DOCUMENT_DATE',
    legal_basis         text,
    requires_verification boolean NOT NULL DEFAULT true,
    verified_by         uuid REFERENCES users (id) ON DELETE SET NULL,
    verified_at         timestamptz,
    version             integer NOT NULL DEFAULT 1,
    valid_from          timestamptz NOT NULL DEFAULT now(),
    valid_to            timestamptz,
    created_by          uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_retention_policies_period_chk CHECK (
        retention_period_months IS NULL OR retention_period_months > 0
    ),
    CONSTRAINT document_retention_policies_trigger_chk CHECK (retention_trigger IN (
        'DOCUMENT_DATE', 'RECEIPT_DATE', 'CASE_CLOSED', 'CONTRACT_END', 'MANUAL'
    ))
);

CREATE UNIQUE INDEX document_retention_policies_code_key
    ON document_retention_policies (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), code, version);

CREATE TRIGGER document_retention_policies_set_updated_at BEFORE UPDATE ON document_retention_policies
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE documents
    ADD CONSTRAINT documents_retention_policy_fk
    FOREIGN KEY (retention_policy_id) REFERENCES document_retention_policies (id) ON DELETE SET NULL;

-- -------------------------------------------------------------------------------------
-- document_versions — immutable history of every accepted file version (§6.2)
-- -------------------------------------------------------------------------------------
CREATE TABLE document_versions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    version         integer NOT NULL,
    blob_id         uuid NOT NULL REFERENCES document_blobs (id) ON DELETE RESTRICT,
    content_hash    char(64) NOT NULL,
    size_bytes      bigint NOT NULL,
    mime_type       varchar(150) NOT NULL,
    original_filename varchar(400) NOT NULL,
    change_note     varchar(500),
    created_by      uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_versions_uq UNIQUE (document_id, version),
    CONSTRAINT document_versions_version_chk CHECK (version >= 1),
    CONSTRAINT document_versions_hash_format_chk CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX document_versions_document_idx ON document_versions (document_id, version DESC);

-- -------------------------------------------------------------------------------------
-- document_pages — one row per page, with OCR diagnostics (§9.2)
-- -------------------------------------------------------------------------------------
CREATE TABLE document_pages (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    version_id      uuid NOT NULL REFERENCES document_versions (id) ON DELETE CASCADE,
    page_number     integer NOT NULL,
    text_content    text,
    ocr_used        boolean NOT NULL DEFAULT false,
    ocr_engine      varchar(60),
    ocr_language    varchar(40),
    ocr_confidence  numeric(5, 4),
    text_blocks     jsonb NOT NULL DEFAULT '[]'::jsonb,   -- coordinates when available
    image_width     integer,
    image_height    integer,
    error_message   text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_pages_uq UNIQUE (version_id, page_number),
    CONSTRAINT document_pages_number_chk CHECK (page_number >= 1),
    CONSTRAINT document_pages_confidence_chk CHECK (
        ocr_confidence IS NULL OR (ocr_confidence >= 0 AND ocr_confidence <= 1)
    )
);

CREATE INDEX document_pages_document_idx ON document_pages (document_id, page_number);
CREATE INDEX document_pages_text_idx ON document_pages
    USING gin (to_tsvector('german', coalesce(text_content, '')));

-- -------------------------------------------------------------------------------------
-- document_extractions — structured fields with mandatory provenance (§9.4, §32)
-- -------------------------------------------------------------------------------------
CREATE TABLE document_extractions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    version_id      uuid REFERENCES document_versions (id) ON DELETE CASCADE,
    field_key       varchar(80) NOT NULL,
    field_value     text,
    normalized_value text,
    value_type      varchar(30) NOT NULL DEFAULT 'TEXT',
    confidence      numeric(5, 4),
    -- Provenance: where the value came from. NULL is only allowed for MANUAL input.
    source_kind     varchar(20) NOT NULL,
    source_page     integer,
    source_snippet  text,
    extractor       varchar(20) NOT NULL,
    ai_model_run_id uuid,
    verified_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    verified_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_extractions_uq UNIQUE (document_id, version_id, field_key),
    CONSTRAINT document_extractions_source_kind_chk CHECK (source_kind IN (
        'TEXT', 'OCR', 'STRUCTURED', 'MANUAL', 'LINKED_OBJECT'
    )),
    CONSTRAINT document_extractions_extractor_chk CHECK (extractor IN ('RULE', 'AI', 'MANUAL')),
    CONSTRAINT document_extractions_confidence_chk CHECK (
        confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    ),
    -- A machine-extracted value without a source is not acceptable (§9.4).
    CONSTRAINT document_extractions_provenance_chk CHECK (
        extractor = 'MANUAL'
        OR source_snippet IS NOT NULL
        OR source_kind = 'LINKED_OBJECT'
    )
);

CREATE INDEX document_extractions_document_idx ON document_extractions (document_id);
CREATE INDEX document_extractions_field_idx ON document_extractions (field_key);

CREATE TRIGGER document_extractions_set_updated_at BEFORE UPDATE ON document_extractions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- document_classifications — classification history, newest marked is_current
-- -------------------------------------------------------------------------------------
CREATE TABLE document_classifications (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id      uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    version_id       uuid REFERENCES document_versions (id) ON DELETE CASCADE,
    document_type_id uuid NOT NULL REFERENCES document_types (id) ON DELETE RESTRICT,
    confidence       numeric(5, 4),
    method           varchar(20) NOT NULL,
    rationale        text,
    ai_model_run_id  uuid,
    is_current       boolean NOT NULL DEFAULT true,
    created_by       uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_classifications_method_chk CHECK (method IN ('RULE', 'AI', 'MANUAL')),
    CONSTRAINT document_classifications_confidence_chk CHECK (
        confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    )
);

CREATE INDEX document_classifications_document_idx ON document_classifications (document_id, created_at DESC);
CREATE UNIQUE INDEX document_classifications_current_key ON document_classifications (document_id)
    WHERE is_current;

-- -------------------------------------------------------------------------------------
-- tags
-- -------------------------------------------------------------------------------------
CREATE TABLE document_tags (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name        varchar(80) NOT NULL,
    color       varchar(20),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_tags_name_uq UNIQUE (tenant_id, name)
);

CREATE TABLE document_tag_links (
    document_id uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    tag_id      uuid NOT NULL REFERENCES document_tags (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, tag_id)
);

-- -------------------------------------------------------------------------------------
-- document_relations — explicit, typed relations between documents
-- -------------------------------------------------------------------------------------
CREATE TABLE document_relations (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    source_document_id  uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    target_document_id  uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    relation_type       varchar(40) NOT NULL,
    confidence          numeric(5, 4),
    created_by          uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_relations_uq UNIQUE (source_document_id, target_document_id, relation_type),
    CONSTRAINT document_relations_no_self_chk CHECK (source_document_id <> target_document_id),
    CONSTRAINT document_relations_type_chk CHECK (relation_type IN (
        'DUPLICATE', 'SUPERSEDES', 'SUPERSEDED_BY', 'ATTACHMENT', 'REFERENCE',
        'CREDIT_NOTE_FOR', 'CORRECTION_OF', 'PART_OF', 'RESPONSE_TO'
    ))
);

CREATE INDEX document_relations_target_idx ON document_relations (target_document_id);

-- -------------------------------------------------------------------------------------
-- document_sources — how a document entered the system (§7)
-- -------------------------------------------------------------------------------------
CREATE TABLE document_sources (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id      uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    tenant_id        uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    source_type      varchar(30) NOT NULL,
    external_id      varchar(300),
    external_system  varchar(80),
    -- Linked mail message; FK is added in V7 once mail_messages exists.
    mail_message_id  uuid,
    raw_metadata     jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- Idempotency of external ingest: the same external object must not create
    -- a second document on redelivery (§7.4, §16.3).
    idempotency_key  varchar(200),
    received_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_sources_type_chk CHECK (source_type IN (
        'UPLOAD', 'EMAIL', 'SCANNER', 'API', 'FOLDER_IMPORT', 'MOBILE', 'GENERATED', 'MIGRATION'
    ))
);

CREATE INDEX document_sources_document_idx ON document_sources (document_id);
CREATE UNIQUE INDEX document_sources_idempotency_key
    ON document_sources (tenant_id, source_type, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX document_sources_external_key
    ON document_sources (tenant_id, external_system, external_id)
    WHERE external_id IS NOT NULL AND external_system IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- document_processing_steps — per-step status, diagnostics and retry (§9.1)
-- -------------------------------------------------------------------------------------
CREATE TABLE document_processing_steps (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id   uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    version_id    uuid REFERENCES document_versions (id) ON DELETE CASCADE,
    step          varchar(40) NOT NULL,
    status        varchar(20) NOT NULL DEFAULT 'PENDING',
    attempt       integer NOT NULL DEFAULT 0,
    started_at    timestamptz,
    finished_at   timestamptz,
    duration_ms   bigint,
    diagnostics   jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code    varchar(80),
    error_message text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_processing_steps_step_chk CHECK (step IN (
        'SECURITY_SCAN', 'HASH', 'DUPLICATE_CHECK', 'FORMAT_DETECT', 'TEXT_EXTRACT',
        'OCR', 'LANGUAGE_DETECT', 'CLASSIFY', 'FIELD_EXTRACT', 'MATCHING',
        'CONTRADICTION_CHECK', 'ACTION_DERIVATION', 'RISK_ASSESSMENT', 'PERSIST', 'INDEX'
    )),
    CONSTRAINT document_processing_steps_status_chk CHECK (status IN (
        'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED'
    )),
    CONSTRAINT document_processing_steps_attempt_chk CHECK (attempt >= 0)
);

CREATE UNIQUE INDEX document_processing_steps_uq ON document_processing_steps (document_id, step);
CREATE INDEX document_processing_steps_failed_idx ON document_processing_steps (status)
    WHERE status = 'FAILED';

CREATE TRIGGER document_processing_steps_set_updated_at BEFORE UPDATE ON document_processing_steps
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- document_reviews — human-in-the-loop decisions (§1.1 п.7, §9.1 п.16)
-- -------------------------------------------------------------------------------------
CREATE TABLE document_reviews (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id    uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    version_id     uuid REFERENCES document_versions (id) ON DELETE SET NULL,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    review_type    varchar(40) NOT NULL,
    status         varchar(20) NOT NULL DEFAULT 'PENDING',
    reason         text,
    assigned_to    uuid REFERENCES users (id) ON DELETE SET NULL,
    requested_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    decided_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    decision_note  text,
    changes        jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    decided_at     timestamptz,
    CONSTRAINT document_reviews_type_chk CHECK (review_type IN (
        'CLASSIFICATION', 'EXTRACTION', 'DUPLICATE', 'SECURITY', 'MATCHING', 'COMPLETENESS'
    )),
    CONSTRAINT document_reviews_status_chk CHECK (status IN (
        'PENDING', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'CANCELLED'
    ))
);

CREATE INDEX document_reviews_queue_idx ON document_reviews (tenant_id, status, created_at)
    WHERE status IN ('PENDING', 'IN_REVIEW');
CREATE INDEX document_reviews_document_idx ON document_reviews (document_id);

-- -------------------------------------------------------------------------------------
-- document_access_logs — who looked at which document (§21.4)
-- -------------------------------------------------------------------------------------
CREATE TABLE document_access_logs (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    document_id  uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    user_id      uuid REFERENCES users (id) ON DELETE SET NULL,
    action       varchar(20) NOT NULL,
    ip_address   inet,
    user_agent   varchar(400),
    details      jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_access_logs_action_chk CHECK (action IN (
        'VIEW', 'DOWNLOAD', 'EXPORT', 'PRINT', 'SHARE', 'DELETE', 'RESTORE'
    ))
);

CREATE INDEX document_access_logs_document_idx ON document_access_logs (document_id, created_at DESC);
CREATE INDEX document_access_logs_tenant_idx ON document_access_logs (tenant_id, created_at DESC);
