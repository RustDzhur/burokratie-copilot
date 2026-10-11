-- =====================================================================================
-- V17 — Classification rules (spec §9.3, §9.4, §31)
--
-- The taxonomy itself (document_types, 79 types in 8 categories) was seeded in V12. What was
-- missing is the *evidence* a classifier works with, and that evidence is configuration, not code:
-- a customer, an industry or a language variant needs different patterns, and changing them must
-- not require a release.
--
-- A rule says "this pattern is a hint for this document type, with this weight". Weights are
-- combined with a probabilistic OR (1 - Π(1 - w)), which is explainable to a user: two independent
-- hints of 0.6 give 0.84, and no single weak hint can push a type over the automation threshold.
--
-- The seeded patterns are German administrative vocabulary. They are heuristics for *document type
-- recognition*, not legal or tax rules, and they are editable by an administrator like every other
-- configurable value in this system.
-- =====================================================================================

CREATE TABLE document_type_rules (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_type_id uuid NOT NULL REFERENCES document_types (id) ON DELETE CASCADE,
    tenant_id        uuid REFERENCES tenants (id) ON DELETE CASCADE,   -- NULL = system default
    rule_type        varchar(20) NOT NULL DEFAULT 'KEYWORD',
    -- The pattern is matched case-insensitively against the document text.
    pattern          varchar(300) NOT NULL,
    -- How strongly a match indicates this type, 0 < weight <= 1.
    weight           numeric(4, 3) NOT NULL DEFAULT 0.500,
    -- Rules can require several hints to fire together; empty means "on its own".
    requires_all     jsonb NOT NULL DEFAULT '[]'::jsonb,
    description      jsonb NOT NULL DEFAULT '{}'::jsonb,
    is_active        boolean NOT NULL DEFAULT true,
    created_by       uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_type_rules_type_chk CHECK (rule_type IN ('KEYWORD', 'REGEX', 'SENDER_DOMAIN', 'FILENAME')),
    CONSTRAINT document_type_rules_weight_chk CHECK (weight > 0 AND weight <= 1),
    CONSTRAINT document_type_rules_pattern_chk CHECK (length(btrim(pattern)) >= 2)
);

CREATE INDEX document_type_rules_type_idx ON document_type_rules (document_type_id)
    WHERE is_active;
CREATE UNIQUE INDEX document_type_rules_unique
    ON document_type_rules (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid),
                            document_type_id, rule_type, pattern);

