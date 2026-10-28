-- =====================================================================================
-- V86 — ELSTER export: the client's tax facts and the EÜR line of every category.
--
-- Why these settings and not constants: whether a client is a Kleinunternehmer (§ 19 UStG),
-- whether VAT is due on the invoice (SOLL) or on payment (IST), which Wirtschaftsjahr the
-- export belongs to and whether Bewirtung is split 70/30 are facts only the client (or their
-- adviser) can state. The export must not guess them.
--
-- Why the EÜR line and Kennzahl live on the category and not in the export code: V81 made the
-- catalogue a table so that a chart can be corrected without a release. The line a receipt
-- belongs to is exactly such an attribute; docs/ELSTER_MAPPING.md and
-- docs/ELSTER_CATEGORIES.csv are the reviewed source of the seed below.
--
-- Additive only: two nullable columns, one seed of catalogue attributes, four platform
-- defaults. No existing value is changed or removed.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- EÜR line and Kennzahl of the Anlage EÜR 2025 per category
-- -------------------------------------------------------------------------------------
ALTER TABLE expense_categories
    ADD COLUMN euer_line varchar(20),
    ADD COLUMN euer_kz   varchar(20);

COMMENT ON COLUMN expense_categories.euer_line IS
    'Zielzeile der Anlage EÜR 2025; NULL = keine eigene Zeile (Sammelkategorie oder bewusst nicht betrieblich). '
    'Mehrfachzeilen wie 31-38 markieren die AfA-Sammelzeilen.';
COMMENT ON COLUMN expense_categories.euer_kz IS
    'Kennzahl der Anlage EÜR 2025; bei Zeilen mit zwei Betragsspalten beide Kennzahlen, z. B. 165/175.';

UPDATE expense_categories c
SET euer_line = m.euer_line,
    euer_kz   = m.euer_kz
FROM (VALUES
    ('anlagevermoegen', NULL, NULL),
    ('material', NULL, NULL),
    ('werkzeuge', NULL, NULL),
    ('fahrzeug', NULL, NULL),
    ('betriebsausgaben', NULL, NULL),
    ('loehne', NULL, NULL),
    ('steuern', NULL, NULL),
    ('privatentnahme', NULL, NULL),
    ('anlagevermoegen.maschinen', '33', '130'),
    ('anlagevermoegen.fahrzeuge', '33', '130'),
    ('anlagevermoegen.betriebsausstattung', '33', '130'),
    ('anlagevermoegen.bueromoebel', '33', '130'),
    ('anlagevermoegen.it_hardware', '33', '130'),
    ('anlagevermoegen.software_lizenzen', '32', '131'),
    ('anlagevermoegen.gebaeude', '31', '136'),
    ('anlagevermoegen.gwg', '36', '132'),
    ('anlagevermoegen.gwg_sammelposten', '37', '137'),
    ('material.waren', '27', '100'),
    ('material.rohstoffe', '27', '100'),
    ('material.hilfsstoffe', '27', '100'),
    ('material.betriebsstoffe', '27', '100'),
    ('material.verpackung', '53', '227'),
    ('material.fremdleistungen', '29', '110'),
    ('werkzeuge.handwerkzeug', '51', '229'),
    ('werkzeuge.elektrowerkzeuge', '51', '229'),
    ('werkzeuge.maschinen_werkzeuge', '51', '229'),
    ('werkzeuge.messgeraete', '51', '229'),
    ('werkzeuge.verbrauchsmaterial_werkzeug', '51', '229'),
    ('werkzeuge.werkzeug_reparatur', '48', '225'),
    ('werkzeuge.werkzeug_leasing', '47', '222'),
    ('fahrzeug.kraftstoff', '70', '146'),
    ('fahrzeug.strom_laden', '70', '146'),
    ('fahrzeug.reparatur', '70', '146'),
    ('fahrzeug.wartung', '70', '146'),
    ('fahrzeug.versicherung', '69', '145'),
    ('fahrzeug.kfz_steuer', '69', '145'),
    ('fahrzeug.leasing', '68', '144'),
    ('fahrzeug.miete', '70', '146'),
    ('fahrzeug.reifen', '70', '146'),
    ('fahrzeug.pflege', '70', '146'),
    ('fahrzeug.parken', '70', '146'),
    ('fahrzeug.maut', '69', '145'),
    ('fahrzeug.sonstiges', '70', '146'),
    ('fahrzeug.privatnutzung', '20', '106'),
    ('betriebsausgaben.miete', '39', '150'),
    ('betriebsausgaben.pacht', '39', '150'),
    ('betriebsausgaben.nebenkosten_raum', '41', '151'),
    ('betriebsausgaben.heizung', '41', '151'),
    ('betriebsausgaben.strom', '41', '151'),
    ('betriebsausgaben.gas', '41', '151'),
    ('betriebsausgaben.wasser', '41', '151'),
    ('betriebsausgaben.versicherungen', '49', '223'),
    ('betriebsausgaben.versicherung_gebaeude', '41', '151'),
    ('betriebsausgaben.buero', '51', '229'),
    ('betriebsausgaben.telefon', '43', '280'),
    ('betriebsausgaben.internet', '43', '280'),
    ('betriebsausgaben.porto', '51', '229'),
    ('betriebsausgaben.reisekosten', '44', '221'),
    ('betriebsausgaben.uebernachtung', '44', '221'),
    ('betriebsausgaben.verpflegung', '64', '171'),
    ('betriebsausgaben.fahrtkosten', '70', '146'),
    ('betriebsausgaben.flug', '70', '146'),
    ('betriebsausgaben.fortbildung', '45', '281'),
    ('betriebsausgaben.marketing', '54', '224'),
    ('betriebsausgaben.beratung', '60', '183'),
    ('betriebsausgaben.steuerberatung', '46', '194'),
    ('betriebsausgaben.rechtsberatung', '46', '194'),
    ('betriebsausgaben.software_it', '50', '228'),
    ('betriebsausgaben.hosting_cloud', '50', '228'),
    ('betriebsausgaben.wartung_software', '50', '228'),
    ('betriebsausgaben.beitraege', '49', '223'),
    ('betriebsausgaben.gebuehren', '49', '223'),
    ('betriebsausgaben.bankgebuehren', '60', '183'),
    ('betriebsausgaben.nebenkosten_geldverkehr', '60', '183'),
    ('betriebsausgaben.abschreibungen', '31-38', NULL),
    ('betriebsausgaben.bewirtung', '63', '165/175'),
    ('betriebsausgaben.geschenke', '62', '164/174'),
    ('betriebsausgaben.arbeitskleidung', '51', '229'),
    ('betriebsausgaben.arbeitssicherheit', '51', '229'),
    ('betriebsausgaben.entsorgung', '52', '226'),
    ('betriebsausgaben.reinigung', '41', '151'),
    ('betriebsausgaben.reparatur_instandhaltung', '48', '225'),
    ('betriebsausgaben.instandhaltung_gebaeude', '41', '151'),
    ('betriebsausgaben.instandhaltung_maschinen', '48', '225'),
    ('betriebsausgaben.instandhaltung_ausstattung', '48', '225'),
    ('betriebsausgaben.miete_bewegliche', '47', '222'),
    ('betriebsausgaben.leasing_bewegliche', '47', '222'),
    ('betriebsausgaben.literatur', '51', '229'),
    ('loehne.gehaelter', '30', '120'),
    ('loehne.loehne', '30', '120'),
    ('loehne.aushilfen', '30', '120'),
    ('loehne.minijob', '30', '120'),
    ('loehne.auszubildende', '30', '120'),
    ('loehne.geschaeftsfuehrer', '30', '120'),
    ('loehne.sozialversicherung', '30', '120'),
    ('loehne.berufsgenossenschaft', '30', '120'),
    ('loehne.minijob_abgaben', '30', '120'),
    ('loehne.vermoegenswirksame_leistungen', '30', '120'),
    ('loehne.freiwillige_sozialleistungen', '30', '120'),
    ('steuern.umsatzsteuer', '58', '186'),
    ('steuern.gewerbesteuer', '67', '168/177'),
    ('steuern.grundsteuer', '41', '151'),
    ('steuern.sonstige_betriebssteuern', '67', '168/177'),
    ('steuern.saeumniszuschlag', '60', '183'),
    ('privatentnahme.geld', '106', '122'),
    ('privatentnahme.waren', '106', '122'),
    ('privatentnahme.ausgaben', NULL, NULL),
    ('privatentnahme.versicherung', NULL, NULL),
    ('privatentnahme.kfz', NULL, NULL),
    ('privatentnahme.reisen', NULL, NULL),
    ('privatentnahme.steuern', NULL, NULL),
    ('privatentnahme.lebenshaltung', NULL, NULL)
) AS m(code, euer_line, euer_kz)
WHERE c.code = m.code;

