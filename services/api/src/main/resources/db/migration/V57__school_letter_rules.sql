-- =====================================================================================
-- V57 - school letters (task RD-09, holdout batch 1)
--
-- The first holdout set of the German evaluation found a kind of letter without any rule: a primary school's notice to
-- parents was not recognised at all (no type, no suggestion). The words below are those such notices use. As with V55,
-- the weights are technical starting values.
-- =====================================================================================

INSERT INTO document_type_rules (document_type_id, tenant_id, rule_type, pattern, weight, description)
SELECT t.id, NULL, 'KEYWORD', v.pattern, v.weight, jsonb_build_object('de', v.description)
FROM document_types t
JOIN (VALUES
    ('schule', 'elternabend', 0.800, 'Einladung zum Elternabend'),
    ('schule', 'elterninformation', 0.800, 'Information an die Eltern'),
    ('schule', 'grundschule', 0.600, 'Absender ist eine Grundschule'),
    ('schule', 'schuljahr', 0.500, 'Schuljahr'),
    ('schule', 'schulkonferenz', 0.800, 'Schulkonferenz')
) AS v(code, pattern, weight, description) ON v.code = t.code
ON CONFLICT DO NOTHING;
