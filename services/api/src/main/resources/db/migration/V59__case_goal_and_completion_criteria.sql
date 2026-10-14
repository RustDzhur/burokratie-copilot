-- =====================================================================================
-- V59 - a case has a goal and checkable criteria for being finished (tasks CA-01, CA-05)
--
-- A case is not finished because the platform read a document or created a reminder. It is finished when the result it was
-- opened for has been reached, or when a person decides in words that nothing more is needed. The goal says what the case is
-- for; the completion criteria say how that is checked, one fact each: a task done, a document received, a letter delivered,
-- an answer received, a payment confirmed, a decision recorded. Sending a letter, receiving an answer and taking a decision
-- are three different criteria and never stand in for each other.
-- case statuses stay the canonical ones (decision D-03).
-- =====================================================================================

ALTER TABLE cases ADD COLUMN goal text;
ALTER TABLE cases ADD COLUMN next_action text;

CREATE TABLE case_completion_criteria (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    case_id              uuid NOT NULL REFERENCES cases (id) ON DELETE CASCADE,
    kind                 varchar(30) NOT NULL,
    description          varchar(500) NOT NULL,
    status               varchar(10) NOT NULL DEFAULT 'OPEN',
    task_id              uuid REFERENCES tasks (id) ON DELETE SET NULL,
    document_type_id     uuid REFERENCES document_types (id) ON DELETE SET NULL,
    outbound_message_id  uuid REFERENCES outbound_messages (id) ON DELETE SET NULL,
    evidence_ref         jsonb,
    evidence_hash        varchar(64),
    met_at               timestamptz,
    met_by               uuid REFERENCES users (id) ON DELETE SET NULL,
    waived_reason        varchar(500),
    sort_order           integer NOT NULL DEFAULT 100,
    created_by           uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT case_completion_criteria_kind_chk CHECK (kind IN (
        'TASK_DONE', 'DOCUMENT_RECEIVED', 'MESSAGE_DELIVERED', 'REPLY_RECEIVED', 'PAYMENT_CONFIRMED', 'DECISION', 'CUSTOM')),
    CONSTRAINT case_completion_criteria_status_chk CHECK (status IN ('OPEN', 'MET', 'WAIVED')),
    CONSTRAINT case_completion_criteria_met_chk CHECK (status <> 'MET' OR met_at IS NOT NULL),
    -- Giving up a criterion is a decision of a person with a reason.
    CONSTRAINT case_completion_criteria_waived_chk CHECK (status <> 'WAIVED' OR (waived_reason IS NOT NULL AND met_by IS NOT NULL))
);

CREATE INDEX case_completion_criteria_case_idx ON case_completion_criteria (case_id, sort_order);

CREATE TRIGGER case_completion_criteria_set_updated_at BEFORE UPDATE ON case_completion_criteria
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE case_completion_criteria ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_completion_criteria FORCE ROW LEVEL SECURITY;
CREATE POLICY case_completion_criteria_tenant_isolation ON case_completion_criteria
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- The history of a case learns what happens to its goal and criteria.
ALTER TABLE case_events DROP CONSTRAINT case_events_type_chk;
ALTER TABLE case_events ADD CONSTRAINT case_events_type_chk CHECK (event_type IN (
    'CASE_CREATED', 'STATUS_CHANGED', 'DOCUMENT_LINKED', 'DOCUMENT_UNLINKED',
    'TASK_CREATED', 'TASK_COMPLETED', 'DEADLINE_SET', 'DEADLINE_CHANGED',
    'NOTE_ADDED', 'CORRESPONDENCE_SENT', 'CORRESPONDENCE_RECEIVED',
    'PAYMENT_RECORDED', 'CONTRACT_LINKED', 'ASSIGNEE_CHANGED', 'CASE_CLOSED',
    'CASE_REOPENED', 'APPROVAL_REQUESTED', 'APPROVAL_GRANTED', 'APPROVAL_REJECTED',
    'GOAL_SET', 'CRITERION_ADDED', 'CRITERION_MET', 'CRITERION_WAIVED', 'PLAN_PROPOSED', 'PLAN_STARTED', 'REPLY_LINKED'
));
