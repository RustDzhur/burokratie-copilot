-- =====================================================================================
-- V12 — Reference data: permissions, base roles, document taxonomy, case types,
--       holiday calendars.
--
-- IMPORTANT: this migration intentionally contains NO legal deadlines, NO tax rates and
-- NO retention periods. Those are customer-specific, must be verifiable, and are created
-- by a responsible person through the settings UI (§31, §12.3, §14.2, §22).
-- German nationwide public holidays are statutory facts and are seeded for convenience;
-- region-specific holidays remain configurable per federal state (§24).
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Permissions (area × action)
-- -------------------------------------------------------------------------------------
INSERT INTO permissions (code, area, action, name) VALUES
-- documents
('documents.view','documents','view','{"de":"Dokumente ansehen","en":"View documents","ru":"Просмотр документов"}'),
('documents.create','documents','create','{"de":"Dokumente hochladen","en":"Upload documents","ru":"Загрузка документов"}'),
('documents.edit','documents','edit','{"de":"Dokumente bearbeiten","en":"Edit documents","ru":"Редактирование документов"}'),
('documents.delete','documents','delete','{"de":"Dokumente löschen","en":"Delete documents","ru":"Удаление документов"}'),
('documents.export','documents','export','{"de":"Dokumente exportieren","en":"Export documents","ru":"Экспорт документов"}'),
('documents.review','documents','review','{"de":"Dokumente prüfen","en":"Review documents","ru":"Проверка документов"}'),
('documents.approve','documents','approve','{"de":"Dokumente freigeben","en":"Approve documents","ru":"Утверждение документов"}'),
-- cases
('cases.view','cases','view','{"de":"Vorgänge ansehen","en":"View cases","ru":"Просмотр дел"}'),
('cases.create','cases','create','{"de":"Vorgänge anlegen","en":"Create cases","ru":"Создание дел"}'),
('cases.edit','cases','edit','{"de":"Vorgänge bearbeiten","en":"Edit cases","ru":"Редактирование дел"}'),
('cases.delete','cases','delete','{"de":"Vorgänge löschen","en":"Delete cases","ru":"Удаление дел"}'),
('cases.close','cases','close','{"de":"Vorgänge abschließen","en":"Close cases","ru":"Закрытие дел"}'),
('cases.assign','cases','assign','{"de":"Vorgänge zuweisen","en":"Assign cases","ru":"Назначение ответственных"}'),
('cases.export','cases','export','{"de":"Vorgänge exportieren","en":"Export cases","ru":"Экспорт дел"}'),
-- tasks
('tasks.view','tasks','view','{"de":"Aufgaben ansehen","en":"View tasks","ru":"Просмотр задач"}'),
('tasks.create','tasks','create','{"de":"Aufgaben erstellen","en":"Create tasks","ru":"Создание задач"}'),
('tasks.edit','tasks','edit','{"de":"Aufgaben bearbeiten","en":"Edit tasks","ru":"Редактирование задач"}'),
('tasks.delete','tasks','delete','{"de":"Aufgaben löschen","en":"Delete tasks","ru":"Удаление задач"}'),
('tasks.assign','tasks','assign','{"de":"Aufgaben zuweisen","en":"Assign tasks","ru":"Назначение задач"}'),
('tasks.complete','tasks','complete','{"de":"Aufgaben abschließen","en":"Complete tasks","ru":"Завершение задач"}'),
('tasks.approve','tasks','approve','{"de":"Aufgaben freigeben","en":"Approve tasks","ru":"Утверждение задач"}'),
-- contracts
('contracts.view','contracts','view','{"de":"Verträge ansehen","en":"View contracts","ru":"Просмотр договоров"}'),
('contracts.create','contracts','create','{"de":"Verträge anlegen","en":"Create contracts","ru":"Создание договоров"}'),
('contracts.edit','contracts','edit','{"de":"Verträge bearbeiten","en":"Edit contracts","ru":"Редактирование договоров"}'),
('contracts.delete','contracts','delete','{"de":"Verträge löschen","en":"Delete contracts","ru":"Удаление договоров"}'),
('contracts.terminate','contracts','terminate','{"de":"Verträge kündigen","en":"Terminate contracts","ru":"Расторжение договоров"}'),
('contracts.export','contracts','export','{"de":"Verträge exportieren","en":"Export contracts","ru":"Экспорт договоров"}'),
-- finance
('finance.view','finance','view','{"de":"Finanzen ansehen","en":"View finance","ru":"Просмотр финансов"}'),
('finance.create','finance','create','{"de":"Finanzbelege anlegen","en":"Create finance records","ru":"Создание финансовых документов"}'),
('finance.edit','finance','edit','{"de":"Finanzbelege bearbeiten","en":"Edit finance records","ru":"Редактирование финансовых документов"}'),
('finance.delete','finance','delete','{"de":"Finanzbelege löschen","en":"Delete finance records","ru":"Удаление финансовых документов"}'),
('finance.approve','finance','approve','{"de":"Rechnungen freigeben","en":"Approve invoices","ru":"Утверждение счетов"}'),
('finance.execute_payment','finance','execute_payment','{"de":"Zahlungen bestätigen","en":"Confirm payments","ru":"Подтверждение платежей"}'),
('finance.reconcile','finance','reconcile','{"de":"Zahlungen abgleichen","en":"Reconcile payments","ru":"Сверка платежей"}'),
('finance.export','finance','export','{"de":"Finanzen exportieren","en":"Export finance","ru":"Экспорт финансов"}'),
-- contacts
('contacts.view','contacts','view','{"de":"Kontakte ansehen","en":"View contacts","ru":"Просмотр контактов"}'),
('contacts.create','contacts','create','{"de":"Kontakte anlegen","en":"Create contacts","ru":"Создание контактов"}'),
('contacts.edit','contacts','edit','{"de":"Kontakte bearbeiten","en":"Edit contacts","ru":"Редактирование контактов"}'),
('contacts.delete','contacts','delete','{"de":"Kontakte löschen","en":"Delete contacts","ru":"Удаление контактов"}'),
('contacts.export','contacts','export','{"de":"Kontakte exportieren","en":"Export contacts","ru":"Экспорт контактов"}'),
-- mail
('mail.view','mail','view','{"de":"E-Mails ansehen","en":"View mail","ru":"Просмотр почты"}'),
('mail.connect','mail','connect','{"de":"Postfächer verbinden","en":"Connect mailboxes","ru":"Подключение почтовых ящиков"}'),
('mail.send','mail','send','{"de":"E-Mails senden","en":"Send mail","ru":"Отправка писем"}'),
('mail.delete','mail','delete','{"de":"E-Mails löschen","en":"Delete mail","ru":"Удаление писем"}'),
('mail.configure','mail','configure','{"de":"Postregeln konfigurieren","en":"Configure mail rules","ru":"Настройка правил почты"}'),
-- templates
('templates.view','templates','view','{"de":"Vorlagen ansehen","en":"View templates","ru":"Просмотр шаблонов"}'),
('templates.create','templates','create','{"de":"Vorlagen erstellen","en":"Create templates","ru":"Создание шаблонов"}'),
('templates.edit','templates','edit','{"de":"Vorlagen bearbeiten","en":"Edit templates","ru":"Редактирование шаблонов"}'),
('templates.delete','templates','delete','{"de":"Vorlagen löschen","en":"Delete templates","ru":"Удаление шаблонов"}'),
('templates.publish','templates','publish','{"de":"Vorlagen veröffentlichen","en":"Publish templates","ru":"Публикация шаблонов"}'),
-- workflows
('workflows.view','workflows','view','{"de":"Prozesse ansehen","en":"View workflows","ru":"Просмотр процессов"}'),
('workflows.create','workflows','create','{"de":"Prozesse erstellen","en":"Create workflows","ru":"Создание процессов"}'),
('workflows.edit','workflows','edit','{"de":"Prozesse bearbeiten","en":"Edit workflows","ru":"Редактирование процессов"}'),
('workflows.publish','workflows','publish','{"de":"Prozesse veröffentlichen","en":"Publish workflows","ru":"Публикация процессов"}'),
('workflows.execute','workflows','execute','{"de":"Prozesse starten","en":"Execute workflows","ru":"Запуск процессов"}'),
('workflows.cancel','workflows','cancel','{"de":"Prozesse abbrechen","en":"Cancel workflows","ru":"Отмена процессов"}'),
-- integrations
('integrations.view','integrations','view','{"de":"Integrationen ansehen","en":"View integrations","ru":"Просмотр интеграций"}'),
('integrations.connect','integrations','connect','{"de":"Integrationen verbinden","en":"Connect integrations","ru":"Подключение интеграций"}'),
('integrations.disconnect','integrations','disconnect','{"de":"Integrationen trennen","en":"Disconnect integrations","ru":"Отключение интеграций"}'),
('integrations.configure','integrations','configure','{"de":"Integrationen konfigurieren","en":"Configure integrations","ru":"Настройка интеграций"}'),
-- devices
('devices.view','devices','view','{"de":"Geräte ansehen","en":"View devices","ru":"Просмотр устройств"}'),
('devices.register','devices','register','{"de":"Geräte registrieren","en":"Register devices","ru":"Регистрация устройств"}'),
('devices.manage','devices','manage','{"de":"Geräte verwalten","en":"Manage devices","ru":"Управление устройствами"}'),
('devices.scan','devices','scan','{"de":"Scannen starten","en":"Start scanning","ru":"Запуск сканирования"}'),
-- staff
('staff.view','staff','view','{"de":"Mitarbeiter ansehen","en":"View staff","ru":"Просмотр сотрудников"}'),
('staff.invite','staff','invite','{"de":"Mitarbeiter einladen","en":"Invite staff","ru":"Приглашение сотрудников"}'),
('staff.manage_roles','staff','manage_roles','{"de":"Rollen verwalten","en":"Manage roles","ru":"Управление ролями"}'),
('staff.remove','staff','remove','{"de":"Zugriff entziehen","en":"Revoke access","ru":"Отзыв доступа"}'),
-- settings
('settings.view','settings','view','{"de":"Einstellungen ansehen","en":"View settings","ru":"Просмотр настроек"}'),
('settings.edit','settings','edit','{"de":"Einstellungen bearbeiten","en":"Edit settings","ru":"Редактирование настроек"}'),
('settings.manage_rules','settings','manage_rules','{"de":"Fachliche Regeln verwalten","en":"Manage business rules","ru":"Управление бизнес-правилами"}'),
('settings.manage_retention','settings','manage_retention','{"de":"Aufbewahrung verwalten","en":"Manage retention","ru":"Управление хранением"}'),
('settings.transfer_ownership','settings','transfer_ownership','{"de":"Eigentum übertragen","en":"Transfer ownership","ru":"Передача владения"}'),
('settings.delete_tenant','settings','delete_tenant','{"de":"Konto löschen","en":"Delete account","ru":"Удаление аккаунта"}'),
-- audit
('audit.view','audit','view','{"de":"Audit-Log ansehen","en":"View audit log","ru":"Просмотр журнала аудита"}'),
('audit.export','audit','export','{"de":"Audit-Log exportieren","en":"Export audit log","ru":"Экспорт журнала аудита"}'),
-- reports
('reports.view','reports','view','{"de":"Berichte ansehen","en":"View reports","ru":"Просмотр отчётов"}'),
('reports.export','reports','export','{"de":"Berichte exportieren","en":"Export reports","ru":"Экспорт отчётов"}'),
-- notifications
('notifications.view','notifications','view','{"de":"Benachrichtigungen ansehen","en":"View notifications","ru":"Просмотр уведомлений"}'),
('notifications.configure','notifications','configure','{"de":"Benachrichtigungen konfigurieren","en":"Configure notifications","ru":"Настройка уведомлений"}');

