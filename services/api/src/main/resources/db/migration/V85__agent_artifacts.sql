-- =====================================================================================
-- V85 — Artifacts written by catalogue agents.
--
-- Several industry agents (knowledge, real estate, hospitality, healthcare) work on
-- domains whose master data is not modelled in this platform yet. Their honest output is
-- a prepared artifact: an index, a dossier, an exposé, a cost calculation. Such an
-- artifact has to be reproducible and idempotent — running the same agent twice with the
-- same source must not create a second copy and must not silently overwrite a different
-- result under the same key. The row therefore carries a source key plus a content hash:
-- an identical payload is reused, a changed payload replaces the stored object while the
-- row keeps its identity.
--
-- The bytes live in the configured object storage (BlobStore); only the reference is
-- kept here. Like every tenant-owned table the row is protected by row level security.
-- =====================================================================================

CREATE TABLE agent_artifacts (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    artifact_kind   varchar(60) NOT NULL,
    source_key      varchar(300) NOT NULL,
    storage_key     varchar(500) NOT NULL,
    storage_backend varchar(30) NOT NULL,
    content_type    varchar(150) NOT NULL DEFAULT 'application/json',
    content_hash    char(64) NOT NULL,               -- SHA-256 hex of the stored bytes
    size_bytes      bigint NOT NULL DEFAULT 0,
    created_by      uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT agent_artifacts_key_uq UNIQUE (tenant_id, artifact_kind, source_key),
    CONSTRAINT agent_artifacts_hash_chk CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT agent_artifacts_size_chk CHECK (size_bytes >= 0)
);

CREATE INDEX agent_artifacts_tenant_idx ON agent_artifacts (tenant_id, updated_at DESC);

CREATE TRIGGER agent_artifacts_set_updated_at BEFORE UPDATE ON agent_artifacts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE agent_artifacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_artifacts FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_artifacts_tenant_isolation ON agent_artifacts
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

COMMENT ON TABLE agent_artifacts IS
    'Prepared agent output (index, dossier, exposé, calculation): one row per source key, stored as an object.';
COMMENT ON COLUMN agent_artifacts.source_key IS
    'Stable business key of the input (case id, property id, question hash); makes a rerun idempotent.';
COMMENT ON COLUMN agent_artifacts.content_hash IS
    'SHA-256 of the stored payload; an identical hash means the artifact was not rewritten.';
