-- =====================================================================================
-- V47 - identifiers of a contact (task BM-01, decision D-02)
--
-- A contact is recognised by an identifier (VAT id, tax number, IBAN, e-mail, phone, domain), never by its name: two
-- different companies can share a name, and a wrong link attaches correspondence to the wrong party. The value is stored
-- as written and normalised; the same normalised identifier cannot belong to two contacts of one tenant.
-- =====================================================================================
CREATE TABLE contact_identifiers (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    contact_id       uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    kind             varchar(20) NOT NULL,
    value            varchar(300) NOT NULL,
    normalized_value varchar(300) NOT NULL,
    created_by       uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT contact_identifiers_kind_chk CHECK (kind IN
        ('VAT_ID', 'TAX_NUMBER', 'IBAN', 'EMAIL', 'DOMAIN', 'PHONE', 'EXTERNAL')),
    CONSTRAINT contact_identifiers_unique UNIQUE (tenant_id, kind, normalized_value)
);

CREATE INDEX contact_identifiers_contact_idx ON contact_identifiers (contact_id);

-- What the contacts already carry becomes identifiers (the first contact to claim a value keeps it). The owner of the
-- table reads every tenant's contacts for this one statement.
ALTER TABLE contacts NO FORCE ROW LEVEL SECURITY;

INSERT INTO contact_identifiers (tenant_id, contact_id, kind, value, normalized_value)
SELECT DISTINCT ON (tenant_id, kind, normalized_value) tenant_id, contact_id, kind, value, normalized_value
FROM (
    SELECT tenant_id, id AS contact_id, 'EMAIL' AS kind, email::text AS value, lower(trim(email::text)) AS normalized_value,
           created_at
    FROM contacts WHERE email IS NOT NULL AND trim(email::text) <> '' AND deleted_at IS NULL
    UNION ALL
    SELECT tenant_id, id, 'VAT_ID', vat_id, upper(regexp_replace(vat_id, '[\s.\-]', '', 'g')), created_at
    FROM contacts WHERE vat_id IS NOT NULL AND trim(vat_id) <> '' AND deleted_at IS NULL
    UNION ALL
    SELECT tenant_id, id, 'TAX_NUMBER', tax_number, upper(regexp_replace(tax_number, '[^0-9A-Za-z]', '', 'g')), created_at
    FROM contacts WHERE tax_number IS NOT NULL AND trim(tax_number) <> '' AND deleted_at IS NULL
) source
ORDER BY tenant_id, kind, normalized_value, created_at;

ALTER TABLE contacts FORCE ROW LEVEL SECURITY;

-- Row level security is switched on after the backfill: the migration writes rows of every tenant.
ALTER TABLE contact_identifiers ENABLE ROW LEVEL SECURITY;
ALTER TABLE contact_identifiers FORCE ROW LEVEL SECURITY;
CREATE POLICY contact_identifiers_tenant_isolation ON contact_identifiers
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
