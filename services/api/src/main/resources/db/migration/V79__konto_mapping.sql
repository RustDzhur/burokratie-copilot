-- =============================================================================
--  V79 - Konto-Mapping fuer den DATEV-Export  (Kanzlei edition, §5.6, §8.4)
-- =============================================================================
--  A voucher can only become a booking row when somebody said which accounts it
--  belongs to. Until now there was no place for that knowledge. This table is
--  the office's answer: supplier/category (and optionally a specific client)
--  map to Sollkonto, Habenkonto and Steuerschluessel.
--
--  The columns are deliberately nullable: a row may be specific to a client, to
--  a supplier, to a category, or to a combination. The service picks the most
--  specific matching row:
--      client + supplier + category
--   >  client + supplier
--   >  client + category
--   >  client only
--   >  supplier + category
--   >  supplier only
--   >  category only
--   >  the account-wide default (all three NULL)
--  A row without both accounts is not a usable mapping and is ignored.
--
--  The exact DATEV account lengths and tax keys are part of Appendix 1 and are
--  NOT agreed yet (§12.1). The columns are therefore wide enough for the
--  candidate profiles, and the mapping table does not pretend to validate them.
-- =============================================================================

CREATE TABLE konto_mapping (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    mandant_id      uuid REFERENCES contacts (id) ON DELETE CASCADE,
    supplier        varchar(400),
    category        varchar(40),
    sollkonto       varchar(20) NOT NULL,
    habenkonto      varchar(20) NOT NULL,
    steuerschluessel varchar(20),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT konto_mapping_accounts_chk CHECK (
        length(btrim(sollkonto)) > 0 AND length(btrim(habenkonto)) > 0)
);

COMMENT ON TABLE konto_mapping IS
    'Supplier/category/client -> Sollkonto, Habenkonto, Steuerschluessel for the DATEV export (provisional format).';
COMMENT ON COLUMN konto_mapping.mandant_id IS
    'The client this mapping belongs to; NULL = valid for every client of the account.';
COMMENT ON COLUMN konto_mapping.supplier IS
    'Supplier name as written on the voucher (compared case-insensitively); NULL = any supplier.';
COMMENT ON COLUMN konto_mapping.category IS
    'Document type category (FINANCE, GOVERNMENT, ...); NULL = any category.';
COMMENT ON COLUMN konto_mapping.steuerschluessel IS
    'DATEV tax key. NULL/empty is allowed: not every booking carries one, and the exact key set is not agreed yet.';

-- One mapping per combination. NULLS NOT DISTINCT makes "no client, no supplier,
-- no category" a single account-wide default instead of two rows that PostgreSQL
-- would otherwise consider different.
CREATE UNIQUE INDEX konto_mapping_combination_uq
    ON konto_mapping (tenant_id, mandant_id, supplier, category) NULLS NOT DISTINCT;

-- The service loads all mappings of one account and matches them in memory; this
-- index keeps that read cheap and also serves the tenant check of the policy.
CREATE INDEX konto_mapping_tenant_idx
    ON konto_mapping (tenant_id);

ALTER TABLE konto_mapping ENABLE ROW LEVEL SECURITY;
ALTER TABLE konto_mapping FORCE ROW LEVEL SECURITY;
CREATE POLICY konto_mapping_tenant_isolation ON konto_mapping
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
