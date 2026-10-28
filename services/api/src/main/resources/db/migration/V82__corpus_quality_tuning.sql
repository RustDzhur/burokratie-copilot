-- =====================================================================================
-- V82 - tuning found by the synthetic scan corpus (scripts/quality/run-corpus.sh)
--
-- The image corpus (133 generated documents: clean scans, photos, blur/dark/overexposure,
-- rotated and curved pages, foreign objects, crops, multi-page letters, handwriting,
-- stamps and Russian translations) was run through the real pipeline with Tesseract.
-- The baseline report is docs/quality/DOCUMENT_QUALITY.md. Every change below is
-- minimal and evidence-based; no API or schema contract changes.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 1. "Zahlungsbestätigung" is a confirmation, not a payment record
--
-- A document whose title is literally "Zahlungsbestätigung" was typed as "Zahlungsbeleg"
-- because that type carried the stronger 0.990 rule for the same word and the confirmation
-- only 0.900. The title names the kind of letter; the payment record is the weaker reading.
-- -------------------------------------------------------------------------------------
UPDATE document_type_rules r SET weight = 0.400
FROM document_types t
WHERE t.id = r.document_type_id AND t.code = 'zahlungsbeleg' AND r.tenant_id IS NULL
  AND r.pattern = 'zahlungsbestätigung';

UPDATE document_type_rules r SET weight = 0.990
FROM document_types t
WHERE t.id = r.document_type_id AND t.code = 'zahlungsbestaetigung' AND r.tenant_id IS NULL
  AND r.pattern = 'zahlungsbestätigung';

-- -------------------------------------------------------------------------------------
-- 2. An objection must beat the decision it objects to
--
-- "Widerspruch gegen den Bescheid" matched the authority decision much better than the
-- objection: "bescheid" + "aktenzeichen" combined to 0.996 while "widerspruch gegen" alone
-- gave 0.950. The phrase that names the act of objecting is the stronger evidence.
-- The existing regex was too narrow ("... Widerspruch ... ein" within 20 characters); a
-- real sentence often has the reason between the verb and "ein".
-- -------------------------------------------------------------------------------------
INSERT INTO document_type_rules (document_type_id, tenant_id, rule_type, pattern, weight, description)
SELECT t.id, NULL, 'KEYWORD', v.pattern, v.weight, jsonb_build_object('de', v.description)
FROM document_types t
JOIN (VALUES
    ('widerspruch', 'widerspruch gegen den bescheid', 0.990, 'Widerspruch gegen einen Bescheid'),
    ('widerspruch', 'widerspruch gegen diesen bescheid', 0.990, 'Widerspruch gegen diesen Bescheid'),
    ('widerspruch', 'widerspruch einlegen', 0.700, 'Widerspruch einlegen')
) AS v(code, pattern, weight, description) ON v.code = t.code
ON CONFLICT DO NOTHING;

UPDATE document_type_rules r SET weight = 0.700
FROM document_types t
WHERE t.id = r.document_type_id AND t.code = 'behoerdenbescheid' AND r.tenant_id IS NULL
  AND r.pattern = 'widerspruch';

-- -------------------------------------------------------------------------------------
-- 3. OCR drops the umlaut dots, not the letter
--
-- Tesseract read "Arbeitsunfähigkeitsbescheinigung" as "Arbeitsunfahigkeitsbescheinigung"
-- (u without dots). The folded rule "arbeitsunfähigkeitsbescheinigung" becomes
-- "arbeitsunfaehigkeitsbescheinigung" and no longer matches, while the medical-document
-- rules ("patient", "diagnose", "dr. med.") still do. The variant without the umlaut is not
-- a different document, it is the same word after a known OCR error.
-- -------------------------------------------------------------------------------------
INSERT INTO document_type_rules (document_type_id, tenant_id, rule_type, pattern, weight, description)
SELECT t.id, NULL, 'KEYWORD', v.pattern, v.weight, jsonb_build_object('de', v.description)
FROM document_types t
JOIN (VALUES
    ('krankmeldung', 'arbeitsunfahigkeitsbescheinigung', 0.950, 'AU-Bescheinigung nach OCR-Verlust der Umlautpunkte'),
    ('krankmeldung', 'arbeitsunfahig', 0.850, 'arbeitsunfähig nach OCR-Verlust der Umlautpunkte'),
    ('krankmeldung', 'krankmeldung', 0.900, 'Krankmeldung')
) AS v(code, pattern, weight, description) ON v.code = t.code
ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------------------------
-- 4. Field labels the documents actually use
--
-- The generic field set is shared by the types that have no catalogue of their own.
-- These labels were printed by the corpus and looked up by the extractor, but were missing
-- from the hint list, so a value that was plainly on the page was reported as not found.
-- The additions are labels, not new extraction semantics.
-- -------------------------------------------------------------------------------------
-- Angebotsnummer before Kundennummer: a quote has both, and the quote number is the one
-- that identifies the document. Belegnummer likewise belongs before the customer number.
UPDATE document_type_fields f
SET extraction_hint = replace(f.extraction_hint, '|Kundennummer',
                              '|Angebotsnummer|Belegnummer|Fallnummer|Kundennummer')
WHERE f.field_key = 'Aktenzeichen'
  AND f.extraction_hint IS NOT NULL
  AND f.extraction_hint LIKE '%|Kundennummer%'
  AND f.extraction_hint NOT LIKE '%Angebotsnummer%';

UPDATE document_type_fields f
SET extraction_hint = f.extraction_hint || '|Angebotsnummer|Belegnummer|Fallnummer'
WHERE f.field_key = 'Aktenzeichen'
  AND f.extraction_hint IS NOT NULL
  AND f.extraction_hint NOT LIKE '%Angebotsnummer%';

UPDATE document_type_fields f
SET extraction_hint = f.extraction_hint || '|Nettobetrag|Auszahlungsbetrag|Gehalt|Kosten|Miete|Neues Entgelt|Festgesetzte Steuer|Gutschrift|Nachzahlung'
WHERE f.field_key = 'Betrag'
  AND f.extraction_hint IS NOT NULL
  AND f.extraction_hint NOT LIKE '%Nettobetrag%';

UPDATE document_type_fields f
SET extraction_hint = f.extraction_hint || '|Schreibendatum|Rechnungsdatum|Zeugnisdatum|Bescheiddatum|Datum des Bescheides'
WHERE f.field_key = 'Schreibendatum'
  AND f.extraction_hint IS NOT NULL
  AND f.extraction_hint NOT LIKE '%Schreibendatum%';

UPDATE document_type_fields f
SET extraction_hint = f.extraction_hint || '|Nachzahlung|Guthaben'
WHERE f.field_key = 'Saldo'
  AND f.extraction_hint IS NOT NULL
  AND f.extraction_hint NOT LIKE '%Nachzahlung%';

UPDATE document_type_fields f
SET extraction_hint = f.extraction_hint || '|Arbeitsunfähig bis|Arbeitsunfaehig bis'
WHERE f.field_key = 'Frist'
  AND f.extraction_hint IS NOT NULL
  AND f.extraction_hint NOT LIKE '%Arbeitsunfähig bis%';