-- -------------------------------------------------------------------------------------
-- The client's tax facts (platform defaults; a tenant overrides them in system_settings)
-- -------------------------------------------------------------------------------------
INSERT INTO system_settings (tenant_id, setting_key, category, value, value_type, description,
                             requires_verification)
VALUES
    (NULL, 'tax.kleinunternehmer', 'OTHER', 'false'::jsonb, 'BOOLEAN',
     '{"de":"Kleinunternehmer nach § 19 UStG: Beträge gehen brutto in die EÜR, USt-Spalten bleiben 0 und eine USt-Voranmeldung entfällt.","en":"Small business under § 19 UStG: amounts are gross in the EÜR, the VAT columns stay 0 and no VAT return is filed.","ru":"Малый предприниматель по § 19 UStG: суммы идут брутто, колонки НДС остаются 0, декларация по НДС не требуется."}'::jsonb,
     false),
    (NULL, 'tax.soll_ist', 'OTHER', '"SOLL"'::jsonb, 'STRING',
     '{"de":"Besteuerungsart der Umsatzsteuer: SOLL (nach Rechnungsstellung) oder IST (nach Zahlung).","en":"VAT accounting method: SOLL (on invoice) or IST (on payment).","ru":"Метод учёта НДС: SOLL (по счёту) или IST (по оплате)."}'::jsonb,
     false),
    (NULL, 'tax.wirtschaftsjahr', 'OTHER', 'null'::jsonb, 'NUMBER',
     '{"de":"Wirtschaftsjahr für den ELSTER-Export; ohne gesetzten Wert gilt das laufende Kalenderjahr.","en":"Business year for the ELSTER export; when unset the current calendar year is used.","ru":"Хозяйственный год для экспорта ELSTER; если не задан — текущий календарный год."}'::jsonb,
     false),
    (NULL, 'tax.bewirtung_split', 'OTHER', 'true'::jsonb, 'BOOLEAN',
     '{"de":"Bewirtungskosten zu 70 % abziehbar und 30 % nicht abziehbar aufteilen (EÜR Zeile 63, Kz 175/165).","en":"Split entertainment costs into 70 % deductible and 30 % non-deductible (EÜR line 63, Kz 175/165).","ru":"Делить представительские расходы на 70 % вычитаемых и 30 % невычитаемых (строка 63, Kz 175/165)."}'::jsonb,
     false)
ON CONFLICT DO NOTHING;
