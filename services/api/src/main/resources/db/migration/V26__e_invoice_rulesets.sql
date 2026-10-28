-- =====================================================================================
-- V26 — Versioned rule sets for e-invoice validation
--
-- e_invoice_records.ruleset_version has been empty since the table was created, because nothing
-- published rule sets. This adds the register, and it is deliberately a register of WHICH rules are
-- in force rather than an engine that knows what they mean.
--
-- Why that distinction is the whole point: the rules of EN 16931 are a legal and technical
-- specification this platform did not write and cannot verify. What a platform can honestly do is
-- record which set of rules was in force at a moment, apply the checks it implements, and say which
-- version it applied. Inventing the rules themselves - or, worse, guessing at them - would produce
-- invoices marked valid by a checklist nobody qualified has ever read.
--
-- The register therefore starts EMPTY of business rules, exactly as the deadline register does (C-004).
-- Publishing a rule set is an act of the operator, who supplies codes, severities and descriptions.
-- A validation that ran without a published set records that it ran without one, rather than claiming
-- a conformance it did not check.
-- =====================================================================================

CREATE TABLE e_invoice_rulesets (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    version        integer     NOT NULL,
    specification  varchar(32) NOT NULL DEFAULT 'EN16931',
    status         varchar(16) NOT NULL DEFAULT 'DRAFT',
    description    text,
    source         text,
    published_by   uuid        REFERENCES users(id) ON DELETE SET NULL,
    published_at   timestamptz,
    retired_at     timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT e_invoice_rulesets_version_uq UNIQUE (specification, version),
    CONSTRAINT e_invoice_rulesets_status_chk CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    -- A published set has to say when it was published and by whom; otherwise nobody can tell which
    -- rules were in force when an invoice was checked.
    CONSTRAINT e_invoice_rulesets_published_chk
        CHECK (status <> 'PUBLISHED' OR (published_at IS NOT NULL AND published_by IS NOT NULL))
);

CREATE TABLE e_invoice_rules (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    ruleset_id   uuid        NOT NULL REFERENCES e_invoice_rulesets(id) ON DELETE CASCADE,
    code         varchar(64) NOT NULL,
    severity     varchar(16) NOT NULL DEFAULT 'ERROR',
    description  text        NOT NULL,
    reference    text,
    sort_order   integer     NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT e_invoice_rules_code_uq UNIQUE (ruleset_id, code),
    CONSTRAINT e_invoice_rules_severity_chk CHECK (severity IN ('ERROR', 'WARNING', 'INFO'))
);

CREATE INDEX e_invoice_rules_ruleset_idx ON e_invoice_rules (ruleset_id, sort_order);

-- The register is platform-wide, like the document types: a version of the specification is not a
-- tenant's to define. There is no tenant column, and therefore no policy to write here.

COMMENT ON TABLE e_invoice_rulesets IS
    'A version of a rule set. Published once and never edited, so that a validation can name the rules
     that were in force. Starts empty of business rules on purpose: the specification is not this
     platform''s to invent.';
COMMENT ON TABLE e_invoice_rules IS
    'Which rules a version contains and how severe each one is. The check itself is implemented in the
     application; this records what is in force, not what it means.';
