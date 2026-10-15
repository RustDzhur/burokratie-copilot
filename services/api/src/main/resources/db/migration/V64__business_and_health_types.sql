-- =====================================================================================
-- V64 - business documents and medical/school reports as their own kinds (task S3 of the scanner/sorting request)
--
-- The owner photographed a business plan, a notice to the trade office about a firm and a medical commission's report for a child,
-- and found them in the wrong drawers. The catalogue had no kind for a business plan, trade-office forms lived under "authorities",
-- and nothing described the reports of medical or school commissions, so the rules that did fire were accidents. This migration
-- gives them their own kinds and a drawer "business" (BUSINESS). The weights are technical starting values, to be tuned with the
-- measurements of the evaluation set (docs/testing/test-strategy.md), not legal or tax rules.
-- The text and the rule patterns are compared in folded form (umlauts written out, case ignored), so "Förderbescheid" and
-- "foerderbescheid" are the same word.
-- =====================================================================================

ALTER TABLE document_types DROP CONSTRAINT document_types_category_chk;
ALTER TABLE document_types ADD CONSTRAINT document_types_category_chk CHECK (category IN (
    'FINANCE', 'CONTRACTS', 'GOVERNMENT', 'HOUSING', 'EMPLOYMENT', 'FAMILY_EDUCATION_HEALTH', 'BUSINESS', 'LEGAL', 'OTHER'));

-- Documents of a firm belong to the business drawer, wherever they used to be.
UPDATE document_types SET category = 'BUSINESS' WHERE code IN ('gewerbeanmeldung', 'handelsregisterauszug', 'ihk_beitragsbescheid');
UPDATE document_types SET name = '{"de": "Gewerbeanmeldung, -ummeldung, -abmeldung", "en": "Trade registration, change, deregistration", "ru": "Регистрация, изменение и снятие бизнеса (Gewerbe)"}'::jsonb
 WHERE code = 'gewerbeanmeldung';

INSERT INTO document_types (code, category, name, requires_review, audiences, sort_order) VALUES
('businessplan', 'BUSINESS', '{"de": "Businessplan / Gründungskonzept", "en": "Business plan", "ru": "Бизнес-план"}'::jsonb, false, 'private,company', 100),
('foerdermittel_antrag', 'BUSINESS', '{"de": "Förderantrag (Gründung, Zuschuss)", "en": "Funding application", "ru": "Заявка на субсидию"}'::jsonb, false, 'private,company', 110),
('aerztliches_gutachten', 'FAMILY_EDUCATION_HEALTH', '{"de": "Ärztliches Gutachten / Kommission / Stellungnahme", "en": "Medical report / commission / opinion", "ru": "Медицинское заключение / комиссия"}'::jsonb, true, 'private,family,company', 120),
('aerztliches_attest', 'FAMILY_EDUCATION_HEALTH', '{"de": "Ärztliches Attest / Bescheinigung", "en": "Medical certificate", "ru": "Медицинская справка"}'::jsonb, true, 'private,family,company', 125),
('schwerbehinderung_bescheid', 'FAMILY_EDUCATION_HEALTH', '{"de": "Schwerbehinderung (Bescheid, Ausweis)", "en": "Disability notice / card", "ru": "Инвалидность (решение, удостоверение)"}'::jsonb, true, 'private,family', 130),
('foerderbedarf_gutachten', 'FAMILY_EDUCATION_HEALTH', '{"de": "Gutachten zum Förderbedarf (Schule, Kind)", "en": "Special educational needs report", "ru": "Заключение об особых образовательных потребностях"}'::jsonb, true, 'private,family', 135),
('vorsorge_impfung', 'FAMILY_EDUCATION_HEALTH', '{"de": "Vorsorgeheft / Impfausweis", "en": "Check-up booklet / vaccination record", "ru": "Книжка профилактики / прививочный сертификат"}'::jsonb, true, 'private,family', 140)
ON CONFLICT (code) DO NOTHING;

