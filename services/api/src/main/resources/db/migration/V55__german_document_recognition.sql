-- =====================================================================================
-- V55 - German-only document recognition, tuned against the evaluation set (task RD-09)
--
-- Decision of the owner (2026-10-15): the platform works with German documentation only. Russian and English exist for the
-- interface, not for documents. The Russian and Ukrainian classification rules added in V37 are therefore removed: a rule
-- that fires on a word of another language can only produce a wrong type on a German document.
--
-- The evaluation set (services/api/src/test/resources/eval, 37 synthetic German documents) measured the rules before this
-- migration: 78.8 % right types, and 22 % of the automatically accepted types were wrong. The causes were not missing
-- vocabulary but weights: a word that names the SENDER of a letter (Finanzamt, Krankenkasse, Wohngeldstelle) or a
-- common part of every invoice (Bankverbindung, IBAN) was rated as strongly as the words that name the KIND of letter.
-- A type is now decided by the words of the kind of letter; the sender word only supports it. All weights below are
-- technical starting values chosen from that evidence, to be adjusted with further measurements; they are not legal rules.
-- =====================================================================================

DELETE FROM document_type_rules WHERE tenant_id IS NULL AND pattern ~ '[А-Яа-яЁёІіЇїЄєҐґ]';

-- A bank detail on an invoice does not make the invoice a bank-detail notice; only a NEW one does.
UPDATE document_type_rules r SET weight = v.weight
FROM document_types t, (VALUES
    ('bankverbindung', 'bankverbindung', 0.350), ('bankverbindung', 'iban', 0.150), ('bankverbindung', 'bic', 0.100),
    ('bankverbindung', 'kontoinhaber', 0.300),
    -- The sender names the office, the kind of letter names the type.
    ('finanzamt', 'finanzamt', 0.500), ('krankenkasse', 'krankenkasse', 0.500),
    ('wohngeldbescheid', 'wohngeld', 0.500), ('wohngeldbescheid', 'wohngeldstelle', 0.500),
    ('widerspruch', 'widerspruch gegen', 0.950)
) AS v(code, pattern, weight)
WHERE t.id = r.document_type_id AND t.code = v.code AND r.pattern = v.pattern AND r.tenant_id IS NULL;

