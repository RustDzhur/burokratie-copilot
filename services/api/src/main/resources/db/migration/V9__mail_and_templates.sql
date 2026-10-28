-- =====================================================================================
-- V9 — Communication and document generation (§15, §19)
--
-- Delivery semantics are modelled explicitly: handing a message to a provider is *not*
-- the same as delivery to the recipient (§15.3). message_delivery_events keeps the
-- distinction so the UI can never claim more than the provider confirmed.
-- =====================================================================================

CREATE TABLE mail_accounts (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    workspace_id      uuid REFERENCES workspaces (id) ON DELETE SET NULL,
    display_name      varchar(200) NOT NULL,
    email_address     citext NOT NULL,
    provider          varchar(30) NOT NULL,
    status            varchar(30) NOT NULL DEFAULT 'DISCONNECTED',

    imap_host         varchar(300),
    imap_port         integer,
    imap_username     varchar(300),
    imap_use_ssl      boolean NOT NULL DEFAULT true,
    smtp_host         varchar(300),
    smtp_port         integer,
    smtp_username     varchar(300),
    smtp_use_starttls boolean NOT NULL DEFAULT true,

    -- Secrets are encrypted at rest with the tenant key; plaintext is never stored (§7.2).
    credential_encrypted text,
    integration_id    uuid,

    sync_folders      jsonb NOT NULL DEFAULT '["INBOX"]'::jsonb,
    sync_interval     varchar(20) NOT NULL DEFAULT 'PT15M',
    -- Per-mailbox routing rules (§7.2: multiple mailboxes, rules each).
    routing_rules     jsonb NOT NULL DEFAULT '[]'::jsonb,
    import_attachments boolean NOT NULL DEFAULT true,
    import_emails_as_documents boolean NOT NULL DEFAULT true,

    last_sync_at      timestamptz,
    last_sync_status  varchar(20),
    last_sync_error   text,
    consecutive_failures integer NOT NULL DEFAULT 0,
    is_active         boolean NOT NULL DEFAULT true,
    created_by        uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    deleted_at        timestamptz,

    CONSTRAINT mail_accounts_provider_chk CHECK (provider IN (
        'IMAP', 'MICROSOFT365', 'GMAIL', 'SMTP_ONLY', 'OTHER'
    )),
    CONSTRAINT mail_accounts_status_chk CHECK (status IN (
        'DISCONNECTED', 'CONNECTED', 'ERROR', 'AUTH_REQUIRED', 'SYNCING', 'DISABLED'
    )),
    CONSTRAINT mail_accounts_sync_status_chk CHECK (
        last_sync_status IS NULL OR last_sync_status IN ('SUCCESS', 'PARTIAL', 'FAILED')
    ),
    -- An IMAP account without host/username is unusable and must not be reported as connected.
    CONSTRAINT mail_accounts_imap_chk CHECK (
        provider <> 'IMAP' OR (imap_host IS NOT NULL AND imap_username IS NOT NULL)
    )
);

CREATE UNIQUE INDEX mail_accounts_tenant_email_key ON mail_accounts (tenant_id, email_address)
    WHERE deleted_at IS NULL;
CREATE INDEX mail_accounts_sync_idx ON mail_accounts (status, last_sync_at)
    WHERE is_active AND deleted_at IS NULL;

