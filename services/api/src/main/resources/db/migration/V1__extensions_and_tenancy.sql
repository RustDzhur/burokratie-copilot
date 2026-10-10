-- =====================================================================================
-- V1 — Extensions, tenancy root, organizations and workspaces
--
-- Isolation model (spec §3.2):
--   tenants            — hard isolation boundary; every business row carries tenant_id
--   organizations      — legal entities inside a tenant (a family has one, a company
--                        may have several legal entities)
--   workspaces         — a working area with a mode (PRIVATE/FAMILY/BUSINESS/...)
--
-- All timestamps are timestamptz and stored in UTC. Deadlines that depend on a place or a
-- time zone carry an explicit zone column instead of relying on the server default (§19).
-- =====================================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- -------------------------------------------------------------------------------------
-- Shared helper: keeps updated_at honest without application cooperation.
-- -------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- -------------------------------------------------------------------------------------
-- tenants
-- -------------------------------------------------------------------------------------
CREATE TABLE tenants (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug                varchar(80)  NOT NULL,
    name                varchar(200) NOT NULL,
    account_type        varchar(40)  NOT NULL,
    status              varchar(30)  NOT NULL DEFAULT 'ACTIVE',
    data_region         varchar(40)  NOT NULL DEFAULT 'eu-central',
    default_locale      varchar(10)  NOT NULL DEFAULT 'de',
    default_time_zone   varchar(60)  NOT NULL DEFAULT 'Europe/Berlin',
    -- Corporate customers may require their own environment and encryption keys (§3.2).
    dedicated_environment boolean    NOT NULL DEFAULT false,
    encryption_key_ref  varchar(200),
    retention_overrides jsonb        NOT NULL DEFAULT '{}'::jsonb,
    settings            jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    deleted_at          timestamptz,
    CONSTRAINT tenants_slug_key UNIQUE (slug),
    CONSTRAINT tenants_account_type_chk CHECK (account_type IN (
        'PRIVATE_PERSON', 'FAMILY_HOUSEHOLD', 'FREELANCER', 'COMPANY',
        'COMPANY_TEAM', 'EXTERNAL_REPRESENTATIVE', 'HIGH_SECURITY_ORGANIZATION'
    )),
    CONSTRAINT tenants_status_chk CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT tenants_slug_format_chk CHECK (slug ~ '^[a-z0-9]([a-z0-9-]{1,78}[a-z0-9])$')
);

CREATE INDEX tenants_status_idx ON tenants (status) WHERE deleted_at IS NULL;

CREATE TRIGGER tenants_set_updated_at BEFORE UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- organizations
-- -------------------------------------------------------------------------------------
CREATE TABLE organizations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name            varchar(250) NOT NULL,
    legal_form      varchar(80),
    register_number varchar(80),          -- Handelsregister / Vereinsregister number
    vat_id          varchar(40),          -- USt-IdNr.
    tax_number      varchar(40),          -- Steuernummer
    email           citext,
    phone           varchar(60),
    website         varchar(300),
    street          varchar(200),
    house_number    varchar(30),
    address_extra   varchar(200),
    postal_code     varchar(20),
    city            varchar(120),
    country_code    char(2) NOT NULL DEFAULT 'DE',
    is_own_entity   boolean NOT NULL DEFAULT true,
    notes           text,
    metadata        jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,
    CONSTRAINT organizations_country_chk CHECK (country_code ~ '^[A-Z]{2}$')
);

CREATE INDEX organizations_tenant_idx ON organizations (tenant_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX organizations_tenant_vat_key ON organizations (tenant_id, vat_id)
    WHERE vat_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX organizations_name_trgm_idx ON organizations USING gin (name gin_trgm_ops);

CREATE TRIGGER organizations_set_updated_at BEFORE UPDATE ON organizations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- workspaces
-- -------------------------------------------------------------------------------------
CREATE TABLE workspaces (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    organization_id uuid REFERENCES organizations (id) ON DELETE SET NULL,
    name            varchar(200) NOT NULL,
    mode            varchar(30) NOT NULL,
    description     text,
    is_default      boolean NOT NULL DEFAULT false,
    settings        jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,
    CONSTRAINT workspaces_mode_chk CHECK (mode IN (
        'PRIVATE', 'FAMILY', 'BUSINESS', 'ADVISOR', 'PUBLIC_SECTOR'
    ))
);

CREATE INDEX workspaces_tenant_idx ON workspaces (tenant_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX workspaces_tenant_default_key ON workspaces (tenant_id)
    WHERE is_default AND deleted_at IS NULL;

CREATE TRIGGER workspaces_set_updated_at BEFORE UPDATE ON workspaces
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
