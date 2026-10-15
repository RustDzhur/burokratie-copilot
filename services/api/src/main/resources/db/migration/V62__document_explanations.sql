-- =====================================================================================
-- V62 - explanation and translation of a document (tasks TR-01, TR-03)
--
-- What a model says about a document is kept apart from the document: the pages stay as they were read, and an explanation
-- or a translation is a separate, labelled row that names the run which wrote it and the text it was written from. Several
-- rows per document are kept (a newer one does not erase an older one); the reader is shown the latest.
--   mode EXPLAIN    the ten blocks, every claim typed (SOURCE_FACT / AI_INTERPRETATION / USER_CONFIRMED / UNKNOWN /
--                   REQUIRES_EXPERT_REVIEW) and, for a fact, tied to a quoted fragment that exists in the document;
--   mode TRANSLATE  the text in another language, or in simple language; always marked as a machine translation.
-- =====================================================================================

CREATE TABLE document_explanations (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    document_id         uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    mode                varchar(12) NOT NULL,
    language            varchar(5) NOT NULL,
    simple_language     boolean NOT NULL DEFAULT false,
    content             jsonb NOT NULL,
    source_text_hash    varchar(64) NOT NULL,
    instruction_version varchar(40) NOT NULL,
    ai_model_run_id     uuid,
    created_by          uuid REFERENCES users (id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_explanations_mode_chk CHECK (mode IN ('EXPLAIN', 'TRANSLATE')),
    CONSTRAINT document_explanations_lang_chk CHECK (language IN ('de', 'en', 'ru'))
);

CREATE INDEX document_explanations_doc_idx ON document_explanations (tenant_id, document_id, mode, language, created_at DESC);

ALTER TABLE document_explanations ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_explanations FORCE ROW LEVEL SECURITY;
CREATE POLICY document_explanations_tenant_isolation ON document_explanations
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

INSERT INTO system_settings (tenant_id, setting_key, category, value, value_type, description, requires_verification) VALUES
(NULL, 'documents.explain.enabled', 'AI', 'true', 'BOOLEAN',
 '{"de":"KI-Erklärung und Übersetzung von Dokumenten anbieten","en":"Offer AI explanation and translation of documents","ru":"Предлагать ИИ-объяснение и перевод документов"}', false)
ON CONFLICT DO NOTHING;
