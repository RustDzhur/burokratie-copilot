-- =====================================================================================
-- V33 - plans, subscriptions, platform billing invoices and platform settings
--
-- plans:            the price list (private person / family / company). Global, edited by the platform
--                   administrator, never hard-coded in the application.
-- subscriptions:    one row per tenant. `access_until` is the single date the access gate looks at: a free
--                   trial (60 days by default) and every paid period both just move it. `retention_until`
--                   is set when access ends and is the day the data is deleted if nothing happens.
-- billing_invoices: invoices the platform issues to its customers. NOT removed with the tenant: an invoice is
--                   an accounting record and has its own statutory retention, so there is deliberately no
--                   foreign key to tenants.
-- platform_settings: key/value settings of the platform itself (bank details, wallet, trial length, ...).
-- =====================================================================================

CREATE TABLE plans (
    code         varchar(30)  PRIMARY KEY,
    name         varchar(80)  NOT NULL,
    account_type varchar(40)  NOT NULL,
    price_cents  integer      NOT NULL CHECK (price_cents >= 0),
    currency     varchar(3)   NOT NULL DEFAULT 'EUR',
    sort_order   integer      NOT NULL DEFAULT 0,
    is_active    boolean      NOT NULL DEFAULT true,
    updated_at   timestamptz  NOT NULL DEFAULT now()
);

INSERT INTO plans (code, name, account_type, price_cents, sort_order) VALUES
    ('PRIVATE', 'Privat',   'PRIVATE_PERSON',   700, 1),
    ('FAMILY',  'Familie',  'FAMILY_HOUSEHOLD', 1200, 2),
    ('COMPANY', 'Firma',    'COMPANY',          2900, 3);

CREATE TABLE subscriptions (
    tenant_id        uuid PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    plan_code        varchar(30) NOT NULL REFERENCES plans (code),
    status           varchar(20) NOT NULL DEFAULT 'TRIAL',
    trial_ends_at    timestamptz,
    paid_until       timestamptz,
    access_until     timestamptz NOT NULL,
    retention_until  timestamptz,
    data_choice      varchar(10),
    data_choice_at   timestamptz,
    reminders_sent   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT subscriptions_status_chk CHECK (status IN ('TRIAL', 'ACTIVE', 'EXPIRED', 'SUSPENDED')),
    CONSTRAINT subscriptions_choice_chk CHECK (data_choice IS NULL OR data_choice IN ('KEEP', 'DELETE'))
);

CREATE INDEX subscriptions_access_until_idx ON subscriptions (access_until);

CREATE TRIGGER subscriptions_set_updated_at BEFORE UPDATE ON subscriptions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Same rule as the V13 tables: a request sees its own tenant, the system context (the daily job, the platform
-- administrator's overview) sees all.
ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscriptions FORCE ROW LEVEL SECURITY;
CREATE POLICY subscriptions_tenant_isolation ON subscriptions
    USING (current_tenant_id() IS NULL OR tenant_id = current_tenant_id())
    WITH CHECK (current_tenant_id() IS NULL OR tenant_id = current_tenant_id());

CREATE SEQUENCE billing_invoice_seq START 1;

CREATE TABLE billing_invoices (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL,
    invoice_number    varchar(30) NOT NULL,
    plan_code         varchar(30) NOT NULL,
    period_months     integer     NOT NULL DEFAULT 1 CHECK (period_months BETWEEN 1 AND 24),
    amount_cents      integer     NOT NULL CHECK (amount_cents >= 0),
    currency          varchar(3)  NOT NULL DEFAULT 'EUR',
    status            varchar(12) NOT NULL DEFAULT 'ISSUED',
    method            varchar(10) NOT NULL,
    reference         varchar(40) NOT NULL,
    customer_name     varchar(200) NOT NULL,
    customer_address  varchar(500) NOT NULL,
    customer_country  varchar(2)  NOT NULL DEFAULT 'DE',
    customer_vat_id   varchar(30),
    customer_email    varchar(320),
    crypto_asset      varchar(20),
    crypto_network    varchar(40),
    crypto_address    varchar(200),
    crypto_amount     numeric(24, 8),
    issued_at         timestamptz NOT NULL DEFAULT now(),
    due_at            timestamptz NOT NULL,
    paid_at           timestamptz,
    confirmed_by      uuid,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT billing_invoices_status_chk CHECK (status IN ('ISSUED', 'PAID', 'CANCELLED')),
    CONSTRAINT billing_invoices_method_chk CHECK (method IN ('INVOICE', 'CRYPTO')),
    CONSTRAINT billing_invoices_number_key UNIQUE (invoice_number)
);

CREATE INDEX billing_invoices_tenant_idx ON billing_invoices (tenant_id, issued_at DESC);

ALTER TABLE billing_invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing_invoices FORCE ROW LEVEL SECURITY;
CREATE POLICY billing_invoices_tenant_isolation ON billing_invoices
    USING (current_tenant_id() IS NULL OR tenant_id = current_tenant_id())
    WITH CHECK (current_tenant_id() IS NULL OR tenant_id = current_tenant_id());

CREATE TABLE platform_settings (
    setting_key  varchar(80) PRIMARY KEY,
    setting_value jsonb      NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   uuid
);

INSERT INTO platform_settings (setting_key, setting_value) VALUES
    ('billing.trial_days',     '60'::jsonb),
    ('billing.retention_days', '100'::jsonb),
    ('billing.vat_percent',    '0'::jsonb),
    ('billing.payee',          '{"name":"","address":"","iban":"","bic":"","taxId":"","vatId":"","note":""}'::jsonb),
    ('billing.crypto',         '{"asset":"","network":"","address":"","eurPerUnit":""}'::jsonb);

-- Everybody who is already on the platform gets the free trial, counted from today.
INSERT INTO subscriptions (tenant_id, plan_code, status, trial_ends_at, access_until)
SELECT t.id,
       CASE t.account_type
           WHEN 'PRIVATE_PERSON'   THEN 'PRIVATE'
           WHEN 'FAMILY_HOUSEHOLD' THEN 'FAMILY'
           ELSE 'COMPANY'
       END,
       'TRIAL', now() + interval '60 days', now() + interval '60 days'
FROM tenants t
WHERE t.deleted_at IS NULL;

-- -------------------------------------------------------------------------------------
-- Deleting a tenant (the customer chose "delete", or the retention period ran out) has to be able to remove the
-- tenant's audit trail and access logs as well: they carry names and e-mail addresses. The tables stay
-- append-only for everything else. The one exception is a DELETE inside a transaction that has declared, with
-- the transaction-local setting app.tenant_purge, that exactly this tenant is being purged.
-- -------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION forbid_append_only_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.tenant_id IS NOT NULL
       AND current_setting('app.tenant_purge', true) = OLD.tenant_id::text THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Table % is append-only; UPDATE and DELETE are not permitted', TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;
