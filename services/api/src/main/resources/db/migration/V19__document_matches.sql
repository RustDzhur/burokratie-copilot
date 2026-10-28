-- =====================================================================================
-- V19 — Proposed document matches (spec §9.5)
--
-- B-010 requires the platform to propose links between a document and the cases, contacts
-- and contracts it belongs to, and requires the user to see WHY. A proposal therefore stores
-- the score AND the evidence that produced it, and a confirmed match is a separate decision
-- recorded with who made it and when.
--
-- Nothing is linked silently: a row exists as SUGGESTED until a rule (above the configured
-- threshold) or a person confirms it, and `rationale` travels with the row either way.
-- =====================================================================================

CREATE TABLE document_matches (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    document_id  uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    version_id   uuid REFERENCES document_versions (id) ON DELETE SET NULL,
    match_type   varchar(20) NOT NULL,
    target_id    uuid NOT NULL,
    score        numeric(5, 4) NOT NULL,
    -- Human readable explanation, localised: what matched and where it was found.
    rationale    jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- The concrete evidence: the exact string, the field, the page.
    evidence     jsonb NOT NULL DEFAULT '{}'::jsonb,
    status       varchar(20) NOT NULL DEFAULT 'SUGGESTED',
    decided_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    decided_at   timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_matches_type_chk CHECK (match_type IN ('CASE', 'CONTACT', 'CONTRACT', 'INVOICE')),
    CONSTRAINT document_matches_status_chk CHECK (status IN ('SUGGESTED', 'CONFIRMED', 'REJECTED')),
    CONSTRAINT document_matches_score_chk CHECK (score >= 0 AND score <= 1),
    CONSTRAINT document_matches_decision_chk CHECK (
        (status = 'SUGGESTED' AND decided_at IS NULL)
        OR (status <> 'SUGGESTED' AND decided_at IS NOT NULL)
    )
);

-- One proposal per document and target: re-running the matching stage updates the score instead of
-- creating a second row a user would have to decide about twice.
CREATE UNIQUE INDEX document_matches_unique ON document_matches (document_id, match_type, target_id);

CREATE INDEX document_matches_document_idx ON document_matches (document_id);
CREATE INDEX document_matches_pending_idx ON document_matches (tenant_id, status) WHERE status = 'SUGGESTED';

-- Row level security, following the pattern of V13: the same policy shape as every other
-- tenant-owned table, so a missing WHERE clause cannot leak a proposal into another tenant.
ALTER TABLE document_matches ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_matches FORCE ROW LEVEL SECURITY;
CREATE POLICY document_matches_tenant_isolation ON document_matches
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

COMMENT ON COLUMN document_matches.rationale IS
    'Why this match was proposed, localised. A match a user cannot understand is a match they cannot judge.';
COMMENT ON COLUMN document_matches.evidence IS
    'The exact string that matched and where it was found, so a reviewer can verify the proposal.';
