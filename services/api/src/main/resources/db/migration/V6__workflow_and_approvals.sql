-- =====================================================================================
-- V6 — Workflow engine and approvals (§16, §19, §32)
--
-- The engine is data-driven: a workflow definition is JSON that is validated against a
-- schema at publish time and executed by a generic interpreter, so a new process does not
-- require Java code (§16 "без изменения Java-кода").
-- =====================================================================================

CREATE TABLE workflow_definitions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid REFERENCES tenants (id) ON DELETE CASCADE,   -- NULL = system default
    code            varchar(80) NOT NULL,
    name            jsonb NOT NULL,
    description     jsonb NOT NULL DEFAULT '{}'::jsonb,
    category        varchar(40) NOT NULL DEFAULT 'GENERAL',
    -- Trigger descriptor is data: {"type":"DOCUMENT_CLASSIFIED","filter":{...}}
    trigger_config  jsonb NOT NULL DEFAULT '{}'::jsonb,
    is_active       boolean NOT NULL DEFAULT false,
    current_version integer,
    created_by      uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT workflow_definitions_code_chk CHECK (code ~ '^[a-z0-9_\-]+$')
);

CREATE UNIQUE INDEX workflow_definitions_code_key
    ON workflow_definitions (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), code);

CREATE TRIGGER workflow_definitions_set_updated_at BEFORE UPDATE ON workflow_definitions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE workflow_versions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    definition_id   uuid NOT NULL REFERENCES workflow_definitions (id) ON DELETE CASCADE,
    tenant_id       uuid REFERENCES tenants (id) ON DELETE CASCADE,
    version         integer NOT NULL,
    status          varchar(20) NOT NULL DEFAULT 'DRAFT',
    -- The executable process graph. Immutable once published.
    definition      jsonb NOT NULL,
    input_schema    jsonb NOT NULL DEFAULT '{}'::jsonb,
    change_note     text,
    published_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    published_at    timestamptz,
    created_by      uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT workflow_versions_uq UNIQUE (definition_id, version),
    CONSTRAINT workflow_versions_version_chk CHECK (version >= 1),
    CONSTRAINT workflow_versions_status_chk CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT workflow_versions_published_chk CHECK (
        status = 'DRAFT' OR published_at IS NOT NULL
    )
);

CREATE INDEX workflow_versions_definition_idx ON workflow_versions (definition_id, version DESC);

CREATE TABLE workflow_runs (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    definition_id    uuid NOT NULL REFERENCES workflow_definitions (id) ON DELETE RESTRICT,
    version_id       uuid NOT NULL REFERENCES workflow_versions (id) ON DELETE RESTRICT,
    status           varchar(20) NOT NULL DEFAULT 'PENDING',
    trigger_type     varchar(60) NOT NULL,
    trigger_payload  jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- Subject of the run: a document, a case, an invoice, ...
    subject_type     varchar(40),
    subject_id       uuid,
    case_id          uuid REFERENCES cases (id) ON DELETE SET NULL,
    current_step_key varchar(80),
    -- Waiting state: time or event the run is parked on.
    wait_until       timestamptz,
    waiting_for_event varchar(80),
    attempt_count    integer NOT NULL DEFAULT 0,
    -- Idempotency: the same trigger must not start the process twice (§16.3).
    idempotency_key  varchar(200),
    started_at       timestamptz,
    finished_at      timestamptz,
    failure_code     varchar(80),
    failure_message  text,
    context          jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT workflow_runs_status_chk CHECK (status IN (
        'PENDING', 'RUNNING', 'WAITING', 'WAITING_APPROVAL', 'COMPLETED',
        'FAILED', 'CANCELLED', 'COMPENSATED'
    )),
    CONSTRAINT workflow_runs_attempt_chk CHECK (attempt_count >= 0)
);

