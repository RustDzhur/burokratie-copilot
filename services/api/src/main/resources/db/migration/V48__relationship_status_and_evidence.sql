-- =====================================================================================
-- V48 - relationships between documents and the things they belong to (task BM-02, decision D-02)
--
-- The relationship of the business memory is not a new table that links anything to anything; it is what already exists:
--   document_matches    a document to a case, contact, contract or invoice (and now an obligation or a task);
--   document_relations  a document to another document (duplicate, attachment, reply, correction, replacement).
-- document_relations gets what document_matches already has: a status a person can change and the evidence.
-- =====================================================================================

ALTER TABLE document_matches DROP CONSTRAINT document_matches_type_chk;
ALTER TABLE document_matches ADD CONSTRAINT document_matches_type_chk CHECK (match_type IN
    ('CASE', 'CONTACT', 'CONTRACT', 'INVOICE', 'OBLIGATION', 'TASK'));

ALTER TABLE document_relations ADD COLUMN status varchar(20) NOT NULL DEFAULT 'SUGGESTED';
ALTER TABLE document_relations ADD COLUMN evidence jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE document_relations ADD COLUMN decided_by uuid REFERENCES users (id) ON DELETE SET NULL;
ALTER TABLE document_relations ADD COLUMN decided_at timestamptz;

-- A relation somebody created by hand was a decision; one without an author was found by the platform.
UPDATE document_relations SET status = 'CONFIRMED', decided_by = created_by, decided_at = created_at
WHERE created_by IS NOT NULL;

ALTER TABLE document_relations ADD CONSTRAINT document_relations_status_chk
    CHECK (status IN ('SUGGESTED', 'CONFIRMED', 'REJECTED'));

-- Documents already recognised as copies get their relation, with the reason.
INSERT INTO document_relations (tenant_id, source_document_id, target_document_id, relation_type, confidence, status, evidence)
SELECT d.tenant_id, d.id, d.duplicate_of_document_id, 'DUPLICATE', 1.0, 'CONFIRMED',
       jsonb_build_object('rule', 'identical content hash', 'contentHash', d.content_hash)
FROM documents d
WHERE d.duplicate_of_document_id IS NOT NULL
ON CONFLICT DO NOTHING;
