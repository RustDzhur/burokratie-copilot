-- =====================================================================================
-- V32 - appointments and reminders, and per-person interface preferences
--
-- calendar_events: the calendar screen shows deadlines (which live on tasks and cases) and, next to
-- them, appointments and reminders a person enters by hand. Those had no table: the screen offered a
-- button that could only ever fail.
--
-- user_preferences: accessibility settings belong to a person, not to a workspace (a person who needs
-- large text needs it in every workspace), so there is no tenant_id here. The key set is closed by the
-- application; the value is whatever that key's owner stores.
-- =====================================================================================

CREATE TABLE calendar_events (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    title       varchar(300) NOT NULL,
    event_type  varchar(20) NOT NULL,
    starts_on   date NOT NULL,
    ends_on     date,
    all_day     boolean NOT NULL DEFAULT true,
    case_id     uuid REFERENCES cases (id) ON DELETE SET NULL,
    created_by  uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT calendar_events_type_chk CHECK (event_type IN ('APPOINTMENT', 'REMINDER')),
    CONSTRAINT calendar_events_dates_chk CHECK (ends_on IS NULL OR ends_on >= starts_on)
);

CREATE INDEX calendar_events_tenant_date_idx ON calendar_events (tenant_id, starts_on)
    WHERE deleted_at IS NULL;

CREATE TRIGGER calendar_events_set_updated_at BEFORE UPDATE ON calendar_events
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE calendar_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE calendar_events FORCE ROW LEVEL SECURITY;
CREATE POLICY calendar_events_tenant_isolation ON calendar_events
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

CREATE TABLE user_preferences (
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    pref_key    varchar(60) NOT NULL,
    pref_value  jsonb NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, pref_key)
);

COMMENT ON TABLE user_preferences IS
    'Per-person interface settings (for example accessibility); intentionally not tenant scoped.';