CREATE INDEX workflow_runs_tenant_status_idx ON workflow_runs (tenant_id, status, created_at);
CREATE INDEX workflow_runs_subject_idx ON workflow_runs (subject_type, subject_id);
CREATE INDEX workflow_runs_waiting_idx ON workflow_runs (wait_until) WHERE status = 'WAITING';
CREATE UNIQUE INDEX workflow_runs_idempotency_key
    ON workflow_runs (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TRIGGER workflow_runs_set_updated_at BEFORE UPDATE ON workflow_runs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE tasks
    ADD CONSTRAINT tasks_workflow_run_fk
    FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs (id) ON DELETE SET NULL;

CREATE TABLE workflow_run_steps (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id         uuid NOT NULL REFERENCES workflow_runs (id) ON DELETE CASCADE,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    step_key       varchar(80) NOT NULL,
    step_type      varchar(40) NOT NULL,
    step_index     integer NOT NULL DEFAULT 0,
    status         varchar(20) NOT NULL DEFAULT 'PENDING',
    attempt        integer NOT NULL DEFAULT 0,
    input          jsonb NOT NULL DEFAULT '{}'::jsonb,
    output         jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- Set by the interpreter so a restart resumes at the right place (§16.3).
    next_step_key  varchar(80),
    wait_until     timestamptz,
    started_at     timestamptz,
    finished_at    timestamptz,
    duration_ms    bigint,
    error_code     varchar(80),
    error_message  text,
    -- Compensating action executed when a later step failed (§16.3).
    compensation_status varchar(20),
    compensation_details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT workflow_run_steps_status_chk CHECK (status IN (
        'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'WAITING', 'COMPENSATED'
    )),
    CONSTRAINT workflow_run_steps_type_chk CHECK (step_type IN (
        'ACTION', 'CONDITION', 'WAIT', 'DELAY', 'PARALLEL', 'APPROVAL',
        'NOTIFICATION', 'TASK_CREATE', 'INTEGRATION_CALL', 'SUB_WORKFLOW', 'TERMINATE'
    ))
);

CREATE INDEX workflow_run_steps_run_idx ON workflow_run_steps (run_id, step_index);
CREATE INDEX workflow_run_steps_waiting_idx ON workflow_run_steps (wait_until) WHERE status = 'WAITING';

CREATE TRIGGER workflow_run_steps_set_updated_at BEFORE UPDATE ON workflow_run_steps
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE workflow_events (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id      uuid NOT NULL REFERENCES workflow_runs (id) ON DELETE CASCADE,
    tenant_id   uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    event_type  varchar(60) NOT NULL,
    step_key    varchar(80),
    payload     jsonb NOT NULL DEFAULT '{}'::jsonb,
    message     text,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX workflow_events_run_idx ON workflow_events (run_id, created_at DESC);

-- -------------------------------------------------------------------------------------
-- approvals — level 3/4 actions need an authorised confirmation (§32)
-- -------------------------------------------------------------------------------------
CREATE TABLE approvals (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    subject_type   varchar(40) NOT NULL,
    subject_id     uuid NOT NULL,
    approval_type  varchar(40) NOT NULL,
    status         varchar(20) NOT NULL DEFAULT 'PENDING',
    -- Risk level 1..4 as defined in §32; level 4 always requires an explicit human yes.
    automation_level integer NOT NULL DEFAULT 4,
    title          varchar(300) NOT NULL,
    justification  text,
    requested_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    required_permission varchar(80),
    required_role  varchar(60),
    assigned_to    uuid REFERENCES users (id) ON DELETE SET NULL,
    decided_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    decided_at     timestamptz,
    decision_note  text,
    expires_at     timestamptz,
    workflow_run_id uuid REFERENCES workflow_runs (id) ON DELETE SET NULL,
    -- Idempotency of an approval action: replaying the confirmation must not
    -- execute the underlying action twice (§16.3).
    action_token   varchar(120),
    metadata       jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT approvals_status_chk CHECK (status IN (
        'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED'
    )),
    CONSTRAINT approvals_level_chk CHECK (automation_level BETWEEN 1 AND 4),
    CONSTRAINT approvals_type_chk CHECK (approval_type IN (
        'INVOICE_APPROVAL', 'PAYMENT_APPROVAL', 'CONTRACT_TERMINATION', 'OUTBOUND_MESSAGE',
        'DOCUMENT_DELETION', 'DATA_EXPORT', 'ROLE_CHANGE', 'WORKFLOW_PUBLISH',
        'INTEGRATION_CONNECT', 'HIGH_RISK_AI_ACTION', 'OTHER'
    )),
    CONSTRAINT approvals_decision_chk CHECK (
        (status IN ('APPROVED', 'REJECTED')) = (decided_at IS NOT NULL AND decided_by IS NOT NULL)
    )
);

CREATE INDEX approvals_queue_idx ON approvals (tenant_id, status, created_at);
CREATE INDEX approvals_subject_idx ON approvals (subject_type, subject_id);
CREATE UNIQUE INDEX approvals_action_token_key ON approvals (tenant_id, action_token)
    WHERE action_token IS NOT NULL;

CREATE TRIGGER approvals_set_updated_at BEFORE UPDATE ON approvals
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE approval_events (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_id    uuid NOT NULL REFERENCES approvals (id) ON DELETE CASCADE,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    event_type     varchar(40) NOT NULL,
    actor_user_id  uuid REFERENCES users (id) ON DELETE SET NULL,
    comment        text,
    metadata       jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX approval_events_approval_idx ON approval_events (approval_id, created_at DESC);
