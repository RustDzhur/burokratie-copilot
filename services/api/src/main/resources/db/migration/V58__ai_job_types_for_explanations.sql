-- =====================================================================================
-- V58 - more kinds of AI jobs (tasks RD-10, CA-03, TR-01)
--
-- The job types are a closed set so that an AI job of an unknown kind cannot exist. Three kinds join it:
--   EXPLAIN_FINDING   the explanation of a Radar finding; it never changes the severity;
--   PLAN              a proposal of a case plan, which the platform validates before anything is saved;
--   EXPLAIN_DOCUMENT  the explanation of a document in simple language, for the Translator.
-- (TRANSLATE already exists.)
-- =====================================================================================

ALTER TABLE ai_jobs DROP CONSTRAINT ai_jobs_type_chk;
ALTER TABLE ai_jobs ADD CONSTRAINT ai_jobs_type_chk CHECK (job_type IN (
    'CLASSIFY', 'EXTRACT', 'SUMMARIZE', 'DRAFT', 'EMBED', 'CHAT', 'TRANSLATE', 'QUALITY_CHECK',
    'EXPLAIN_FINDING', 'PLAN', 'EXPLAIN_DOCUMENT'
));

INSERT INTO system_settings (tenant_id, setting_key, category, value, value_type, description, requires_verification) VALUES
(NULL, 'radar.explain.enabled', 'AI', 'true', 'BOOLEAN',
 '{"de":"KI-Erklärung zu Radar-Befunden anbieten (die Schwere ändert sie nie)","en":"Offer AI explanations of Radar findings (they never change the severity)","ru":"Предлагать ИИ-объяснение находок Радара (серьёзность оно не меняет)"}', false)
ON CONFLICT DO NOTHING;
