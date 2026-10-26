-- Quality stand follow-up (corpus run, seed 20261026): the Kita fee notice states its amount as
-- "Kita-Beitrag". Add that label to the amount hints so the field is found without a new document type.
UPDATE document_type_fields f
SET extraction_hint = f.extraction_hint || '|Kita-Beitrag'
WHERE f.field_key = 'Betrag'
  AND f.extraction_hint IS NOT NULL
  AND f.extraction_hint NOT LIKE '%Kita-Beitrag%';
