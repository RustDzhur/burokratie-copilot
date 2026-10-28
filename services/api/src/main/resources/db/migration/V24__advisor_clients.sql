-- =====================================================================================
-- V24 — An advisor's clients
--
-- H-005 requires that a consultant sees only the clients assigned to them. Nothing in the schema
-- expressed that: a workspace has a mode of ADVISOR, and that is all.
--
-- What is recorded here is a grant FROM the client's side as well as the advisor's, which is the part
-- that matters. A consultation is an arrangement between two independent parties, and the party whose
-- data is being looked at has to be the one that agreed to it. A row created by an advisor alone would
-- be an advisor granting themselves access.
--
-- The access level is stored because "sees the client" is not one thing: a tax advisor needs the
-- figures, a lawyer may need everything, and a person asking for a second opinion needs one case.
-- =====================================================================================

CREATE TABLE advisor_clients (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    client_tenant_id uuid        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    status           varchar(16) NOT NULL DEFAULT 'INVITED',
    access_level     varchar(16) NOT NULL DEFAULT 'READ',
    scope            varchar(16) NOT NULL DEFAULT 'ALL',
    label            varchar(200),
    invited_by       uuid        REFERENCES users(id) ON DELETE SET NULL,
    accepted_by      uuid        REFERENCES users(id) ON DELETE SET NULL,
    invited_at       timestamptz NOT NULL DEFAULT now(),
    accepted_at      timestamptz,
    ended_at         timestamptz,
    ended_by         uuid        REFERENCES users(id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    -- An advisor is not their own client.
    CONSTRAINT advisor_clients_not_self CHECK (tenant_id <> client_tenant_id),
    CONSTRAINT advisor_clients_status_chk
        CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'ENDED')),
    -- The levels the case model already uses, so a right means the same thing in both places.
    CONSTRAINT advisor_clients_level_chk
        CHECK (access_level IN ('READ', 'COMMENT', 'WRITE', 'APPROVE', 'FULL')),
    CONSTRAINT advisor_clients_scope_chk
        CHECK (scope IN ('ALL', 'CASES', 'DOCUMENTS', 'FINANCE')),
    CONSTRAINT advisor_clients_uq UNIQUE (tenant_id, client_tenant_id)
);

CREATE INDEX advisor_clients_advisor_idx ON advisor_clients (tenant_id, status);
CREATE INDEX advisor_clients_client_idx ON advisor_clients (client_tenant_id, status);

ALTER TABLE advisor_clients ENABLE ROW LEVEL SECURITY;
ALTER TABLE advisor_clients FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS advisor_clients_tenant_isolation ON advisor_clients;
CREATE POLICY advisor_clients_tenant_isolation ON advisor_clients
    USING (tenant_id = current_tenant_id() OR client_tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id() OR client_tenant_id = current_tenant_id());

COMMENT ON TABLE advisor_clients IS
    'An arrangement between an advisor and a client. A row is created by the advisor and becomes ACTIVE
     only once the client accepts, because the party whose data is looked at has to be the one that
     agreed.';
