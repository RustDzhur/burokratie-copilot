-- =====================================================================================
-- V15 — Tenant indexes for high-volume tables
--
-- Spec §19 requires indexes "по tenant и основным фильтрам". Row Level Security adds a
-- `tenant_id = …` predicate to every query, so an index on tenant_id keeps the isolation
-- check cheap for large tables. Low-volume configuration tables (tax_rates, case_types,
-- feature_flags, …) are intentionally left without an extra index: they are read rarely
-- and their unique keys are expression indexes on coalesce(tenant_id, …).
-- =====================================================================================

DO $$
DECLARE
    target_table text;
    index_name   text;
    indexed_tables text[] := ARRAY[
        'approval_events', 'case_documents', 'case_participants', 'contact_addresses',
        'contact_relations', 'contract_clauses', 'contract_events', 'contract_parties',
        'contract_versions', 'document_relations', 'document_versions', 'e_invoice_records',
        'integration_credentials', 'integration_sync_runs', 'invoice_lines',
        'mail_attachments', 'message_delivery_events', 'notification_deliveries',
        'notification_preferences', 'notifications', 'organizations_contacts',
        'outbox_events', 'payment_matches', 'retention_jobs', 'sessions',
        'system_setting_history', 'task_assignees', 'task_comments', 'task_evidence',
        'template_versions'
    ];
BEGIN
    FOREACH target_table IN ARRAY indexed_tables LOOP
        index_name := target_table || '_tenant_idx';
        EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I (tenant_id)', index_name, target_table);
    END LOOP;
END $$;
