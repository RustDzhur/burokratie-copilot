-- =====================================================================================
-- V2 — Identity, access control and sessions (spec §4, §19, §21.3)
--
-- users          — global identity; a person may be a member of several tenants
-- memberships    — the (tenant, user) link carrying roles and status
-- roles/permissions/role_permissions/membership_roles — RBAC, extensible to ABAC later
-- invitations    — invitations with role, workspace, expiry and audit trail
-- sessions       — server-side sessions referenced by an opaque cookie token hash
-- mfa_methods    — TOTP secrets (encrypted) and hashed recovery codes
-- =====================================================================================

CREATE TABLE users (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email                   citext NOT NULL,
    password_hash           varchar(255),
    status                  varchar(30) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    display_name            varchar(200) NOT NULL,
    locale                  varchar(10) NOT NULL DEFAULT 'de',
    time_zone               varchar(60) NOT NULL DEFAULT 'Europe/Berlin',
    email_verified_at       timestamptz,
    failed_login_attempts   integer NOT NULL DEFAULT 0,
    locked_until            timestamptz,
    last_login_at           timestamptz,
    password_changed_at     timestamptz,
    must_change_password    boolean NOT NULL DEFAULT false,
    -- Federated identity (OIDC) — password_hash stays NULL for such accounts.
    identity_provider       varchar(40),
    external_subject        varchar(200),
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    deleted_at              timestamptz,
    CONSTRAINT users_status_chk CHECK (status IN (
        'PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'DISABLED', 'DELETED'
    )),
    CONSTRAINT users_email_format_chk CHECK (email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
    CONSTRAINT users_failed_attempts_chk CHECK (failed_login_attempts >= 0)
);

CREATE UNIQUE INDEX users_email_key ON users (email) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX users_external_identity_key ON users (identity_provider, external_subject)
    WHERE identity_provider IS NOT NULL AND external_subject IS NOT NULL;
CREATE INDEX users_status_idx ON users (status) WHERE deleted_at IS NULL;

CREATE TRIGGER users_set_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- user_profiles — personal data kept separate from the authentication record
-- (data minimisation, spec §21.1: authentication code never needs the address).
-- -------------------------------------------------------------------------------------
CREATE TABLE user_profiles (
    user_id         uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    first_name      varchar(120),
    last_name       varchar(120),
    salutation      varchar(40),
    phone           varchar(60),
    date_of_birth   date,
    street          varchar(200),
    house_number    varchar(30),
    address_extra   varchar(200),
    postal_code     varchar(20),
    city            varchar(120),
    country_code    char(2) NOT NULL DEFAULT 'DE',
    preferred_language varchar(10),
    accessibility   jsonb NOT NULL DEFAULT '{}'::jsonb,   -- reduce motion, contrast, font size (§23)
    ui_preferences  jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER user_profiles_set_updated_at BEFORE UPDATE ON user_profiles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- roles / permissions
-- -------------------------------------------------------------------------------------
CREATE TABLE roles (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code            varchar(60) NOT NULL,
    -- NULL tenant_id means a system role available to every tenant (§4 base roles).
    tenant_id       uuid REFERENCES tenants (id) ON DELETE CASCADE,
    name            jsonb NOT NULL,          -- {"de": "...", "en": "...", "ru": "..."}
    description     jsonb NOT NULL DEFAULT '{}'::jsonb,
    is_system       boolean NOT NULL DEFAULT false,
    rank            integer NOT NULL DEFAULT 100,   -- lower rank = more authority
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT roles_code_uq UNIQUE (tenant_id, code)
);

CREATE UNIQUE INDEX roles_system_code_key ON roles (code) WHERE tenant_id IS NULL;

CREATE TRIGGER roles_set_updated_at BEFORE UPDATE ON roles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE permissions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code        varchar(80) NOT NULL,
    area        varchar(40) NOT NULL,
    action      varchar(40) NOT NULL,
    name        jsonb NOT NULL,
    description jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT permissions_code_key UNIQUE (code),
    CONSTRAINT permissions_area_chk CHECK (area IN (
        'documents', 'cases', 'tasks', 'contracts', 'finance', 'contacts', 'mail',
        'templates', 'workflows', 'integrations', 'devices', 'staff', 'settings',
        'audit', 'reports', 'notifications'
    ))
);

CREATE TABLE role_permissions (
    role_id       uuid NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id uuid NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- -------------------------------------------------------------------------------------
-- memberships
-- -------------------------------------------------------------------------------------
CREATE TABLE memberships (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id             uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    workspace_id        uuid REFERENCES workspaces (id) ON DELETE SET NULL,
    status              varchar(30) NOT NULL DEFAULT 'INVITED',
    is_owner            boolean NOT NULL DEFAULT false,
    invited_by          uuid REFERENCES users (id) ON DELETE SET NULL,
    joined_at           timestamptz,
    -- Temporary access (§4): when set, the membership is only valid inside the window.
    access_valid_from   timestamptz,
    access_valid_until  timestamptz,
    revoked_at          timestamptz,
    revoked_by          uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT memberships_status_chk CHECK (status IN (
        'INVITED', 'ACTIVE', 'SUSPENDED', 'REVOKED', 'EXPIRED'
    ))
);

CREATE UNIQUE INDEX memberships_tenant_user_key ON memberships (tenant_id, user_id);
CREATE INDEX memberships_user_idx ON memberships (user_id) WHERE status = 'ACTIVE';
CREATE INDEX memberships_tenant_idx ON memberships (tenant_id, status);

CREATE TRIGGER memberships_set_updated_at BEFORE UPDATE ON memberships
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE membership_roles (
    membership_id uuid NOT NULL REFERENCES memberships (id) ON DELETE CASCADE,
    role_id       uuid NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    granted_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    granted_at    timestamptz NOT NULL DEFAULT now(),
    -- Time-boxed roles are supported so that temporary access really expires (§4).
    expires_at    timestamptz,
    PRIMARY KEY (membership_id, role_id)
);

CREATE INDEX membership_roles_expiry_idx ON membership_roles (expires_at)
    WHERE expires_at IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- invitations
-- -------------------------------------------------------------------------------------
CREATE TABLE invitations (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    workspace_id  uuid REFERENCES workspaces (id) ON DELETE SET NULL,
    email         citext NOT NULL,
    role_id       uuid REFERENCES roles (id) ON DELETE SET NULL,
    invited_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    -- Only the hash is stored: a leaked database row must not grant access (§21.3).
    token_hash    varchar(128) NOT NULL,
    status        varchar(30) NOT NULL DEFAULT 'PENDING',
    message       text,
    expires_at    timestamptz NOT NULL,
    accepted_at   timestamptz,
    accepted_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    revoked_at    timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT invitations_status_chk CHECK (status IN (
        'PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED'
    )),
    CONSTRAINT invitations_token_hash_key UNIQUE (token_hash)
);

CREATE INDEX invitations_tenant_idx ON invitations (tenant_id, status);
CREATE UNIQUE INDEX invitations_pending_email_key ON invitations (tenant_id, email)
    WHERE status = 'PENDING';

CREATE TRIGGER invitations_set_updated_at BEFORE UPDATE ON invitations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- sessions — server-side sessions; the cookie only carries an opaque random token.
-- -------------------------------------------------------------------------------------
CREATE TABLE sessions (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tenant_id        uuid REFERENCES tenants (id) ON DELETE CASCADE,
    token_hash       varchar(128) NOT NULL,
    mfa_verified     boolean NOT NULL DEFAULT false,
    ip_address       inet,
    user_agent       varchar(400),
    created_at       timestamptz NOT NULL DEFAULT now(),
    last_seen_at     timestamptz NOT NULL DEFAULT now(),
    expires_at       timestamptz NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    revoked_at       timestamptz,
    revoked_reason   varchar(100),
    CONSTRAINT sessions_token_hash_key UNIQUE (token_hash)
);

CREATE INDEX sessions_user_idx ON sessions (user_id) WHERE revoked_at IS NULL;
CREATE INDEX sessions_expiry_idx ON sessions (expires_at) WHERE revoked_at IS NULL;

-- -------------------------------------------------------------------------------------
-- mfa_methods (+ recovery codes)
-- -------------------------------------------------------------------------------------
CREATE TABLE mfa_methods (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type              varchar(20) NOT NULL,
    -- AES-GCM ciphertext for TOTP secrets; never plaintext (§21.1).
    secret_encrypted  text,
    label             varchar(120),
    confirmed_at      timestamptz,
    last_used_at      timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    disabled_at       timestamptz,
    CONSTRAINT mfa_methods_type_chk CHECK (type IN ('TOTP', 'WEBAUTHN', 'EMAIL', 'SMS')),
    CONSTRAINT mfa_methods_secret_chk CHECK (
        (type = 'TOTP' AND secret_encrypted IS NOT NULL) OR type <> 'TOTP'
    )
);

CREATE INDEX mfa_methods_user_idx ON mfa_methods (user_id) WHERE disabled_at IS NULL;

-- Recovery codes are stored as hashes only; a used code is deleted, never re-issued.
CREATE TABLE mfa_recovery_codes (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash    varchar(255) NOT NULL,
    used_at      timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT mfa_recovery_codes_hash_key UNIQUE (code_hash)
);

CREATE INDEX mfa_recovery_codes_user_idx ON mfa_recovery_codes (user_id) WHERE used_at IS NULL;

-- -------------------------------------------------------------------------------------
-- One-time tokens (e-mail verification, password reset) — hash only, single use.
-- -------------------------------------------------------------------------------------
CREATE TABLE one_time_tokens (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose      varchar(40) NOT NULL,
    token_hash   varchar(128) NOT NULL,
    expires_at   timestamptz NOT NULL,
    consumed_at  timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT one_time_tokens_purpose_chk CHECK (purpose IN (
        'EMAIL_VERIFICATION', 'PASSWORD_RESET', 'MFA_ENROLLMENT', 'ADMIN_BOOTSTRAP'
    )),
    CONSTRAINT one_time_tokens_hash_key UNIQUE (token_hash)
);

CREATE INDEX one_time_tokens_user_idx ON one_time_tokens (user_id, purpose)
    WHERE consumed_at IS NULL;

-- -------------------------------------------------------------------------------------
-- Access-log for permission changes (§4: журнал выдачи и изменения прав).
-- -------------------------------------------------------------------------------------
CREATE TABLE access_change_log (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    subject_user_id uuid REFERENCES users (id) ON DELETE SET NULL,
    actor_user_id  uuid REFERENCES users (id) ON DELETE SET NULL,
    change_type    varchar(40) NOT NULL,
    role_code      varchar(60),
    details        jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT access_change_log_type_chk CHECK (change_type IN (
        'INVITED', 'INVITATION_REVOKED', 'INVITATION_ACCEPTED', 'ROLE_GRANTED',
        'ROLE_REVOKED', 'ACCESS_REVOKED', 'TEMPORARY_ACCESS_SET', 'MEMBER_SUSPENDED'
    ))
);

CREATE INDEX access_change_log_tenant_idx ON access_change_log (tenant_id, created_at DESC);