-- -------------------------------------------------------------------------------------
-- Base roles (§4)
-- -------------------------------------------------------------------------------------
INSERT INTO roles (code, tenant_id, name, description, is_system, rank) VALUES
('OWNER',        NULL, '{"de":"Inhaber","en":"Owner","ru":"Владелец"}',
 '{"de":"Vollzugriff auf das gesamte Konto","en":"Full access to the whole account","ru":"Полный доступ к аккаунту"}', true, 10),
('ADMIN',        NULL, '{"de":"Administrator","en":"Administrator","ru":"Администратор"}',
 '{"de":"Verwaltet Konto, Rollen und Einstellungen","en":"Manages account, roles and settings","ru":"Управляет аккаунтом, ролями и настройками"}', true, 20),
('MANAGER',      NULL, '{"de":"Leitung","en":"Manager","ru":"Руководитель"}',
 '{"de":"Führt Vorgänge, Aufgaben und Freigaben durch","en":"Runs cases, tasks and approvals","ru":"Ведёт дела, задачи и согласования"}', true, 30),
('EMPLOYEE',     NULL, '{"de":"Mitarbeiter","en":"Employee","ru":"Сотрудник"}',
 '{"de":"Bearbeitet zugewiesene Vorgänge und Dokumente","en":"Works on assigned cases and documents","ru":"Работает с назначенными делами и документами"}', true, 40),
