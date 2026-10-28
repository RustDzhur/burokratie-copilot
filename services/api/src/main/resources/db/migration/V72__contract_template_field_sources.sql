-- =====================================================================================
-- V72 — Contract document templates (§13, §15)
--
-- A contract template draws its values from the contract itself, its parties, the
-- workspace and the customer contact. Those are four new field sources, named the way
-- a German template addresses them: {{Vertrag.Title}}, {{Partei.Name}}, {{Werk.Name}},
-- {{Kunde.Name}}. The sources existed in the renderer but not in the schema, so a
-- contract template could not be defined at all.
-- =====================================================================================

ALTER TABLE template_fields DROP CONSTRAINT template_fields_entity_chk;

ALTER TABLE template_fields ADD CONSTRAINT template_fields_entity_chk CHECK (source_entity IN (
    'USER', 'ORGANIZATION', 'CONTACT', 'CASE', 'CONTRACT', 'INVOICE', 'PAYMENT', 'TASK', 'CUSTOM',
    'PARTEI', 'VERTRAG', 'WERK', 'KUNDE'
));
