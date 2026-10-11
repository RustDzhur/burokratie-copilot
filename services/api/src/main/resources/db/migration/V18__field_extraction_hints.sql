-- =====================================================================================
-- V18 — Extraction hints for the document type fields (spec §9.4, §10)
--
-- V12 seeded what CAN be extracted from each document type (37 fields across rechnung, mahnung,
-- behoerdenbescheid, kontoauszug and vertrag) but not how to find it. This migration fills
-- extraction_hint.
--
-- Hint format, deliberately small and readable:
--
--     hint := label ( "|" label )*
--
-- The extractor looks for the first label that occurs in the page text, takes the rest of that
-- line, strips a leading separator, and then validates and normalises the result according to the
-- field's value_type (AMOUNT, DATE, IBAN, ...). Nothing here decides what is legally correct: a
-- hint says where a value is written, not what it means, and a value that fails its type check is
-- reported as not found rather than stored as a guess.
--
-- Hints are data, so a customer extends them without a release. Adding a variant of a label in
-- another language or from another sender is a configuration change.
-- =====================================================================================

UPDATE document_type_fields f
SET extraction_hint = v.hint
FROM (VALUES
    -- ---------------------------------------------------------------------------- Rechnung
    ('rechnung', 'Rechnungsnummer',
     'Rechnungsnummer|Rechnungs-Nr|Rechnungsnr|Rechnung Nr|Belegnummer|Invoice number'),
    ('rechnung', 'Rechnungsdatum',
     'Rechnungsdatum|Rechnungs-Datum|Ausstellungsdatum|Datum der Rechnung'),
    ('rechnung', 'Zahlungsziel',
     'Zahlbar bis|Zahlungsziel|Fällig am|Fälligkeit|Zu zahlen bis|zahlbar innerhalb'),
    ('rechnung', 'IBAN',
     'IBAN|Kontonummer für Überweisung|Bankverbindung'),
    ('rechnung', 'Bestellreferenz',
     'Bestellnummer|Bestellreferenz|Kundenreferenz|Ihre Bestellung|Referenz'),
    ('rechnung', 'Rechnungsempfaenger',
     'Rechnungsempfänger|Rechnung an|Empfänger'),
    ('rechnung', 'Leistungsdatum',
     'Leistungsdatum|Leistungszeitraum|Lieferdatum|Leistung vom'),
    ('rechnung', 'Netto',
     'Nettobetrag|Netto|Summe netto|Zwischensumme netto|Netto-Betrag'),
    ('rechnung', 'Umsatzsteuer',
     'Umsatzsteuer|MwSt|Mehrwertsteuer|USt|Umsatzsteuerbetrag'),
    ('rechnung', 'Brutto',
     'Bruttobetrag|Gesamtbetrag|Rechnungsbetrag|Zu zahlender Betrag|Endbetrag|Gesamtsumme'),
    ('rechnung', 'Waehrung',
     'Währung|Waehrung'),
    ('rechnung', 'Lieferant',
     'Lieferant|Rechnungssteller|Leistungserbringer|Auftragnehmer'),

    -- ---------------------------------------------------------------------------- Mahnung
    ('mahnung', 'Rechnungsnummer',
     'Rechnungsnummer|Rechnungs-Nr|Rechnung Nr|Belegnummer'),
    ('mahnung', 'Frist',
     'Zahlungsfrist|Frist|Zu zahlen bis|Zahlung bis|bis zum|Zahlbar bis'),
    ('mahnung', 'OffenerBetrag',
     'Offener Betrag|Offenstehender Betrag|Gesamtforderung|Rückstand|Offener Rechnungsbetrag'),
    ('mahnung', 'Mahngebuehr',
     'Mahngebühr|Mahnkosten|Bearbeitungsgebühr|Mahnspesen'),

    -- ------------------------------------------------------------------ Behördenbescheid
    ('behoerdenbescheid', 'Aktenzeichen',
     'Aktenzeichen|Geschäftszeichen|Aktenz|Az'),
    ('behoerdenbescheid', 'Bescheiddatum',
     'Bescheiddatum|Datum des Bescheides|Ergangen am|Ausstellungsdatum'),
    ('behoerdenbescheid', 'Frist',
     'Widerspruchsfrist|Frist|Innerhalb von|bis zum|Zahlungsfrist'),
    ('behoerdenbescheid', 'Behoerde',
     'Behörde|Landratsamt|Stadtverwaltung|Finanzamt|Jobcenter|Amt für'),
    ('behoerdenbescheid', 'Adressat',
     'Adressat|Empfänger|Bescheid für'),
    ('behoerdenbescheid', 'ErforderlicheAntwort',
     'Erforderliche Antwort|Von Ihnen wird erwartet|Bitte reichen Sie'),
    ('behoerdenbescheid', 'AngeforderteUnterlagen',
     'Angeforderte Unterlagen|Folgende Unterlagen|Nachweise|Unterlagen'),
    ('behoerdenbescheid', 'Rechtsbehelf',
     'Rechtsbehelfsbelehrung|Widerspruch|Klage|Rechtsbehelf'),

    -- ------------------------------------------------------------------------ Kontoauszug
    ('kontoauszug', 'Kontonummer',
     'IBAN|Kontonummer|Konto-Nr'),
    ('kontoauszug', 'Saldo',
     'Neuer Saldo|Schlusssaldo|Kontostand|Saldo|Endsaldo'),
    ('kontoauszug', 'Auszugszeitraum',
     'Auszugszeitraum|Zeitraum|Auszug vom|Kontoauszug vom'),

    -- ---------------------------------------------------------------------------- Vertrag
    ('vertrag', 'Vertragsgegenstand',
     'Vertragsgegenstand|Gegenstand des Vertrages|Gegenstand dieser Vereinbarung'),
    ('vertrag', 'Vertragsparteien',
     'Vertragsparteien|Vertragspartner|zwischen|Parteien'),
    ('vertrag', 'Anlagen',
     'Anlagen|Anlage|Bestandteil des Vertrages'),
    ('vertrag', 'Pflichten',
     'Pflichten|Verpflichtungen|Leistungspflichten'),
    ('vertrag', 'Preisaenderungsregelung',
     'Preisanpassung|Preisänderung|Preisanpassungsklausel|Anpassung der Vergütung'),
    ('vertrag', 'Kosten',
     'Kosten|Vergütung|Honorar|Monatspreis|Preis'),
    ('vertrag', 'Kuendigungsfrist',
     'Kündigungsfrist|Kündigung mit einer Frist|Frist zur Kündigung'),
    ('vertrag', 'Verlaengerungsklausel',
     'Verlängerung|Verlängerungsklausel|verlängert sich'),
    ('vertrag', 'Laufzeit',
     'Laufzeit|Vertragsdauer|Dauer des Vertrages'),
    ('vertrag', 'Beginn',
     'Vertragsbeginn|Beginn des Vertrages|beginnt am|Beginn')
) AS v(type_code, field_key, hint)
WHERE f.document_type_id = (SELECT id FROM document_types WHERE code = v.type_code)
  AND f.field_key = v.field_key;

-- A field without a hint cannot be extracted automatically; the extraction stage reports it as
-- not found with that reason instead of silently skipping it.
COMMENT ON COLUMN document_type_fields.extraction_hint IS
    'Pipe separated label variants. The extractor takes the rest of the line after the first label '
    'that occurs and validates it against value_type. NULL means the field is filled by a human.';