('ACCOUNTANT',   NULL, '{"de":"Buchhaltung","en":"Accountant","ru":"Бухгалтер"}',
 '{"de":"Zuständig für Finanzen und Belege","en":"Responsible for finance and receipts","ru":"Отвечает за финансы и документы"}', true, 40),
('LEGAL_ADVISOR',NULL, '{"de":"Rechtsberatung","en":"Legal advisor","ru":"Юридический консультант"}',
 '{"de":"Verträge und rechtliche Vorgänge","en":"Contracts and legal matters","ru":"Договоры и юридические вопросы"}', true, 40),
('EXTERNAL_ADVISOR',NULL,'{"de":"Externe Beratung","en":"External advisor","ru":"Внешний специалист"}',
 '{"de":"Zugriff nur auf zugewiesene Vorgänge","en":"Access limited to assigned cases","ru":"Доступ только к назначенным делам"}', true, 60),
('FAMILY_MEMBER',NULL, '{"de":"Familienmitglied","en":"Family member","ru":"Член семьи"}',
 '{"de":"Eigene Dokumente und gemeinsame Vorgänge","en":"Own documents and shared cases","ru":"Свои документы и общие дела"}', true, 50),
('AUDITOR',      NULL, '{"de":"Prüfung","en":"Auditor","ru":"Аудитор"}',
 '{"de":"Zugriff auf Protokolle und Nachweise","en":"Access to logs and evidence","ru":"Доступ к журналам и доказательствам"}', true, 70),
('READ_ONLY',    NULL, '{"de":"Nur Lesen","en":"Read only","ru":"Только чтение"}',
 '{"de":"Keine Änderungen möglich","en":"No changes possible","ru":"Без права изменений"}', true, 90);

-- -------------------------------------------------------------------------------------
-- Role → permission mapping
-- -------------------------------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON
    -- OWNER: everything.
    (r.code = 'OWNER')
    -- ADMIN: everything except ownership transfer and account deletion.
    OR (r.code = 'ADMIN' AND p.code NOT IN ('settings.transfer_ownership', 'settings.delete_tenant'))
    -- MANAGER: operational lead.
    OR (r.code = 'MANAGER' AND p.code = ANY (ARRAY[
        'documents.view','documents.create','documents.edit','documents.export','documents.review','documents.approve',
        'cases.view','cases.create','cases.edit','cases.close','cases.assign','cases.export',
        'tasks.view','tasks.create','tasks.edit','tasks.assign','tasks.complete','tasks.approve',
        'contracts.view','contracts.create','contracts.edit','contracts.export',
        'finance.view','finance.create','finance.edit','finance.approve','finance.reconcile','finance.export',
        'contacts.view','contacts.create','contacts.edit','contacts.export',
        'mail.view','mail.send','templates.view','templates.create','templates.edit',
        'workflows.view','workflows.execute','integrations.view','devices.view','devices.scan',
        'staff.view','settings.view','audit.view','reports.view','reports.export',
        'notifications.view','notifications.configure']))
    -- EMPLOYEE: day-to-day work.
    OR (r.code = 'EMPLOYEE' AND p.code = ANY (ARRAY[
        'documents.view','documents.create','documents.edit','documents.export',
        'cases.view','cases.create','cases.edit',
        'tasks.view','tasks.create','tasks.edit','tasks.complete',
        'contacts.view','contacts.create','contacts.edit',
        'mail.view','mail.send','templates.view','workflows.view',
        'devices.view','devices.scan','reports.view','notifications.view']))
    -- ACCOUNTANT: finance focus.
    OR (r.code = 'ACCOUNTANT' AND p.code = ANY (ARRAY[
        'documents.view','documents.export','cases.view','tasks.view','tasks.create','tasks.edit','tasks.complete',
        'contracts.view','finance.view','finance.create','finance.edit','finance.reconcile','finance.export',
        'contacts.view','contacts.create','contacts.edit','reports.view','reports.export',
        'audit.view','notifications.view']))
    -- LEGAL_ADVISOR: contracts and legal cases.
    OR (r.code = 'LEGAL_ADVISOR' AND p.code = ANY (ARRAY[
        'documents.view','documents.edit','documents.export','documents.review',
        'cases.view','cases.create','cases.edit','cases.close','cases.export',
        'tasks.view','tasks.create','tasks.edit','tasks.complete',
        'contracts.view','contracts.create','contracts.edit','contracts.terminate','contracts.export',
        'contacts.view','templates.view','templates.create','templates.edit',
        'reports.view','notifications.view']))
    -- EXTERNAL_ADVISOR: only what is assigned to them.
    OR (r.code = 'EXTERNAL_ADVISOR' AND p.code = ANY (ARRAY[
        'documents.view','documents.export','cases.view','tasks.view','tasks.create','tasks.edit','tasks.complete',
        'contracts.view','reports.view','notifications.view']))
    -- FAMILY_MEMBER: limited household rights.
    OR (r.code = 'FAMILY_MEMBER' AND p.code = ANY (ARRAY[
        'documents.view','documents.create','documents.edit','cases.view','cases.create',
        'tasks.view','tasks.create','tasks.edit','tasks.complete','contacts.view',
        'mail.view','devices.view','devices.scan','notifications.view']))
    -- AUDITOR: evidence and logs, read-only.
    OR (r.code = 'AUDITOR' AND p.code = ANY (ARRAY[
        'audit.view','audit.export','documents.view','documents.export','cases.view',
        'contracts.view','finance.view','reports.view','reports.export','settings.view']))
    -- READ_ONLY: view permissions only.
    OR (r.code = 'READ_ONLY' AND p.action = 'view')
