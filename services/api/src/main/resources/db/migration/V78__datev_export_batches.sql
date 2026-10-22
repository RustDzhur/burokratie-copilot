-- =============================================================================
--  V78 - DATEV-Exportpakete / DATEV export batches  (Kanzlei edition, §5.6)
-- =============================================================================
--  Every export is recorded as a package, not just as a file on somebody's
--  desktop. The office has to be able to answer three questions later: which
--  vouchers left the house, when, and with which total. The file itself is
--  stored byte-for-byte, because a later download must be the file that was
--  handed to DATEV and not a fresh rendering of data that may have changed.
--
--  The CSV profile is explicitly PROVISIONAL: the exact field order, encoding
--  and DATEV metadata are fixed with the first Kanzlei in Appendix 1 and are
--  NOT agreed yet. The table therefore stores what was produced, not a promise
--  that DATEV will accept it.
--
--  export_batches and export_batch_items are tenant tables with the same Row
--  Level Security as every other tenant table: without a tenant in context
--  they are empty. There is deliberately no system-context bypass here - a
--  maintenance job has no business reading export contents.
-- =============================================================================

CREATE TABLE export_batches (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    created_by      uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    period_from     date NOT NULL,
    period_to       date NOT NULL,
    -- Null means "all clients". A package with a client filter may only contain
    -- vouchers of that client; the service checks this, and the FK keeps the
    -- identifier pointing at a real contact (RLS does not cover FK checks).
    mandant_filter  uuid REFERENCES contacts (id) ON DELETE SET NULL,
    beleg_count     integer NOT NULL DEFAULT 0,
    total_amount    numeric(14, 2) NOT NULL DEFAULT 0,
    status          varchar(20) NOT NULL,
    filename        varchar(255) NOT NULL,
    -- The exact bytes that were (or can be) downloaded. NULL only for a FAILED
    -- attempt, where no file was produced.
    content         bytea,
    CONSTRAINT export_batches_status_chk CHECK (status IN ('CREATED', 'FAILED')),
    CONSTRAINT export_batches_period_chk CHECK (period_from <= period_to),
    CONSTRAINT export_batches_count_chk CHECK (beleg_count >= 0),
    CONSTRAINT export_batches_content_chk CHECK (status = 'FAILED' OR content IS NOT NULL)
);

COMMENT ON TABLE export_batches IS
    'One DATEV export attempt. The CSV profile is provisional and not agreed with a Kanzlei yet (§5.6, §12.1).';
COMMENT ON COLUMN export_batches.mandant_filter IS
    'The client this package was limited to; NULL = all clients of the account.';
COMMENT ON COLUMN export_batches.content IS
    'The exact CSV bytes (UTF-8 with BOM). Stored so a later download is the file that left the house.';
COMMENT ON COLUMN export_batches.status IS
    'CREATED = a file was produced; FAILED = the generation stopped, no file exists.';

-- The history screen reads newest first, always within one account.
CREATE INDEX export_batches_tenant_created_idx
    ON export_batches (tenant_id, created_at DESC);

CREATE TABLE export_batch_items (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Denormalised tenant on purpose: Row Level Security needs a tenant_id column
    -- of its own (the specification's isolation rule), and a join-only table would
    -- have to be protected through the parent with a different policy than the rest.
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    batch_id        uuid NOT NULL REFERENCES export_batches (id) ON DELETE CASCADE,
    document_id     uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    status          varchar(20) NOT NULL,
    -- Why a voucher is not in the file ("Ohne Mandant", "Kein Konto-Mapping"),
    -- or which error stopped it. The same sentence the screen shows; kept here so
    -- the package explains itself without re-reading today's data.
    error_message   text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT export_batch_items_status_chk CHECK (status IN ('OK', 'SKIPPED', 'FAILED')),
    CONSTRAINT export_batch_items_document_uq UNIQUE (batch_id, document_id)
);

COMMENT ON TABLE export_batch_items IS
    'One row per voucher considered by a batch: exported (OK), deliberately left out (SKIPPED), or failed.';
COMMENT ON COLUMN export_batch_items.error_message IS
    'Reason for SKIPPED/FAILED, in the office language; NULL for OK.';

CREATE INDEX export_batch_items_batch_idx
    ON export_batch_items (batch_id);
CREATE INDEX export_batch_items_tenant_created_idx
    ON export_batch_items (tenant_id, created_at DESC);

ALTER TABLE export_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE export_batches FORCE ROW LEVEL SECURITY;
CREATE POLICY export_batches_tenant_isolation ON export_batches
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE export_batch_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE export_batch_items FORCE ROW LEVEL SECURITY;
CREATE POLICY export_batch_items_tenant_isolation ON export_batch_items
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
