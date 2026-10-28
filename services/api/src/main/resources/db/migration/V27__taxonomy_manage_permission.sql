-- =====================================================================================
-- V27 — the permission the taxonomy endpoints require did not exist
--
-- TaxonomyAdminController requires 'taxonomy.manage'. The permission was never in the catalogue,
-- because the service test that exercised the endpoints CREATED it if it was missing - so the service
-- tests passed while no real user could reach a single endpoint. Every call answered 403.
--
-- This is the class of defect a service-level test cannot see, which is exactly why the HTTP test was
-- written: a permission nobody holds and a permission everybody holds look identical from inside the
-- service.
--
-- The permission is added to the catalogue and granted where the platform grants administration:
-- OWNER holds everything, ADMIN holds everything except transferring ownership and deleting the
-- account. The grant is written as a statement rather than as a list so that it cannot drift from the
-- roles the rest of the platform uses.
-- =====================================================================================

INSERT INTO permissions (id, code, area, action, name, description)
VALUES (gen_random_uuid(), 'taxonomy.manage', 'documents', 'manage',
        '{"de":"Dokumenttypen pflegen","en":"Manage document types","ru":"Вести таксономию"}'::jsonb,
        '{"de":"Dokumenttypen und ihre Klassifizierungsregeln bearbeiten"}'::jsonb)
ON CONFLICT (code) DO NOTHING;

-- OWNER: everything, as V12 states. ADMIN: everything except ownership transfer and account deletion.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'taxonomy.manage'
WHERE r.tenant_id IS NULL
  AND (r.code = 'OWNER' OR r.code = 'ADMIN')
ON CONFLICT DO NOTHING;

COMMENT ON TABLE permissions IS
    'The permission catalogue. A permission that exists but is granted to no role makes every endpoint
     that requires it unreachable; V27 exists because exactly that had happened.';