WHERE r.tenant_id IS NULL;

-- -------------------------------------------------------------------------------------
-- Document taxonomy (§9.3) — editable by an administrator afterwards.
-- -------------------------------------------------------------------------------------
INSERT INTO document_types (code, category, name, requires_review) VALUES
-- Finanzen
('rechnung','FINANCE','{"de":"Rechnung","en":"Invoice","ru":"Счёт"}',false),
('gutschrift','FINANCE','{"de":"Gutschrift","en":"Credit note","ru":"Кредит-нота"}',false),
('zahlungserinnerung','FINANCE','{"de":"Zahlungserinnerung","en":"Payment reminder","ru":"Напоминание об оплате"}',false),
('mahnung','FINANCE','{"de":"Mahnung","en":"Dunning letter","ru":"Требование об оплате"}',false),
('inkasso','FINANCE','{"de":"Inkasso","en":"Debt collection","ru":"Взыскание долга"}',true),
('kontoauszug','FINANCE','{"de":"Kontoauszug","en":"Bank statement","ru":"Выписка по счёту"}',false),
('zahlungsbestaetigung','FINANCE','{"de":"Zahlungsbestätigung","en":"Payment confirmation","ru":"Подтверждение оплаты"}',false),
('angebot','FINANCE','{"de":"Angebot","en":"Quotation","ru":"Коммерческое предложение"}',false),
('bestellung','FINANCE','{"de":"Bestellung","en":"Order","ru":"Заказ"}',false),
('auftragsbestaetigung','FINANCE','{"de":"Auftragsbestätigung","en":"Order confirmation","ru":"Подтверждение заказа"}',false),
('lieferschein','FINANCE','{"de":"Lieferschein","en":"Delivery note","ru":"Накладная"}',false),
('kostenaufstellung','FINANCE','{"de":"Kostenaufstellung","en":"Cost breakdown","ru":"Смета расходов"}',false),
('nebenkostenabrechnung','FINANCE','{"de":"Nebenkostenabrechnung","en":"Service charge statement","ru":"Расчёт коммунальных платежей"}',true),
('beitragsbescheid','FINANCE','{"de":"Beitragsbescheid","en":"Contribution notice","ru":"Уведомление о взносах"}',true),
-- Verträge
('vertrag','CONTRACTS','{"de":"Vertrag","en":"Contract","ru":"Договор"}',true),
('vertragsaenderung','CONTRACTS','{"de":"Vertragsänderung","en":"Contract amendment","ru":"Изменение договора"}',true),
('kuendigung','CONTRACTS','{"de":"Kündigung","en":"Termination","ru":"Расторжение"}',true),
('kuendigungsbestaetigung','CONTRACTS','{"de":"Kündigungsbestätigung","en":"Termination confirmation","ru":"Подтверждение расторжения"}',false),
('vertragsverlaengerung','CONTRACTS','{"de":"Vertragsverlängerung","en":"Contract renewal","ru":"Продление договора"}',true),
('preiserhoehung','CONTRACTS','{"de":"Preiserhöhung","en":"Price increase","ru":"Повышение цены"}',true),
('widerrufsbelehrung','CONTRACTS','{"de":"Widerrufsbelehrung","en":"Right of withdrawal notice","ru":"Уведомление о праве отказа"}',true),
('garantie','CONTRACTS','{"de":"Garantie","en":"Guarantee","ru":"Гарантия"}',false),
('gewaehrleistung','CONTRACTS','{"de":"Gewährleistung","en":"Warranty","ru":"Гарантийные обязательства"}',true),
('agb','CONTRACTS','{"de":"AGB","en":"Terms and conditions","ru":"Общие условия"}',true),
('nachtrag','CONTRACTS','{"de":"Nachtrag","en":"Addendum","ru":"Дополнение к договору"}',true),
('leistungsbeschreibung','CONTRACTS','{"de":"Leistungsbeschreibung","en":"Scope of services","ru":"Описание услуг"}',false),
-- Behörden
('finanzamt','GOVERNMENT','{"de":"Finanzamt","en":"Tax office","ru":"Налоговая служба"}',true),
('krankenkasse','GOVERNMENT','{"de":"Krankenkasse","en":"Health insurance","ru":"Медицинская касса"}',true),
('rentenversicherung','GOVERNMENT','{"de":"Rentenversicherung","en":"Pension insurance","ru":"Пенсионное страхование"}',true),
('jobcenter','GOVERNMENT','{"de":"Jobcenter","en":"Job centre","ru":"Джобцентр"}',true),
('agentur_fuer_arbeit','GOVERNMENT','{"de":"Agentur für Arbeit","en":"Employment agency","ru":"Агентство по труду"}',true),
('buergeramt','GOVERNMENT','{"de":"Bürgeramt","en":"Citizens office","ru":"Гражданский офис"}',true),
('familienkasse','GOVERNMENT','{"de":"Familienkasse","en":"Family benefits office","ru":"Касса семейных пособий"}',true),
('wohngeldstelle','GOVERNMENT','{"de":"Wohngeldstelle","en":"Housing benefit office","ru":"Служба жилищных субсидий"}',true),
('sozialamt','GOVERNMENT','{"de":"Sozialamt","en":"Social services office","ru":"Социальная служба"}',true),
('behoerdenbescheid','GOVERNMENT','{"de":"Behördenbescheid","en":"Official decision","ru":"Решение ведомства"}',true),
('anforderung_fehlender_unterlagen','GOVERNMENT','{"de":"Anforderung fehlender Unterlagen","en":"Request for missing documents","ru":"Запрос недостающих документов"}',true),
('widerspruch','GOVERNMENT','{"de":"Widerspruch","en":"Objection","ru":"Возражение"}',true),
('anhoerung','GOVERNMENT','{"de":"Anhörung","en":"Hearing","ru":"Запрос пояснений"}',true),
('fristsetzung','GOVERNMENT','{"de":"Fristsetzung","en":"Deadline notice","ru":"Установление срока"}',true),
-- Wohnen
('mietvertrag','HOUSING','{"de":"Mietvertrag","en":"Rental agreement","ru":"Договор аренды"}',true),
('mietaenderung','HOUSING','{"de":"Mietänderung","en":"Rent change","ru":"Изменение арендной платы"}',true),
('betriebskostenabrechnung','HOUSING','{"de":"Betriebskostenabrechnung","en":"Operating cost statement","ru":"Расчёт эксплуатационных расходов"}',true),
('kautionsabrechnung','HOUSING','{"de":"Kautionsabrechnung","en":"Deposit statement","ru":"Расчёт залога"}',true),
('uebergabeprotokoll','HOUSING','{"de":"Übergabeprotokoll","en":"Handover protocol","ru":"Протокол передачи"}',false),
('hausverwaltung','HOUSING','{"de":"Hausverwaltung","en":"Property management","ru":"Управляющая компания"}',false),
('handwerkerangebot','HOUSING','{"de":"Handwerkerangebot","en":"Craftsman quotation","ru":"Предложение подрядчика"}',false),
('reparaturrechnung','HOUSING','{"de":"Reparaturrechnung","en":"Repair invoice","ru":"Счёт за ремонт"}',false),
('eigentumsunterlagen','HOUSING','{"de":"Eigentumsunterlagen","en":"Ownership documents","ru":"Документы о собственности"}',true),
-- Arbeit
('arbeitsvertrag','EMPLOYMENT','{"de":"Arbeitsvertrag","en":"Employment contract","ru":"Трудовой договор"}',true),
('lohnabrechnung','EMPLOYMENT','{"de":"Lohnabrechnung","en":"Payslip","ru":"Расчётный лист"}',false),
('arbeitszeugnis','EMPLOYMENT','{"de":"Arbeitszeugnis","en":"Employment reference","ru":"Трудовая характеристика"}',false),
('urlaubsantrag','EMPLOYMENT','{"de":"Urlaubsantrag","en":"Leave request","ru":"Заявление на отпуск"}',false),
('krankmeldung','EMPLOYMENT','{"de":"Krankmeldung","en":"Sick note","ru":"Больничный лист"}',true),
('personalunterlagen','EMPLOYMENT','{"de":"Personalunterlagen","en":"Personnel documents","ru":"Кадровые документы"}',true),
('bewerbungsunterlagen','EMPLOYMENT','{"de":"Bewerbungsunterlagen","en":"Application documents","ru":"Документы для трудоустройства"}',false),
('arbeitgeberbescheinigung','EMPLOYMENT','{"de":"Arbeitgeberbescheinigung","en":"Employer certificate","ru":"Справка от работодателя"}',false),
-- Familie, Bildung, Gesundheit
('kita','FAMILY_EDUCATION_HEALTH','{"de":"Kita","en":"Day care","ru":"Детский сад"}',false),
('schule','FAMILY_EDUCATION_HEALTH','{"de":"Schule","en":"School","ru":"Школа"}',false),
('universitaet','FAMILY_EDUCATION_HEALTH','{"de":"Universität","en":"University","ru":"Университет"}',false),
('familienkommunikation','FAMILY_EDUCATION_HEALTH','{"de":"Familienkommunikation","en":"Family communication","ru":"Семейная переписка"}',false),
('elternbeitrag','FAMILY_EDUCATION_HEALTH','{"de":"Elternbeitrag","en":"Parental contribution","ru":"Родительский взнос"}',false),
('versicherungsunterlagen','FAMILY_EDUCATION_HEALTH','{"de":"Versicherungsunterlagen","en":"Insurance documents","ru":"Страховые документы"}',true),
('medizinische_rechnung','FAMILY_EDUCATION_HEALTH','{"de":"Medizinische Rechnung","en":"Medical invoice","ru":"Медицинский счёт"}',true),
('terminbestaetigung','FAMILY_EDUCATION_HEALTH','{"de":"Terminbestätigung","en":"Appointment confirmation","ru":"Подтверждение записи"}',false),
('erstattungsbescheid','FAMILY_EDUCATION_HEALTH','{"de":"Erstattungsbescheid","en":"Reimbursement decision","ru":"Решение о возмещении"}',true),
-- Recht
('schreiben_anwalt','LEGAL','{"de":"Schreiben vom Anwalt","en":"Letter from a lawyer","ru":"Письмо от адвоката"}',true),
('gerichtliche_mitteilung','LEGAL','{"de":"Gerichtliche Mitteilung","en":"Court communication","ru":"Судебное уведомление"}',true),
('vollstreckungsbescheid','LEGAL','{"de":"Vollstreckungsbescheid","en":"Enforcement order","ru":"Исполнительный лист"}',true),
('mahnbescheid','LEGAL','{"de":"Mahnbescheid","en":"Payment order","ru":"Судебный приказ о взыскании"}',true),
('rechtsbehelfsbelehrung','LEGAL','{"de":"Rechtsbehelfsbelehrung","en":"Legal remedy instruction","ru":"Разъяснение о средствах правовой защиты"}',true),
-- Sonstiges
('reklame','OTHER','{"de":"Reklame","en":"Advertising","ru":"Реклама"}',false),
('newsletter','OTHER','{"de":"Newsletter","en":"Newsletter","ru":"Рассылка"}',false),
('informationsschreiben','OTHER','{"de":"Informationsschreiben","en":"Information letter","ru":"Информационное письмо"}',false),
('kopie','OTHER','{"de":"Kopie","en":"Copy","ru":"Копия"}',false),
('unbekannt','OTHER','{"de":"Unbekannt","en":"Unknown","ru":"Неизвестно"}',true),
('verdaechtiges_dokument','OTHER','{"de":"Verdächtiges Dokument","en":"Suspicious document","ru":"Подозрительный документ"}',true);