CREATE TRIGGER document_type_rules_set_updated_at BEFORE UPDATE ON document_type_rules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- -------------------------------------------------------------------------------------
-- Seeded rules for the types a first deployment meets most often.
-- -------------------------------------------------------------------------------------
INSERT INTO document_type_rules (document_type_id, tenant_id, rule_type, pattern, weight, description)
SELECT dt.id, NULL, r.rule_type, r.pattern, r.weight, r.description::jsonb
FROM document_types dt
JOIN (VALUES
    -- Rechnung
    ('rechnung','KEYWORD','rechnungsnummer',0.800,'{"de":"Typische Feldbezeichnung einer Rechnung","en":"Typical invoice field label","ru":"Характерное поле счёта"}'),
    ('rechnung','KEYWORD','rechnungsbetrag',0.700,'{"de":"Bezeichnung des Rechnungsbetrags","en":"Invoice amount label","ru":"Обозначение суммы счёта"}'),
    ('rechnung','KEYWORD','zahlbar bis',0.700,'{"de":"Zahlungsziel","en":"Payment due date","ru":"Срок оплаты"}'),
    ('rechnung','KEYWORD','umsatzsteuer',0.500,'{"de":"Umsatzsteuerausweis","en":"VAT line","ru":"Строка НДС"}'),
    ('rechnung','KEYWORD','netto',0.300,'{"de":"Nettobetrag","en":"Net amount","ru":"Сумма без налога"}'),
    -- Mahnung
    ('mahnung','KEYWORD','mahnung',0.900,'{"de":"Wort Mahnung im Schreiben","en":"The word dunning","ru":"Слово «напоминание»"}'),
    ('mahnung','KEYWORD','mahngebühr',0.800,'{"de":"Mahngebühr","en":"Dunning fee","ru":"Плата за напоминание"}'),
    ('mahnung','KEYWORD','letzte mahnung',0.950,'{"de":"Letzte Mahnung vor weiteren Schritten","en":"Final notice","ru":"Последнее напоминание"}'),
    ('mahnung','KEYWORD','säumniszuschlag',0.700,'{"de":"Säumniszuschlag","en":"Late payment surcharge","ru":"Пеня за просрочку"}'),
    -- Zahlungserinnerung
    ('zahlungserinnerung','KEYWORD','zahlungserinnerung',0.950,'{"de":"Wort Zahlungserinnerung","en":"Payment reminder","ru":"Напоминание об оплате"}'),
    ('zahlungserinnerung','KEYWORD','freundliche erinnerung',0.600,'{"de":"Höfliche Erinnerung","en":"Friendly reminder","ru":"Вежливое напоминание"}'),
    -- Kuendigung
    ('kuendigung','KEYWORD','kündigung',0.900,'{"de":"Wort Kündigung","en":"The word termination","ru":"Слово «расторжение»"}'),
    ('kuendigung','KEYWORD','kündigungsfrist',0.800,'{"de":"Kündigungsfrist","en":"Notice period","ru":"Срок уведомления"}'),
    ('kuendigung','KEYWORD','zum nächstmöglichen zeitpunkt',0.700,'{"de":"Typische Kündigungsformel","en":"Typical termination wording","ru":"Типичная формулировка расторжения"}'),
    -- Kuendigungsbestaetigung
    ('kuendigungsbestaetigung','KEYWORD','kündigung bestätigen',0.850,'{"de":"Bestätigung der Kündigung","en":"Confirmation of termination","ru":"Подтверждение расторжения"}'),
    ('kuendigungsbestaetigung','KEYWORD','bestätigung ihrer kündigung',0.900,'{"de":"Bestätigung der Kündigung","en":"Termination confirmation","ru":"Подтверждение расторжения"}'),
    -- Vertrag
    ('vertrag','KEYWORD','vertragsparteien',0.700,'{"de":"Vertragsparteien","en":"Contract parties","ru":"Стороны договора"}'),
    ('vertrag','KEYWORD','vertragsgegenstand',0.800,'{"de":"Vertragsgegenstand","en":"Subject matter","ru":"Предмет договора"}'),
    ('vertrag','KEYWORD','laufzeit',0.500,'{"de":"Laufzeit","en":"Term","ru":"Срок действия"}'),
    ('vertrag','KEYWORD','vertrag geschlossen',0.600,'{"de":"Vertragsschluss","en":"Contract concluded","ru":"Договор заключён"}'),
    -- Mietvertrag
    ('mietvertrag','KEYWORD','mietvertrag',0.950,'{"de":"Wort Mietvertrag","en":"Rental agreement","ru":"Договор аренды"}'),
    ('mietvertrag','KEYWORD','mieter',0.600,'{"de":"Mieter","en":"Tenant","ru":"Арендатор"}'),
    ('mietvertrag','KEYWORD','vermieters',0.600,'{"de":"Vermieter","en":"Landlord","ru":"Арендодатель"}'),
    ('mietvertrag','KEYWORD','wohnung',0.300,'{"de":"Wohnung als Vertragsgegenstand","en":"The flat","ru":"Квартира"}'),
    -- Nebenkostenabrechnung
    ('nebenkostenabrechnung','KEYWORD','betriebskostenabrechnung',0.900,'{"de":"Betriebskostenabrechnung","en":"Operating cost statement","ru":"Расчёт эксплуатационных расходов"}'),
    ('nebenkostenabrechnung','KEYWORD','nebenkostenabrechnung',0.900,'{"de":"Nebenkostenabrechnung","en":"Service charge statement","ru":"Расчёт коммунальных платежей"}'),
    ('nebenkostenabrechnung','KEYWORD','umlagefähig',0.700,'{"de":"Umlagefähige Kosten","en":"Apportionable costs","ru":"Распределяемые расходы"}'),
    -- Behoerdenbescheid
    ('behoerdenbescheid','KEYWORD','bescheid',0.700,'{"de":"Bescheid","en":"Official decision","ru":"Решение ведомства"}'),
    ('behoerdenbescheid','KEYWORD','aktenzeichen',0.700,'{"de":"Aktenzeichen","en":"File reference","ru":"Номер дела"}'),
    ('behoerdenbescheid','KEYWORD','rechtsbehelfsbelehrung',0.900,'{"de":"Rechtsbehelfsbelehrung","en":"Legal remedy instruction","ru":"Разъяснение о средствах правовой защиты"}'),
    ('behoerdenbescheid','KEYWORD','widerspruch',0.600,'{"de":"Widerspruch möglich","en":"Objection possible","ru":"Возможно возражение"}'),
    -- Anforderung fehlender Unterlagen
    ('anforderung_fehlender_unterlagen','KEYWORD','reichen sie',0.500,'{"de":"Aufforderung zur Nachreichung","en":"Request to submit","ru":"Требование представить"}'),
    ('anforderung_fehlender_unterlagen','KEYWORD','folgende unterlagen',0.800,'{"de":"Liste der Unterlagen","en":"List of documents","ru":"Перечень документов"}'),
    ('anforderung_fehlender_unterlagen','KEYWORD','nachzureichen',0.700,'{"de":"Nachreichung","en":"Submit later","ru":"Дополнительно представить"}'),
    -- Finanzamt
    ('finanzamt','KEYWORD','finanzamt',0.900,'{"de":"Finanzamt","en":"Tax office","ru":"Налоговая служба"}'),
    ('finanzamt','KEYWORD','steuernummer',0.600,'{"de":"Steuernummer","en":"Tax number","ru":"Налоговый номер"}'),
    ('finanzamt','KEYWORD','steuerbescheid',0.800,'{"de":"Steuerbescheid","en":"Tax assessment","ru":"Налоговое решение"}'),
    -- Krankenkasse
    ('krankenkasse','KEYWORD','krankenkasse',0.900,'{"de":"Krankenkasse","en":"Health insurance","ru":"Медицинская касса"}'),
    ('krankenkasse','KEYWORD','versichertennummer',0.700,'{"de":"Versichertennummer","en":"Insurance number","ru":"Номер застрахованного"}'),
    ('krankenkasse','KEYWORD','beitragsbescheid',0.600,'{"de":"Beitragsbescheid","en":"Contribution notice","ru":"Уведомление о взносах"}'),
    -- Lohnabrechnung
    ('lohnabrechnung','KEYWORD','lohnabrechnung',0.950,'{"de":"Lohnabrechnung","en":"Payslip","ru":"Расчётный лист"}'),
    ('lohnabrechnung','KEYWORD','bruttoarbeitslohn',0.800,'{"de":"Bruttoarbeitslohn","en":"Gross wage","ru":"Начисленная зарплата"}'),
    ('lohnabrechnung','KEYWORD','sozialversicherung',0.500,'{"de":"Sozialversicherungsbeiträge","en":"Social insurance contributions","ru":"Взносы на соцстрахование"}'),
    -- Kontoauszug
    ('kontoauszug','KEYWORD','kontoauszug',0.900,'{"de":"Kontoauszug","en":"Bank statement","ru":"Выписка по счёту"}'),
    ('kontoauszug','KEYWORD','kontostand',0.700,'{"de":"Kontostand","en":"Account balance","ru":"Остаток на счёте"}'),
    ('kontoauszug','KEYWORD','buchungstag',0.600,'{"de":"Buchungstag","en":"Booking date","ru":"Дата проводки"}'),
    ('kontoauszug','KEYWORD','alter kontostand',0.600,'{"de":"Alter Kontostand","en":"Previous balance","ru":"Предыдущий остаток"}'),
    -- Zahlungsbestaetigung
    ('zahlungsbestaetigung','KEYWORD','zahlungsbestätigung',0.900,'{"de":"Zahlungsbestätigung","en":"Payment confirmation","ru":"Подтверждение оплаты"}'),
    ('zahlungsbestaetigung','KEYWORD','zahlung erhalten',0.700,'{"de":"Zahlungseingang","en":"Payment received","ru":"Платёж получен"}'),
    ('zahlungsbestaetigung','KEYWORD','vielen dank für ihre zahlung',0.700,'{"de":"Dank für die Zahlung","en":"Thanks for the payment","ru":"Благодарность за оплату"}'),
    -- Angebot
    ('angebot','KEYWORD','angebot',0.600,'{"de":"Angebot","en":"Quotation","ru":"Коммерческое предложение"}'),
    ('angebot','KEYWORD','angebotsnummer',0.900,'{"de":"Angebotsnummer","en":"Quotation number","ru":"Номер предложения"}'),
    ('angebot','KEYWORD','wir bieten ihnen',0.500,'{"de":"Angebotsformulierung","en":"Quotation wording","ru":"Формулировка предложения"}'),
    -- Auftragsbestaetigung
    ('auftragsbestaetigung','KEYWORD','auftragsbestätigung',0.950,'{"de":"Auftragsbestätigung","en":"Order confirmation","ru":"Подтверждение заказа"}'),
    ('auftragsbestaetigung','KEYWORD','bestellnummer',0.500,'{"de":"Bestellnummer","en":"Order number","ru":"Номер заказа"}'),
    -- Vertragsaenderung / Preiserhoehung
    ('preiserhoehung','KEYWORD','preiserhöhung',0.900,'{"de":"Preiserhöhung","en":"Price increase","ru":"Повышение цены"}'),
    ('preiserhoehung','KEYWORD','anpassung der preise',0.800,'{"de":"Preisanpassung","en":"Price adjustment","ru":"Изменение цены"}'),
    ('vertragsverlaengerung','KEYWORD','vertragsverlängerung',0.900,'{"de":"Vertragsverlängerung","en":"Contract renewal","ru":"Продление договора"}'),
    ('vertragsverlaengerung','KEYWORD','verlängert sich automatisch',0.700,'{"de":"Automatische Verlängerung","en":"Automatic renewal","ru":"Автоматическое продление"}'),
    -- Reklame / Newsletter
    ('reklame','KEYWORD','rabatt',0.400,'{"de":"Werbung mit Rabatt","en":"Advertising discount","ru":"Рекламная скидка"}'),
    ('reklame','KEYWORD','jetzt bestellen',0.600,'{"de":"Werbesprache","en":"Advertising call to action","ru":"Рекламный призыв"}'),
    ('newsletter','KEYWORD','newsletter',0.800,'{"de":"Newsletter","en":"Newsletter","ru":"Рассылка"}'),
    ('newsletter','KEYWORD','abmelden',0.500,'{"de":"Abmeldelink","en":"Unsubscribe link","ru":"Ссылка отписки"}')
) AS r(type_code, rule_type, pattern, weight, description)
  ON r.type_code = dt.code;
