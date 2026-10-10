-- =====================================================================================
-- V11 — System data: notifications, audit, AI, settings, outbox, idempotency (§19, §31)
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- audit_events — append only. UPDATE/DELETE are revoked in V14 (RLS & grants).
-- -------------------------------------------------------------------------------------
CREATE TABLE audit_events (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid REFERENCES tenants (id) ON DELETE SET NULL,
    workspace_id   uuid REFERENCES workspaces (id) ON DELETE SET NULL,
    actor_user_id  uuid REFERENCES users (id) ON DELETE SET NULL,
    actor_type     varchar(20) NOT NULL DEFAULT 'USER',
    actor_label    varchar(200),
    category       varchar(40) NOT NULL,
    action         varchar(80) NOT NULL,
    entity_type    varchar(60),
    entity_id      uuid,
    entity_label   varchar(300),
    description    text,
    -- What changed: field-level diff with secrets already removed (§21.4).
    changes        jsonb NOT NULL DEFAULT '{}'::jsonb,
    result         varchar(20) NOT NULL DEFAULT 'SUCCESS',
    severity       varchar(20) NOT NULL DEFAULT 'INFO',
    ip_address     inet,
    user_agent     varchar(400),
    correlation_id varchar(80),
    request_path   varchar(400),
    http_method    varchar(10),
    -- Optional link to the AI run that caused the action (§32).
    ai_model_run_id uuid,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT audit_events_actor_chk CHECK (actor_type IN ('USER', 'SYSTEM', 'API_TOKEN', 'WORKFLOW', 'AI', 'DEVICE')),
    CONSTRAINT audit_events_result_chk CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED')),
    CONSTRAINT audit_events_severity_chk CHECK (severity IN ('INFO', 'NOTICE', 'WARNING', 'CRITICAL')),
    CONSTRAINT audit_events_category_chk CHECK (category IN (
        'AUTHENTICATION', 'AUTHORIZATION', 'DOCUMENT', 'DOCUMENT_ACCESS', 'CASE', 'TASK',
        'CONTRACT', 'FINANCE', 'PAYMENT', 'MAIL', 'TEMPLATE', 'WORKFLOW', 'APPROVAL',
        'INTEGRATION', 'DEVICE', 'AI', 'SETTINGS', 'EXPORT', 'DELETION', 'ADMIN', 'RETENTION'
    ))
);

CREATE INDEX audit_events_tenant_time_idx ON audit_events (tenant_id, created_at DESC);
CREATE INDEX audit_events_actor_idx ON audit_events (actor_user_id, created_at DESC);
CREATE INDEX audit_events_entity_idx ON audit_events (entity_type, entity_id, created_at DESC);
CREATE INDEX audit_events_category_idx ON audit_events (tenant_id, category, created_at DESC);
CREATE INDEX audit_events_correlation_idx ON audit_events (correlation_id);

-- -------------------------------------------------------------------------------------
-- notifications (§25)
-- -------------------------------------------------------------------------------------
CREATE TABLE notifications (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    notification_type varchar(50) NOT NULL,
    category       varchar(30) NOT NULL,
    severity       varchar(20) NOT NULL DEFAULT 'INFO',
    -- i18n key + parameters: never store a pre-rendered sentence in one language only.
    title_key      varchar(120) NOT NULL,
    body_key       varchar(120),
    parameters     jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- Deep link to the object that needs attention (§25).
    entity_type    varchar(60),
    entity_id      uuid,
    action_url     varchar(500),
    is_read        boolean NOT NULL DEFAULT false,
    read_at        timestamptz,
    -- Push notifications must not contain confidential content (§25).
    is_confidential boolean NOT NULL DEFAULT false,
    dedup_key      varchar(200),
    created_at     timestamptz NOT NULL DEFAULT now(),
    expires_at     timestamptz,
    CONSTRAINT notifications_severity_chk CHECK (severity IN ('INFO', 'NOTICE', 'WARNING', 'CRITICAL')),
    CONSTRAINT notifications_category_chk CHECK (category IN (
        'TASK', 'DEADLINE', 'MISSED_DEADLINE', 'NEW_MAIL', 'DOCUMENT_REVIEW',
        'APPROVAL', 'INTEGRATION_ERROR', 'AUTOMATION_RESULT', 'EXTERNAL_RESPONSE',
        'CONTRACT_CHANGE', 'ARCHIVING', 'SECURITY', 'SYSTEM'
    )),
    CONSTRAINT notifications_read_chk CHECK (is_read = (read_at IS NOT NULL))
);

