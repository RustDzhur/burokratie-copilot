-- =====================================================================================
-- V5 — Tasks, typed deadlines and calendars (§12, §19)
--
-- Deadlines are modelled explicitly by *kind* because the specification forbids
-- collapsing them into a single field (§12.3), and because a "legal" deadline may only
-- exist when a verified rule produced it.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- tasks (§12.1)
-- -------------------------------------------------------------------------------------
CREATE TABLE tasks (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    workspace_id          uuid REFERENCES workspaces (id) ON DELETE SET NULL,
    case_id               uuid REFERENCES cases (id) ON DELETE SET NULL,
    parent_task_id        uuid REFERENCES tasks (id) ON DELETE CASCADE,

    title                 varchar(300) NOT NULL,
    description           text,
    status                varchar(30) NOT NULL DEFAULT 'NEW',
    priority              varchar(20) NOT NULL DEFAULT 'NORMAL',
    -- Whether the priority came from a rule, from AI or from a human — the user must be
    -- able to see the basis of a recommendation (§12.2).
    priority_source       varchar(20) NOT NULL DEFAULT 'MANUAL',
    priority_rationale    text,

    created_by_user_id    uuid REFERENCES users (id) ON DELETE SET NULL,   -- постановщик
    approver_user_id      uuid REFERENCES users (id) ON DELETE SET NULL,   -- согласующий
    -- No assignee -> the task belongs to the manual distribution queue (§12.4).
    assignment_pending    boolean NOT NULL DEFAULT true,

    due_at                timestamptz,
    remind_at             timestamptz,
    started_at            timestamptz,
    completed_at          timestamptz,
    completed_by          uuid REFERENCES users (id) ON DELETE SET NULL,
    cancelled_at          timestamptz,
    cancel_reason         varchar(500),

    source                varchar(30) NOT NULL DEFAULT 'MANUAL',
    source_document_id    uuid REFERENCES documents (id) ON DELETE SET NULL,
    workflow_run_id       uuid,

    completion_criteria   text,
    requires_approval     boolean NOT NULL DEFAULT false,
    approved_by           uuid REFERENCES users (id) ON DELETE SET NULL,
    approved_at           timestamptz,
    -- Blocking reason is mandatory while status = BLOCKED.
    blocked_reason        varchar(500),

    tags                  jsonb NOT NULL DEFAULT '[]'::jsonb,
    metadata              jsonb NOT NULL DEFAULT '{}'::jsonb,
    search_vector         tsvector GENERATED ALWAYS AS (
                              to_tsvector('german',
                                  coalesce(title, '') || ' ' || coalesce(description, ''))
                          ) STORED,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    deleted_at            timestamptz,

    CONSTRAINT tasks_status_chk CHECK (status IN (
        'NEW', 'IN_PROGRESS', 'WAITING', 'BLOCKED', 'IN_REVIEW', 'DONE', 'CANCELLED'
    )),
    CONSTRAINT tasks_priority_chk CHECK (priority IN (
        'CRITICAL', 'HIGH', 'NORMAL', 'LOW', 'INFORMATIONAL'
    )),
    CONSTRAINT tasks_priority_source_chk CHECK (priority_source IN ('MANUAL', 'RULE', 'AI', 'DOCUMENT', 'WORKFLOW')),
    CONSTRAINT tasks_source_chk CHECK (source IN (
        'MANUAL', 'DOCUMENT', 'WORKFLOW', 'AI', 'EMAIL', 'SYSTEM', 'IMPORT'
    )),
    CONSTRAINT tasks_blocked_reason_chk CHECK (status <> 'BLOCKED' OR blocked_reason IS NOT NULL),
    CONSTRAINT tasks_done_consistency_chk CHECK ((status = 'DONE') = (completed_at IS NOT NULL)),
    CONSTRAINT tasks_cancel_consistency_chk CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

CREATE INDEX tasks_tenant_status_idx ON tasks (tenant_id, status) WHERE deleted_at IS NULL;
CREATE INDEX tasks_tenant_due_idx ON tasks (tenant_id, due_at) WHERE deleted_at IS NULL AND status <> 'DONE';
CREATE INDEX tasks_case_idx ON tasks (case_id) WHERE deleted_at IS NULL;
CREATE INDEX tasks_assignment_queue_idx ON tasks (tenant_id, priority, created_at)
    WHERE assignment_pending AND deleted_at IS NULL;
CREATE INDEX tasks_search_idx ON tasks USING gin (search_vector);

CREATE TRIGGER tasks_set_updated_at BEFORE UPDATE ON tasks
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE task_assignees (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id       uuid NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role          varchar(20) NOT NULL DEFAULT 'ASSIGNEE',
    assigned_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    assigned_at   timestamptz NOT NULL DEFAULT now(),
    completed_at  timestamptz,
    CONSTRAINT task_assignees_uq UNIQUE (task_id, user_id, role),
    CONSTRAINT task_assignees_role_chk CHECK (role IN ('ASSIGNEE', 'APPROVER', 'WATCHER', 'REVIEWER'))
);

CREATE INDEX task_assignees_user_idx ON task_assignees (user_id) WHERE completed_at IS NULL;
CREATE INDEX task_assignees_task_idx ON task_assignees (task_id);

CREATE TABLE task_comments (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id        uuid NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    author_user_id uuid REFERENCES users (id) ON DELETE SET NULL,
    body           text NOT NULL,
    internal       boolean NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    deleted_at     timestamptz
);

CREATE INDEX task_comments_task_idx ON task_comments (task_id, created_at);

CREATE TRIGGER task_comments_set_updated_at BEFORE UPDATE ON task_comments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE task_checklist_items (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id       uuid NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    label         varchar(500) NOT NULL,
    is_required   boolean NOT NULL DEFAULT false,
    is_done       boolean NOT NULL DEFAULT false,
    done_by       uuid REFERENCES users (id) ON DELETE SET NULL,
    done_at       timestamptz,
    sort_order    integer NOT NULL DEFAULT 100,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT task_checklist_done_chk CHECK (is_done = (done_at IS NOT NULL))
);

CREATE INDEX task_checklist_task_idx ON task_checklist_items (task_id, sort_order);

-- Evidence that the task was really completed (§12.1 "доказательства выполнения").
CREATE TABLE task_evidence (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id      uuid NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    document_id  uuid REFERENCES documents (id) ON DELETE CASCADE,
    blob_id      uuid REFERENCES document_blobs (id) ON DELETE CASCADE,
    evidence_type varchar(30) NOT NULL DEFAULT 'DOCUMENT',
    note         varchar(1000),
    created_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT task_evidence_subject_chk CHECK (document_id IS NOT NULL OR blob_id IS NOT NULL OR note IS NOT NULL),
    CONSTRAINT task_evidence_type_chk CHECK (evidence_type IN (
        'DOCUMENT', 'SENT_CONFIRMATION', 'PAYMENT_PROOF', 'RECEIPT', 'NOTE', 'EXTERNAL_REFERENCE'
    ))
);

CREATE INDEX task_evidence_task_idx ON task_evidence (task_id);

CREATE TABLE task_events (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id        uuid NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    event_type     varchar(40) NOT NULL,
    actor_user_id  uuid REFERENCES users (id) ON DELETE SET NULL,
    description    text,
    changes        jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT task_events_type_chk CHECK (event_type IN (
        'CREATED', 'UPDATED', 'STATUS_CHANGED', 'PRIORITY_CHANGED', 'ASSIGNED',
        'UNASSIGNED', 'COMMENTED', 'CHECKLIST_UPDATED', 'DEADLINE_SET',
        'DEADLINE_CHANGED', 'EVIDENCE_ADDED', 'APPROVAL_REQUESTED',
        'APPROVAL_GRANTED', 'APPROVAL_REJECTED', 'COMPLETED', 'CANCELLED', 'REOPENED'
    ))
);

CREATE INDEX task_events_task_idx ON task_events (task_id, created_at DESC);
CREATE INDEX task_events_tenant_idx ON task_events (tenant_id, created_at DESC);

-- -------------------------------------------------------------------------------------
-- task_deadlines — the six deadline kinds of §12.3, kept strictly apart.
-- -------------------------------------------------------------------------------------
CREATE TABLE task_deadlines (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id        uuid NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    case_id        uuid REFERENCES cases (id) ON DELETE CASCADE,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    deadline_type  varchar(30) NOT NULL,
    due_at         timestamptz,
    due_date       date,
    time_zone      varchar(60) NOT NULL DEFAULT 'Europe/Berlin',
    -- Provenance and confidence: a deadline shown to the user must be explainable.
    source         varchar(20) NOT NULL DEFAULT 'MANUAL',
    source_document_id uuid REFERENCES documents (id) ON DELETE SET NULL,
    source_snippet text,
    rule_id        uuid,
    -- Set when no verified rule exists: the UI must show "verification required"
    -- instead of a fabricated legal deadline (§12.3).
    requires_verification boolean NOT NULL DEFAULT false,
    calculated_at  timestamptz,
    confirmed_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    confirmed_at   timestamptz,
    is_active      boolean NOT NULL DEFAULT true,
    note           varchar(500),
    created_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT task_deadlines_type_chk CHECK (deadline_type IN (
        'DOCUMENT_DATE', 'INTERNAL', 'REMINDER', 'EXTERNAL_ORGANIZATION',
        'ASSUMED_LEGAL', 'CONFIRMED_LEGAL'
    )),
    CONSTRAINT task_deadlines_source_chk CHECK (source IN ('MANUAL', 'DOCUMENT', 'RULE', 'AI', 'WORKFLOW')),
    CONSTRAINT task_deadlines_value_chk CHECK (due_at IS NOT NULL OR due_date IS NOT NULL),
    -- Only a rule-based, human-confirmed deadline may be called legally confirmed.
    CONSTRAINT task_deadlines_legal_chk CHECK (
        deadline_type <> 'CONFIRMED_LEGAL' OR confirmed_at IS NOT NULL
    )
);

CREATE INDEX task_deadlines_task_idx ON task_deadlines (task_id) WHERE is_active;
CREATE INDEX task_deadlines_due_idx ON task_deadlines (tenant_id, due_at) WHERE is_active;

CREATE TRIGGER task_deadlines_set_updated_at BEFORE UPDATE ON task_deadlines
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- holiday calendars (Feiertage) — configurable per country/region (§24)
-- -------------------------------------------------------------------------------------
CREATE TABLE holiday_calendars (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid REFERENCES tenants (id) ON DELETE CASCADE,     -- NULL = system default
    code         varchar(80) NOT NULL,
    name         jsonb NOT NULL,
    country_code char(2) NOT NULL DEFAULT 'DE',
    region       varchar(60),                                        -- Bundesland
    time_zone    varchar(60) NOT NULL DEFAULT 'Europe/Berlin',
    is_default   boolean NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT holiday_calendars_country_chk CHECK (country_code ~ '^[A-Z]{2}$')
);

CREATE UNIQUE INDEX holiday_calendars_code_key
    ON holiday_calendars (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), code);

