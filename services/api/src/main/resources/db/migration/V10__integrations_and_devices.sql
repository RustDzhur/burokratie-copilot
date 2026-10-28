-- =====================================================================================
-- V10 — Integrations and devices (§8, §18, §19)
--
-- Honesty requirements encoded in the schema:
--  * An integration that still needs something from the system owner must say so
--    (`requires_owner_action` + `owner_action_hint`) instead of pretending to work (§18).
--  * Secrets live only in encrypted form and are never part of the plaintext config.
-- =====================================================================================

CREATE TABLE integrations (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    workspace_id         uuid REFERENCES workspaces (id) ON DELETE SET NULL,
    provider             varchar(40) NOT NULL,
    category             varchar(40) NOT NULL,
    display_name         varchar(200) NOT NULL,
    status               varchar(30) NOT NULL DEFAULT 'NOT_CONFIGURED',
    -- Non-secret configuration only. Anything secret goes to integration_credentials.
    config               jsonb NOT NULL DEFAULT '{}'::jsonb,
    granted_scopes       jsonb NOT NULL DEFAULT '[]'::jsonb,
    connected_account    varchar(300),
    connected_by         uuid REFERENCES users (id) ON DELETE SET NULL,
    connected_at         timestamptz,
    last_sync_at         timestamptz,
    last_sync_status     varchar(20),
    last_error_code      varchar(80),
    last_error_message   text,
    consecutive_failures integer NOT NULL DEFAULT 0,
    -- The provider cannot be used until the customer supplies access/permission/contract.
    requires_owner_action boolean NOT NULL DEFAULT false,
    owner_action_hint    text,
    is_enabled           boolean NOT NULL DEFAULT false,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    deleted_at           timestamptz,

    CONSTRAINT integrations_provider_chk CHECK (provider IN (
        'MICROSOFT365', 'GOOGLE_WORKSPACE', 'IMAP', 'SMTP', 'CALENDAR_MICROSOFT',
        'CALENDAR_GOOGLE', 'BANK_AGGREGATOR', 'BANK_FINTS', 'ACCOUNTING_DATEV',
        'ACCOUNTING_LEXWARE', 'ACCOUNTING_SEVDESK', 'CRM_HUBSPOT', 'CRM_SALESFORCE',
        'E_SIGNATURE_DOCUSIGN', 'E_SIGNATURE_SKRIBBLE', 'CLOUD_STORAGE_DRIVE',
        'CLOUD_STORAGE_ONEDRIVE', 'OCR_TESSERACT', 'OCR_CLOUD', 'AI_OPENAI',
        'AI_AZURE_OPENAI', 'AI_OLLAMA', 'AI_ANTHROPIC', 'AI_CUSTOM',
        'E_INVOICING_PROVIDER', 'SCANNER_BRIDGE', 'WEBHOOK', 'CUSTOM'
    )),
    CONSTRAINT integrations_category_chk CHECK (category IN (
        'EMAIL', 'CALENDAR', 'BANKING', 'ACCOUNTING', 'CRM', 'SIGNATURE',
        'STORAGE', 'OCR', 'AI', 'E_INVOICING', 'DEVICE', 'OTHER'
    )),
    CONSTRAINT integrations_status_chk CHECK (status IN (
        'NOT_CONFIGURED', 'PENDING_AUTHORIZATION', 'CONNECTED', 'DEGRADED',
        'ERROR', 'DISABLED', 'NOT_SUPPORTED'
    )),
    CONSTRAINT integrations_sync_status_chk CHECK (
        last_sync_status IS NULL OR last_sync_status IN ('SUCCESS', 'PARTIAL', 'FAILED', 'RUNNING')
    ),
    -- A blocked integration must explain what is missing (§18, §36 п.16).
    CONSTRAINT integrations_owner_action_chk CHECK (
        NOT requires_owner_action OR owner_action_hint IS NOT NULL
    )
);

