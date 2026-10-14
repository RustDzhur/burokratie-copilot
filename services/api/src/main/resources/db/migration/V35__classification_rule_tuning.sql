-- =====================================================================================
-- V35 - a contract is not a termination notice
--
-- Found by the first end-to-end test of the whole chain: a contract that mentions its notice period
-- ("Kündigungsfrist: 3 Monate") was typed as a termination notice with 98 % confidence, because the two
-- generic words "kündigung" and "kündigungsfrist" scored 0.9 and 0.8 on their own and every contract
-- contains both. A contract typed as a termination notice gets the wrong task and no contract card.
--
-- The generic words keep a small weight (they still support the type); what identifies a notice is the
-- act of terminating, so those phrases carry the weight. A contract is identified by what makes it one:
-- its parties, its subject, its start and its term.
-- =====================================================================================

UPDATE document_type_rules r SET weight = 0.350
FROM document_types t
WHERE t.id = r.document_type_id AND t.code = 'kuendigung' AND r.tenant_id IS NULL
  AND r.pattern = 'kündigung';

UPDATE document_type_rules r SET weight = 0.200
FROM document_types t
WHERE t.id = r.document_type_id AND t.code = 'kuendigung' AND r.tenant_id IS NULL
  AND r.pattern = 'kündigungsfrist';

INSERT INTO document_type_rules (document_type_id, tenant_id, rule_type, pattern, weight)
SELECT t.id, NULL, 'KEYWORD', r.pattern, r.weight
FROM (VALUES
 ('kuendigung', 'hiermit kündigen', 0.950), ('kuendigung', 'kündigen wir', 0.900),
 ('kuendigung', 'kündige ich', 0.900), ('kuendigung', 'kündigung des vertrages', 0.850),
 ('kuendigung', 'kündigung ihres vertrages', 0.850), ('kuendigung', 'außerordentliche kündigung', 0.850),
 ('vertrag', 'vertragsbeginn', 0.700), ('vertrag', 'vertragspartner', 0.600),
 ('vertrag', 'vertragsnummer', 0.400), ('vertrag', 'vertrag über', 0.500),
 ('vertrag', 'laufzeit des vertrages', 0.700), ('vertrag', 'vertragslaufzeit', 0.700),
 ('newsletter', 'newsletter abbestellen', 0.800), ('newsletter', 'abbestellen', 0.500),
 ('newsletter', 'im browser ansehen', 0.600)
) AS r(code, pattern, weight)
JOIN document_types t ON t.code = r.code
ON CONFLICT DO NOTHING;
