-- =====================================================================================
-- V52 - Bürokratie-Radar: findings, their evidence and their history (task RD-01)
--
-- A finding is a signal the platform raised because several sources together point at a possible problem. It is not a
-- decision: a person confirms or dismisses it, and the platform keeps both the finding and what was decided.
--   radar_findings           the signal; finding_key is stable, so scanning the same document twice finds the same row
--   radar_finding_evidence   what the signal rests on: documents, pages, facts - real foreign keys, not copied text
--   radar_finding_events     every change of the signal, with who and why (append-only)
-- Severity is computed by the rule that raised the finding, never by a language model (spec 6.4).
-- =====================================================================================

CREATE TABLE radar_findings (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    finding_key          varchar(200) NOT NULL,
    finding_type         varchar(30) NOT NULL,
    rule_id              varchar(40) NOT NULL,
    rule_version         integer NOT NULL,
    title                varchar(300) NOT NULL,
    description          text,
    status               varchar(20) NOT NULL DEFAULT 'NEW',
    severity             varchar(10) NOT NULL,
    confidence           numeric(5, 4),
    detected_at          timestamptz NOT NULL DEFAULT now(),
    last_evaluated_at    timestamptz NOT NULL DEFAULT now(),
    related_case_id      uuid REFERENCES cases (id) ON DELETE SET NULL,
    related_contact_id   uuid REFERENCES contacts (id) ON DELETE SET NULL,
    related_contract_id  uuid REFERENCES contracts (id) ON DELETE SET NULL,
    reason_codes         jsonb NOT NULL DEFAULT '[]'::jsonb,
    recommended_actions  jsonb NOT NULL DEFAULT '[]'::jsonb,
    assigned_to          uuid REFERENCES users (id) ON DELETE SET NULL,
    snoozed_until        timestamptz,
    resolved_at          timestamptz,
    resolution_reason    varchar(500),
    explanation          text,
    ai_model_run_id      uuid,
    notified_at          timestamptz,
    material_hash        varchar(64),
    reopen_count         integer NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT radar_findings_key_uq UNIQUE (tenant_id, finding_key),
    CONSTRAINT radar_findings_type_chk CHECK (finding_type IN (
        'CONTRACT_RISK', 'INVOICE_ANOMALY', 'MISSING_INFORMATION', 'UNRESOLVED_CASE', 'CONFLICTING_FACTS',
        'OPERATIONAL_RISK')),
    CONSTRAINT radar_findings_status_chk CHECK (status IN (
        'NEW', 'UNDER_REVIEW', 'CONFIRMED', 'DISMISSED', 'SNOOZED', 'RESOLVED', 'REOPENED')),
    CONSTRAINT radar_findings_severity_chk CHECK (severity IN ('INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT radar_findings_confidence_chk CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT radar_findings_snooze_chk CHECK (status <> 'SNOOZED' OR snoozed_until IS NOT NULL),
    CONSTRAINT radar_findings_resolved_chk CHECK (status NOT IN ('RESOLVED', 'DISMISSED') OR resolved_at IS NOT NULL)
);

CREATE INDEX radar_findings_open_idx ON radar_findings (tenant_id, status, severity)
    WHERE status NOT IN ('RESOLVED', 'DISMISSED');
CREATE INDEX radar_findings_case_idx ON radar_findings (related_case_id) WHERE related_case_id IS NOT NULL;
CREATE INDEX radar_findings_contact_idx ON radar_findings (related_contact_id) WHERE related_contact_id IS NOT NULL;
CREATE INDEX radar_findings_snooze_idx ON radar_findings (snoozed_until) WHERE status = 'SNOOZED';

CREATE TABLE radar_finding_evidence (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    finding_id          uuid NOT NULL REFERENCES radar_findings (id) ON DELETE CASCADE,
    role                varchar(20) NOT NULL DEFAULT 'PRIMARY',
    document_id         uuid REFERENCES documents (id) ON DELETE CASCADE,
    page                integer,
    snippet             text,
    fact_id             uuid REFERENCES document_extractions (id) ON DELETE SET NULL,
    contract_clause_id  uuid REFERENCES contract_clauses (id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT radar_finding_evidence_role_chk CHECK (role IN ('PRIMARY', 'COMPARISON', 'CONTEXT')),
    CONSTRAINT radar_finding_evidence_target_chk CHECK (document_id IS NOT NULL OR fact_id IS NOT NULL OR contract_clause_id IS NOT NULL)
);

CREATE INDEX radar_finding_evidence_finding_idx ON radar_finding_evidence (finding_id);
CREATE INDEX radar_finding_evidence_document_idx ON radar_finding_evidence (document_id) WHERE document_id IS NOT NULL;
CREATE UNIQUE INDEX radar_finding_evidence_uq ON radar_finding_evidence
    (finding_id, role, coalesce(document_id, '00000000-0000-0000-0000-000000000000'::uuid),
     coalesce(fact_id, '00000000-0000-0000-0000-000000000000'::uuid),
     coalesce(contract_clause_id, '00000000-0000-0000-0000-000000000000'::uuid), coalesce(page, 0));

CREATE TABLE radar_finding_events (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    finding_id     uuid NOT NULL REFERENCES radar_findings (id) ON DELETE CASCADE,
    event_type     varchar(20) NOT NULL,
    from_status    varchar(20),
    to_status      varchar(20),
    actor_user_id  uuid REFERENCES users (id) ON DELETE SET NULL,
    reason         varchar(500),
    details        jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT radar_finding_events_type_chk CHECK (event_type IN (
        'DETECTED', 'RE_EVALUATED', 'STATUS_CHANGED', 'ASSIGNED', 'REOPENED', 'EXPLAINED', 'NOTIFIED', 'CASE_CREATED'))
);

CREATE INDEX radar_finding_events_finding_idx ON radar_finding_events (finding_id, created_at DESC);

-- A history is never edited.
CREATE TRIGGER radar_finding_events_append_only
    BEFORE UPDATE ON radar_finding_events
    FOR EACH ROW EXECUTE FUNCTION forbid_append_only_mutation();

ALTER TABLE radar_findings ENABLE ROW LEVEL SECURITY;
ALTER TABLE radar_findings FORCE ROW LEVEL SECURITY;
CREATE POLICY radar_findings_tenant_isolation ON radar_findings
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE radar_finding_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE radar_finding_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY radar_finding_evidence_tenant_isolation ON radar_finding_evidence
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE radar_finding_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE radar_finding_events FORCE ROW LEVEL SECURITY;
CREATE POLICY radar_finding_events_tenant_isolation ON radar_finding_events
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

COMMENT ON COLUMN radar_findings.finding_key IS
    'Stable key of the situation (rule, objects involved); the same situation found twice is the same row.';
COMMENT ON COLUMN radar_findings.material_hash IS
    'Hash of the data the finding rests on. A closed finding is reopened only when a new evaluation brings a different hash.';
COMMENT ON COLUMN radar_findings.severity IS
    'Computed by the rule. Mapping to notification severity: INFO,LOW -> INFO; MEDIUM -> NOTICE; HIGH -> WARNING; CRITICAL -> CRITICAL.';
