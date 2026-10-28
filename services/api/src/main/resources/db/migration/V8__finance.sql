-- =====================================================================================
-- V8 — Finance: invoices, payments, banks, e-invoicing (§14, §19)
--
-- Invariants enforced here:
--  * Tax rates and payment terms are never invented by the software: they are stored
--    values that a responsible person (or the customer's advisor) maintains (§14.2).
--  * Preparing a payment and confirming it are separate states (§14.4).
--  * Banking credentials/TAN are never stored in the database — only a reference to an
--    encrypted secret is kept (§14.4).
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- suppliers / customers
-- -------------------------------------------------------------------------------------
CREATE TABLE suppliers (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    contact_id            uuid REFERENCES contacts (id) ON DELETE SET NULL,
    name                  varchar(300) NOT NULL,
    supplier_number       varchar(60),
    vat_id                varchar(40),
    tax_number            varchar(40),
    email                 citext,
    phone                 varchar(60),
    default_payment_terms integer,
    default_currency      char(3) NOT NULL DEFAULT 'EUR',
    is_active             boolean NOT NULL DEFAULT true,
    notes                 text,
    metadata              jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    deleted_at            timestamptz,
    CONSTRAINT suppliers_payment_terms_chk CHECK (
        default_payment_terms IS NULL OR default_payment_terms >= 0
    )
);

CREATE INDEX suppliers_tenant_idx ON suppliers (tenant_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX suppliers_number_key ON suppliers (tenant_id, supplier_number)
    WHERE supplier_number IS NOT NULL AND deleted_at IS NULL;

CREATE TRIGGER suppliers_set_updated_at BEFORE UPDATE ON suppliers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Bank details are versioned: a silent IBAN change on an invoice must be detectable (§14.1).
CREATE TABLE supplier_bank_accounts (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id   uuid NOT NULL REFERENCES suppliers (id) ON DELETE CASCADE,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    iban          varchar(34) NOT NULL,
    bic           varchar(11),
    bank_name     varchar(200),
    account_holder varchar(300),
    valid_from    date NOT NULL DEFAULT current_date,
    valid_to      date,
    is_primary    boolean NOT NULL DEFAULT false,
    -- Changing bank details is a fraud vector: changes must be explicitly confirmed.
    change_confirmed_by uuid REFERENCES users (id) ON DELETE SET NULL,
    change_confirmed_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT supplier_bank_accounts_iban_chk CHECK (iban ~ '^[A-Z]{2}[0-9A-Z]{13,32}$')
);

CREATE UNIQUE INDEX supplier_bank_accounts_primary_key ON supplier_bank_accounts (supplier_id)
    WHERE is_primary AND valid_to IS NULL;
CREATE INDEX supplier_bank_accounts_iban_idx ON supplier_bank_accounts (tenant_id, iban);

CREATE TABLE customers (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    contact_id            uuid REFERENCES contacts (id) ON DELETE SET NULL,
    name                  varchar(300) NOT NULL,
    customer_number       varchar(60) NOT NULL,
    vat_id                varchar(40),
    email                 citext,
    phone                 varchar(60),
    default_payment_terms integer,
    default_currency      char(3) NOT NULL DEFAULT 'EUR',
    billing_address       jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- Individual numbering sequence per customer group is configurable data (§14.2).
    invoice_number_sequence_id uuid,
    is_active             boolean NOT NULL DEFAULT true,
    notes                 text,
    metadata              jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    deleted_at            timestamptz,
    CONSTRAINT customers_number_key UNIQUE (tenant_id, customer_number)
);

CREATE INDEX customers_tenant_idx ON customers (tenant_id) WHERE deleted_at IS NULL;

CREATE TRIGGER customers_set_updated_at BEFORE UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE financial_accounts (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    organization_id uuid REFERENCES organizations (id) ON DELETE SET NULL,
    name           varchar(200) NOT NULL,
    account_type   varchar(30) NOT NULL,
    iban           varchar(34),
    bic            varchar(11),
    bank_name      varchar(200),
    account_holder varchar(300),
    currency       char(3) NOT NULL DEFAULT 'EUR',
    opening_balance numeric(14, 2) NOT NULL DEFAULT 0,
    -- Never store banking logins/TAN. Only a pointer to an encrypted secret store entry.
    credential_ref varchar(200),
    integration_id uuid,
    is_active      boolean NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    deleted_at     timestamptz,
    CONSTRAINT financial_accounts_type_chk CHECK (account_type IN (
        'BANK', 'CASH', 'CREDIT_CARD', 'PAYPAL', 'OTHER'
    ))
);

CREATE INDEX financial_accounts_tenant_idx ON financial_accounts (tenant_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX financial_accounts_iban_key ON financial_accounts (tenant_id, iban)
    WHERE iban IS NOT NULL AND deleted_at IS NULL;

CREATE TRIGGER financial_accounts_set_updated_at BEFORE UPDATE ON financial_accounts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- purchase_orders
-- -------------------------------------------------------------------------------------
CREATE TABLE purchase_orders (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    supplier_id   uuid REFERENCES suppliers (id) ON DELETE SET NULL,
    contract_id   uuid REFERENCES contracts (id) ON DELETE SET NULL,
    case_id       uuid REFERENCES cases (id) ON DELETE SET NULL,
    document_id   uuid REFERENCES documents (id) ON DELETE SET NULL,
    po_number     varchar(60) NOT NULL,
    order_date    date,
    expected_delivery_date date,
    status        varchar(30) NOT NULL DEFAULT 'OPEN',
    currency      char(3) NOT NULL DEFAULT 'EUR',
    net_amount    numeric(14, 2),
    gross_amount  numeric(14, 2),
    description   text,
    created_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    deleted_at    timestamptz,
    CONSTRAINT purchase_orders_number_key UNIQUE (tenant_id, po_number),
    CONSTRAINT purchase_orders_status_chk CHECK (status IN (
        'DRAFT', 'OPEN', 'PARTIALLY_DELIVERED', 'DELIVERED', 'INVOICED', 'CANCELLED', 'CLOSED'
    ))
);

CREATE INDEX purchase_orders_supplier_idx ON purchase_orders (supplier_id);

CREATE TRIGGER purchase_orders_set_updated_at BEFORE UPDATE ON purchase_orders
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- Invoice number sequences (configurable, gap-free numbering is a configuration, not a rule)
-- -------------------------------------------------------------------------------------
CREATE TABLE invoice_number_sequences (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    code           varchar(40) NOT NULL,
    name           varchar(200) NOT NULL,
    pattern        varchar(120) NOT NULL DEFAULT '{YYYY}-{SEQ:5}',
    reset_policy   varchar(20) NOT NULL DEFAULT 'YEARLY',
    next_value     bigint NOT NULL DEFAULT 1,
    year           integer,
    is_active      boolean NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT invoice_number_sequences_key UNIQUE (tenant_id, code),
    CONSTRAINT invoice_number_sequences_reset_chk CHECK (reset_policy IN ('NEVER', 'YEARLY', 'MONTHLY'))
);

CREATE TRIGGER invoice_number_sequences_set_updated_at BEFORE UPDATE ON invoice_number_sequences
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE customers
    ADD CONSTRAINT customers_invoice_sequence_fk
    FOREIGN KEY (invoice_number_sequence_id) REFERENCES invoice_number_sequences (id) ON DELETE SET NULL;

-- -------------------------------------------------------------------------------------
-- invoices
-- -------------------------------------------------------------------------------------
CREATE TABLE invoices (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    workspace_id       uuid REFERENCES workspaces (id) ON DELETE SET NULL,
    case_id            uuid REFERENCES cases (id) ON DELETE SET NULL,
    organization_id    uuid REFERENCES organizations (id) ON DELETE SET NULL,
    direction          varchar(20) NOT NULL,
    invoice_type       varchar(30) NOT NULL DEFAULT 'INVOICE',
    invoice_number     varchar(120),
    supplier_id        uuid REFERENCES suppliers (id) ON DELETE SET NULL,
    customer_id        uuid REFERENCES customers (id) ON DELETE SET NULL,
    contact_id         uuid REFERENCES contacts (id) ON DELETE SET NULL,
    contract_id        uuid REFERENCES contracts (id) ON DELETE SET NULL,
    purchase_order_id  uuid REFERENCES purchase_orders (id) ON DELETE SET NULL,
    document_id        uuid REFERENCES documents (id) ON DELETE SET NULL,

    issue_date         date,
    delivery_date      date,
    due_date           date,
    payment_terms_days integer,
    currency           char(3) NOT NULL DEFAULT 'EUR',
    net_amount         numeric(14, 2),
    tax_amount         numeric(14, 2),
    gross_amount       numeric(14, 2),
    paid_amount        numeric(14, 2) NOT NULL DEFAULT 0,
    -- Tax rates are data maintained by the customer/advisor, never hard-coded (§14.2).
    default_tax_rate_id uuid,
    tax_note           text,

    status             varchar(30) NOT NULL DEFAULT 'RECEIVED',
    payment_status     varchar(20) NOT NULL DEFAULT 'UNPAID',
    -- Formal validation results (arithmetic, required fields, duplicates) with details.
    validation_state   jsonb NOT NULL DEFAULT '{}'::jsonb,
    validation_flags   jsonb NOT NULL DEFAULT '[]'::jsonb,
    duplicate_of_invoice_id uuid REFERENCES invoices (id) ON DELETE SET NULL,

    iban               varchar(34),
    bic                varchar(11),
    payment_reference  varchar(200),
    -- Bank detail change detection compared to the known supplier accounts (§14.1).
    bank_details_changed boolean NOT NULL DEFAULT false,

    approved_by        uuid REFERENCES users (id) ON DELETE SET NULL,
    approved_at        timestamptz,
    rejection_reason   text,

    e_invoice_id       uuid,
    source             varchar(30) NOT NULL DEFAULT 'DOCUMENT',
    notes              text,
    metadata           jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by         uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    deleted_at         timestamptz,

    CONSTRAINT invoices_direction_chk CHECK (direction IN ('INCOMING', 'OUTGOING')),
    CONSTRAINT invoices_type_chk CHECK (invoice_type IN (
        'INVOICE', 'CREDIT_NOTE', 'CORRECTION', 'SELF_BILLING', 'RECURRING'
    )),
    CONSTRAINT invoices_status_chk CHECK (status IN (
        'DRAFT', 'RECEIVED', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'SCHEDULED',
        'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELLED', 'CREDITED'
    )),
    CONSTRAINT invoices_payment_status_chk CHECK (payment_status IN (
        'UNPAID', 'PARTIALLY_PAID', 'PAID', 'OVERPAID', 'NOT_APPLICABLE'
    )),
    CONSTRAINT invoices_source_chk CHECK (source IN ('DOCUMENT', 'MANUAL', 'IMPORT', 'E_INVOICE', 'RECURRING')),
    CONSTRAINT invoices_amounts_chk CHECK (
        (net_amount IS NULL OR net_amount >= 0)
        AND (tax_amount IS NULL OR tax_amount >= 0)
        AND (gross_amount IS NULL OR gross_amount >= 0)
        AND paid_amount >= 0
    ),
    CONSTRAINT invoices_paid_chk CHECK (paid_amount = 0 OR gross_amount IS NULL OR paid_amount <= gross_amount * 1.5)
);

CREATE INDEX invoices_tenant_status_idx ON invoices (tenant_id, direction, status) WHERE deleted_at IS NULL;
CREATE INDEX invoices_due_idx ON invoices (tenant_id, due_date)
    WHERE deleted_at IS NULL AND payment_status IN ('UNPAID', 'PARTIALLY_PAID');
CREATE INDEX invoices_supplier_idx ON invoices (supplier_id);
CREATE INDEX invoices_customer_idx ON invoices (customer_id);
CREATE INDEX invoices_document_idx ON invoices (document_id);
CREATE INDEX invoices_case_idx ON invoices (case_id);
-- Duplicate protection for incoming invoices (§14.1): same supplier + same number.
CREATE UNIQUE INDEX invoices_supplier_number_key
    ON invoices (tenant_id, supplier_id, invoice_number)
    WHERE direction = 'INCOMING' AND supplier_id IS NOT NULL AND invoice_number IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX invoices_outgoing_number_key
    ON invoices (tenant_id, invoice_number)
    WHERE direction = 'OUTGOING' AND invoice_number IS NOT NULL AND deleted_at IS NULL;

CREATE TRIGGER invoices_set_updated_at BEFORE UPDATE ON invoices
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE invoice_lines (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id       uuid NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    tenant_id        uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    line_number      integer NOT NULL,
    description      varchar(1000) NOT NULL,
    quantity         numeric(14, 4),
    unit             varchar(30),
    unit_price       numeric(14, 4),
    discount_percent numeric(5, 2),
    net_amount       numeric(14, 2),
    tax_rate_percent numeric(5, 2),
    tax_amount       numeric(14, 2),
    gross_amount     numeric(14, 2),
    cost_category    varchar(80),
    cost_center      varchar(80),
    article_number   varchar(80),
    metadata         jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT invoice_lines_uq UNIQUE (invoice_id, line_number),
    CONSTRAINT invoice_lines_discount_chk CHECK (
        discount_percent IS NULL OR (discount_percent >= 0 AND discount_percent <= 100)
    )
);

CREATE INDEX invoice_lines_invoice_idx ON invoice_lines (invoice_id, line_number);

CREATE TABLE tax_rates (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid REFERENCES tenants (id) ON DELETE CASCADE,   -- NULL = system default
    code          varchar(40) NOT NULL,
    name          jsonb NOT NULL,
    rate_percent  numeric(5, 2) NOT NULL,
    country_code  char(2) NOT NULL DEFAULT 'DE',
    -- Rates are legal parameters: the customer must confirm them before use (§14.2).
    requires_verification boolean NOT NULL DEFAULT true,
    verified_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    verified_at   timestamptz,
    valid_from    date,
    valid_to      date,
    is_active     boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tax_rates_rate_chk CHECK (rate_percent >= 0 AND rate_percent <= 100)
);

CREATE UNIQUE INDEX tax_rates_code_key
    ON tax_rates (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), code, coalesce(valid_from, '1900-01-01'::date));

CREATE TRIGGER tax_rates_set_updated_at BEFORE UPDATE ON tax_rates
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE invoices
    ADD CONSTRAINT invoices_tax_rate_fk
    FOREIGN KEY (default_tax_rate_id) REFERENCES tax_rates (id) ON DELETE SET NULL;

CREATE TABLE credit_notes (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    invoice_id     uuid REFERENCES invoices (id) ON DELETE SET NULL,
    document_id    uuid REFERENCES documents (id) ON DELETE SET NULL,
    credit_note_number varchar(120),
    issue_date     date,
    amount         numeric(14, 2) NOT NULL,
    currency       char(3) NOT NULL DEFAULT 'EUR',
    reason         text,
    status         varchar(20) NOT NULL DEFAULT 'DRAFT',
    created_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT credit_notes_amount_chk CHECK (amount >= 0),
    CONSTRAINT credit_notes_status_chk CHECK (status IN ('DRAFT', 'ISSUED', 'APPLIED', 'CANCELLED'))
);

CREATE INDEX credit_notes_invoice_idx ON credit_notes (invoice_id);
CREATE UNIQUE INDEX credit_notes_number_key ON credit_notes (tenant_id, credit_note_number)
    WHERE credit_note_number IS NOT NULL;

CREATE TRIGGER credit_notes_set_updated_at BEFORE UPDATE ON credit_notes
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- E-Rechnung records (§14.3) — the original structured XML is preserved untouched.
-- -------------------------------------------------------------------------------------
CREATE TABLE e_invoice_records (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    document_id        uuid REFERENCES documents (id) ON DELETE SET NULL,
    invoice_id         uuid REFERENCES invoices (id) ON DELETE SET NULL,
    format             varchar(30) NOT NULL,
    syntax             varchar(20),
    profile            varchar(60),
    specification_version varchar(40),
    validation_status  varchar(20) NOT NULL DEFAULT 'NOT_VALIDATED',
    validation_results jsonb NOT NULL DEFAULT '[]'::jsonb,
    -- Versioned rule set: legal requirements change and must be updatable (§14.3).
    ruleset_version    varchar(40),
    original_xml_blob_id uuid REFERENCES document_blobs (id) ON DELETE RESTRICT,
    extracted_at       timestamptz,
    validated_at       timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT e_invoice_records_format_chk CHECK (format IN ('XRECHNUNG', 'ZUGFERD', 'OTHER')),
    CONSTRAINT e_invoice_records_status_chk CHECK (validation_status IN (
        'NOT_VALIDATED', 'VALID', 'VALID_WITH_WARNINGS', 'INVALID', 'NOT_SUPPORTED'
    ))
);

CREATE INDEX e_invoice_records_document_idx ON e_invoice_records (document_id);
CREATE INDEX e_invoice_records_invoice_idx ON e_invoice_records (invoice_id);

CREATE TRIGGER e_invoice_records_set_updated_at BEFORE UPDATE ON e_invoice_records
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE invoices
    ADD CONSTRAINT invoices_e_invoice_fk
    FOREIGN KEY (e_invoice_id) REFERENCES e_invoice_records (id) ON DELETE SET NULL;

-- -------------------------------------------------------------------------------------
-- Bank statements and transactions (offline import path — works without a provider, Q-04)
-- -------------------------------------------------------------------------------------
CREATE TABLE bank_statements (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    financial_account_id uuid REFERENCES financial_accounts (id) ON DELETE SET NULL,
    format               varchar(20) NOT NULL,
    period_start         date,
    period_end           date,
    opening_balance      numeric(14, 2),
    closing_balance      numeric(14, 2),
    currency             char(3) NOT NULL DEFAULT 'EUR',
    source_blob_id       uuid REFERENCES document_blobs (id) ON DELETE SET NULL,
    status               varchar(20) NOT NULL DEFAULT 'IMPORTED',
    import_note          text,
    imported_by          uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT bank_statements_format_chk CHECK (format IN ('CAMT053', 'CAMT052', 'MT940', 'CSV', 'API', 'OTHER')),
    CONSTRAINT bank_statements_status_chk CHECK (status IN ('IMPORTED', 'RECONCILED', 'PARTIAL', 'FAILED'))
);

CREATE INDEX bank_statements_tenant_idx ON bank_statements (tenant_id, period_end DESC);

CREATE TRIGGER bank_statements_set_updated_at BEFORE UPDATE ON bank_statements
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE bank_transactions (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    statement_id         uuid REFERENCES bank_statements (id) ON DELETE CASCADE,
    tenant_id            uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    financial_account_id uuid REFERENCES financial_accounts (id) ON DELETE SET NULL,
    booking_date         date NOT NULL,
    value_date           date,
    amount               numeric(14, 2) NOT NULL,
    currency             char(3) NOT NULL DEFAULT 'EUR',
    counterparty_name    varchar(300),
    counterparty_iban    varchar(34),
    purpose              text,
    reference            varchar(200),
    end_to_end_id        varchar(120),
    transaction_type     varchar(60),
    status               varchar(20) NOT NULL DEFAULT 'UNMATCHED',
    external_id          varchar(200),
    metadata             jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT bank_transactions_status_chk CHECK (status IN (
        'UNMATCHED', 'SUGGESTED', 'MATCHED', 'IGNORED', 'INTERNAL'
    ))
);

CREATE INDEX bank_transactions_account_idx ON bank_transactions (tenant_id, financial_account_id, booking_date DESC);
CREATE INDEX bank_transactions_status_idx ON bank_transactions (tenant_id, status)
    WHERE status IN ('UNMATCHED', 'SUGGESTED');
CREATE UNIQUE INDEX bank_transactions_external_key
    ON bank_transactions (tenant_id, financial_account_id, external_id)
    WHERE external_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- payments — preparation and execution are strictly separated (§14.4)
-- -------------------------------------------------------------------------------------
CREATE TABLE payments (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    direction            varchar(20) NOT NULL,
    invoice_id           uuid REFERENCES invoices (id) ON DELETE SET NULL,
    case_id              uuid REFERENCES cases (id) ON DELETE SET NULL,
    financial_account_id uuid REFERENCES financial_accounts (id) ON DELETE SET NULL,
    amount               numeric(14, 2) NOT NULL,
    currency             char(3) NOT NULL DEFAULT 'EUR',
    payment_date         date,
    value_date           date,
    method               varchar(30),
    status               varchar(30) NOT NULL DEFAULT 'PLANNED',
    reference            varchar(200),
    counterparty_name    varchar(300),
    counterparty_iban    varchar(34),
    external_id          varchar(200),
    -- Evidence of execution (bank confirmation, receipt) attached after the fact.
    evidence_blob_id     uuid REFERENCES document_blobs (id) ON DELETE SET NULL,
    -- The user who confirmed the execution; mandatory before status EXECUTED.
    confirmed_by         uuid REFERENCES users (id) ON DELETE SET NULL,
    confirmed_at         timestamptz,
    executed_by          uuid REFERENCES users (id) ON DELETE SET NULL,
    executed_at          timestamptz,
    failure_reason       text,
    -- Retry safety: a background job must not create the payment twice (§16.3).
    idempotency_key      varchar(200),
    notes                text,
    metadata             jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by           uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    deleted_at           timestamptz,
    CONSTRAINT payments_direction_chk CHECK (direction IN ('INCOMING', 'OUTGOING')),
    CONSTRAINT payments_method_chk CHECK (method IS NULL OR method IN (
        'BANK_TRANSFER', 'DIRECT_DEBIT', 'CARD', 'CASH', 'CHEQUE', 'OTHER'
    )),
    CONSTRAINT payments_status_chk CHECK (status IN (
        'PLANNED', 'PREPARED', 'AWAITING_APPROVAL', 'APPROVED', 'EXECUTED',
        'SETTLED', 'FAILED', 'CANCELLED', 'REJECTED'
    )),
    CONSTRAINT payments_amount_chk CHECK (amount > 0),
    -- No payment may be marked executed without a confirming user (§21, §32).
    CONSTRAINT payments_executed_chk CHECK (
        status NOT IN ('EXECUTED', 'SETTLED') OR (executed_at IS NOT NULL AND confirmed_by IS NOT NULL)
    )
);

CREATE INDEX payments_tenant_status_idx ON payments (tenant_id, status) WHERE deleted_at IS NULL;
CREATE INDEX payments_invoice_idx ON payments (invoice_id);
CREATE INDEX payments_date_idx ON payments (tenant_id, payment_date DESC);
CREATE UNIQUE INDEX payments_idempotency_key ON payments (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TRIGGER payments_set_updated_at BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE payment_matches (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    payment_id          uuid REFERENCES payments (id) ON DELETE CASCADE,
    invoice_id          uuid REFERENCES invoices (id) ON DELETE CASCADE,
    bank_transaction_id uuid REFERENCES bank_transactions (id) ON DELETE CASCADE,
    match_type          varchar(20) NOT NULL DEFAULT 'SUGGESTED',
    status              varchar(20) NOT NULL DEFAULT 'PROPOSED',
    matched_amount      numeric(14, 2),
    currency            char(3) NOT NULL DEFAULT 'EUR',
    confidence          numeric(5, 4),
    match_reason        text,
    matched_by          uuid REFERENCES users (id) ON DELETE SET NULL,
    matched_at          timestamptz,
    note                text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT payment_matches_type_chk CHECK (match_type IN ('AUTOMATIC', 'MANUAL', 'SUGGESTED')),
    CONSTRAINT payment_matches_status_chk CHECK (status IN ('PROPOSED', 'CONFIRMED', 'REJECTED')),
    -- A match must always relate a payment/transaction to an invoice.
    CONSTRAINT payment_matches_subject_chk CHECK (
        invoice_id IS NOT NULL AND (payment_id IS NOT NULL OR bank_transaction_id IS NOT NULL)
    ),
    CONSTRAINT payment_matches_confidence_chk CHECK (
        confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    ),
    -- Only a confirmed match may be recorded as confirmed (§14.4: ambiguous matches
    -- require manual confirmation).
    CONSTRAINT payment_matches_confirmed_chk CHECK (
        status <> 'CONFIRMED' OR (matched_by IS NOT NULL AND matched_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX payment_matches_pair_key
    ON payment_matches (coalesce(payment_id, '00000000-0000-0000-0000-000000000000'::uuid),
                        coalesce(bank_transaction_id, '00000000-0000-0000-0000-000000000000'::uuid),
                        invoice_id);
CREATE INDEX payment_matches_invoice_idx ON payment_matches (invoice_id);

-- -------------------------------------------------------------------------------------
-- payment approval rules and decisions (§14.1 "согласование счёта")
-- -------------------------------------------------------------------------------------
CREATE TABLE payment_approval_rules (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid REFERENCES tenants (id) ON DELETE CASCADE,   -- NULL = system default
    code              varchar(80) NOT NULL,
    name              jsonb NOT NULL,
    min_amount        numeric(14, 2),
    max_amount        numeric(14, 2),
    currency          char(3) NOT NULL DEFAULT 'EUR',
    direction         varchar(20) NOT NULL DEFAULT 'OUTGOING',
    required_role     varchar(60),
    required_permission varchar(80),
    approval_levels   integer NOT NULL DEFAULT 1,
    -- Four-eyes principle: approver must differ from the creator.
    require_second_person boolean NOT NULL DEFAULT true,
    is_active         boolean NOT NULL DEFAULT true,
    version           integer NOT NULL DEFAULT 1,
    created_by        uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT payment_approval_rules_amount_chk CHECK (
        (min_amount IS NULL OR min_amount >= 0)
        AND (max_amount IS NULL OR max_amount >= 0)
        AND (min_amount IS NULL OR max_amount IS NULL OR max_amount >= min_amount)
    ),
    CONSTRAINT payment_approval_rules_levels_chk CHECK (approval_levels BETWEEN 1 AND 5),
    CONSTRAINT payment_approval_rules_direction_chk CHECK (direction IN ('INCOMING', 'OUTGOING', 'ANY'))
);

CREATE UNIQUE INDEX payment_approval_rules_code_key
    ON payment_approval_rules (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), code, version);

CREATE TRIGGER payment_approval_rules_set_updated_at BEFORE UPDATE ON payment_approval_rules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE payment_approvals (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    payment_id     uuid NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    invoice_id     uuid REFERENCES invoices (id) ON DELETE SET NULL,
    rule_id        uuid REFERENCES payment_approval_rules (id) ON DELETE SET NULL,
    approval_id    uuid REFERENCES approvals (id) ON DELETE SET NULL,
    approval_level integer NOT NULL DEFAULT 1,
    status         varchar(20) NOT NULL DEFAULT 'PENDING',
    approver_user_id uuid REFERENCES users (id) ON DELETE SET NULL,
    decided_at     timestamptz,
    comment        text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT payment_approvals_status_chk CHECK (status IN (
        'PENDING', 'APPROVED', 'REJECTED', 'SKIPPED', 'EXPIRED'
    )),
    CONSTRAINT payment_approvals_level_chk CHECK (approval_level BETWEEN 1 AND 5),
    CONSTRAINT payment_approvals_decided_chk CHECK (
        (status IN ('APPROVED', 'REJECTED')) = (decided_at IS NOT NULL AND approver_user_id IS NOT NULL)
    )
);

CREATE INDEX payment_approvals_payment_idx ON payment_approvals (payment_id);
CREATE INDEX payment_approvals_queue_idx ON payment_approvals (tenant_id, status) WHERE status = 'PENDING';
