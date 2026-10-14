-- =====================================================================================
-- V46 - permissions of the Business Memory, Radar and Case Autopilot modules (task R-04)
--
-- Rights come before the code that checks them (docs/architecture/security-and-tenancy.md). The assignment to roles
-- is a starting point that follows the existing matrix; a tenant narrows or widens it through its own roles.
-- =====================================================================================

ALTER TABLE permissions DROP CONSTRAINT permissions_area_chk;
ALTER TABLE permissions ADD CONSTRAINT permissions_area_chk CHECK (area IN (
    'documents', 'cases', 'tasks', 'contracts', 'finance', 'contacts', 'mail',
    'templates', 'workflows', 'integrations', 'devices', 'staff', 'settings',
    'audit', 'reports', 'notifications', 'memory', 'radar', 'autopilot', 'approvals'
));

INSERT INTO permissions (code, area, action, name) VALUES
('memory.view', 'memory', 'view', '{"de":"Verknüpfungen, Fakten und Verlauf ansehen","en":"View links, facts and timeline","ru":"Просмотр связей, фактов и хронологии"}'),
('memory.confirm', 'memory', 'confirm', '{"de":"Verknüpfungen und Fakten bestätigen","en":"Confirm links and facts","ru":"Подтверждение связей и фактов"}'),
('memory.reject', 'memory', 'reject', '{"de":"Verknüpfungen ablehnen","en":"Reject links","ru":"Отклонение связей"}'),
('radar.view', 'radar', 'view', '{"de":"Radar-Hinweise ansehen","en":"View radar findings","ru":"Просмотр находок радара"}'),
('radar.triage', 'radar', 'triage', '{"de":"Hinweise bestätigen, ablehnen, zurückstellen, zuweisen","en":"Confirm, dismiss, snooze, assign findings","ru":"Подтверждение, отклонение, откладывание, назначение находок"}'),
('radar.manage_rules', 'radar', 'manage_rules', '{"de":"Radar-Regeln und Empfindlichkeit verwalten","en":"Manage radar rules and sensitivity","ru":"Управление правилами и чувствительностью радара"}'),
('autopilot.plan', 'autopilot', 'plan', '{"de":"Pläne für Vorgänge erstellen und ändern","en":"Create and change case plans","ru":"Создание и изменение планов дел"}'),
('autopilot.execute', 'autopilot', 'execute', '{"de":"Vorbereitete Schritte ausführen","en":"Execute prepared steps","ru":"Исполнение подготовленных шагов"}'),
('approvals.decide', 'approvals', 'decide', '{"de":"Über Freigaben entscheiden","en":"Decide on approvals","ru":"Принятие решений по согласованиям"}');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('memory.view','memory.confirm','memory.reject','radar.view','radar.triage',
                                 'radar.manage_rules','autopilot.plan','autopilot.execute','approvals.decide')
WHERE r.tenant_id IS NULL AND (
    r.code IN ('OWNER', 'ADMIN')
    OR (r.code = 'MANAGER' AND p.code <> 'radar.manage_rules')
    OR (r.code = 'EMPLOYEE' AND p.code IN ('memory.view', 'radar.view'))
    OR (r.code = 'ACCOUNTANT' AND p.code IN ('memory.view', 'radar.view', 'radar.triage'))
    OR (r.code = 'LEGAL_ADVISOR' AND p.code IN ('memory.view', 'memory.confirm', 'memory.reject', 'radar.view', 'radar.triage'))
    OR (r.code IN ('AUDITOR', 'READ_ONLY') AND p.code IN ('memory.view', 'radar.view'))
);
