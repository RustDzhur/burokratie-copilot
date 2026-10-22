-- =============================================================================
--  V77 - restore the two tables V75 dropped, because the product still uses them
-- =============================================================================
--  V75 removed the memory module together with its tables. That was wrong: the contact card and the invoice card
--  read their whole content from memory, so two working screens disappeared. The module is back, and V75 cannot be
--  edited - a migration that has run is history. So this one recreates what was dropped.
--
--  Only two tables ever existed: contact_relations (V4) and fact_revisions (V49). contact_profiles was dropped by
--  a `if exists` clause that never matched anything.
-- =============================================================================

CREATE TABLE contact_relations (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    from_contact_id   uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    to_contact_id     uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    relation_type     varchar(40) NOT NULL,
    valid_from        date,
    valid_to          date,
    notes             text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT contact_relations_uq UNIQUE (from_contact_id, to_contact_id, relation_type),
    CONSTRAINT contact_relations_no_self_chk CHECK (from_contact_id <> to_contact_id),
    CONSTRAINT contact_relations_type_chk CHECK (relation_type IN (
        'SPOUSE_OF', 'CHILD_OF', 'PARENT_OF', 'GUARDIAN_OF', 'HOUSEHOLD_MEMBER_OF',
        'EMPLOYEE_OF', 'EMPLOYER_OF', 'REPRESENTATIVE_OF', 'MEMBER_OF',
        'SUPPLIER_OF', 'CUSTOMER_OF', 'PARTNER_OF', 'OTHER'))
);

CREATE TABLE fact_revisions (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    extraction_id           uuid NOT NULL REFERENCES document_extractions (id) ON DELETE CASCADE,
    previous_value          text,
    new_value               text,
    previous_verification   varchar(20) NOT NULL,
    new_verification        varchar(20) NOT NULL,
    changed_by              uuid REFERENCES users (id) ON DELETE SET NULL,
    changed_at              timestamptz NOT NULL DEFAULT now(),
    reason                  varchar(30) NOT NULL,
    supersedes_revision_id  uuid REFERENCES fact_revisions (id) ON DELETE SET NULL,
    CONSTRAINT fact_revisions_reason_chk CHECK (reason IN (
        'USER_CORRECTION', 'USER_CONFIRMATION', 'REEXTRACTION'))
);

CREATE INDEX contact_relations_tenant_idx ON contact_relations (tenant_id);
CREATE INDEX fact_revisions_tenant_idx ON fact_revisions (tenant_id);

-- The same isolation every tenant table has: without a tenant in context, nothing is visible.
ALTER TABLE contact_relations ENABLE ROW LEVEL SECURITY;
ALTER TABLE contact_relations FORCE ROW LEVEL SECURITY;
CREATE POLICY contact_relations_tenant_isolation ON contact_relations
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE fact_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE fact_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY fact_revisions_tenant_isolation ON fact_revisions
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