CREATE INDEX notifications_user_idx ON notifications (user_id, is_read, created_at DESC);
CREATE UNIQUE INDEX notifications_dedup_key ON notifications (user_id, dedup_key)
    WHERE dedup_key IS NOT NULL;

CREATE TABLE notification_preferences (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    category    varchar(30) NOT NULL,
    channel     varchar(20) NOT NULL,
    is_enabled  boolean NOT NULL DEFAULT true,
    frequency   varchar(20) NOT NULL DEFAULT 'IMMEDIATE',
    quiet_hours jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notification_preferences_uq UNIQUE (user_id, category, channel),
    CONSTRAINT notification_preferences_channel_chk CHECK (channel IN (
        'IN_APP', 'EMAIL', 'PUSH', 'WEBHOOK', 'SMS'
    )),
    CONSTRAINT notification_preferences_frequency_chk CHECK (frequency IN (
        'IMMEDIATE', 'DAILY_DIGEST', 'WEEKLY_DIGEST', 'NEVER'
    ))
);

CREATE TRIGGER notification_preferences_set_updated_at BEFORE UPDATE ON notification_preferences
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE notification_deliveries (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id uuid NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    channel         varchar(20) NOT NULL,
    status          varchar(20) NOT NULL DEFAULT 'PENDING',
    attempts        integer NOT NULL DEFAULT 0,
    provider_message_id varchar(300),
    error_message   text,
    sent_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notification_deliveries_channel_chk CHECK (channel IN (
        'IN_APP', 'EMAIL', 'PUSH', 'WEBHOOK', 'SMS'
    )),
    CONSTRAINT notification_deliveries_status_chk CHECK (status IN (
        'PENDING', 'SENT', 'FAILED', 'SKIPPED', 'SUPPRESSED'
    )),
    CONSTRAINT notification_deliveries_attempts_chk CHECK (attempts >= 0)
);

CREATE INDEX notification_deliveries_pending_idx ON notification_deliveries (status, created_at)
    WHERE status = 'PENDING';

-- -------------------------------------------------------------------------------------
-- AI: jobs, model runs, usage/budget (§10, §19, §32)
-- -------------------------------------------------------------------------------------
CREATE TABLE ai_jobs (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    job_type       varchar(40) NOT NULL,
    status         varchar(20) NOT NULL DEFAULT 'QUEUED',
    priority       integer NOT NULL DEFAULT 100,
    subject_type   varchar(40),
    subject_id     uuid,
    document_id    uuid REFERENCES documents (id) ON DELETE CASCADE,
    case_id        uuid REFERENCES cases (id) ON DELETE SET NULL,
    input          jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- Instruction version: every AI decision must be traceable to the prompt used (§32).
    instruction_version varchar(40),
    provider       varchar(40),
    model          varchar(120),
    requested_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    attempts       integer NOT NULL DEFAULT 0,
    max_attempts   integer NOT NULL DEFAULT 3,
    scheduled_at   timestamptz NOT NULL DEFAULT now(),
    started_at     timestamptz,
    finished_at    timestamptz,
    error_code     varchar(80),
    error_message  text,
    result_run_id  uuid,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ai_jobs_type_chk CHECK (job_type IN (
        'CLASSIFY', 'EXTRACT', 'SUMMARIZE', 'DRAFT', 'EMBED', 'CHAT', 'TRANSLATE', 'QUALITY_CHECK'
    )),
    CONSTRAINT ai_jobs_status_chk CHECK (status IN (
        'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED_BUDGET', 'SKIPPED_DISABLED'
    )),
    CONSTRAINT ai_jobs_attempt_chk CHECK (attempts >= 0 AND max_attempts >= 1)
);

CREATE INDEX ai_jobs_queue_idx ON ai_jobs (status, priority, scheduled_at)
    WHERE status = 'QUEUED';
