-- =====================================================================================
-- V37 - a medical document is not an offer; Russian and Ukrainian documents are recognised
--
-- Found on the live site: a medical document was suggested as "Angebot" at 60 %. The only evidence was the word
-- "angebot" appearing somewhere in the text (the rule matches inside longer words such as "Leistungsangebot"), and
-- there was no type for a medical document, so the nearest wrong one won. Three changes:
--
--   * a lone "angebot" is weak evidence (0.45, below the 0.60 at which a suggestion is made), the specific
--     phrases of an offer carry the weight;
--   * a type for medical documents (findings, doctor's letters, prescriptions, discharge summaries) with its
--     own rules, in German, Russian and Ukrainian;
--   * rules in Russian and Ukrainian for the most frequent kinds of letters. The people who use the platform
--     receive such documents, and until now only German words could classify anything.
-- =====================================================================================

UPDATE document_type_rules r SET weight = 0.450
FROM document_types t
WHERE t.id = r.document_type_id AND t.code = 'angebot' AND r.tenant_id IS NULL AND r.pattern = 'angebot';

INSERT INTO document_types (code, category, name, description, requires_review, sort_order)
VALUES ('medizinisches_dokument', 'FAMILY_EDUCATION_HEALTH',
        '{"de": "Medizinisches Dokument (Befund, Arztbrief, Rezept)", "en": "Medical document (findings, doctor''s letter, prescription)", "ru": "Медицинский документ (заключение, выписка, рецепт)"}',
        '{"de": "Befunde, Arztbriefe, Entlassungsberichte, Rezepte, Überweisungen. Vertraulich.", "en": "Findings, doctor''s letters, discharge summaries, prescriptions, referrals. Confidential.", "ru": "Заключения, врачебные письма, выписки, рецепты, направления. Конфиденциально."}',
        false, 100)
ON CONFLICT (code) DO NOTHING;

INSERT INTO document_type_scenarios
    (document_type_code, action_kind, priority, case_type_code, creates_task, task_title, completion_criteria,
     checklist, due_field_keys, amount_field_keys, default_due_days, lead_days, requires_approval,
     requires_human_review, legal_deadline, creates_contract, follow_up_days, follow_up_title)
VALUES ('medizinisches_dokument', 'REVIEW', 'NORMAL', NULL, true,
        'Medizinisches Dokument prüfen und ablegen (vertraulich)',
        'Der Inhalt ist gelesen, Termine und Folgeschritte sind festgehalten.',
        'Inhalt lesen|Termine und Folgeschritte festhalten|Bei Bedarf Krankenkasse oder Versicherung informieren',
        'Frist', NULL, 7, 1, false, false, false, false, NULL, NULL)
ON CONFLICT DO NOTHING;

INSERT INTO document_type_fields (document_type_id, field_key, label, value_type, is_required, is_critical,
                                  extraction_hint, sort_order)
SELECT t.id, f.field_key, f.label::jsonb, f.value_type, false, false, f.hint, f.sort_order
FROM document_types t
CROSS JOIN (VALUES
 ('Aktenzeichen', '{"de":"Aktenzeichen / Referenz","en":"Reference","ru":"Номер / ссылка"}', 'REFERENCE',
  'Aktenzeichen|Patientennummer|Fallnummer|Referenz', 10),
 ('Betrag', '{"de":"Betrag","en":"Amount","ru":"Сумма"}', 'AMOUNT', 'Gesamtbetrag|Rechnungsbetrag|Betrag|Summe', 20),
 ('Frist', '{"de":"Frist / Termin","en":"Deadline / appointment","ru":"Срок / приём"}', 'DATE',
  'Termin am|Nächster Termin|Kontrolltermin|Frist|bis spätestens', 30),
 ('Schreibendatum', '{"de":"Datum des Schreibens","en":"Date of letter","ru":"Дата документа"}', 'DATE',
  'Datum|Ausgestellt am|vom', 50)
) AS f(field_key, label, value_type, hint, sort_order)
WHERE t.code = 'medizinisches_dokument'
ON CONFLICT DO NOTHING;

INSERT INTO document_type_rules (document_type_id, tenant_id, rule_type, pattern, weight)
SELECT t.id, NULL, 'KEYWORD', r.pattern, r.weight
FROM (VALUES
 -- German
 ('medizinisches_dokument', 'arztbrief', 0.950), ('medizinisches_dokument', 'laborbefund', 0.950),
 ('medizinisches_dokument', 'entlassungsbericht', 0.950), ('medizinisches_dokument', 'entlassbrief', 0.950),
 ('medizinisches_dokument', 'anamnese', 0.800), ('medizinisches_dokument', 'diagnose', 0.700),
 ('medizinisches_dokument', 'befund', 0.700), ('medizinisches_dokument', 'laborwerte', 0.800),
 ('medizinisches_dokument', 'überweisungsschein', 0.900), ('medizinisches_dokument', 'rezept', 0.500),
 ('medizinisches_dokument', 'patientin', 0.600), ('medizinisches_dokument', 'patient', 0.600),
 ('medizinisches_dokument', 'krankenhaus', 0.600), ('medizinisches_dokument', 'klinikum', 0.600),
 ('medizinisches_dokument', 'dr. med.', 0.800), ('medizinisches_dokument', 'facharzt', 0.600),
 ('medizinisches_dokument', 'fachärztin', 0.600), ('medizinisches_dokument', 'medikation', 0.700),
 ('medizinisches_dokument', 'impfpass', 0.800), ('medizinisches_dokument', 'impfung', 0.600),
 ('medizinisches_dokument', 'therapie', 0.500), ('medizinisches_dokument', 'untersuchung', 0.500),
 -- Russian
 ('medizinisches_dokument', 'диагноз', 0.800), ('medizinisches_dokument', 'пациент', 0.700),
 ('medizinisches_dokument', 'выписка из истории болезни', 0.950), ('medizinisches_dokument', 'медицинск', 0.600),
 ('medizinisches_dokument', 'больниц', 0.600), ('medizinisches_dokument', 'врач', 0.500),
 ('medizinisches_dokument', 'рецепт', 0.500), ('medizinisches_dokument', 'анализ крови', 0.800),
 ('medizinisches_dokument', 'заключение врача', 0.900), ('medizinisches_dokument', 'лечение', 0.500),
 -- Ukrainian
 ('medizinisches_dokument', 'діагноз', 0.800), ('medizinisches_dokument', 'пацієнт', 0.700),
 ('medizinisches_dokument', 'виписка з історії хвороби', 0.950), ('medizinisches_dokument', 'медичн', 0.600),
 ('medizinisches_dokument', 'лікарня', 0.600), ('medizinisches_dokument', 'лікар', 0.500),
 ('medizinisches_dokument', 'аналіз крові', 0.800),
 ('medizinisches_dokument', 'висновок лікаря', 0.900), ('medizinisches_dokument', 'лікування', 0.500),
 ('medizinisches_dokument', 'направлення', 0.600),
 -- an offer is identified by what makes it one, not by the word alone
 ('angebot', 'angebotsnummer', 0.900), ('angebot', 'angebot nr', 0.800), ('angebot', 'unser angebot', 0.700),
 ('angebot', 'angebot vom', 0.600), ('angebot', 'gültig bis', 0.300),
 -- Russian and Ukrainian: the most frequent letters
 ('rechnung', 'рахунок на оплату', 0.800), ('rechnung', 'счёт на оплату', 0.800), ('rechnung', 'счет на оплату', 0.800),
 ('rechnung', 'рахунок-фактура', 0.800), ('rechnung', 'счет-фактура', 0.800),
 ('zahlungserinnerung', 'нагадування про оплату', 0.900), ('zahlungserinnerung', 'напоминание об оплате', 0.900),
 ('mahnung', 'заборгованість', 0.500), ('mahnung', 'задолженность', 0.500),
 ('mahnung', 'остання вимога', 0.900), ('mahnung', 'последнее напоминание', 0.900),
 ('vertrag', 'договір', 0.500), ('vertrag', 'договор', 0.500),
 ('vertrag', 'сторони договору', 0.700), ('vertrag', 'стороны договора', 0.700),
 ('vertrag', 'предмет договору', 0.700), ('vertrag', 'предмет договора', 0.700),
 ('mietvertrag', 'договір оренди', 0.900), ('mietvertrag', 'договор аренды', 0.900), ('mietvertrag', 'договор найма', 0.900),
 ('arbeitsvertrag', 'трудовий договір', 0.900), ('arbeitsvertrag', 'трудовой договор', 0.900),
 ('kuendigung', 'розірвання договору', 0.800), ('kuendigung', 'расторжение договора', 0.800),
 ('kuendigung', 'заява про розірвання', 0.900), ('kuendigung', 'заявление о расторжении', 0.900),
 ('finanzamt', 'податкова інспекція', 0.900), ('finanzamt', 'налоговая инспекция', 0.900),
 ('finanzamt', 'податкове повідомлення', 0.800), ('finanzamt', 'налоговое уведомление', 0.800),
 ('jobcenter', 'джобцентр', 0.900), ('jobcenter', 'джоб-центр', 0.900),
 ('jobcenter', 'bedarfsgemeinschaft', 0.900), ('jobcenter', 'sgb ii', 0.700),
 ('jobcenter', 'einkommenserklärung', 0.800), ('jobcenter', 'selbständiger tätigkeit', 0.500),
 ('schule', 'школа', 0.500), ('schule', 'школьн', 0.500), ('schule', 'шкільн', 0.500),
 ('krankenkasse', 'медична страховка', 0.700), ('krankenkasse', 'медицинская страховка', 0.700),
 ('versicherungsunterlagen', 'страховий поліс', 0.800), ('versicherungsunterlagen', 'страховой полис', 0.800),
 ('behoerdenbescheid', 'решение органа', 0.500)
) AS r(code, pattern, weight)
JOIN document_types t ON t.code = r.code
ON CONFLICT DO NOTHING;

-- The period a document is about ("Zeitraum: 01.11.2025 bis 31.12.2025": a form for two months of last year), for
-- every type. Without it a form about a closed period looked like fresh work.
INSERT INTO document_type_fields (document_type_id, field_key, label, value_type, is_required, is_critical,
                                  extraction_hint, sort_order)
SELECT t.id, 'Zeitraum', '{"de":"Zeitraum","en":"Period","ru":"Период"}'::jsonb, 'PERIOD', false, false,
       'Bewilligungszeitraum|Berichtszeitraum|Leistungszeitraum|Abrechnungszeitraum|Bewilligungsabschnitt|für den Zeitraum|Zeitraum von|Zeitraum|Период|Період', 60
FROM document_types t
WHERE NOT EXISTS (SELECT 1 FROM document_type_fields x WHERE x.document_type_id = t.id AND x.field_key = 'Zeitraum')
ON CONFLICT DO NOTHING;