-- -------------------------------------------------------------------------------------
-- Field catalogue for the most important document types (§9.4).
-- Only fields that are genuinely applicable are defined; nothing is invented.
-- -------------------------------------------------------------------------------------
INSERT INTO document_type_fields (document_type_id, field_key, label, value_type, is_required, is_critical)
SELECT dt.id, f.field_key, f.label::jsonb, f.value_type, f.is_required, f.is_critical
FROM document_types dt
JOIN (VALUES
    -- Rechnung
    ('rechnung','Rechnungsnummer','{"de":"Rechnungsnummer","en":"Invoice number","ru":"Номер счёта"}','REFERENCE',true,true),
    ('rechnung','Rechnungsdatum','{"de":"Rechnungsdatum","en":"Invoice date","ru":"Дата счёта"}','DATE',true,true),
    ('rechnung','Lieferant','{"de":"Lieferant","en":"Supplier","ru":"Поставщик"}','TEXT',true,true),
    ('rechnung','Rechnungsempfaenger','{"de":"Rechnungsempfänger","en":"Invoice recipient","ru":"Получатель счёта"}','TEXT',false,false),
    ('rechnung','Leistungsdatum','{"de":"Leistungsdatum","en":"Service date","ru":"Дата услуги"}','DATE',false,false),
    ('rechnung','Netto','{"de":"Netto","en":"Net amount","ru":"Сумма без налога"}','AMOUNT',true,true),
    ('rechnung','Umsatzsteuer','{"de":"Umsatzsteuer","en":"VAT","ru":"НДС"}','AMOUNT',false,true),
    ('rechnung','Brutto','{"de":"Brutto","en":"Gross amount","ru":"Итоговая сумма"}','AMOUNT',true,true),
    ('rechnung','Waehrung','{"de":"Währung","en":"Currency","ru":"Валюта"}','CURRENCY',false,false),
    ('rechnung','Zahlungsziel','{"de":"Zahlungsziel","en":"Payment due date","ru":"Срок оплаты"}','DATE',false,true),
    ('rechnung','IBAN','{"de":"IBAN","en":"IBAN","ru":"IBAN"}','IBAN',false,true),
    ('rechnung','Bestellreferenz','{"de":"Bestellreferenz","en":"Order reference","ru":"Ссылка на заказ"}','REFERENCE',false,false),
    -- Vertrag
    ('vertrag','Vertragsparteien','{"de":"Vertragsparteien","en":"Contract parties","ru":"Стороны договора"}','LIST',true,true),
    ('vertrag','Vertragsgegenstand','{"de":"Vertragsgegenstand","en":"Subject matter","ru":"Предмет договора"}','TEXT',true,true),
    ('vertrag','Beginn','{"de":"Beginn","en":"Start","ru":"Начало"}','DATE',false,true),
    ('vertrag','Laufzeit','{"de":"Laufzeit","en":"Term","ru":"Срок действия"}','PERIOD',false,true),
    ('vertrag','Verlaengerungsklausel','{"de":"Verlängerungsklausel","en":"Renewal clause","ru":"Условие продления"}','TEXT',false,true),
    ('vertrag','Kuendigungsfrist','{"de":"Kündigungsfrist","en":"Notice period","ru":"Срок уведомления"}','PERIOD',false,true),
    ('vertrag','Kosten','{"de":"Kosten","en":"Costs","ru":"Стоимость"}','AMOUNT',false,false),
    ('vertrag','Preisaenderungsregelung','{"de":"Preisänderungsregelung","en":"Price adjustment rule","ru":"Правило изменения цены"}','TEXT',false,false),
    ('vertrag','Pflichten','{"de":"Pflichten","en":"Obligations","ru":"Обязанности"}','LIST',false,false),
    ('vertrag','Anlagen','{"de":"Referenzierte Anlagen","en":"Referenced annexes","ru":"Упомянутые приложения"}','LIST',false,false),
    -- Behördenbescheid
    ('behoerdenbescheid','Behoerde','{"de":"Behörde","en":"Authority","ru":"Ведомство"}','TEXT',true,true),
    ('behoerdenbescheid','Aktenzeichen','{"de":"Aktenzeichen","en":"File reference","ru":"Номер дела"}','REFERENCE',true,true),
    ('behoerdenbescheid','Bescheiddatum','{"de":"Bescheiddatum","en":"Decision date","ru":"Дата решения"}','DATE',true,true),
    ('behoerdenbescheid','Adressat','{"de":"Adressat","en":"Addressee","ru":"Адресат"}','TEXT',true,false),
    ('behoerdenbescheid','AngeforderteUnterlagen','{"de":"Angeforderte Unterlagen","en":"Requested documents","ru":"Запрошенные документы"}','LIST',false,true),
    ('behoerdenbescheid','Frist','{"de":"Genannte Frist","en":"Stated deadline","ru":"Указанный срок"}','DATE',false,true),
    ('behoerdenbescheid','Rechtsbehelf','{"de":"Rechtsbehelfsinformation","en":"Legal remedy information","ru":"Информация о правовой защите"}','TEXT',false,true),
    ('behoerdenbescheid','ErforderlicheAntwort','{"de":"Erforderliche Antwort","en":"Required response","ru":"Требуемый ответ"}','TEXT',false,false),
    -- Mahnung
    ('mahnung','Rechnungsnummer','{"de":"Rechnungsnummer","en":"Invoice number","ru":"Номер счёта"}','REFERENCE',true,true),
    ('mahnung','Mahngebuehr','{"de":"Mahngebühr","en":"Dunning fee","ru":"Плата за напоминание"}','AMOUNT',false,false),
    ('mahnung','OffenerBetrag','{"de":"Offener Betrag","en":"Outstanding amount","ru":"Сумма к оплате"}','AMOUNT',true,true),
    ('mahnung','Frist','{"de":"Zahlungsfrist","en":"Payment deadline","ru":"Срок оплаты"}','DATE',true,true),
    -- Kontoauszug
    ('kontoauszug','Kontonummer','{"de":"Kontonummer/IBAN","en":"Account/IBAN","ru":"Счёт/IBAN"}','IBAN',false,true),
    ('kontoauszug','Auszugszeitraum','{"de":"Auszugszeitraum","en":"Statement period","ru":"Период выписки"}','PERIOD',true,false),
    ('kontoauszug','Saldo','{"de":"Saldo","en":"Balance","ru":"Остаток"}','AMOUNT',false,false)
) AS f(type_code, field_key, label, value_type, is_required, is_critical)
  ON f.type_code = dt.code;