INSERT INTO document_type_rules (document_type_id, tenant_id, rule_type, pattern, weight, description)
SELECT t.id, NULL, 'KEYWORD', v.pattern, v.weight, jsonb_build_object('de', v.description)
FROM document_types t
JOIN (VALUES
    -- business plan
    ('businessplan', 'businessplan', 0.900, 'Businessplan'),
    ('businessplan', 'business plan', 0.850, 'Business Plan'),
    ('businessplan', 'gründungskonzept', 0.900, 'Gründungskonzept'),
    ('businessplan', 'rentabilitätsvorschau', 0.700, 'Rentabilitätsvorschau'),
    ('businessplan', 'executive summary', 0.600, 'Executive Summary'),
    ('businessplan', 'finanzplan', 0.450, 'Finanzplan'),
    ('businessplan', 'liquiditätsplan', 0.500, 'Liquiditätsplan'),
    ('businessplan', 'marktanalyse', 0.450, 'Marktanalyse'),
    ('businessplan', 'umsatzplanung', 0.450, 'Umsatzplanung'),
    ('businessplan', 'kapitalbedarf', 0.500, 'Kapitalbedarf'),
    ('businessplan', 'geschäftsidee', 0.450, 'Geschäftsidee'),
    -- trade office: registration, change, deregistration, the forms
    ('gewerbeanmeldung', 'gewerbeummeldung', 0.990, 'Gewerbeummeldung'),
    ('gewerbeanmeldung', 'gewerbeanzeige', 0.900, 'Gewerbeanzeige'),
    ('gewerbeanmeldung', 'gewerbe abmelden', 0.800, 'Gewerbe abmelden'),
    ('gewerbeanmeldung', 'gewerbe ummelden', 0.800, 'Gewerbe ummelden'),
    ('gewerbeanmeldung', 'melde ich mein gewerbe ab', 0.900, 'Ich melde mein Gewerbe ab'),
    ('gewerbeanmeldung', '14 gewo', 0.800, 'Anzeige nach § 14 GewO'),
    ('gewerbeanmeldung', 'verlegung der betriebsstätte', 0.600, 'Verlegung der Betriebsstätte'),
    ('gewerbeanmeldung', 'betriebsaufgabe', 0.500, 'Betriebsaufgabe'),
    ('gewerbeanmeldung', 'gewerbetreibende', 0.500, 'Gewerbetreibende(r)'),
    ('gewerbeanmeldung', 'gewerbebetrieb', 0.350, 'Gewerbebetrieb'),
    -- funding applications
    ('foerdermittel_antrag', 'förderantrag', 0.900, 'Förderantrag'),
    ('foerdermittel_antrag', 'antrag auf förderung', 0.800, 'Antrag auf Förderung'),
    ('foerdermittel_antrag', 'gründungszuschuss', 0.800, 'Gründungszuschuss'),
    ('foerdermittel_antrag', 'einstiegsgeld', 0.700, 'Einstiegsgeld'),
    ('foerdermittel_antrag', 'existenzgründung', 0.500, 'Existenzgründung'),
    -- medical reports, commissions, opinions
    ('aerztliches_gutachten', 'ärztliches gutachten', 0.950, 'Ärztliches Gutachten'),
    ('aerztliches_gutachten', 'medizinisches gutachten', 0.950, 'Medizinisches Gutachten'),
    ('aerztliches_gutachten', 'medizinische kommission', 0.950, 'Medizinische Kommission'),
    ('aerztliches_gutachten', 'ärztliche kommission', 0.950, 'Ärztliche Kommission'),
    ('aerztliches_gutachten', 'ärztliche stellungnahme', 0.900, 'Ärztliche Stellungnahme'),
    ('aerztliches_gutachten', 'fachärztliche stellungnahme', 0.900, 'Fachärztliche Stellungnahme'),
    ('aerztliches_gutachten', 'befundbericht', 0.700, 'Befundbericht'),
    ('aerztliches_gutachten', 'gutachten', 0.350, 'Gutachten'),
    ('aerztliches_gutachten', 'kommission', 0.200, 'Kommission'),
    ('aerztliches_gutachten', 'entwicklungsstand', 0.350, 'Entwicklungsstand'),
    -- medical certificate
    ('aerztliches_attest', 'ärztliches attest', 0.950, 'Ärztliches Attest'),
    ('aerztliches_attest', 'attest', 0.700, 'Attest'),
    ('aerztliches_attest', 'bescheinigung des arztes', 0.800, 'Bescheinigung des Arztes'),
    ('aerztliches_attest', 'ärztliche bescheinigung', 0.850, 'Ärztliche Bescheinigung'),
    ('aerztliches_attest', 'sportunfähig', 0.500, 'sportunfähig'),
    -- disability
    ('schwerbehinderung_bescheid', 'schwerbehindertenausweis', 0.950, 'Schwerbehindertenausweis'),
    ('schwerbehinderung_bescheid', 'grad der behinderung', 0.900, 'Grad der Behinderung'),
    ('schwerbehinderung_bescheid', 'versorgungsamt', 0.700, 'Versorgungsamt'),
    ('schwerbehinderung_bescheid', 'merkzeichen', 0.600, 'Merkzeichen'),
    ('schwerbehinderung_bescheid', 'schwerbehinderung', 0.700, 'Schwerbehinderung'),
    -- special educational needs
    ('foerderbedarf_gutachten', 'sonderpädagogischer förderbedarf', 0.950, 'Sonderpädagogischer Förderbedarf'),
    ('foerderbedarf_gutachten', 'förderschwerpunkt', 0.800, 'Förderschwerpunkt'),
    ('foerderbedarf_gutachten', 'förderdiagnostik', 0.800, 'Förderdiagnostik'),
    ('foerderbedarf_gutachten', 'schulpsychologisch', 0.700, 'Schulpsychologisch'),
    ('foerderbedarf_gutachten', 'sonderpädagogisch', 0.600, 'Sonderpädagogisch'),
    ('foerderbedarf_gutachten', 'förderplan', 0.500, 'Förderplan'),
    -- check-up booklet, vaccination record
    ('vorsorge_impfung', 'kinderuntersuchungsheft', 0.950, 'Kinderuntersuchungsheft'),
    ('vorsorge_impfung', 'vorsorgeheft', 0.900, 'Vorsorgeheft'),
    ('vorsorge_impfung', 'impfausweis', 0.900, 'Impfausweis'),
    ('vorsorge_impfung', 'u untersuchung', 0.850, 'U-Untersuchung'),
    ('vorsorge_impfung', 'gelbes heft', 0.800, 'Gelbes Heft')
) AS v(code, pattern, weight, description) ON v.code = t.code
ON CONFLICT DO NOTHING;
