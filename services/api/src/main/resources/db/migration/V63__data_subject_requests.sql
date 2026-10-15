-- =====================================================================================
-- V63 - requests of a data subject (task HD-GDPR-01, spec S16-02)
--
-- A person whose data the workspace holds can ask what is held, ask for a copy, ask for a correction, or ask for deletion.
-- Each such request is kept with who handled it and what came of it. The record holds counts and the names of changed
-- fields, never a second copy of the person's data.
-- =====================================================================================

ALTER TABLE permissions DROP CONSTRAINT permissions_area_chk;
ALTER TABLE permissions ADD CONSTRAINT permissions_area_chk CHECK (area IN (
    'documents', 'cases', 'tasks', 'contracts', 'finance', 'contacts', 'mail',
    'templates', 'workflows', 'integrations', 'devices', 'staff', 'settings',
    'audit', 'reports', 'notifications', 'memory', 'radar', 'autopilot', 'approvals', 'privacy'
));

INSERT INTO permissions (code, area, action, name) VALUES
('privacy.manage', 'privacy', 'manage',
 '{"de":"Anfragen betroffener Personen bearbeiten (Auskunft, Export, Berichtigung, Löschung)","en":"Handle data subject requests (access, export, correction, deletion)","ru":"Обработка запросов субъектов данных (доступ, выгрузка, исправление, удаление)"}');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'privacy.manage'
WHERE r.tenant_id IS NULL AND r.code IN ('OWNER', 'ADMIN');

CREATE TABLE data_subject_requests (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    contact_id   uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    kind         varchar(20) NOT NULL,
    reason       varchar(500),
    handled_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    result       jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT data_subject_requests_kind_chk CHECK (kind IN ('ACCESS', 'EXPORT', 'RECTIFICATION', 'ERASURE'))
);

CREATE INDEX data_subject_requests_contact_idx ON data_subject_requests (tenant_id, contact_id, created_at DESC);

ALTER TABLE data_subject_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE data_subject_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY data_subject_requests_tenant_isolation ON data_subject_requests
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
