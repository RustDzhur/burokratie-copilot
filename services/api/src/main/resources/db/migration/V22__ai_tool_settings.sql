-- =====================================================================================
-- V22 — Which AI tools a tenant allows
--
-- E-006 needs a place to record that a tenant has narrowed the list of tools its assistant
-- may use. The list itself lives in code, because a tool is code: it has a name, a risk and
-- a permission, and a row in a table cannot implement any of those. What belongs in the
-- database is the tenant's decision about which of them are available.
--
-- Without this table the platform would have to choose between two wrong answers: let every
-- tenant use every tool - including the ones that send mail and export data - or keep the
-- list in a configuration file that only a deployment can change. The specification asks for
-- per-tenant configuration, so it is a table.
--
-- No row for a tool means the tenant has not decided. The application then allows only the
-- tools that cannot do harm (risk READ_ONLY) and refuses the rest, which is the safe reading
-- of "not configured".
-- =====================================================================================

CREATE TABLE ai_tool_settings (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    tool_name   varchar(80) NOT NULL,
    is_active   boolean     NOT NULL DEFAULT true,
    changed_by  uuid        REFERENCES users(id) ON DELETE SET NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ai_tool_settings_uq UNIQUE (tenant_id, tool_name)
);

CREATE INDEX ai_tool_settings_tenant_idx ON ai_tool_settings (tenant_id);

ALTER TABLE ai_tool_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_tool_settings FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS ai_tool_settings_tenant_isolation ON ai_tool_settings;
CREATE POLICY ai_tool_settings_tenant_isolation ON ai_tool_settings
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

COMMENT ON TABLE ai_tool_settings IS
    'Per-tenant allowlist for AI tools. A missing row means "not decided": read-only tools are
     allowed, everything with an effect is refused.';
