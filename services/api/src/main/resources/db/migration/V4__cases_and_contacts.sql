-- =====================================================================================
-- V4 — Contacts and cases (§11, §19)
--
-- A "Fall/Akte" (case) is the administrative thread that ties documents, people,
-- organisations, tasks, deadlines, contracts, money and correspondence together.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- contacts — persons and external organisations (authorities, suppliers, customers)
-- -------------------------------------------------------------------------------------
CREATE TABLE contacts (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    contact_type      varchar(20) NOT NULL,
    display_name      varchar(300) NOT NULL,
    first_name        varchar(120),
    last_name         varchar(120),
    organization_name varchar(300),
    legal_form        varchar(80),
    vat_id            varchar(40),
    tax_number        varchar(40),
    email             citext,
    phone             varchar(60),
    mobile            varchar(60),
    fax               varchar(60),
    website           varchar(300),
    -- Authorities are pre-classified so that letters from them can be routed (§9.3).
    is_authority      boolean NOT NULL DEFAULT false,
    authority_code    varchar(80),
    is_customer       boolean NOT NULL DEFAULT false,
    is_supplier       boolean NOT NULL DEFAULT false,
    preferred_language varchar(10),
    notes             text,
    metadata          jsonb NOT NULL DEFAULT '{}'::jsonb,
    search_vector     tsvector GENERATED ALWAYS AS (
                          to_tsvector('german',
                              coalesce(display_name, '') || ' ' ||
                              coalesce(first_name, '') || ' ' ||
                              coalesce(last_name, '') || ' ' ||
                              coalesce(organization_name, ''))
                      ) STORED,
    created_by        uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    deleted_at        timestamptz,
    CONSTRAINT contacts_type_chk CHECK (contact_type IN ('PERSON', 'ORGANIZATION'))
);