CREATE INDEX ai_jobs_document_idx ON ai_jobs (document_id);
CREATE INDEX ai_jobs_tenant_idx ON ai_jobs (tenant_id, created_at DESC);

CREATE TRIGGER ai_jobs_set_updated_at BEFORE UPDATE ON ai_jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE ai_model_runs (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    ai_job_id           uuid REFERENCES ai_jobs (id) ON DELETE SET NULL,
    provider            varchar(40) NOT NULL,
    model               varchar(120) NOT NULL,
    instruction_version varchar(40),
    -- Hash of the system instruction, so a change of rules is detectable (§32).
    instruction_hash    char(64),
    status              varchar(20) NOT NULL DEFAULT 'SUCCEEDED',
    input_tokens        integer NOT NULL DEFAULT 0,
    output_tokens       integer NOT NULL DEFAULT 0,
    cost_amount         numeric(12, 6) NOT NULL DEFAULT 0,
    currency            char(3) NOT NULL DEFAULT 'EUR',
    latency_ms          bigint,
    -- Redacted request metadata only: no document content, no personal data (§10.1).
    request_meta        jsonb NOT NULL DEFAULT '{}'::jsonb,
    response_structured jsonb,
    response_text       text,
    confidence          numeric(5, 4),
    warnings            jsonb NOT NULL DEFAULT '[]'::jsonb,
    open_questions      jsonb NOT NULL DEFAULT '[]'::jsonb,
    requires_human_review boolean NOT NULL DEFAULT true,
    human_confirmed_by  uuid REFERENCES users (id) ON DELETE SET NULL,
    human_confirmed_at  timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ai_model_runs_status_chk CHECK (status IN ('SUCCEEDED', 'FAILED', 'TIMEOUT', 'REJECTED', 'BUDGET_EXCEEDED')),
    CONSTRAINT ai_model_runs_tokens_chk CHECK (input_tokens >= 0 AND output_tokens >= 0),
    CONSTRAINT ai_model_runs_confidence_chk CHECK (
        confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    )
);

CREATE INDEX ai_model_runs_job_idx ON ai_model_runs (ai_job_id);
CREATE INDEX ai_model_runs_tenant_idx ON ai_model_runs (tenant_id, created_at DESC);

CREATE TABLE ai_usage_records (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    period_month   date NOT NULL,                    -- first day of the month
    provider       varchar(40) NOT NULL,
    model          varchar(120) NOT NULL,
    job_type       varchar(40) NOT NULL,
    request_count  integer NOT NULL DEFAULT 0,
    input_tokens   bigint NOT NULL DEFAULT 0,
    output_tokens  bigint NOT NULL DEFAULT 0,
    cost_amount    numeric(12, 6) NOT NULL DEFAULT 0,
    currency       char(3) NOT NULL DEFAULT 'EUR',
    budget_limit   numeric(12, 6),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ai_usage_records_uq UNIQUE (tenant_id, period_month, provider, model, job_type),
    CONSTRAINT ai_usage_records_counts_chk CHECK (
        request_count >= 0 AND input_tokens >= 0 AND output_tokens >= 0 AND cost_amount >= 0
    ),
    CONSTRAINT ai_usage_records_period_chk CHECK (period_month = date_trunc('month', period_month)::date)
);

CREATE INDEX ai_usage_records_tenant_idx ON ai_usage_records (tenant_id, period_month DESC);

CREATE TRIGGER ai_usage_records_set_updated_at BEFORE UPDATE ON ai_usage_records
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- system_settings — configurable business rules with versioning and history (§31)
-- -------------------------------------------------------------------------------------
CREATE TABLE system_settings (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid REFERENCES tenants (id) ON DELETE CASCADE,   -- NULL = system default
    setting_key   varchar(120) NOT NULL,
    category      varchar(40) NOT NULL,
    value         jsonb NOT NULL,
    value_type    varchar(20) NOT NULL DEFAULT 'JSON',
    description   jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- Rules that affect legal/financial behaviour must be verified by a person (§31).
    requires_verification boolean NOT NULL DEFAULT false,
    verified_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    verified_at   timestamptz,
    version       integer NOT NULL DEFAULT 1,
    is_active     boolean NOT NULL DEFAULT true,
    updated_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT system_settings_category_chk CHECK (category IN (
        'CLASSIFICATION', 'DOCUMENT_TYPES', 'PRIORITY', 'ASSIGNMENT', 'REMINDERS',
        'RETENTION', 'NOTIFICATIONS', 'TEMPLATES', 'LIMITS', 'AI', 'INTEGRATIONS',
        'APPROVAL', 'WORKFLOW', 'LOCALIZATION', 'SECURITY', 'ACCESSIBILITY', 'OTHER'
    )),
    CONSTRAINT system_settings_value_type_chk CHECK (value_type IN (
        'JSON', 'STRING', 'NUMBER', 'BOOLEAN', 'DURATION', 'PERCENT'
    ))
);