-- -------------------------------------------------------------------------------------
-- Case types + document checklists (drives "what is missing for this case?")
-- -------------------------------------------------------------------------------------
INSERT INTO case_types (tenant_id, code, name, description, default_priority) VALUES
(NULL,'kuendigung','{"de":"Kündigung","en":"Termination","ru":"Расторжение договора"}','{"de":"Vertrag kündigen und Bestätigung einholen","en":"Terminate a contract and obtain confirmation","ru":"Расторжение договора и получение подтверждения"}','NORMAL'),
(NULL,'rechnungsklaerung','{"de":"Rechnungsklärung","en":"Invoice clarification","ru":"Разбор счёта"}','{"de":"Streit oder Unklarheit zu einer Rechnung","en":"Dispute or uncertainty about an invoice","ru":"Спор или неясность по счёту"}','HIGH'),
(NULL,'behoerdenanfrage','{"de":"Behördenanfrage","en":"Authority request","ru":"Запрос ведомства"}','{"de":"Antwort auf ein Schreiben einer Behörde","en":"Response to a letter from an authority","ru":"Ответ на письмо ведомства"}','HIGH'),
(NULL,'leistungsantrag','{"de":"Leistungsantrag","en":"Benefit application","ru":"Заявление на пособие"}','{"de":"Antrag auf eine staatliche Leistung","en":"Application for a public benefit","ru":"Заявление на государственную выплату"}','HIGH'),
(NULL,'versicherungsschaden','{"de":"Versicherungsschaden","en":"Insurance claim","ru":"Страховой случай"}','{"de":"Schaden melden und Erstattung verfolgen","en":"Report a loss and track reimbursement","ru":"Заявить ущерб и отследить возмещение"}','NORMAL'),
(NULL,'wohnungsreparatur','{}'::jsonb,'{}'::jsonb,'NORMAL'),
(NULL,'mitarbeitereinstellung','{"de":"Mitarbeitereinstellung","en":"Employee onboarding","ru":"Приём сотрудника"}','{"de":"Neue Mitarbeiterin oder neuen Mitarbeiter aufnehmen","en":"Onboard a new employee","ru":"Оформление нового сотрудника"}','NORMAL'),
(NULL,'steuerunterlagen','{"de":"Steuerunterlagen","en":"Tax documents","ru":"Налоговые документы"}','{"de":"Unterlagen für die Steuerberatung zusammenstellen","en":"Collect documents for the tax advisor","ru":"Подготовка документов для налогового консультанта"}','NORMAL'),
(NULL,'lieferantenstreit','{"de":"Lieferantenstreit","en":"Supplier dispute","ru":"Спор с поставщиком"}','{"de":"Mängel oder Forderungen gegenüber einem Lieferanten","en":"Defects or claims against a supplier","ru":"Претензии к поставщику"}','NORMAL'),
(NULL,'unternehmensgruendung','{"de":"Unternehmensgründung","en":"Company formation","ru":"Регистрация предприятия"}','{"de":"Gewerbe anmelden und Formalitäten erledigen","en":"Register a business and complete formalities","ru":"Регистрация бизнеса и оформление"}','HIGH');