CREATE INDEX contacts_tenant_idx ON contacts (tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX contacts_tenant_name_idx ON contacts (tenant_id, display_name);
CREATE INDEX contacts_search_idx ON contacts USING gin (search_vector);
CREATE UNIQUE INDEX contacts_tenant_vat_key ON contacts (tenant_id, vat_id)
    WHERE vat_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX contacts_authority_idx ON contacts (tenant_id, authority_code)
    WHERE is_authority AND deleted_at IS NULL;

CREATE TRIGGER contacts_set_updated_at BEFORE UPDATE ON contacts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE contact_addresses (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id    uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    address_type  varchar(20) NOT NULL DEFAULT 'MAIN',
    street        varchar(200),
    house_number  varchar(30),
    address_extra varchar(200),
    postal_code   varchar(20),
    city          varchar(120),
    district      varchar(120),
    region        varchar(120),          -- Bundesland
    country_code  char(2) NOT NULL DEFAULT 'DE',
    is_primary    boolean NOT NULL DEFAULT false,
    valid_from    date,
    valid_to      date,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT contact_addresses_type_chk CHECK (address_type IN (
        'MAIN', 'BILLING', 'DELIVERY', 'REGISTERED', 'OTHER'
    )),
    CONSTRAINT contact_addresses_country_chk CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT contact_addresses_validity_chk CHECK (
        valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from
    )
);

CREATE UNIQUE INDEX contact_addresses_primary_key ON contact_addresses (contact_id)
    WHERE is_primary;
CREATE INDEX contact_addresses_contact_idx ON contact_addresses (contact_id);

CREATE TRIGGER contact_addresses_set_updated_at BEFORE UPDATE ON contact_addresses
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Links contacts to the tenant's own organisations (employees, representatives, ...).
CREATE TABLE organizations_contacts (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    contact_id      uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    role            varchar(40) NOT NULL DEFAULT 'CONTACT',
    position        varchar(120),
    department      varchar(120),
    is_primary      boolean NOT NULL DEFAULT false,
    valid_from      date,
    valid_to        date,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT organizations_contacts_uq UNIQUE (organization_id, contact_id, role),
    CONSTRAINT organizations_contacts_role_chk CHECK (role IN (
        'CONTACT', 'EMPLOYEE', 'MANAGING_DIRECTOR', 'REPRESENTATIVE',
        'ACCOUNTANT', 'LEGAL_COUNSEL', 'SUPPLIER_CONTACT', 'CUSTOMER_CONTACT'
    ))
);

CREATE INDEX organizations_contacts_contact_idx ON organizations_contacts (contact_id);

CREATE TABLE contact_relations (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    from_contact_id   uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    to_contact_id     uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    relation_type     varchar(40) NOT NULL,
    valid_from        date,
    valid_to          date,
    notes             text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT contact_relations_uq UNIQUE (from_contact_id, to_contact_id, relation_type),
    CONSTRAINT contact_relations_no_self_chk CHECK (from_contact_id <> to_contact_id),
    CONSTRAINT contact_relations_type_chk CHECK (relation_type IN (
        'SPOUSE_OF', 'CHILD_OF', 'PARENT_OF', 'GUARDIAN_OF', 'HOUSEHOLD_MEMBER_OF',
        'EMPLOYEE_OF', 'EMPLOYER_OF', 'REPRESENTATIVE_OF', 'MEMBER_OF',
        'SUPPLIER_OF', 'CUSTOMER_OF'
    ))
);

-- -------------------------------------------------------------------------------------
-- case_types — configurable case taxonomy; also carries the document checklist so the
-- system can answer "what is missing for this case?" (§10.4, §5.3).
-- -------------------------------------------------------------------------------------
CREATE TABLE case_types (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid REFERENCES tenants (id) ON DELETE CASCADE,   -- NULL = system default
    code          varchar(80) NOT NULL,
    name          jsonb NOT NULL,
    description   jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- Escalation/priority defaults are data, not code (§31).
    default_priority varchar(20) NOT NULL DEFAULT 'NORMAL',
    is_active     boolean NOT NULL DEFAULT true,
    sort_order    integer NOT NULL DEFAULT 100,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT case_types_priority_chk CHECK (default_priority IN (
        'CRITICAL', 'HIGH', 'NORMAL', 'LOW', 'INFORMATIONAL'
    ))
);

CREATE UNIQUE INDEX case_types_code_key
    ON case_types (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), code);

CREATE TRIGGER case_types_set_updated_at BEFORE UPDATE ON case_types
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE case_type_document_requirements (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_type_id     uuid NOT NULL REFERENCES case_types (id) ON DELETE CASCADE,
    document_type_id uuid REFERENCES document_types (id) ON DELETE CASCADE,
    requirement_key  varchar(80) NOT NULL,
    label            jsonb NOT NULL,
    is_mandatory     boolean NOT NULL DEFAULT true,
    sort_order       integer NOT NULL DEFAULT 100,
    CONSTRAINT case_type_document_requirements_uq UNIQUE (case_type_id, requirement_key)
);

