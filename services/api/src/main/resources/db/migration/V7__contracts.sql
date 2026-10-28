-- =====================================================================================
-- V7 — Contracts (§13, §19)
--
-- The module deliberately separates *detected text* from *legal interpretation*:
-- `contract_clauses.clause_text` is what a parser found in the document, while
-- `interpretation` may only be filled by a verified rule or an explicit human decision
-- (§13.2 "Система должна различать обнаруженный текст и юридическую интерпретацию").
-- =====================================================================================

CREATE TABLE contracts (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    workspace_id          uuid REFERENCES workspaces (id) ON DELETE SET NULL,
    organization_id       uuid REFERENCES organizations (id) ON DELETE SET NULL,
    case_id               uuid REFERENCES cases (id) ON DELETE SET NULL,
    counterparty_contact_id uuid REFERENCES contacts (id) ON DELETE SET NULL,

    contract_number       varchar(120),
    title                 varchar(300) NOT NULL,
    contract_type         varchar(40) NOT NULL,
    status                varchar(30) NOT NULL DEFAULT 'DRAFT',
    description           text,

    start_date            date,
    end_date              date,
    signed_date           date,
    auto_renewal          boolean,
    renewal_period_months integer,
    notice_period_value   integer,
    notice_period_unit    varchar(20),
    notice_deadline_date  date,
    notice_deadline_rule_id uuid,

    cost_amount           numeric(14, 2),
    cost_currency         char(3) NOT NULL DEFAULT 'EUR',
    cost_interval         varchar(20),
    price_change_clause   text,

    source_document_id    uuid REFERENCES documents (id) ON DELETE SET NULL,
    confidentiality       varchar(30) NOT NULL DEFAULT 'INTERNAL',
    -- Values below were recognised in the document but not yet verified by a person.
    data_verified_by      uuid REFERENCES users (id) ON DELETE SET NULL,
    data_verified_at      timestamptz,

    tags                  jsonb NOT NULL DEFAULT '[]'::jsonb,
    metadata              jsonb NOT NULL DEFAULT '{}'::jsonb,
    search_vector         tsvector GENERATED ALWAYS AS (
                              to_tsvector('german',
                                  coalesce(title, '') || ' ' ||
                                  coalesce(contract_number, '') || ' ' ||
                                  coalesce(description, ''))
                          ) STORED,
    created_by            uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    deleted_at            timestamptz,

    CONSTRAINT contracts_type_chk CHECK (contract_type IN (
        'RENT', 'INSURANCE', 'MOBILE', 'INTERNET', 'SUBSCRIPTION', 'BANKING',
        'EMPLOYMENT', 'SUPPLY', 'SERVICE', 'SAAS', 'LEASING', 'MAINTENANCE',
        'CONTRACTOR', 'ENERGY', 'OTHER'
    )),
    CONSTRAINT contracts_status_chk CHECK (status IN (
        'DRAFT', 'ACTIVE', 'PENDING_TERMINATION', 'TERMINATED', 'EXPIRED', 'ARCHIVED'
    )),
    CONSTRAINT contracts_interval_chk CHECK (cost_interval IS NULL OR cost_interval IN (
        'ONE_TIME', 'MONTHLY', 'QUARTERLY', 'SEMIANNUAL', 'YEARLY'
    )),
    CONSTRAINT contracts_notice_unit_chk CHECK (notice_period_unit IS NULL OR notice_period_unit IN (
        'DAYS', 'WEEKS', 'MONTHS', 'YEARS'
    )),
    CONSTRAINT contracts_period_chk CHECK (
        end_date IS NULL OR start_date IS NULL OR end_date >= start_date
    ),
    CONSTRAINT contracts_cost_chk CHECK (cost_amount IS NULL OR cost_amount >= 0),
    CONSTRAINT contracts_confidentiality_chk CHECK (confidentiality IN (
        'PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'STRICT'
    ))
);

CREATE INDEX contracts_tenant_status_idx ON contracts (tenant_id, status) WHERE deleted_at IS NULL;
-- "Contracts approaching their end or renewal" is a dashboard block (§5.3).
CREATE INDEX contracts_renewal_idx ON contracts (tenant_id, end_date, notice_deadline_date)
    WHERE deleted_at IS NULL AND status IN ('ACTIVE', 'PENDING_TERMINATION');
CREATE INDEX contracts_case_idx ON contracts (case_id) WHERE deleted_at IS NULL;
CREATE INDEX contracts_search_idx ON contracts USING gin (search_vector);

CREATE TRIGGER contracts_set_updated_at BEFORE UPDATE ON contracts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE contract_parties (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id   uuid NOT NULL REFERENCES contracts (id) ON DELETE CASCADE,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    contact_id    uuid REFERENCES contacts (id) ON DELETE SET NULL,
    organization_id uuid REFERENCES organizations (id) ON DELETE SET NULL,
    party_role    varchar(30) NOT NULL,
    party_name    varchar(300) NOT NULL,
    is_primary    boolean NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT contract_parties_role_chk CHECK (party_role IN (
        'OWN_SIDE', 'COUNTERPARTY', 'GUARANTOR', 'AGENT', 'WITNESS', 'OTHER'
    )),
    CONSTRAINT contract_parties_subject_chk CHECK (
        contact_id IS NOT NULL OR organization_id IS NOT NULL OR party_name IS NOT NULL
    )
);

CREATE INDEX contract_parties_contract_idx ON contract_parties (contract_id);