-- Fix the placeholder row written above with proper localized content.
UPDATE case_types
SET name = '{"de":"Wohnungsreparatur","en":"Apartment repair","ru":"Ремонт квартиры"}',
    description = '{"de":"Reparatur in der Wohnung veranlassen und überwachen","en":"Arrange and monitor a repair in the apartment","ru":"Организация и контроль ремонта в квартире"}'
WHERE code = 'wohnungsreparatur';

-- Checklist requirements for a selection of case types.
INSERT INTO case_type_document_requirements (case_type_id, document_type_id, requirement_key, label, is_mandatory)
SELECT ct.id, dt.id, r.requirement_key, r.label::jsonb, r.is_mandatory
FROM case_types ct
JOIN (VALUES
    ('kuendigung','vertrag','Vertragsdokument','{"de":"Vertrag","en":"Contract","ru":"Договор"}',true),
    ('kuendigung','kuendigungsbestaetigung','Kuendigungsbestaetigung','{"de":"Kündigungsbestätigung","en":"Termination confirmation","ru":"Подтверждение расторжения"}',true),
    ('behoerdenanfrage','behoerdenbescheid','Bescheid','{"de":"Schreiben der Behörde","en":"Authority letter","ru":"Письмо ведомства"}',true),
    ('behoerdenanfrage','anforderung_fehlender_unterlagen','Anforderung','{"de":"Anforderung fehlender Unterlagen","en":"Request for missing documents","ru":"Запрос недостающих документов"}',false),
    ('rechnungsklaerung','rechnung','Rechnung','{"de":"Rechnung","en":"Invoice","ru":"Счёт"}',true),
    ('rechnungsklaerung','zahlungsbestaetigung','Zahlungsnachweis','{"de":"Zahlungsnachweis","en":"Proof of payment","ru":"Подтверждение оплаты"}',false),
    ('leistungsantrag','behoerdenbescheid','Bescheid','{"de":"Bescheid","en":"Decision letter","ru":"Решение"}',false),
    ('versicherungsschaden','versicherungsunterlagen','Versicherungsunterlagen','{"de":"Versicherungsunterlagen","en":"Insurance documents","ru":"Страховые документы"}',true),
    ('steuerunterlagen','rechnung','Rechnungen','{"de":"Rechnungen","en":"Invoices","ru":"Счета"}',true),
    ('steuerunterlagen','kontoauszug','Kontoauszüge','{"de":"Kontoauszüge","en":"Bank statements","ru":"Выписки по счёту"}',false)
) AS r(case_type_code, document_type_code, requirement_key, label, is_mandatory)
  ON r.case_type_code = ct.code