-- -------------------------------------------------------------------------------------
-- cases (§11.1)
-- -------------------------------------------------------------------------------------
CREATE TABLE cases (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    workspace_id        uuid REFERENCES workspaces (id) ON DELETE SET NULL,
    organization_id     uuid REFERENCES organizations (id) ON DELETE SET NULL,
    reference           varchar(60) NOT NULL,          -- human readable, e.g. "FA-2026-000123"
    title               varchar(300) NOT NULL,
    case_type_id        uuid REFERENCES case_types (id) ON DELETE SET NULL,
    description         text,
    status              varchar(40) NOT NULL DEFAULT 'NEW',
    priority            varchar(20) NOT NULL DEFAULT 'NORMAL',
    owner_user_id       uuid REFERENCES users (id) ON DELETE SET NULL,
    responsible_user_id uuid REFERENCES users (id) ON DELETE SET NULL,
    -- Main counterparty (authority, landlord, insurer, customer ...).
    counterparty_contact_id uuid REFERENCES contacts (id) ON DELETE SET NULL,
    -- External reference such as an Aktenzeichen of an authority.
    external_reference  varchar(200),
    authority_contact_id uuid REFERENCES contacts (id) ON DELETE SET NULL,
    opened_at           timestamptz NOT NULL DEFAULT now(),
    due_at              timestamptz,
    waiting_since       timestamptz,
    -- Closing requires an outcome or an informed user decision (§11.2).
    outcome             text,
    closed_at           timestamptz,
    closed_by           uuid REFERENCES users (id) ON DELETE SET NULL,
    close_reason        varchar(40),
    confidentiality     varchar(30) NOT NULL DEFAULT 'INTERNAL',
    tags                jsonb NOT NULL DEFAULT '[]'::jsonb,
    metadata            jsonb NOT NULL DEFAULT '{}'::jsonb,
    search_vector       tsvector GENERATED ALWAYS AS (
                            to_tsvector('german',
                                coalesce(title, '') || ' ' ||
                                coalesce(reference, '') || ' ' ||
                                coalesce(description, '') || ' ' ||
                                coalesce(external_reference, ''))
                        ) STORED,
    created_by          uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    deleted_at          timestamptz,
    CONSTRAINT cases_reference_uq UNIQUE (tenant_id, reference),
    CONSTRAINT cases_status_chk CHECK (status IN (
        'NEW', 'IN_PROGRESS', 'WAITING_FOR_USER', 'WAITING_FOR_EXTERNAL_PARTY',
        'WAITING_FOR_DOCUMENTS', 'WAITING_FOR_APPROVAL', 'ON_HOLD',
        'COMPLETED', 'CLOSED', 'REOPENED'
    )),
    CONSTRAINT cases_priority_chk CHECK (priority IN (
        'CRITICAL', 'HIGH', 'NORMAL', 'LOW', 'INFORMATIONAL'
    )),
    CONSTRAINT cases_confidentiality_chk CHECK (confidentiality IN (
        'PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'STRICT'
    )),
    CONSTRAINT cases_close_reason_chk CHECK (close_reason IS NULL OR close_reason IN (
        'OUTCOME_ACHIEVED', 'USER_DECISION', 'CANCELLED', 'DUPLICATE', 'NOT_REQUIRED', 'OTHER'
    )),
    -- A closed case must state when and why it was closed.
    CONSTRAINT cases_closed_consistency_chk CHECK (
        (status IN ('COMPLETED', 'CLOSED')) = (closed_at IS NOT NULL)
    )
);

CREATE INDEX cases_tenant_status_idx ON cases (tenant_id, status) WHERE deleted_at IS NULL;
CREATE INDEX cases_tenant_due_idx ON cases (tenant_id, due_at) WHERE deleted_at IS NULL;
CREATE INDEX cases_owner_idx ON cases (owner_user_id) WHERE deleted_at IS NULL;
CREATE INDEX cases_responsible_idx ON cases (responsible_user_id) WHERE deleted_at IS NULL;
CREATE INDEX cases_workspace_idx ON cases (workspace_id) WHERE deleted_at IS NULL;
CREATE INDEX cases_search_idx ON cases USING gin (search_vector);
CREATE INDEX cases_counterparty_idx ON cases (counterparty_contact_id);