CREATE UNIQUE INDEX system_settings_key_uq
    ON system_settings (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), setting_key);

CREATE TRIGGER system_settings_set_updated_at BEFORE UPDATE ON system_settings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE system_setting_history (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    setting_id     uuid NOT NULL REFERENCES system_settings (id) ON DELETE CASCADE,
    tenant_id      uuid REFERENCES tenants (id) ON DELETE CASCADE,
    setting_key    varchar(120) NOT NULL,
    version        integer NOT NULL,
    previous_value jsonb,
    new_value      jsonb NOT NULL,
    changed_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    change_reason  text,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX system_setting_history_setting_idx ON system_setting_history (setting_id, created_at DESC);

CREATE TABLE feature_flags (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid REFERENCES tenants (id) ON DELETE CASCADE,   -- NULL = global
    flag_key      varchar(120) NOT NULL,
    description   text,
    is_enabled    boolean NOT NULL DEFAULT false,
    rollout_percent integer NOT NULL DEFAULT 100,
    conditions    jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT feature_flags_rollout_chk CHECK (rollout_percent BETWEEN 0 AND 100)
);

CREATE UNIQUE INDEX feature_flags_key_uq
    ON feature_flags (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), flag_key);

CREATE TRIGGER feature_flags_set_updated_at BEFORE UPDATE ON feature_flags
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- retention_jobs (§22)
-- -------------------------------------------------------------------------------------
CREATE TABLE retention_jobs (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    document_id   uuid REFERENCES documents (id) ON DELETE CASCADE,
    policy_id     uuid REFERENCES document_retention_policies (id) ON DELETE SET NULL,
    action        varchar(30) NOT NULL,
    status        varchar(20) NOT NULL DEFAULT 'SCHEDULED',
    scheduled_for date NOT NULL,
    executed_at   timestamptz,
    -- Why an automatic deletion was refused (legal hold, open case, ...).
    blocked_reason text,
    details       jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT retention_jobs_action_chk CHECK (action IN (
        'REVIEW', 'ARCHIVE', 'DELETE', 'LEGAL_HOLD_CHECK', 'NOTIFY'
    )),
    CONSTRAINT retention_jobs_status_chk CHECK (status IN (
        'SCHEDULED', 'RUNNING', 'COMPLETED', 'SKIPPED', 'FAILED', 'BLOCKED', 'CANCELLED'
    ))
);

CREATE INDEX retention_jobs_due_idx ON retention_jobs (scheduled_for, status)
    WHERE status IN ('SCHEDULED', 'FAILED');
CREATE INDEX retention_jobs_document_idx ON retention_jobs (document_id);

CREATE TRIGGER retention_jobs_set_updated_at BEFORE UPDATE ON retention_jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- outbox_events — transactional outbox (§2.1, §16.3)
-- -------------------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Stable identity of the domain event, used by consumers for deduplication.
    event_id       uuid NOT NULL DEFAULT gen_random_uuid(),
    tenant_id      uuid REFERENCES tenants (id) ON DELETE CASCADE,
    aggregate_type varchar(60) NOT NULL,
    aggregate_id   uuid,
    event_type     varchar(100) NOT NULL,
    payload        jsonb NOT NULL DEFAULT '{}'::jsonb,
    status         varchar(20) NOT NULL DEFAULT 'PENDING',
    attempts       integer NOT NULL DEFAULT 0,
    available_at   timestamptz NOT NULL DEFAULT now(),
    locked_by      varchar(120),
    locked_at      timestamptz,
    published_at   timestamptz,
    error_message  text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT outbox_events_status_chk CHECK (status IN (
        'PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED', 'DEAD_LETTER'
    )),
    CONSTRAINT outbox_events_attempts_chk CHECK (attempts >= 0),
    CONSTRAINT outbox_events_event_id_key UNIQUE (event_id)
);

