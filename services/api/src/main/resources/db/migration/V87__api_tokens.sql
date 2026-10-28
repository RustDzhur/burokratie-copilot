-- =====================================================================================
-- V87 — API tokens become a screen: the permission to issue and revoke them.
--
-- The table and the issuing service exist since V11 / B-013, but nothing in the web layer
-- could reach them: a token was only obtainable by calling a service. Issuing a credential
-- is an administrative act, so the screen that does it needs a permission of its own.
--
-- The permission lives in the area `integrations`: API tokens are the credential of an
-- external integration, and the existing `permissions_area_chk` (widened in V84) already
-- lists that area. The constraint is deliberately NOT touched here — a migration that
-- rewrites the whole area list would collide with every other migration that does the
-- same, and there is nothing to widen for this permission.
--
-- OWNER and ADMIN hold every permission by contract (V12), but a permission created after
-- V12 has to be granted explicitly — the same repair V84 made for agents.*.
-- =====================================================================================

INSERT INTO permissions (code, area, action, name, description) VALUES
('api_tokens.manage', 'integrations', 'manage',
 '{"de":"API-Tokens verwalten","en":"Manage API tokens","ru":"Управление API-токенами"}',
 '{"de":"Tokens für externe Systeme ausstellen, ansehen und widerrufen. Ein Token wird nur einmal angezeigt und wirkt in diesem Konto.",
   "en":"Issue, list and revoke tokens for external systems. A token is shown once and acts in this account.",
   "ru":"Выдача, просмотр и отзыв токенов для внешних систем. Токен показывается один раз и действует в этом аккаунте."}')
ON CONFLICT (code) DO NOTHING;

-- OWNER and ADMIN only: issuing a credential is not a day-to-day operation, and a token
-- bypasses the role model of the person who holds it.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'api_tokens.manage'
WHERE r.tenant_id IS NULL AND r.code IN ('OWNER', 'ADMIN')
ON CONFLICT DO NOTHING;

-- The list screen reads the tokens of a workspace newest first. The partial index from V11
-- only covers active tokens; revoked ones stay visible (that is the point of `revokedAt`),
-- so the listing needs an index that includes them.
CREATE INDEX IF NOT EXISTS api_tokens_tenant_created_idx
    ON api_tokens (tenant_id, created_at DESC);
