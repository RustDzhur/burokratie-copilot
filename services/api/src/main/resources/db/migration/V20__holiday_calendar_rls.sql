-- =====================================================================================
-- V20 — Row level security for the holiday calendars
--
-- V13 put every tenant-owned table under row level security and FORCE RLS. Two tables were
-- missed, and they are not harmless:
--
--   holiday_calendars  has a tenant_id and was readable and writable across tenants
--   holidays           belongs to a tenant through its calendar
--
-- Without a policy, a tenant could read another tenant's calendars - and, because the
-- application filters by `tenant_id is null or tenant_id = :tenantId`, it could pick up
-- somebody else's calendar as its default. That is exactly the failure the specification's
-- isolation requirement is about, and it was found by a test that expected "no default
-- calendar" in a fresh tenant and got one.
--
-- System calendars (tenant_id IS NULL) stay visible to everyone: they are reference data,
-- like the document taxonomy. Everything else is scoped to its tenant.
-- =====================================================================================

ALTER TABLE holiday_calendars ENABLE ROW LEVEL SECURITY;
ALTER TABLE holiday_calendars FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS holiday_calendars_tenant_isolation ON holiday_calendars;
CREATE POLICY holiday_calendars_tenant_isolation ON holiday_calendars
    USING (tenant_id IS NULL OR tenant_id = current_tenant_id())
    WITH CHECK (tenant_id IS NULL OR tenant_id = current_tenant_id());

-- `holidays` has no tenant column of its own: it belongs to a tenant through its calendar. The
-- policy therefore asks the calendar, and the calendar's own policy does not reference this table,
-- so the two do not recurse into each other.
ALTER TABLE holidays ENABLE ROW LEVEL SECURITY;
ALTER TABLE holidays FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS holidays_tenant_isolation ON holidays;
CREATE POLICY holidays_tenant_isolation ON holidays
    USING (EXISTS (
        SELECT 1 FROM holiday_calendars c
        WHERE c.id = holidays.calendar_id
          AND (c.tenant_id IS NULL OR c.tenant_id = current_tenant_id())))
    WITH CHECK (EXISTS (
        SELECT 1 FROM holiday_calendars c
        WHERE c.id = holidays.calendar_id
          AND (c.tenant_id IS NULL OR c.tenant_id = current_tenant_id())));

COMMENT ON POLICY holiday_calendars_tenant_isolation ON holiday_calendars IS
    'System calendars (tenant_id IS NULL) are reference data and visible to every tenant; all others '
    'belong to exactly one.';