CREATE INDEX outbox_events_pending_idx ON outbox_events (available_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX outbox_events_aggregate_idx ON outbox_events (aggregate_type, aggregate_id);

-- Consumer-side deduplication so at-least-once delivery stays effectively-once (§16.3).
CREATE TABLE outbox_consumptions (
    consumer      varchar(120) NOT NULL,
    event_id      uuid NOT NULL,
    tenant_id     uuid REFERENCES tenants (id) ON DELETE CASCADE,
    consumed_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

-- -------------------------------------------------------------------------------------
-- idempotency_records (§7.4, §16.3) — repeated unsafe requests return the same response
-- -------------------------------------------------------------------------------------
CREATE TABLE idempotency_records (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    idempotency_key varchar(200) NOT NULL,
    request_method  varchar(10) NOT NULL,
    request_path    varchar(400) NOT NULL,
    request_hash    char(64) NOT NULL,
    user_id         uuid REFERENCES users (id) ON DELETE SET NULL,
    status          varchar(20) NOT NULL DEFAULT 'IN_PROGRESS',
    response_status integer,
    response_body   jsonb,
    resource_type   varchar(60),
    resource_id     uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    completed_at    timestamptz,
    expires_at      timestamptz NOT NULL,
    CONSTRAINT idempotency_records_uq UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT idempotency_records_status_chk CHECK (status IN (
        'IN_PROGRESS', 'COMPLETED', 'FAILED'
    ))
);

CREATE INDEX idempotency_records_expiry_idx ON idempotency_records (expires_at);

-- -------------------------------------------------------------------------------------
-- API tokens for external integrations (§7.4)
-- -------------------------------------------------------------------------------------
CREATE TABLE api_tokens (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    workspace_id   uuid REFERENCES workspaces (id) ON DELETE SET NULL,
    name           varchar(200) NOT NULL,
    token_prefix   varchar(20) NOT NULL,
    token_hash     varchar(128) NOT NULL,
    scopes         jsonb NOT NULL DEFAULT '[]'::jsonb,
    -- Tokens are limited in rights and time by design (§7.4).
    rate_limit_per_minute integer NOT NULL DEFAULT 60,
    is_active      boolean NOT NULL DEFAULT true,
    last_used_at   timestamptz,
    expires_at     timestamptz,
    created_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    revoked_at     timestamptz,
    revoked_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT api_tokens_hash_key UNIQUE (token_hash),
    CONSTRAINT api_tokens_rate_limit_chk CHECK (rate_limit_per_minute > 0)
);

CREATE INDEX api_tokens_tenant_idx ON api_tokens (tenant_id) WHERE is_active AND revoked_at IS NULL;

-- -------------------------------------------------------------------------------------
-- rate limiting for authentication and public endpoints (§21.3)
-- -------------------------------------------------------------------------------------
CREATE TABLE rate_limit_counters (
    bucket_key   varchar(200) NOT NULL,
    window_start timestamptz NOT NULL,
    counter      integer NOT NULL DEFAULT 0,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (bucket_key, window_start)
);

CREATE INDEX rate_limit_counters_window_idx ON rate_limit_counters (window_start);

-- -------------------------------------------------------------------------------------
-- saved searches / saved views for the dashboard and registries (§5.3)
-- -------------------------------------------------------------------------------------
CREATE TABLE saved_views (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id      uuid REFERENCES users (id) ON DELETE CASCADE,
    name         varchar(200) NOT NULL,
    entity_type  varchar(60) NOT NULL,
    filters      jsonb NOT NULL DEFAULT '{}'::jsonb,
    columns      jsonb NOT NULL DEFAULT '[]'::jsonb,
    is_shared    boolean NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX saved_views_tenant_idx ON saved_views (tenant_id, entity_type);

CREATE TRIGGER saved_views_set_updated_at BEFORE UPDATE ON saved_views
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
