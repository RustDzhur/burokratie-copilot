-- =====================================================================================
-- V80 - a real CalDAV connection to an external calendar (iCloud / generic CalDAV)
--
-- The platform had a one-way feed only: a phone subscribes to a private iCalendar URL
-- and reads what the platform publishes. This table is the other direction: the platform
-- logs into an external calendar (iCloud with an app-specific Apple-ID password, or any
-- CalDAV server) and writes its own events there.
--
-- One active connection per person. The app password is encrypted at rest with the same
-- CredentialCipher as mailbox credentials (AES-256-GCM, bound to tenant and connection id);
-- plaintext never touches the database.
-- =====================================================================================

CREATE TABLE calendar_connections (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id                 uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider                varchar(20) NOT NULL,
    account                 varchar(300) NOT NULL,
    server_url              varchar(500) NOT NULL DEFAULT 'https://caldav.icloud.com',
    calendar_path           varchar(1000) NOT NULL,
    calendar_name           varchar(300),
    -- Secrets are encrypted at rest with the tenant key; plaintext is never stored (§21.1).
    app_password_encrypted  text NOT NULL,
    status                  varchar(20) NOT NULL DEFAULT 'CONNECTED',
    last_sync_at            timestamptz,
    last_sync_status        varchar(20),
    error                   text,
    consecutive_failures    integer NOT NULL DEFAULT 0,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    deleted_at              timestamptz,
    CONSTRAINT calendar_connections_provider_chk CHECK (provider IN ('icloud', 'caldav')),
    CONSTRAINT calendar_connections_status_chk CHECK (status IN ('CONNECTED', 'ERROR', 'DISABLED')),
    CONSTRAINT calendar_connections_sync_status_chk CHECK (
        last_sync_status IS NULL OR last_sync_status IN ('SUCCESS', 'PARTIAL', 'FAILED')
    )
);

CREATE TRIGGER calendar_connections_set_updated_at BEFORE UPDATE ON calendar_connections
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- One active external calendar per person; after a disconnect the row keeps the history,
-- and a new connection reuses it.
CREATE UNIQUE INDEX calendar_connections_active_user_key
    ON calendar_connections (tenant_id, user_id) WHERE deleted_at IS NULL;
CREATE INDEX calendar_connections_tenant_idx ON calendar_connections (tenant_id);

ALTER TABLE calendar_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_connections FORCE ROW LEVEL SECURITY;
-- Background sync runs inside RequestContext.runAs(tenantId), so the strict policy holds;
-- no endpoint reads this table without a login (unlike calendar_feed_tokens).
CREATE POLICY calendar_connections_tenant_isolation ON calendar_connections
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

COMMENT ON TABLE calendar_connections IS
    'CalDAV connections of the platform to an external calendar (iCloud or generic CalDAV), '
    'with the app password encrypted at rest.';