CREATE TRIGGER mail_accounts_set_updated_at BEFORE UPDATE ON mail_accounts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- mail_messages
-- -------------------------------------------------------------------------------------
CREATE TABLE mail_messages (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    mail_account_id     uuid REFERENCES mail_accounts (id) ON DELETE CASCADE,
    direction           varchar(20) NOT NULL DEFAULT 'INBOUND',
    external_id         varchar(500),           -- RFC 5322 Message-ID
    thread_id           varchar(500),
    in_reply_to         varchar(500),
    reference_ids       jsonb NOT NULL DEFAULT '[]'::jsonb,
    subject             varchar(1000),
    from_address        citext,
    from_name           varchar(300),
    to_addresses        jsonb NOT NULL DEFAULT '[]'::jsonb,
    cc_addresses        jsonb NOT NULL DEFAULT '[]'::jsonb,
    bcc_addresses       jsonb NOT NULL DEFAULT '[]'::jsonb,
    reply_to            citext,
    sent_at             timestamptz,
    received_at         timestamptz,
    body_text           text,
    body_html           text,
    has_attachments     boolean NOT NULL DEFAULT false,
    attachment_count    integer NOT NULL DEFAULT 0,
    category            varchar(30) NOT NULL DEFAULT 'OTHER',
    category_confidence numeric(5, 4),
    is_suspicious       boolean NOT NULL DEFAULT false,
    -- Original raw message preserved untouched for evidentiary purposes (§7.2).
    raw_blob_id         uuid REFERENCES document_blobs (id) ON DELETE SET NULL,
    case_id             uuid REFERENCES cases (id) ON DELETE SET NULL,
    contact_id          uuid REFERENCES contacts (id) ON DELETE SET NULL,
    processing_status   varchar(20) NOT NULL DEFAULT 'RECEIVED',
    processed_at        timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT mail_messages_direction_chk CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT mail_messages_category_chk CHECK (category IN (
        'INCOMING_DOCUMENTS', 'INVOICES', 'CONTRACTS', 'NOTIFICATIONS', 'RESPONSES',
        'ADVERTISEMENT', 'SUSPICIOUS', 'PERSONAL', 'OTHER'
    )),
    CONSTRAINT mail_messages_status_chk CHECK (processing_status IN (
        'RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'IGNORED'
    )),
    CONSTRAINT mail_messages_attachment_count_chk CHECK (attachment_count >= 0)
);

CREATE UNIQUE INDEX mail_messages_external_key
    ON mail_messages (tenant_id, mail_account_id, external_id)
    WHERE external_id IS NOT NULL;
CREATE INDEX mail_messages_account_idx ON mail_messages (mail_account_id, received_at DESC);
CREATE INDEX mail_messages_tenant_idx ON mail_messages (tenant_id, received_at DESC);
CREATE INDEX mail_messages_thread_idx ON mail_messages (tenant_id, thread_id);
CREATE INDEX mail_messages_category_idx ON mail_messages (tenant_id, category);

CREATE TRIGGER mail_messages_set_updated_at BEFORE UPDATE ON mail_messages
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Now that mail_messages exists, complete the document_sources link from V3.
ALTER TABLE document_sources
    ADD CONSTRAINT document_sources_mail_message_fk
    FOREIGN KEY (mail_message_id) REFERENCES mail_messages (id) ON DELETE SET NULL;

CREATE INDEX document_sources_mail_idx ON document_sources (mail_message_id)
    WHERE mail_message_id IS NOT NULL;

CREATE TABLE mail_attachments (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    mail_message_id uuid NOT NULL REFERENCES mail_messages (id) ON DELETE CASCADE,
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    filename        varchar(400) NOT NULL,
    mime_type       varchar(150) NOT NULL,
    size_bytes      bigint NOT NULL,
    content_hash    char(64),
    blob_id         uuid REFERENCES document_blobs (id) ON DELETE SET NULL,
    document_id     uuid REFERENCES documents (id) ON DELETE SET NULL,
    is_inline       boolean NOT NULL DEFAULT false,
    content_id      varchar(300),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT mail_attachments_size_chk CHECK (size_bytes >= 0)
);

CREATE INDEX mail_attachments_message_idx ON mail_attachments (mail_message_id);
CREATE INDEX mail_attachments_document_idx ON mail_attachments (document_id);

