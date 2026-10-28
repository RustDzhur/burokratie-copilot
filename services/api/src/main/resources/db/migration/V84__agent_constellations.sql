-- =====================================================================================
-- V84 — Agent constellations (workflow presets) and the missing agents permissions.
--
-- A constellation is a classpath preset (agent-constellations/*.json): an ordered sequence of
-- catalogue agents that solves one recurring task. The presets are code, reviewed with the
-- manifests; only the permission to read and instantiate them is data.
--
-- The migration also repairs V73: the agents view/run permissions were granted to MANAGER and
-- AUDITOR only, although OWNER and ADMIN hold every permission by contract (V12). A new permission
-- created after V12 has to be granted explicitly, which is what this block does.
-- =====================================================================================

ALTER TABLE permissions DROP CONSTRAINT permissions_area_chk;
ALTER TABLE permissions ADD CONSTRAINT permissions_area_chk CHECK (area IN (
    'documents', 'cases', 'tasks', 'contracts', 'finance', 'contacts', 'mail',
    'templates', 'workflows', 'integrations', 'devices', 'staff', 'settings',
    'audit', 'reports', 'notifications', 'memory', 'radar', 'autopilot', 'approvals',
    'privacy', 'agents', 'constellations'
));

INSERT INTO permissions (code, area, action, name) VALUES
('constellations.view', 'constellations', 'view',
 '{"de":"Workflow-Vorlagen ansehen","en":"View workflow presets","ru":"Просмотр шаблонов процессов"}'),
('constellations.instantiate', 'constellations', 'instantiate',
 '{"de":"Workflow-Vorlagen als Graph instanziieren","en":"Instantiate workflow presets as a graph","ru":"Построение графа по шаблону процесса"}')
ON CONFLICT (code) DO NOTHING;

-- OWNER and ADMIN: every permission, including agents.* which V73 did not grant them.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'agents.view', 'agents.run', 'constellations.view', 'constellations.instantiate')
WHERE r.tenant_id IS NULL AND r.code IN ('OWNER', 'ADMIN')
ON CONFLICT DO NOTHING;

-- MANAGER: operational lead, same scope as agents.view/agents.run in V73.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('constellations.view', 'constellations.instantiate')
WHERE r.tenant_id IS NULL AND r.code = 'MANAGER'
ON CONFLICT DO NOTHING;

-- AUDITOR: read-only insight into the presets, like agents.view.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'constellations.view'
WHERE r.tenant_id IS NULL AND r.code = 'AUDITOR'
ON CONFLICT DO NOTHING;
