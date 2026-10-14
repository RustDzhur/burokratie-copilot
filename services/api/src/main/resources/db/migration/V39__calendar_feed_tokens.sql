-- =====================================================================================
-- V39 - the calendar on the phone
--
-- A person subscribes the phone's calendar (iPhone Calendar, Google Calendar through ICSx5 or a URL, any app that reads
-- iCalendar) to a private address. The address carries a secret that stands in for a login, because a phone's
-- calendar app cannot sign in to the platform. Only a hash of the secret is stored, the secret itself is shown once at
-- creation, and a person can revoke any address at any time; a revoked address answers 404 from then on.
-- =====================================================================================

CREATE TABLE calendar_feed_tokens (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   varchar(64) NOT NULL,
    label        varchar(100) NOT NULL,
    locale       varchar(5) NOT NULL DEFAULT 'de',
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    revoked_at   timestamptz,
    CONSTRAINT calendar_feed_tokens_locale_chk CHECK (locale IN ('de', 'en', 'ru'))
);

CREATE UNIQUE INDEX calendar_feed_tokens_hash_key ON calendar_feed_tokens (token_hash);
CREATE INDEX calendar_feed_tokens_user_idx ON calendar_feed_tokens (tenant_id, user_id);

ALTER TABLE calendar_feed_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_feed_tokens FORCE ROW LEVEL SECURITY;
-- The feed address is opened without a login, so the lookup by secret runs without a tenant (the system context
-- the other background jobs use); everything else is limited to the current tenant.
CREATE POLICY calendar_feed_tokens_tenant_isolation ON calendar_feed_tokens
    USING (current_tenant_id() IS NULL OR tenant_id = current_tenant_id())
    WITH CHECK (current_tenant_id() IS NULL OR tenant_id = current_tenant_id());