-- -------------------------------------------------------------------------------------
-- outbound_messages — every message the system prepares or sends (§15.3)
-- -------------------------------------------------------------------------------------
CREATE TABLE outbound_messages (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    case_id             uuid REFERENCES cases (id) ON DELETE SET NULL,
    template_id         uuid,
    generated_document_id uuid,
    mail_account_id     uuid REFERENCES mail_accounts (id) ON DELETE SET NULL,
    approval_id         uuid REFERENCES approvals (id) ON DELETE SET NULL,
    channel             varchar(20) NOT NULL DEFAULT 'EMAIL',
    status              varchar(30) NOT NULL DEFAULT 'DRAFT',
    subject             varchar(1000),
    body_text           text,
    body_html           text,
    from_address        citext,
    to_addresses        jsonb NOT NULL DEFAULT '[]'::jsonb,
    cc_addresses        jsonb NOT NULL DEFAULT '[]'::jsonb,
    bcc_addresses       jsonb NOT NULL DEFAULT '[]'::jsonb,
    attachment_document_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    draft_version       integer NOT NULL DEFAULT 1,
    -- The final, immutable snapshot of what was actually sent (§15.3).
    final_subject       varchar(1000),
    final_body_text     text,
    final_body_html     text,
    final_blob_id       uuid REFERENCES document_blobs (id) ON DELETE SET NULL,
    approved_by         uuid REFERENCES users (id) ON DELETE SET NULL,
    approved_at         timestamptz,
    queued_at           timestamptz,
    sent_at             timestamptz,
    provider_message_id varchar(500),
    error_code          varchar(80),
    error_message       text,
    attempt_count       integer NOT NULL DEFAULT 0,
    idempotency_key     varchar(200),
    created_by          uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    deleted_at          timestamptz,
    CONSTRAINT outbound_messages_channel_chk CHECK (channel IN (
        'EMAIL', 'LETTER', 'PORTAL', 'FAX', 'MANUAL_EXPORT'
    )),
    CONSTRAINT outbound_messages_status_chk CHECK (status IN (
        'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'QUEUED', 'SENT',
        'DELIVERED', 'BOUNCED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT outbound_messages_attempt_chk CHECK (attempt_count >= 0),
    -- Sending requires an explicit approval record for significant messages (§15.3, §32).
    CONSTRAINT outbound_messages_sent_chk CHECK (
        status NOT IN ('SENT', 'DELIVERED') OR (sent_at IS NOT NULL AND approved_by IS NOT NULL)
    )
);

CREATE INDEX outbound_messages_tenant_status_idx ON outbound_messages (tenant_id, status)
    WHERE deleted_at IS NULL;
CREATE INDEX outbound_messages_case_idx ON outbound_messages (case_id);
CREATE INDEX outbound_messages_queue_idx ON outbound_messages (status, queued_at)
    WHERE status IN ('QUEUED', 'APPROVED');
CREATE UNIQUE INDEX outbound_messages_idempotency_key
    ON outbound_messages (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TRIGGER outbound_messages_set_updated_at BEFORE UPDATE ON outbound_messages
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE message_delivery_events (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    outbound_message_id uuid NOT NULL REFERENCES outbound_messages (id) ON DELETE CASCADE,
    tenant_id           uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    event_type          varchar(30) NOT NULL,
    provider_event_id   varchar(300),
    -- Explicit flag: does this event prove delivery to the recipient (§15.3)?
    proves_delivery     boolean NOT NULL DEFAULT false,
    occurred_at         timestamptz NOT NULL DEFAULT now(),
    details             jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT message_delivery_events_type_chk CHECK (event_type IN (
        'QUEUED', 'SUBMITTED', 'ACCEPTED_BY_PROVIDER', 'DELIVERED', 'READ',
        'BOUNCED', 'COMPLAINED', 'REPLIED', 'FAILED', 'RETRY_SCHEDULED'
    )),
    -- Only genuine delivery events may claim to prove delivery.
    CONSTRAINT message_delivery_events_proof_chk CHECK (
        NOT proves_delivery OR event_type IN ('DELIVERED', 'READ', 'REPLIED')
    )
);

CREATE INDEX message_delivery_events_message_idx ON message_delivery_events (outbound_message_id, occurred_at DESC);

-- -------------------------------------------------------------------------------------
-- document_templates / template_versions / template_fields / generated_documents
-- -------------------------------------------------------------------------------------
CREATE TABLE document_templates (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid REFERENCES tenants (id) ON DELETE CASCADE,   -- NULL = system template
    code             varchar(80) NOT NULL,
    name             jsonb NOT NULL,
    description      jsonb NOT NULL DEFAULT '{}'::jsonb,
    document_type_id uuid REFERENCES document_types (id) ON DELETE SET NULL,
    category         varchar(40) NOT NULL DEFAULT 'LETTER',
    -- Suggests which dynamic field sources are available (case, contract, invoice, ...).
    field_scope      jsonb NOT NULL DEFAULT '[]'::jsonb,
    is_active        boolean NOT NULL DEFAULT true,
    current_version  integer,
    created_by       uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_templates_category_chk CHECK (category IN (
        'LETTER', 'APPLICATION', 'OBJECTION', 'TERMINATION', 'REMINDER',
        'COMPLAINT', 'INVOICE', 'CONTRACT', 'INTERNAL', 'OTHER'
    ))
);

CREATE UNIQUE INDEX document_templates_code_key
    ON document_templates (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), code);

CREATE TRIGGER document_templates_set_updated_at BEFORE UPDATE ON document_templates
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE template_versions (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id    uuid NOT NULL REFERENCES document_templates (id) ON DELETE CASCADE,
    tenant_id      uuid REFERENCES tenants (id) ON DELETE CASCADE,
    version        integer NOT NULL,
    status         varchar(20) NOT NULL DEFAULT 'DRAFT',
    language       varchar(10) NOT NULL DEFAULT 'de',
    -- Rich text document model (editor JSON) plus the rendered HTML used for output.
    body_model     jsonb NOT NULL DEFAULT '{}'::jsonb,
    body_html      text NOT NULL DEFAULT '',
    header_html    text,
    footer_html    text,
    page_settings  jsonb NOT NULL DEFAULT '{}'::jsonb,
    subject_template varchar(500),
    change_note    text,
    published_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    published_at   timestamptz,
    created_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT template_versions_uq UNIQUE (template_id, version, language),
    CONSTRAINT template_versions_status_chk CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT template_versions_version_chk CHECK (version >= 1)
);

CREATE INDEX template_versions_template_idx ON template_versions (template_id, version DESC);

CREATE TABLE template_fields (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id      uuid NOT NULL REFERENCES document_templates (id) ON DELETE CASCADE,
    field_key        varchar(120) NOT NULL,        -- e.g. "Client.Name"
    label            jsonb NOT NULL,
    source_entity    varchar(40) NOT NULL,
    source_path      varchar(200) NOT NULL,
    value_type       varchar(30) NOT NULL DEFAULT 'TEXT',
    is_required      boolean NOT NULL DEFAULT false,
    default_value    varchar(500),
    sample_value     varchar(500),
    sort_order       integer NOT NULL DEFAULT 100,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT template_fields_uq UNIQUE (template_id, field_key),
    CONSTRAINT template_fields_entity_chk CHECK (source_entity IN (
        'USER', 'ORGANIZATION', 'CONTACT', 'CASE', 'CONTRACT', 'INVOICE', 'PAYMENT', 'TASK', 'CUSTOM'
    ))
);

CREATE TABLE generated_documents (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    template_id         uuid REFERENCES document_templates (id) ON DELETE SET NULL,
    template_version_id uuid REFERENCES template_versions (id) ON DELETE SET NULL,
    case_id             uuid REFERENCES cases (id) ON DELETE SET NULL,
    subject_type        varchar(40),
    subject_id          uuid,
    title               varchar(300) NOT NULL,
    output_format       varchar(20) NOT NULL DEFAULT 'PDF',
    status              varchar(20) NOT NULL DEFAULT 'DRAFT',
    body_html           text,
    rendered_blob_id    uuid REFERENCES document_blobs (id) ON DELETE SET NULL,
    rendered_document_id uuid REFERENCES documents (id) ON DELETE SET NULL,
    -- Values actually substituted, so a generated document stays explainable (§32).
    field_values        jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- Must be empty for a successful generation: unknown variables block the output
    -- instead of being silently replaced by empty strings (§15.2).
    unresolved_fields   jsonb NOT NULL DEFAULT '[]'::jsonb,
    generated_by        uuid REFERENCES users (id) ON DELETE SET NULL,
    generated_at        timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT generated_documents_format_chk CHECK (output_format IN ('PDF', 'DOCX', 'HTML', 'TXT')),
    CONSTRAINT generated_documents_status_chk CHECK (status IN (
        'DRAFT', 'GENERATED', 'BLOCKED_UNRESOLVED_FIELDS', 'FAILED'
    )),
    CONSTRAINT generated_documents_blocked_chk CHECK (
        status <> 'BLOCKED_UNRESOLVED_FIELDS' OR jsonb_array_length(unresolved_fields) > 0
    )
);

CREATE INDEX generated_documents_case_idx ON generated_documents (case_id);
CREATE INDEX generated_documents_tenant_idx ON generated_documents (tenant_id, created_at DESC);

CREATE TRIGGER generated_documents_set_updated_at BEFORE UPDATE ON generated_documents
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE outbound_messages
    ADD CONSTRAINT outbound_messages_template_fk
    FOREIGN KEY (template_id) REFERENCES document_templates (id) ON DELETE SET NULL,
    ADD CONSTRAINT outbound_messages_generated_document_fk
    FOREIGN KEY (generated_document_id) REFERENCES generated_documents (id) ON DELETE SET NULL;