INSERT INTO document_type_rules (document_type_id, tenant_id, rule_type, pattern, weight, description)
SELECT t.id, NULL, v.rule_type, v.pattern, v.weight, jsonb_build_object('de', v.description)
FROM document_types t
JOIN (VALUES
    -- Rechnung: the usual German invoice vocabulary
    ('rechnung', 'KEYWORD', 'rechnungsdatum', 0.700, 'Feldbezeichnung einer Rechnung'),
    ('rechnung', 'KEYWORD', 'zahlungsziel', 0.500, 'Zahlungsziel einer Rechnung'),
    ('rechnung', 'KEYWORD', 'bruttobetrag', 0.500, 'Bruttobetrag einer Rechnung'),
    ('rechnung', 'KEYWORD', 'nettobetrag', 0.400, 'Nettobetrag einer Rechnung'),
    ('rechnung', 'KEYWORD', 'mwst', 0.400, 'Mehrwertsteuerausweis'),
    ('rechnung', 'KEYWORD', 'mehrwertsteuer', 0.400, 'Mehrwertsteuerausweis'),
    ('rechnung', 'KEYWORD', 'gesamtbetrag', 0.300, 'Gesamtbetrag einer Rechnung'),
    ('rechnung', 'REGEX', 'rechnung\s+(nr\.?|nummer)\s*:?\s*[0-9]', 0.700, 'Rechnung Nr. mit Nummer'),
    -- Preiserhöhung: how a German provider announces it
    ('preiserhoehung', 'KEYWORD', 'erhöht sich das monatliche entgelt', 0.900, 'Ankündigung einer Preiserhöhung'),
    ('preiserhoehung', 'KEYWORD', 'anpassung ihres entgelts', 0.800, 'Ankündigung einer Preisanpassung'),
    ('preiserhoehung', 'KEYWORD', 'preisanpassung', 0.800, 'Ankündigung einer Preisanpassung'),
    ('preiserhoehung', 'KEYWORD', 'erhöhung des entgelts', 0.850, 'Ankündigung einer Preiserhöhung'),
    ('preiserhoehung', 'REGEX', 'erhöht sich[^.]{0,80}(entgelt|preis|beitrag|gebühr)', 0.850, 'Ankündigung einer Erhöhung'),
    ('preiserhoehung', 'KEYWORD', 'außerordentlich zu kündigen', 0.400, 'Sonderkündigungsrecht bei Preiserhöhung'),
    -- Vertragsänderung / Nachtrag
    ('vertragsaenderung', 'KEYWORD', 'geänderte bedingungen', 0.800, 'Mitteilung geänderter Vertragsbedingungen'),
    ('vertragsaenderung', 'REGEX', 'nachtrag\s+(nr\.?|nummer)\s*[0-9]', 0.900, 'Nachtrag Nr. zu einem Vertrag'),
    ('vertragsaenderung', 'KEYWORD', 'nachtrag', 0.500, 'Nachtrag zu einem Vertrag'),
    ('nachtrag', 'REGEX', 'nachtrag\s+(nr\.?|nummer)\s*[0-9]', 0.800, 'Nachtrag Nr. zu einem Vertrag'),
    -- Bußgeldverfahren
    ('anhoerung_bussgeld', 'KEYWORD', 'ordnungswidrigkeit', 0.900, 'Ordnungswidrigkeitenverfahren'),
    ('anhoerung_bussgeld', 'KEYWORD', 'bußgeldstelle', 0.900, 'Bußgeldstelle als Absender'),
    ('anhoerung_bussgeld', 'KEYWORD', 'höchstgeschwindigkeit', 0.500, 'Geschwindigkeitsverstoß'),
    -- Krankenkasse
    ('krankenkasse_beitragsanpassung', 'KEYWORD', 'beitragsfestsetzung', 0.800, 'Festsetzung des Beitrags'),
    ('krankenkasse_beitragsanpassung', 'KEYWORD', 'beitrag beträgt', 0.400, 'Höhe des Beitrags'),
    -- Widerspruch / Einspruch (the letter that objects, not the authority it is addressed to)
    ('widerspruch', 'KEYWORD', 'einspruch gegen', 0.900, 'Einspruch gegen einen Bescheid'),
    ('widerspruch', 'REGEX', 'lege ich[^.]{0,60}(einspruch|widerspruch)[^.]{0,20}ein', 0.990, 'Ich lege Einspruch oder Widerspruch ein'),
    ('widerspruch', 'KEYWORD', 'bitte um überprüfung', 0.300, 'Bitte um Überprüfung'),
    -- Anforderung fehlender Unterlagen
    ('anforderung_fehlender_unterlagen', 'REGEX', 'fehlen[^.]{0,40}unterlagen', 0.900, 'Es fehlen noch Unterlagen'),
    ('anforderung_fehlender_unterlagen', 'KEYWORD', 'fehlen uns noch', 0.900, 'Es fehlen uns noch Unterlagen'),
    ('anforderung_fehlender_unterlagen', 'KEYWORD', 'unterlagen ein', 0.300, 'Bitte reichen Sie Unterlagen ein'),
    -- Werbung
    ('reklame', 'KEYWORD', 'sommerschlussverkauf', 0.800, 'Verkaufsaktion'),
    ('reklame', 'KEYWORD', 'rabatt auf', 0.500, 'Rabattwerbung'),
    ('reklame', 'KEYWORD', 'jetzt sichern', 0.500, 'Werbeaufforderung'),
    ('reklame', 'KEYWORD', 'gutschein sichern', 0.600, 'Werbeaufforderung')
) AS v(code, rule_type, pattern, weight, description) ON v.code = t.code
ON CONFLICT DO NOTHING;

-- More ways in which a German invoice names its number.
UPDATE document_type_fields f
SET extraction_hint = f.extraction_hint || '|Rechnungs-Nummer|Re.-Nr.|RE-Nr|Rechnung-Nr|Invoice No|Invoice Nr'
FROM document_types t
WHERE t.id = f.document_type_id AND t.code = 'rechnung' AND f.field_key = 'Rechnungsnummer'
  AND f.extraction_hint NOT LIKE '%Rechnungs-Nummer%';
