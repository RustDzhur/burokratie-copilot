-- =====================================================================================
-- V56 - second pass over the German evaluation set (task RD-09)
--
-- * A German invoice often puts its date under the plain label "Datum"; it is the last label tried, so a specific label
--   (Rechnungsdatum, Leistungsdatum) always wins.
-- * "Bescheid über die Beitragsfestsetzung" of a health insurer was a tie between the generic authority decision and the
--   insurer's contribution letter; the word that names the kind of letter now decides.
-- =====================================================================================

UPDATE document_type_fields f
SET extraction_hint = f.extraction_hint || '|Datum'
FROM document_types t
WHERE t.id = f.document_type_id AND t.code = 'rechnung' AND f.field_key = 'Rechnungsdatum'
  AND f.extraction_hint NOT LIKE '%|Datum';

UPDATE document_type_rules r SET weight = 0.900
FROM document_types t
WHERE t.id = r.document_type_id AND t.code = 'krankenkasse_beitragsanpassung' AND r.pattern = 'beitragsfestsetzung'
  AND r.tenant_id IS NULL;
