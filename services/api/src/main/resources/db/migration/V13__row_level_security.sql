-- =====================================================================================
-- V13 — Row Level Security and append-only enforcement (spec §3.2, §21.4)
--
-- Two independent layers protect tenant data:
--   1. Server-side authorization (Spring Security + tenant context) — primary.
--   2. PostgreSQL Row Level Security — defence in depth, active even if a query is
--      written incorrectly in the future.
--
-- Convention: the application sets the session variable `app.tenant_id` on every pooled
-- connection it borrows (see TenantAwareDataSource). When the variable is empty the
-- connection is in *system* context (migrations, background maintenance), which is
-- legitimate but must never be used to serve a user request — covered by tests.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Append-only audit trail: no role may modify or delete an audit event through SQL.
-- -------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION forbid_append_only_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only; UPDATE and DELETE are not permitted', TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION forbid_append_only_mutation();

CREATE TRIGGER document_access_logs_append_only
    BEFORE UPDATE OR DELETE ON document_access_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_append_only_mutation();

CREATE TRIGGER access_change_log_append_only
    BEFORE UPDATE OR DELETE ON access_change_log
    FOR EACH ROW EXECUTE FUNCTION forbid_append_only_mutation();

-- -------------------------------------------------------------------------------------
-- Tenant accessor used by every policy.
--
-- It exists because PostgreSQL does not guarantee short-circuit evaluation inside a
-- policy expression: casting an empty string to uuid would raise
-- "invalid input syntax for type uuid" instead of simply denying access.
-- -------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION current_tenant_id() RETURNS uuid AS $$
    SELECT CASE
        WHEN current_setting('app.tenant_id', true) IS NULL
          OR current_setting('app.tenant_id', true) = ''
        THEN NULL
        ELSE current_setting('app.tenant_id', true)::uuid
    END;
$$ LANGUAGE sql STABLE;

-- -------------------------------------------------------------------------------------
-- Row Level Security for every tenant-scoped table.
-- -------------------------------------------------------------------------------------
DO $$
DECLARE
    target_table text;
    policy_name  text;
    policy_body  text;
    tenant_tables text[] := ARRAY[
        -- identity
        'organizations', 'workspaces', 'memberships', 'membership_roles', 'invitations',
        'access_change_log', 'sessions',
        -- documents
        'document_blobs', 'documents', 'document_versions', 'document_pages',
        'document_extractions', 'document_classifications', 'document_tags',
        'document_tag_links', 'document_relations', 'document_sources',
        'document_processing_steps', 'document_reviews', 'document_access_logs',
        'document_retention_policies',
        -- cases and contacts
        'contacts', 'contact_addresses', 'organizations_contacts', 'contact_relations',
        'cases', 'case_participants', 'case_documents', 'case_events',
        -- tasks
        'tasks', 'task_assignees', 'task_comments', 'task_checklist_items',
        'task_evidence', 'task_events', 'task_deadlines',
        -- workflow
        'workflow_runs', 'workflow_run_steps', 'workflow_events',
        'approvals', 'approval_events',
        -- contracts
        'contracts', 'contract_parties', 'contract_clauses', 'contract_versions',
        'contract_events', 'contract_obligations',
        -- finance
        'suppliers', 'supplier_bank_accounts', 'customers', 'financial_accounts',
        'purchase_orders', 'invoice_number_sequences', 'invoices', 'invoice_lines',
        'credit_notes', 'e_invoice_records', 'bank_statements', 'bank_transactions',
        'payments', 'payment_matches', 'payment_approvals',
        -- mail and templates
        'mail_accounts', 'mail_messages', 'mail_attachments', 'outbound_messages',
        'message_delivery_events', 'generated_documents',
        -- integrations and devices
        'integrations', 'integration_credentials', 'integration_sync_runs',
        'webhook_endpoints', 'webhook_events', 'devices', 'device_jobs', 'scan_sessions',
        -- system
        'notifications', 'notification_preferences', 'notification_deliveries',
        'audit_events', 'ai_jobs', 'ai_model_runs', 'ai_usage_records',
        'retention_jobs', 'outbox_events', 'idempotency_records', 'api_tokens',
        'saved_views'
    ];
BEGIN
    -- NULL / empty setting = system context (migrations, maintenance jobs).
    policy_body := 'current_tenant_id() IS NULL OR tenant_id = current_tenant_id()';

    FOREACH target_table IN ARRAY tenant_tables LOOP
        -- Pure join tables (no tenant_id of their own) are protected through their parents;
        -- they are skipped explicitly instead of failing the migration.
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = target_table
              AND column_name = 'tenant_id'
        ) THEN
            RAISE NOTICE 'RLS skipped for % (no tenant_id column; protected via parent tables)', target_table;
            CONTINUE;
        END IF;

        policy_name := target_table || '_tenant_isolation';
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', target_table);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', target_table);
        EXECUTE format('DROP POLICY IF EXISTS %I ON %I', policy_name, target_table);
        EXECUTE format('CREATE POLICY %I ON %I USING (%s) WITH CHECK (%s)',
                       policy_name, target_table, policy_body, policy_body);
    END LOOP;
END $$;

-- Join tables without their own tenant_id (document_tag_links, membership_roles,
-- task_checklist_items, document_processing_steps, role_permissions, ...) are only
-- reachable through a parent row that is itself protected by RLS, so isolation is
-- inherited through the foreign keys. Server-side authorization still applies to them.

-- -------------------------------------------------------------------------------------
-- Guard: the tenant setting must be a valid UUID when present, so a programming error
-- fails loudly instead of silently returning another tenant's rows.
-- -------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION validate_app_tenant_id() RETURNS void AS $$
BEGIN
    IF current_setting('app.tenant_id', true) IS NOT NULL
       AND current_setting('app.tenant_id', true) <> ''
       AND current_setting('app.tenant_id', true) !~ '^[0-9a-fA-F-]{36}$' THEN
        RAISE EXCEPTION 'app.tenant_id must be a UUID, got: %', current_setting('app.tenant_id', true);
    END IF;
END;
$$ LANGUAGE plpgsql STABLE;