CREATE TRIGGER holiday_calendars_set_updated_at BEFORE UPDATE ON holiday_calendars
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE holidays (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    calendar_id   uuid NOT NULL REFERENCES holiday_calendars (id) ON DELETE CASCADE,
    holiday_date  date NOT NULL,
    name          jsonb NOT NULL,
    is_half_day   boolean NOT NULL DEFAULT false,
    is_moveable   boolean NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT holidays_uq UNIQUE (calendar_id, holiday_date, is_half_day)
);

CREATE INDEX holidays_calendar_date_idx ON holidays (calendar_id, holiday_date);

-- -------------------------------------------------------------------------------------
-- deadline_rules — versioned, verifiable legal deadline rules (§12.3, §31).
-- Nothing here is pre-filled with invented legal content: rules are created and
-- verified by the customer's specialist, the software only executes them.
-- -------------------------------------------------------------------------------------
CREATE TABLE deadline_rules (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid REFERENCES tenants (id) ON DELETE CASCADE,   -- NULL = system default
    code             varchar(80) NOT NULL,
    name             jsonb NOT NULL,
    description      text,
    legal_basis      text,
    document_type_id uuid REFERENCES document_types (id) ON DELETE SET NULL,
    case_type_id     uuid REFERENCES case_types (id) ON DELETE SET NULL,
    period_value     integer,
    period_unit      varchar(20),
    counting_mode    varchar(20) NOT NULL DEFAULT 'CALENDAR_DAYS',
    start_event      varchar(30) NOT NULL DEFAULT 'RECEIPT_DATE',
    delivery_method  varchar(20),
    holiday_calendar_id uuid REFERENCES holiday_calendars (id) ON DELETE SET NULL,
    -- A rule is unusable until a responsible person has verified it.
    requires_verification boolean NOT NULL DEFAULT true,
    verified_by      uuid REFERENCES users (id) ON DELETE SET NULL,
    verified_at      timestamptz,
    version          integer NOT NULL DEFAULT 1,
    valid_from       timestamptz NOT NULL DEFAULT now(),
    valid_to         timestamptz,
    is_active        boolean NOT NULL DEFAULT true,
    created_by       uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT deadline_rules_period_chk CHECK (
        (period_value IS NULL AND period_unit IS NULL)
        OR (period_value > 0 AND period_unit IN ('DAYS', 'WEEKS', 'MONTHS', 'YEARS'))
    ),
    CONSTRAINT deadline_rules_counting_chk CHECK (counting_mode IN ('CALENDAR_DAYS', 'WORKING_DAYS')),
    CONSTRAINT deadline_rules_start_chk CHECK (start_event IN (
        'DOCUMENT_DATE', 'RECEIPT_DATE', 'SERVICE_DATE', 'CASE_EVENT', 'CONTRACT_EVENT'
    )),
    CONSTRAINT deadline_rules_delivery_chk CHECK (
        delivery_method IS NULL OR delivery_method IN ('POST', 'EMAIL', 'PERSONAL', 'COURIER', 'UNKNOWN')
    ),
    CONSTRAINT deadline_rules_verified_chk CHECK (
        NOT requires_verification OR verified_at IS NULL OR verified_by IS NOT NULL
    )
);

CREATE UNIQUE INDEX deadline_rules_code_key
    ON deadline_rules (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), code, version);

CREATE TRIGGER deadline_rules_set_updated_at BEFORE UPDATE ON deadline_rules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE task_deadlines
    ADD CONSTRAINT task_deadlines_rule_fk
    FOREIGN KEY (rule_id) REFERENCES deadline_rules (id) ON DELETE SET NULL;
