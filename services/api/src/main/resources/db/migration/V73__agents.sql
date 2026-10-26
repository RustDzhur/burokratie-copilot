-- =====================================================================================
-- V70 — Agent runtime: permissions and run journal.
--
-- The agent catalog itself lives in classpath resources (agents/**/*.json), not in the
-- database: manifests are code, reviewed in the repository, and versioned together with
-- the implementations they describe. The database keeps only what changes at runtime:
-- who ran which agent, with which trigger and inputs, and what the run produced.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Permissions
-- -------------------------------------------------------------------------------------
-- The agent permissions live in their own area; the constraint is widened the same way
-- V46 and V63 widened it for memory/radar/autopilot/approvals/privacy.
ALTER TABLE permissions DROP CONSTRAINT permissions_area_chk;
ALTER TABLE permissions ADD CONSTRAINT permissions_area_chk CHECK (area IN (
    'documents', 'cases', 'tasks', 'contracts', 'finance', 'contacts', 'mail',
    'templates', 'workflows', 'integrations', 'devices', 'staff', 'settings',
    'audit', 'reports', 'notifications', 'memory', 'radar', 'autopilot', 'approvals',
    'privacy', 'agents'
));

INSERT INTO permissions (code, area, action, name) VALUES
('agents.view','agents','view','{"de":"Agenten ansehen","en":"View agents","ru":"Просмотр агентов"}'),
('agents.run','agents','run','{"de":"Agenten ausführen","en":"Run agents","ru":"Запуск агентов"}')
ON CONFLICT (code) DO NOTHING;

-- OWNER and ADMIN receive every permission automatically in V12; MANAGER and the
-- remaining roles get the agent permissions explicitly, because agents are an
-- operational tool.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON
    (r.code = 'MANAGER' AND p.code IN ('agents.view','agents.run'))
    OR (r.code = 'AUDITOR' AND p.code = 'agents.view')
WHERE r.tenant_id IS NULL
ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------------------------
-- Run journal
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_runs (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL,
    actor_user_id  uuid,
    agent_id       text NOT NULL,
    trigger        text NOT NULL DEFAULT 'manual',
    inputs         jsonb NOT NULL DEFAULT '{}'::jsonb,
    ok             boolean NOT NULL,
    summary        text NOT NULL DEFAULT '',
    meta           jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at     timestamptz NOT NULL DEFAULT now(),
    finished_at    timestamptz
);

CREATE INDEX IF NOT EXISTS agent_runs_tenant_agent_idx ON agent_runs (tenant_id, agent_id, started_at DESC);