CREATE TABLE contract_clauses (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id   uuid NOT NULL REFERENCES contracts (id) ON DELETE CASCADE,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    clause_key    varchar(120) NOT NULL,
    clause_type   varchar(40) NOT NULL,
    title         varchar(300),
    -- Recognised text (verbatim excerpt), always with its source location.
    clause_text   text NOT NULL,
    source_page   integer,
    source_snippet text,
    confidence    numeric(5, 4),
    is_critical   boolean NOT NULL DEFAULT false,
    -- Legal interpretation: only from an explicit human decision or a verified rule.
    interpretation text,
    interpretation_source varchar(20) NOT NULL DEFAULT 'NONE',
    interpretation_verified_by uuid REFERENCES users (id) ON DELETE SET NULL,
    interpretation_verified_at timestamptz,
    sort_order    integer NOT NULL DEFAULT 100,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT contract_clauses_uq UNIQUE (contract_id, clause_key),
    CONSTRAINT contract_clauses_type_chk CHECK (clause_type IN (
        'TERM', 'NOTICE_PERIOD', 'AUTO_RENEWAL', 'PRICE', 'PRICE_ADJUSTMENT',
        'OBLIGATION', 'TERMINATION', 'LIABILITY', 'WARRANTY', 'DATA_PROTECTION',
        'SERVICE_SCOPE', 'PAYMENT_TERMS', 'OTHER'
    )),
    CONSTRAINT contract_clauses_interp_source_chk CHECK (interpretation_source IN (
        'NONE', 'RULE', 'HUMAN'
    )),
    -- An interpretation without a responsible human or verified rule is not allowed.
    CONSTRAINT contract_clauses_interpretation_chk CHECK (
        interpretation IS NULL
        OR interpretation_verified_at IS NOT NULL
        OR interpretation_source = 'RULE'
    )
);

CREATE INDEX contract_clauses_contract_idx ON contract_clauses (contract_id, sort_order);
CREATE INDEX contract_clauses_type_idx ON contract_clauses (contract_id, clause_type);

CREATE TRIGGER contract_clauses_set_updated_at BEFORE UPDATE ON contract_clauses
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Document versions that belong to a contract: enables version comparison (§13.2).
CREATE TABLE contract_versions (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id        uuid NOT NULL REFERENCES contracts (id) ON DELETE CASCADE,
    tenant_id          uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    document_id        uuid REFERENCES documents (id) ON DELETE SET NULL,
    document_version_id uuid REFERENCES document_versions (id) ON DELETE SET NULL,
    version_label      varchar(120) NOT NULL,
    effective_from     date,
    change_note        text,
    is_current         boolean NOT NULL DEFAULT false,
    created_by         uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT contract_versions_uq UNIQUE (contract_id, version_label)
);

CREATE UNIQUE INDEX contract_versions_current_key ON contract_versions (contract_id)
    WHERE is_current;
CREATE INDEX contract_versions_contract_idx ON contract_versions (contract_id, created_at DESC);

CREATE TABLE contract_events (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id   uuid NOT NULL REFERENCES contracts (id) ON DELETE CASCADE,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    event_type    varchar(40) NOT NULL,
    occurred_at   timestamptz NOT NULL DEFAULT now(),
    effective_date date,
    actor_user_id uuid REFERENCES users (id) ON DELETE SET NULL,
    description   text,
    document_id   uuid REFERENCES documents (id) ON DELETE SET NULL,
    metadata      jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT contract_events_type_chk CHECK (event_type IN (
        'CREATED', 'UPDATED', 'CLAUSE_CHANGED', 'VERSION_ADDED', 'PRICE_CHANGED',
        'RENEWED', 'NOTICE_DRAFTED', 'NOTICE_SENT', 'TERMINATION_CONFIRMED',
        'EXPIRED', 'PAYMENT_LINKED', 'OBLIGATION_DUE', 'ARCHIVED'
    ))
);

CREATE INDEX contract_events_contract_idx ON contract_events (contract_id, occurred_at DESC);

-- Obligations derived from a contract (calendar of commitments, §13.2).
CREATE TABLE contract_obligations (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id    uuid NOT NULL REFERENCES contracts (id) ON DELETE CASCADE,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    obligation_type varchar(40) NOT NULL,
    title          varchar(300) NOT NULL,
    due_date       date,
    recurrence     varchar(30) NOT NULL DEFAULT 'ONE_TIME',
    amount         numeric(14, 2),
    currency       char(3) NOT NULL DEFAULT 'EUR',
    responsible_user_id uuid REFERENCES users (id) ON DELETE SET NULL,
    reminder_days_before integer,
    status         varchar(20) NOT NULL DEFAULT 'OPEN',
    fulfilled_at   timestamptz,
    fulfilled_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    contract_clause_id uuid REFERENCES contract_clauses (id) ON DELETE SET NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT contract_obligations_type_chk CHECK (obligation_type IN (
        'PAYMENT', 'NOTICE', 'REPORT', 'INSPECTION', 'RENEWAL_DECISION', 'DOCUMENT_DELIVERY', 'OTHER'
    )),
    CONSTRAINT contract_obligations_recurrence_chk CHECK (recurrence IN (
        'ONE_TIME', 'MONTHLY', 'QUARTERLY', 'SEMIANNUAL', 'YEARLY'
    )),
    CONSTRAINT contract_obligations_status_chk CHECK (status IN (
        'OPEN', 'DUE', 'FULFILLED', 'MISSED', 'CANCELLED'
    ))
);

CREATE INDEX contract_obligations_due_idx ON contract_obligations (tenant_id, due_date)
    WHERE status IN ('OPEN', 'DUE');

CREATE TRIGGER contract_obligations_set_updated_at BEFORE UPDATE ON contract_obligations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE contracts
    ADD CONSTRAINT contracts_notice_deadline_rule_fk
    FOREIGN KEY (notice_deadline_rule_id) REFERENCES deadline_rules (id) ON DELETE SET NULL;
