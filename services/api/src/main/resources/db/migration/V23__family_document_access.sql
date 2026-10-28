-- =====================================================================================
-- V23 — Personal and shared documents in a household
--
-- H-003 requires that a family member does not automatically get access to another
-- member's personal documents. The schema has `documents.confidentiality`, which says how
-- sensitive a document is, and that is not the same question: "confidential" means the
-- document must not travel, while "personal" means it belongs to one member of the
-- household and is nobody else's business in it.
--
-- Two things are therefore recorded.
--
--   personal_documents  which member a document belongs to, when it is personal
--   document_shares     an explicit decision to let somebody else see it
--
-- A document without a row in the first table is shared with the household. That default is
-- deliberate: the platform should not turn an ordinary family file into a private one
-- because somebody forgot to say otherwise, and marking something personal is a decision a
-- person makes about their own document.
--
-- The second table exists because a rule with no way to make an exception is a rule people
-- work around - by printing the document, or by storing it somewhere else. A share names who
-- granted it, so the person whose document it is can be asked.
-- =====================================================================================

CREATE TABLE personal_documents (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    document_id   uuid        NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    member_user_id uuid       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    note          text,
    created_by    uuid        REFERENCES users(id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT personal_documents_owner_uq UNIQUE (document_id)
);

CREATE INDEX personal_documents_member_idx ON personal_documents (tenant_id, member_user_id);

CREATE TABLE document_shares (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    document_id        uuid        NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    shared_with_user_id uuid       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    granted_by         uuid        REFERENCES users(id) ON DELETE SET NULL,
    granted_at         timestamptz NOT NULL DEFAULT now(),
    expires_at         timestamptz,
    revoked_at         timestamptz,
    CONSTRAINT document_shares_uq UNIQUE (document_id, shared_with_user_id)
);

CREATE INDEX document_shares_recipient_idx ON document_shares (tenant_id, shared_with_user_id);

ALTER TABLE personal_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE personal_documents FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS personal_documents_tenant_isolation ON personal_documents;
CREATE POLICY personal_documents_tenant_isolation ON personal_documents
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE document_shares ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_shares FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS document_shares_tenant_isolation ON document_shares;
CREATE POLICY document_shares_tenant_isolation ON document_shares
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

COMMENT ON TABLE personal_documents IS
    'A document that belongs to one member of a household. No row means the document is shared
     with the household.';
COMMENT ON TABLE document_shares IS
    'An explicit decision to let another member see a personal document; names who granted it.';
