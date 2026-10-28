-- =====================================================================================
-- V49 - history and provenance of facts (task BM-03, decision D-04)
--
-- A fact is a row of document_extractions: a value, where it was read, how sure the reader was. What was missing:
--   * a changed value overwrote the old one, so nobody could tell what it said before or who changed it;
--   * a value read by a machine, a value a person confirmed, a value derived from other facts and a hypothesis were not
--     told apart in one place;
--   * a fact belonged to its document only, not to the contact or contract it is about.
-- fact_revisions keeps every previous value. The trigger writes it, so no code path (re-reading, AI reading, a person's
-- correction) can change a value without leaving the old one behind.
-- =====================================================================================

ALTER TABLE document_extractions ADD COLUMN verification varchar(20) NOT NULL DEFAULT 'UNVERIFIED';
ALTER TABLE document_extractions ADD COLUMN subject_type varchar(20);
ALTER TABLE document_extractions ADD COLUMN subject_id uuid;

ALTER TABLE document_extractions ADD CONSTRAINT document_extractions_verification_chk
    CHECK (verification IN ('UNVERIFIED', 'USER_CONFIRMED', 'DERIVED', 'HYPOTHESIS'));
ALTER TABLE document_extractions ADD CONSTRAINT document_extractions_subject_chk
    CHECK ((subject_type IS NULL AND subject_id IS NULL)
        OR (subject_type IN ('CONTACT', 'CONTRACT', 'CASE', 'INVOICE') AND subject_id IS NOT NULL));

-- What a person confirmed is confirmed; a value taken from another object is derived; the rest stays unverified.
UPDATE document_extractions SET verification = 'USER_CONFIRMED' WHERE verified_by IS NOT NULL;
UPDATE document_extractions SET verification = 'DERIVED' WHERE verified_by IS NULL AND source_kind = 'LINKED_OBJECT';

CREATE INDEX document_extractions_subject_idx ON document_extractions (subject_type, subject_id)
    WHERE subject_id IS NOT NULL;

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
    CONSTRAINT fact_revisions_reason_chk CHECK (reason IN ('USER_CORRECTION', 'USER_CONFIRMATION', 'REEXTRACTION'))
);

CREATE INDEX fact_revisions_extraction_idx ON fact_revisions (extraction_id, changed_at DESC);

ALTER TABLE fact_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE fact_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY fact_revisions_tenant_isolation ON fact_revisions
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- A revision is history: it is never edited.
CREATE TRIGGER fact_revisions_append_only
    BEFORE UPDATE ON fact_revisions
    FOR EACH ROW EXECUTE FUNCTION forbid_append_only_mutation();

-- A person's confirmation marks the fact confirmed, whichever code path stored it.
CREATE OR REPLACE FUNCTION fact_set_verification() RETURNS trigger AS $$
BEGIN
    IF NEW.verified_by IS NOT NULL AND (OLD.verified_by IS NULL OR OLD.verified_by IS DISTINCT FROM NEW.verified_by
                                        OR OLD.verified_at IS DISTINCT FROM NEW.verified_at) THEN
        NEW.verification := 'USER_CONFIRMED';
    ELSIF NEW.verified_by IS NULL AND OLD.verified_by IS NOT NULL AND NEW.field_value IS DISTINCT FROM OLD.field_value THEN
        -- a machine overwrote what a person had confirmed: the confirmation is gone
        NEW.verification := 'UNVERIFIED';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_extractions_verification
    BEFORE UPDATE ON document_extractions
    FOR EACH ROW EXECUTE FUNCTION fact_set_verification();

-- Keeps the previous value whenever the value or its verification changes.
CREATE OR REPLACE FUNCTION fact_record_revision() RETURNS trigger AS $$
DECLARE
    owner_tenant uuid;
    last_revision uuid;
    why varchar(30);
BEGIN
    IF NEW.field_value IS NOT DISTINCT FROM OLD.field_value AND NEW.verification = OLD.verification THEN
        RETURN NEW;
    END IF;
    SELECT d.tenant_id INTO owner_tenant FROM documents d WHERE d.id = NEW.document_id;
    IF owner_tenant IS NULL THEN
        RETURN NEW; -- outside a tenant context nothing can be attributed; the application always runs in one
    END IF;
    IF NEW.verification = 'USER_CONFIRMED' AND NEW.field_value IS DISTINCT FROM OLD.field_value THEN
        why := 'USER_CORRECTION';
    ELSIF NEW.verification = 'USER_CONFIRMED' THEN
        why := 'USER_CONFIRMATION';
    ELSE
        why := 'REEXTRACTION';
    END IF;
    SELECT r.id INTO last_revision FROM fact_revisions r WHERE r.extraction_id = NEW.id
    ORDER BY r.changed_at DESC, r.id DESC LIMIT 1;
    INSERT INTO fact_revisions (tenant_id, extraction_id, previous_value, new_value, previous_verification,
                                new_verification, changed_by, reason, supersedes_revision_id)
    VALUES (owner_tenant, NEW.id, OLD.field_value, NEW.field_value, OLD.verification, NEW.verification,
            NEW.verified_by, why, last_revision);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_extractions_revision
    AFTER UPDATE ON document_extractions
    FOR EACH ROW EXECUTE FUNCTION fact_record_revision();

COMMENT ON COLUMN document_extractions.verification IS
    'UNVERIFIED: read by a machine. USER_CONFIRMED: a person confirmed or corrected it. DERIVED: computed from other facts. HYPOTHESIS: an assumption, never shown as a fact.';