JOIN document_types dt ON dt.code = r.document_type_code;

-- -------------------------------------------------------------------------------------
-- Default holiday calendar: nationwide German public holidays (fixed dates).
-- Moveable holidays (Easter-dependent) are generated by the application for each year.
-- Region-specific holidays must be configured per federal state (§24).
-- -------------------------------------------------------------------------------------
INSERT INTO holiday_calendars (tenant_id, code, name, country_code, region, time_zone, is_default)
VALUES (NULL, 'de-national', '{"de":"Deutschland – bundesweite Feiertage","en":"Germany – nationwide public holidays","ru":"Германия — общефедеральные праздники"}', 'DE', NULL, 'Europe/Berlin', true);

INSERT INTO holidays (calendar_id, holiday_date, name, is_moveable)
SELECT hc.id, h.holiday_date, h.name::jsonb, h.is_moveable
FROM holiday_calendars hc
CROSS JOIN (VALUES
    (DATE '2026-01-01', '{"de":"Neujahr","en":"New Year","ru":"Новый год"}', false),
    (DATE '2026-05-01', '{"de":"Tag der Arbeit","en":"Labour Day","ru":"День труда"}', false),
    (DATE '2026-10-03', '{"de":"Tag der Deutschen Einheit","en":"German Unity Day","ru":"День немецкого единства"}', false),
    (DATE '2026-12-25', '{"de":"1. Weihnachtstag","en":"Christmas Day","ru":"Рождество"}', false),
    (DATE '2026-12-26', '{"de":"2. Weihnachtstag","en":"Boxing Day","ru":"Второй день Рождества"}', false),
    (DATE '2027-01-01', '{"de":"Neujahr","en":"New Year","ru":"Новый год"}', false),
    (DATE '2027-05-01', '{"de":"Tag der Arbeit","en":"Labour Day","ru":"День труда"}', false),
    (DATE '2027-10-03', '{"de":"Tag der Deutschen Einheit","en":"German Unity Day","ru":"День немецкого единства"}', false),
    (DATE '2027-12-25', '{"de":"1. Weihnachtstag","en":"Christmas Day","ru":"Рождество"}', false),
    (DATE '2027-12-26', '{"de":"2. Weihnachtstag","en":"Boxing Day","ru":"Второй день Рождества"}', false)
) AS h(holiday_date, name, is_moveable)
WHERE hc.code = 'de-national';

-- -------------------------------------------------------------------------------------
-- Baseline system settings. Values here are *switches and limits*, never legal rules.
-- -------------------------------------------------------------------------------------
INSERT INTO system_settings (tenant_id, setting_key, category, value, value_type, description, requires_verification) VALUES
(NULL,'classification.auto_accept_threshold','CLASSIFICATION','0.85','NUMBER',
 '{"de":"Mindestkonfidenz für automatische Übernahme der Klassifizierung","en":"Minimum confidence for automatic classification acceptance","ru":"Минимальная уверенность для автоматического принятия классификации"}',false),
(NULL,'classification.review_threshold','CLASSIFICATION','0.60','NUMBER',
 '{"de":"Unterhalb dieses Werts ist immer eine manuelle Prüfung nötig","en":"Below this value a manual review is always required","ru":"Ниже этого значения всегда требуется ручная проверка"}',false),
(NULL,'extraction.critical_field_review','CLASSIFICATION','true','BOOLEAN',
 '{"de":"Kritische Felder müssen vor Automatisierung geprüft werden","en":"Critical fields must be reviewed before automation","ru":"Критические поля должны проверяться до автоматизации"}',false),
(NULL,'document.max_file_size_mb','LIMITS','100','NUMBER',
 '{"de":"Maximale Dateigröße pro Upload in MB","en":"Maximum file size per upload in MB","ru":"Максимальный размер файла при загрузке, МБ"}',false),
(NULL,'document.duplicate_window_days','CLASSIFICATION','365','NUMBER',
 '{"de":"Zeitraum für die Duplikaterkennung","en":"Window used for duplicate detection","ru":"Окно поиска дубликатов"}',false),
(NULL,'notification.default_channels','NOTIFICATIONS','["IN_APP","EMAIL"]','JSON',
 '{"de":"Standardkanäle für neue Benutzer","en":"Default channels for new users","ru":"Каналы по умолчанию для новых пользователей"}',false),
(NULL,'ai.enabled','AI','false','BOOLEAN',
 '{"de":"Externe KI-Nutzung erlauben (pro Tenant)","en":"Allow external AI usage (per tenant)","ru":"Разрешить внешний ИИ (для tenant)"}',false),
(NULL,'ai.monthly_budget_eur','AI','0','NUMBER',
 '{"de":"Monatliches KI-Budget in EUR (0 = kein Budget gesetzt, externe KI blockiert)","en":"Monthly AI budget in EUR (0 = no budget set, external AI blocked)","ru":"Месячный бюджет ИИ в EUR (0 = бюджет не задан, внешний ИИ заблокирован)"}',false),
(NULL,'approval.require_second_person','APPROVAL','true','BOOLEAN',
 '{"de":"Vier-Augen-Prinzip für Zahlungsfreigaben","en":"Four-eyes principle for payment approvals","ru":"Принцип четырёх глаз для согласования платежей"}',false),
(NULL,'retention.default_policy_configured','RETENTION','false','BOOLEAN',
 '{"de":"Aufbewahrungsfristen sind noch nicht durch einen Verantwortlichen konfiguriert","en":"Retention periods have not yet been configured by a responsible person","ru":"Сроки хранения ещё не настроены ответственным лицом"}',true);
