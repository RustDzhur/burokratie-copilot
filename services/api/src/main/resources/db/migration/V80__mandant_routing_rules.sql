-- =============================================================================
--  V80 - Mandanten-Zuordnung: was die Plattform ueber Signale gelernt hat
--        (Kanzlei edition, §5.2 and §8.3)
-- =============================================================================
--  §5.2 asks for routing with confidence and for learning from corrections.
--  This migration adds the two pieces the code did not have:
--
--    1. mandant_routing_rules - the remembered signal -> client mappings.
--       A rule is written when a person assigns a voucher by hand (quelle =
--       'MANUELL') or when the platform assigns one with enough confidence
--       (quelle = 'AUTO'). The unique key is the signal itself, so a signal
--       can never point at two clients of the same account: the second
--       assignment replaces the first instead of creating a rival rule.
--
--    2. documents.mandant_confidence / documents.zuordnungs_status - how the
--       client field was filled. Without them the screen cannot tell "the
--       platform was sure" from "a person decided", and §8.3 asks for exactly
--       that distinction. The columns are nullable because documents that
--       existed before this migration have no such history.
--
--  Nothing here rewrites an assignment. A document that already has a client
--  keeps it; the routing service refuses to change it (§5.2).
-- =============================================================================

CREATE TABLE mandant_routing_rules (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    signal_typ      varchar(30) NOT NULL,
    -- The signal as the matcher compares it: lower case e-mail, IBAN without
    -- spaces, tax ids without punctuation, company names folded the German way.
    muster          text NOT NULL,
    mandant_id      uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    -- 'AUTO' = the platform derived the rule from its own assignment;
    -- 'MANUELL' = a person assigned the voucher and thereby taught the rule.
    quelle          varchar(10) NOT NULL DEFAULT 'MANUELL',
    -- How often the rule was used and when it was last used, for the settings
    -- screen: a rule that never fires is a rule worth reviewing.
    treffer         integer NOT NULL DEFAULT 0,
    letzte_nutzung  timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT mandant_routing_rules_signal_typ_chk CHECK (signal_typ IN (
        'ABSENDER_EMAIL', 'IBAN', 'USTID', 'STEUERNUMMER', 'FIRMENNAME', 'SCHLUESSELWORT'
    )),
    CONSTRAINT mandant_routing_rules_quelle_chk CHECK (quelle IN ('AUTO', 'MANUELL')),
    CONSTRAINT mandant_routing_rules_muster_chk CHECK (length(btrim(muster)) > 0),
    CONSTRAINT mandant_routing_rules_treffer_chk CHECK (treffer >= 0)
);

COMMENT ON TABLE mandant_routing_rules IS
    'Remembered signal -> client mappings for automatic voucher routing (Kanzlei edition, §5.2).';
COMMENT ON COLUMN mandant_routing_rules.muster IS
    'Normalised signal value. A person sees the readable form on the settings screen.';
COMMENT ON COLUMN mandant_routing_rules.quelle IS
    'MANUELL: learned from a person''s assignment. AUTO: derived from an automatic assignment.';
COMMENT ON COLUMN mandant_routing_rules.treffer IS
    'Number of automatic assignments that used this rule. A manual assignment does not increase it.';

-- One signal, one client. The index also serves the lookup by signal during routing and the
-- tenant filter of the policy.
CREATE UNIQUE INDEX mandant_routing_rules_signal_uq
    ON mandant_routing_rules (tenant_id, signal_typ, muster);
CREATE INDEX mandant_routing_rules_mandant_idx
    ON mandant_routing_rules (tenant_id, mandant_id);

CREATE TRIGGER mandant_routing_rules_set_updated_at BEFORE UPDATE ON mandant_routing_rules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Row level security exactly as every other tenant table: the policy is the second guard behind the
-- tenant_id in each query. The service takes the account from the session, never from the request.
ALTER TABLE mandant_routing_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE mandant_routing_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY mandant_routing_rules_tenant_isolation ON mandant_routing_rules
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- -----------------------------------------------------------------------------
--  How the client of a voucher was determined (§8.3).
-- -----------------------------------------------------------------------------
ALTER TABLE documents ADD COLUMN mandant_confidence numeric(5, 4);
ALTER TABLE documents ADD COLUMN zuordnungs_status varchar(12);

ALTER TABLE documents ADD CONSTRAINT documents_mandant_confidence_chk CHECK (
    mandant_confidence IS NULL OR (mandant_confidence >= 0 AND mandant_confidence <= 1)
);
ALTER TABLE documents ADD CONSTRAINT documents_zuordnungs_status_chk CHECK (
    zuordnungs_status IS NULL
    OR zuordnungs_status IN ('AUTO', 'UNKLAR', 'MANUELL', 'BESTAETIGT')
);

COMMENT ON COLUMN documents.mandant_confidence IS
    'Confidence of the automatic client assignment; NULL for a manual assignment or none at all.';
COMMENT ON COLUMN documents.zuordnungs_status IS
    'AUTO: assigned by the platform with confidence. UNKLAR: signals did not decide. MANUELL: a person chose.';

-- The Posteingang asks for "Ohne Mandant" and for unclear vouchers; both are small subsets of a
-- growing table, so a partial index keeps that read cheap without indexing every voucher twice.
CREATE INDEX documents_zuordnung_open_idx ON documents (tenant_id, zuordnungs_status)
    WHERE mandant_id IS NULL AND deleted_at IS NULL;
