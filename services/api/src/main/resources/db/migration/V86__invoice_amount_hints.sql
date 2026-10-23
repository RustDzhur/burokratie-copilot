-- =============================================================================
--  V86 - the words German invoices actually use for the amount
-- =============================================================================
--  Found on the live stand: an invoice reading "Betrag: 1.190,00 EUR" produced no amount at all, because the
--  hints for Brutto were Bruttobetrag, Gesamtbetrag, Rechnungsbetrag, Zu zahlender Betrag, Endbetrag and
--  Gesamtsumme. "Betrag" - the shortest and most common word on the page - was missing.
--
--  The DATEV export reads Brutto first, so a voucher with an unread amount leaves the office with a line that has
--  everything except the money. These are additions, not replacements: the existing hints stay.
-- =============================================================================

UPDATE document_type_fields f
   SET extraction_hint = f.extraction_hint || '|Betrag|Gesamt|Summe|Total|Offener Betrag|Zahlbetrag'
  FROM document_types t
 WHERE t.id = f.document_type_id
   AND t.code = 'rechnung'
   AND f.field_key = 'Brutto';

UPDATE document_type_fields f
   SET extraction_hint = f.extraction_hint || '|Netto|Nettobetrag'
  FROM document_types t
 WHERE t.id = f.document_type_id
   AND t.code = 'rechnung'
   AND f.field_key = 'Netto';