CREATE TRIGGER cases_set_updated_at BEFORE UPDATE ON cases
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- case_participants — including per-case access rights (§4: доступ к конкретным делам)
-- -------------------------------------------------------------------------------------
CREATE TABLE case_participants (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id      uuid NOT NULL REFERENCES cases (id) ON DELETE CASCADE,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id      uuid REFERENCES users (id) ON DELETE CASCADE,
    contact_id   uuid REFERENCES contacts (id) ON DELETE CASCADE,
    role         varchar(40) NOT NULL,
    access_level varchar(20) NOT NULL DEFAULT 'READ',
    granted_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    granted_at   timestamptz NOT NULL DEFAULT now(),
    -- Temporary case access (§4).
    expires_at   timestamptz,
    revoked_at   timestamptz,
    CONSTRAINT case_participants_subject_chk CHECK (
        (user_id IS NOT NULL AND contact_id IS NULL)
        OR (user_id IS NULL AND contact_id IS NOT NULL)
    ),
    CONSTRAINT case_participants_role_chk CHECK (role IN (
        'OWNER', 'RESPONSIBLE', 'PARTICIPANT', 'OBSERVER', 'APPROVER',
        'EXTERNAL_ADVISOR', 'LEGAL_ADVISOR', 'COUNTERPARTY'
    )),
    CONSTRAINT case_participants_access_chk CHECK (access_level IN (
        'READ', 'COMMENT', 'WRITE', 'APPROVE', 'FULL'
    ))
);

CREATE UNIQUE INDEX case_participants_user_key ON case_participants (case_id, user_id, role)
    WHERE user_id IS NOT NULL AND revoked_at IS NULL;
CREATE UNIQUE INDEX case_participants_contact_key ON case_participants (case_id, contact_id, role)
    WHERE contact_id IS NOT NULL AND revoked_at IS NULL;
CREATE INDEX case_participants_user_idx ON case_participants (user_id) WHERE revoked_at IS NULL;

-- -------------------------------------------------------------------------------------
-- case_documents
-- -------------------------------------------------------------------------------------
CREATE TABLE case_documents (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id      uuid NOT NULL REFERENCES cases (id) ON DELETE CASCADE,
    document_id  uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    is_primary   boolean NOT NULL DEFAULT false,
    note         varchar(500),
    linked_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    -- Links created by the matching engine are proposals until confirmed (§1.1 п.7).
    link_origin  varchar(20) NOT NULL DEFAULT 'MANUAL',
    confidence   numeric(5, 4),
    confirmed_at timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT case_documents_uq UNIQUE (case_id, document_id),
    CONSTRAINT case_documents_origin_chk CHECK (link_origin IN ('MANUAL', 'RULE', 'AI', 'IMPORT')),
    CONSTRAINT case_documents_confidence_chk CHECK (
        confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    )
);

CREATE INDEX case_documents_document_idx ON case_documents (document_id);
CREATE INDEX case_documents_unconfirmed_idx ON case_documents (case_id) WHERE confirmed_at IS NULL;

-- -------------------------------------------------------------------------------------
-- case_events — the case timeline (§11.1: события, история действий)
-- -------------------------------------------------------------------------------------
CREATE TABLE case_events (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id        uuid NOT NULL REFERENCES cases (id) ON DELETE CASCADE,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    event_type     varchar(50) NOT NULL,
    title          varchar(300) NOT NULL,
    description    text,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    actor_user_id  uuid REFERENCES users (id) ON DELETE SET NULL,
    document_id    uuid REFERENCES documents (id) ON DELETE SET NULL,
    task_id        uuid,
    contact_id     uuid REFERENCES contacts (id) ON DELETE SET NULL,
    metadata       jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT case_events_type_chk CHECK (event_type IN (
        'CASE_CREATED', 'STATUS_CHANGED', 'DOCUMENT_LINKED', 'DOCUMENT_UNLINKED',
        'TASK_CREATED', 'TASK_COMPLETED', 'DEADLINE_SET', 'DEADLINE_CHANGED',
        'NOTE_ADDED', 'CORRESPONDENCE_SENT', 'CORRESPONDENCE_RECEIVED',
        'PAYMENT_RECORDED', 'CONTRACT_LINKED', 'ASSIGNEE_CHANGED', 'CASE_CLOSED',
        'CASE_REOPENED', 'APPROVAL_REQUESTED', 'APPROVAL_GRANTED', 'APPROVAL_REJECTED'
    ))
);

CREATE INDEX case_events_case_idx ON case_events (case_id, occurred_at DESC);
CREATE INDEX case_events_tenant_idx ON case_events (tenant_id, occurred_at DESC);
