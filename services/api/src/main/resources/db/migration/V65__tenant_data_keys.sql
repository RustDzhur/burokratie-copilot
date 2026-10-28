-- =====================================================================================
-- V65 - one data key per workspace for the encryption of stored documents (task S05-03)
--
-- Documents are encrypted at rest with a key of their own workspace (AES-256-GCM). The key is stored here only in wrapped form,
-- encrypted with the platform key from BC_ENCRYPTION_KEY, which never reaches the database. A workspace is read with its own
-- key only (row level security), so a key of another workspace is not even visible to a query.
-- =====================================================================================
CREATE TABLE tenant_data_keys (
    tenant_id    uuid PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    key_version  smallint NOT NULL DEFAULT 1,
    wrapped_key  bytea NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE tenant_data_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_data_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_data_keys_tenant_isolation ON tenant_data_keys
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