CREATE UNIQUE INDEX integrations_tenant_provider_key
    ON integrations (tenant_id, provider, coalesce(workspace_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE deleted_at IS NULL;
CREATE INDEX integrations_sync_idx ON integrations (status, last_sync_at)
    WHERE is_enabled AND deleted_at IS NULL;

CREATE TRIGGER integrations_set_updated_at BEFORE UPDATE ON integrations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE integration_credentials (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    integration_id     uuid NOT NULL REFERENCES integrations (id) ON DELETE CASCADE,
    tenant_id          uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    credential_type    varchar(30) NOT NULL,
    -- AES-GCM ciphertext produced by the application's key management; never plaintext.
    encrypted_payload  text NOT NULL,
    key_reference      varchar(200),
    key_version        integer NOT NULL DEFAULT 1,
    access_token_expires_at  timestamptz,
    refresh_token_expires_at timestamptz,
    scopes             jsonb NOT NULL DEFAULT '[]'::jsonb,
    rotated_at         timestamptz,
    last_used_at       timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT integration_credentials_type_chk CHECK (credential_type IN (
        'OAUTH2', 'API_KEY', 'PASSWORD', 'CERTIFICATE', 'SERVICE_ACCOUNT'
    ))
);

CREATE INDEX integration_credentials_integration_idx ON integration_credentials (integration_id);

CREATE TRIGGER integration_credentials_set_updated_at BEFORE UPDATE ON integration_credentials
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE integration_sync_runs (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    integration_id uuid NOT NULL REFERENCES integrations (id) ON DELETE CASCADE,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    run_type       varchar(20) NOT NULL DEFAULT 'INCREMENTAL',
    status         varchar(20) NOT NULL DEFAULT 'RUNNING',
    items_processed integer NOT NULL DEFAULT 0,
    items_failed   integer NOT NULL DEFAULT 0,
    cursor         jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at     timestamptz NOT NULL DEFAULT now(),
    finished_at    timestamptz,
    error_code     varchar(80),
    error_message  text,
    details        jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT integration_sync_runs_type_chk CHECK (run_type IN ('FULL', 'INCREMENTAL', 'WEBHOOK', 'MANUAL', 'RETRY')),
    CONSTRAINT integration_sync_runs_status_chk CHECK (status IN ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED')),
    CONSTRAINT integration_sync_runs_counts_chk CHECK (items_processed >= 0 AND items_failed >= 0)
);

CREATE INDEX integration_sync_runs_integration_idx ON integration_sync_runs (integration_id, started_at DESC);
CREATE INDEX integration_sync_runs_running_idx ON integration_sync_runs (status) WHERE status = 'RUNNING';

-- -------------------------------------------------------------------------------------
-- webhooks (§7.4)
-- -------------------------------------------------------------------------------------
CREATE TABLE webhook_endpoints (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name              varchar(200) NOT NULL,
    url               varchar(1000) NOT NULL,
    direction         varchar(20) NOT NULL DEFAULT 'OUTBOUND',
    -- The signing secret is stored encrypted; only its hash is searchable.
    secret_encrypted  text,
    secret_hint       varchar(20),
    events            jsonb NOT NULL DEFAULT '[]'::jsonb,
    is_active         boolean NOT NULL DEFAULT true,
    description       text,
    last_delivery_at  timestamptz,
    failure_count     integer NOT NULL DEFAULT 0,
    created_by        uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    deleted_at        timestamptz,
    CONSTRAINT webhook_endpoints_direction_chk CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT webhook_endpoints_url_chk CHECK (url ~ '^https?://'),
    CONSTRAINT webhook_endpoints_failure_count_chk CHECK (failure_count >= 0)
);

CREATE INDEX webhook_endpoints_tenant_idx ON webhook_endpoints (tenant_id) WHERE deleted_at IS NULL;

CREATE TRIGGER webhook_endpoints_set_updated_at BEFORE UPDATE ON webhook_endpoints
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE webhook_events (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id       uuid REFERENCES webhook_endpoints (id) ON DELETE CASCADE,
    tenant_id         uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    event_type        varchar(80) NOT NULL,
    payload           jsonb NOT NULL DEFAULT '{}'::jsonb,
    direction         varchar(20) NOT NULL DEFAULT 'OUTBOUND',
    signature         varchar(300),
    signature_verified boolean,
    status            varchar(20) NOT NULL DEFAULT 'PENDING',
    attempts          integer NOT NULL DEFAULT 0,
    last_attempt_at   timestamptz,
    next_attempt_at   timestamptz,
    response_status   integer,
    response_body     text,
    error_message     text,
    -- Redelivery of the same external event must be idempotent (§7.4).
    idempotency_key   varchar(200),
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT webhook_events_status_chk CHECK (status IN (
        'PENDING', 'DELIVERED', 'FAILED', 'DEAD_LETTER', 'RECEIVED', 'REJECTED'
    )),
    CONSTRAINT webhook_events_direction_chk CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT webhook_events_attempts_chk CHECK (attempts >= 0)
);

CREATE INDEX webhook_events_pending_idx ON webhook_events (status, next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX webhook_events_tenant_idx ON webhook_events (tenant_id, created_at DESC);
CREATE UNIQUE INDEX webhook_events_idempotency_key
    ON webhook_events (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- devices and scan sessions (§8)
-- -------------------------------------------------------------------------------------
CREATE TABLE devices (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    identifier      varchar(120) NOT NULL,
    name            varchar(200) NOT NULL,
    device_type     varchar(30) NOT NULL,
    connection_type varchar(30) NOT NULL DEFAULT 'PULL',
    status          varchar(20) NOT NULL DEFAULT 'PENDING',
    -- Devices authenticate with their own credential hash; revocation must be immediate.
    api_key_hash    varchar(128),
    capabilities    jsonb NOT NULL DEFAULT '{}'::jsonb,
    driver          varchar(60),
    app_version     varchar(40),
    os_name         varchar(80),
    last_seen_at    timestamptz,
    last_error      text,
    registered_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    registered_at   timestamptz NOT NULL DEFAULT now(),
    revoked_at      timestamptz,
    revoked_by      uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT devices_type_chk CHECK (device_type IN (
        'SCANNER', 'MULTIFUNCTION', 'PRINTER', 'MOBILE', 'DESKTOP_BRIDGE', 'FOLDER_WATCHER'
    )),
    CONSTRAINT devices_status_chk CHECK (status IN (
        'PENDING', 'ACTIVE', 'OFFLINE', 'ERROR', 'REVOKED', 'UNSUPPORTED'
    )),
    CONSTRAINT devices_connection_chk CHECK (connection_type IN ('PULL', 'LOCAL_BRIDGE', 'AGENT'))
);

CREATE UNIQUE INDEX devices_tenant_identifier_key ON devices (tenant_id, identifier);
CREATE INDEX devices_status_idx ON devices (tenant_id, status);
CREATE INDEX devices_last_seen_idx ON devices (tenant_id, last_seen_at DESC);

CREATE TRIGGER devices_set_updated_at BEFORE UPDATE ON devices
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE device_jobs (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id      uuid NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    job_type       varchar(20) NOT NULL,
    status         varchar(20) NOT NULL DEFAULT 'QUEUED',
    parameters     jsonb NOT NULL DEFAULT '{}'::jsonb,
    result         jsonb NOT NULL DEFAULT '{}'::jsonb,
    priority       integer NOT NULL DEFAULT 100,
    attempt        integer NOT NULL DEFAULT 0,
    max_attempts   integer NOT NULL DEFAULT 3,
    created_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    claimed_at     timestamptz,
    started_at     timestamptz,
    finished_at    timestamptz,
    expires_at     timestamptz,
    document_id    uuid REFERENCES documents (id) ON DELETE SET NULL,
    error_code     varchar(80),
    error_message  text,
    idempotency_key varchar(200),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT device_jobs_type_chk CHECK (job_type IN ('SCAN', 'PRINT', 'PING', 'CONFIG', 'CANCEL')),
    CONSTRAINT device_jobs_status_chk CHECK (status IN (
        'QUEUED', 'CLAIMED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'
    )),
    CONSTRAINT device_jobs_attempt_chk CHECK (attempt >= 0 AND max_attempts >= 1)
);

CREATE INDEX device_jobs_queue_idx ON device_jobs (device_id, status, priority, created_at)
    WHERE status IN ('QUEUED', 'CLAIMED');
CREATE INDEX device_jobs_tenant_idx ON device_jobs (tenant_id, created_at DESC);
CREATE UNIQUE INDEX device_jobs_idempotency_key ON device_jobs (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TRIGGER device_jobs_set_updated_at BEFORE UPDATE ON device_jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE scan_sessions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    device_id       uuid REFERENCES devices (id) ON DELETE SET NULL,
    case_id         uuid REFERENCES cases (id) ON DELETE SET NULL,
    status          varchar(20) NOT NULL DEFAULT 'OPEN',
    settings        jsonb NOT NULL DEFAULT '{}'::jsonb,
    page_count      integer NOT NULL DEFAULT 0,
    result_document_id uuid REFERENCES documents (id) ON DELETE SET NULL,
    notes           text,
    started_by      uuid REFERENCES users (id) ON DELETE SET NULL,
    started_at      timestamptz NOT NULL DEFAULT now(),
    completed_at    timestamptz,
    error_message   text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT scan_sessions_status_chk CHECK (status IN (
        'OPEN', 'SCANNING', 'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT scan_sessions_page_count_chk CHECK (page_count >= 0)
);

CREATE INDEX scan_sessions_tenant_idx ON scan_sessions (tenant_id, started_at DESC);
CREATE INDEX scan_sessions_device_idx ON scan_sessions (device_id, status);

CREATE TRIGGER scan_sessions_set_updated_at BEFORE UPDATE ON scan_sessions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
