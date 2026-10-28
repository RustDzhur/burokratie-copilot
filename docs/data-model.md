# Модель данных Bürokratie-Copilot

**Источник:** [`docs/task.md`](task.md) §19. **Реализация:** `services/api/src/main/resources/db/migration/`.
**Актуально на:** 2026-10-11.

## 1. Соглашения

| Тема | Решение |
|---|---|
| Первичные ключи | `uuid`, по умолчанию `gen_random_uuid()` |
| Изоляция | `tenant_id uuid NOT NULL REFERENCES tenants(id)` в каждой бизнес-таблице |
| Время | `timestamptz` в UTC; для сроков, зависящих от места, рядом хранится часовой пояс |
| Статусы | `varchar` + `CHECK` (вместо нативных enum) — новое значение добавляется миграцией без блокировки типа |
| Мягкое удаление | только там, где оно действительно нужно (`deleted_at` у документов, дел, задач, договоров, контактов) |
| JSONB | только для изменяемых структур: результаты ИИ, настройки, метаданные, диагностика шагов обработки |
| Связи | явные таблицы (`case_documents`, `document_relations`, `task_assignees`); универсальных «свяжи что угодно с чем угодно» нет |
| Обновление `updated_at` | триггер `set_updated_at()` — не зависит от дисциплины приложения |

## 2. Карта миграций

Список после V15 собран из заголовков самих файлов (`scripts/facts.sh` считает количество); подробности — в комментариях миграций. Правило: применённая миграция не изменяется, правки — только новой миграцией.

| Файл | Содержимое |
|---|---|
| `V1__extensions_and_tenancy.sql` | расширения, `tenants`, `organizations`, `workspaces`, функция `set_updated_at()` |
| `V2__identity_and_access.sql` | `users`, `user_profiles`, `roles`, `permissions`, `role_permissions`, `memberships`, `membership_roles`, `invitations`, `sessions`, `mfa_methods`, `mfa_recovery_codes`, `one_time_tokens`, `access_change_log` |
| `V3__documents.sql` | `document_types`, `document_type_fields`, `document_blobs`, `documents`, `document_retention_policies`, `document_versions`, `document_pages`, `document_extractions`, `document_classifications`, `document_tags`, `document_tag_links`, `document_relations`, `document_sources`, `document_processing_steps`, `document_reviews`, `document_access_logs` |
| `V4__cases_and_contacts.sql` | `contacts`, `contact_addresses`, `organizations_contacts`, `contact_relations`, `case_types`, `case_type_document_requirements`, `cases`, `case_participants`, `case_documents`, `case_events` |
| `V5__tasks_and_deadlines.sql` | `tasks`, `task_assignees`, `task_comments`, `task_checklist_items`, `task_evidence`, `task_events`, `task_deadlines`, `holiday_calendars`, `holidays`, `deadline_rules` |
| `V6__workflow_and_approvals.sql` | `workflow_definitions`, `workflow_versions`, `workflow_runs`, `workflow_run_steps`, `workflow_events`, `approvals`, `approval_events` |
| `V7__contracts.sql` | `contracts`, `contract_parties`, `contract_clauses`, `contract_versions`, `contract_events`, `contract_obligations` |
| `V8__finance.sql` | `suppliers`, `supplier_bank_accounts`, `customers`, `financial_accounts`, `purchase_orders`, `invoice_number_sequences`, `invoices`, `invoice_lines`, `tax_rates`, `credit_notes`, `e_invoice_records`, `bank_statements`, `bank_transactions`, `payments`, `payment_matches`, `payment_approval_rules`, `payment_approvals` |
| `V9__mail_and_templates.sql` | `mail_accounts`, `mail_messages`, `mail_attachments`, `outbound_messages`, `message_delivery_events`, `document_templates`, `template_versions`, `template_fields`, `generated_documents` |
| `V10__integrations_and_devices.sql` | `integrations`, `integration_credentials`, `integration_sync_runs`, `webhook_endpoints`, `webhook_events`, `devices`, `device_jobs`, `scan_sessions` |
| `V11__system_data.sql` | `audit_events`, `notifications`, `notification_preferences`, `notification_deliveries`, `ai_jobs`, `ai_model_runs`, `ai_usage_records`, `system_settings`, `system_setting_history`, `feature_flags`, `retention_jobs`, `outbox_events`, `outbox_consumptions`, `idempotency_records`, `api_tokens`, `rate_limit_counters`, `saved_views` |
| `V12__reference_data.sql` | 74 права, 10 базовых ролей + матрица, 79 типов документов §9.3, каталог полей §9.4, 10 типов дел, календарь праздников, базовые настройки |
| `V13__row_level_security.sql` | `current_tenant_id()`, RLS-политики (`ENABLE` + `FORCE`), триггеры append-only |
| `V14__semantic_search_optional.sql` | условное создание `document_embeddings` + HNSW-индекс при наличии `pgvector` |
| `V15__tenant_indexes.sql` | tenant-индексы для 30 высоконагруженных таблиц |
| `V16__varchar_instead_of_char.sql` | Replace fixed length `character(n)` columns with `varchar(n)` |
| `V17__document_type_rules.sql` | Classification rules (spec §9.3, §9.4, §31) |
| `V18__field_extraction_hints.sql` | Extraction hints for the document type fields (spec §9.4, §10) |
| `V19__document_matches.sql` | Proposed document matches (spec §9.5) |
| `V20__holiday_calendar_rls.sql` | Row level security for the holiday calendars |
| `V21__contract_clause_versions.sql` | Clauses belong to a contract version |
| `V22__ai_tool_settings.sql` | Which AI tools a tenant allows |
| `V23__family_document_access.sql` | Personal and shared documents in a household |
| `V24__advisor_clients.sql` | An advisor's clients |
| `V25__generated_document_status_width.sql` | the status the schema asks for did not fit in the column |
| `V26__e_invoice_rulesets.sql` | Versioned rule sets for e-invoice validation |
| `V27__taxonomy_manage_permission.sql` | the permission the taxonomy endpoints require did not exist |
| `V28__document_children_tenant_isolation.sql` | Two tables that hold what was taken out of a document had no row-level security at all. |
| `V29__document_children_pages_and_steps_rls.sql` | Three more tables that reach their tenant through a document, and had no row-level security. |
| `V30__tenant_owned_tables_rls.sql` | Six more tables carry a tenant_id of their own and had no row-level security. |
| `V31__catalogues_and_roles_rls.sql` | The last eight tables, and the first case where the policy is not the same for all of them. |
| `V32__calendar_events_and_user_preferences.sql` | appointments and reminders, and per-person interface preferences |
| `V33__plans_subscriptions_billing.sql` | plans, subscriptions, platform billing invoices and platform settings |
| `V34__document_scenarios.sql` | what the platform does with a document once it has understood it |
| `V35__classification_rule_tuning.sql` | a contract is not a termination notice |
| `V36__calendar_event_explanations.sql` | a calendar entry says where it came from |
| `V37__medical_type_and_multilingual_rules.sql` | a medical document is not an offer; Russian and Ukrainian documents are recognised |
| `V38__remove_invented_deadlines.sql` | take back the deadlines the platform invented |
| `V39__calendar_feed_tokens.sql` | the calendar on the phone |
| `V40__cabinets_catalogue_and_parties.sql` | one catalogue for every kind of cabinet: who a document type is for, 100 more German |
| `V41__mail_message_soft_delete.sql` | A mail message a person deleted stays in the table (marked), so that the next collection of the mailbox does not |
| `V42__retry_mailboxes_stopped_by_old_error.sql` | A mailbox stopped after three failures of the duplicate-key bug (fixed since) is collected again. |
| `V43__tariffs_free_personal_business_professional.sql` | the price list: Free, Personal, Business, Professional |
| `V44__telegram_informer.sql` | the Telegram informer: which person gets the platform's news in which chat |
| `V45__workflow_soft_delete.sql` | An automation a person deleted is hidden and switched off; its runs stay as history. Its code is freed so that a new |
| `V46__permissions_memory_radar_autopilot.sql` | permissions of the Business Memory, Radar and Case Autopilot modules (task R-04) |
| `V47__contact_identifiers.sql` | identifiers of a contact (task BM-01, decision D-02) |
| `V48__relationship_status_and_evidence.sql` | relationships between documents and the things they belong to (task BM-02, decision D-02) |
| `V49__fact_revisions.sql` | history and provenance of facts (task BM-03, decision D-04) |
| `V50__business_timeline_view.sql` | one timeline over the event tables (task BM-04, decision D-05) |
| `V51__task_obligation_link.sql` | a task can answer an obligation (task BM-06, decision D-06) |
| `V52__radar_findings.sql` | Bürokratie-Radar: findings, their evidence and their history (task RD-01) |
| `V53__radar_settings_and_notification_category.sql` | what the Radar needs besides its tables (tasks RD-03, RD-04) |
| `V54__deadline_provenance.sql` | where a deadline comes from, precisely (task RD-05, spec 6.5) |
| `V55__german_document_recognition.sql` | German-only document recognition, tuned against the evaluation set (task RD-09) |
| `V56__german_recognition_followup.sql` | second pass over the German evaluation set (task RD-09) |
| `V57__school_letter_rules.sql` | school letters (task RD-09, holdout batch 1) |
| `V58__ai_job_types_for_explanations.sql` | more kinds of AI jobs (tasks RD-10, CA-03, TR-01) |

## 3. Центральные сущности

### Документ (`documents`)
Центральный объект: исходное имя, безопасное внутреннее имя, MIME, размер, SHA-256, версия,
источник, отправитель/получатель, дата и номер документа, тип, язык, извлечённый текст,
структурированные данные, статус обработки, уверенность классификации, метки конфиденциальности,
статус проверки человеком, ссылка на дубликат, политика хранения, legal hold.
Версии — `document_versions` (неизменяемая история), страницы — `document_pages` (текст, OCR-движок,
уверенность, координаты блоков), значения полей — `document_extractions` (с обязательным источником).

Поток статусов (§6.3) контролируется сервисным слоем:
`RECEIVED → QUARANTINED → OCR_PENDING → EXTRACTING → CLASSIFYING → MATCHING → REVIEW_REQUIRED → READY → ACTION_PENDING → COMPLETED`,
плюс `FAILED`, `DUPLICATE`, `UNSUPPORTED`, `CANCELLED`, `ARCHIVED`, `LEGAL_HOLD`.
Диагностика каждого шага — в `document_processing_steps` (статус, попытка, длительность, ошибка).

### Дело (`cases`)
Объединяет документы, задачи, сроки, события, финансы, договоры и переписку.
Состояния §11.2. Закрытие возможно только с датой и причиной; участники — `case_participants`
с уровнем доступа (`READ…FULL`) и сроком действия.

### Задача и сроки (`tasks`, `task_deadlines`)
Задача содержит критерии завершения и доказательства (`task_evidence`). Шесть видов сроков §12.3
хранятся **раздельно**; «подтверждённый юридический срок» невозможен без подтверждения человеком
и ссылки на правило (`deadline_rules` с версией, правовым основанием и `requires_verification`).

### Финансы (`invoices`, `payments`)
Входящие и исходящие счета в одной таблице с `direction`. Налоговые ставки — данные (`tax_rates`)
с признаком проверки. Смена банковских реквизитов поставщика отслеживается через
`supplier_bank_accounts` и флаг `bank_details_changed`. Подготовка платежа и подтверждение
разделены: `payments.status` не может стать `EXECUTED` без `confirmed_by`.

### E-Rechnung (`e_invoice_records`)
Формат, синтаксис, профиль, версия спецификации, статус и результаты валидации, версия набора
правил, ссылка на **неизменённый исходный XML**. PDF без структурированного компонента
E-Rechnung не считается.

### Business Memory и Radar (V47–V58)

| Что | Где | Примечание |
|---|---|---|
| Идентификаторы контрагента (IBAN, USt-IdNr., Steuernummer, e-mail, телефон, домен) | `contact_identifiers` (V47) | нормализованное значение уникально в арендаторе; название не тождество |
| Связь документа с делом/контактом/договором/обязательством/задачей | `document_matches` (+`OBLIGATION`, `TASK`, V48) | статус SUGGESTED/CONFIRMED/REJECTED, доказательство, кто решил |
| Связь документа с документом | `document_relations` (+`status`, `evidence`, V48) | дубликат создаётся при загрузке копии |
| История и происхождение фактов | `fact_revisions` (V49), `document_extractions.verification/subject_*` | пишет триггер БД; UNVERIFIED/USER_CONFIRMED/DERIVED/HYPOTHESIS |
| Единая хронология | представление `v_business_timeline` (V50) | не копия: `case_events`, `contract_events`, `task_events`, `workflow_events`, `message_delivery_events`, значимые `audit_events`; `security_invoker` |
| Задача, отвечающая на обязательство | `tasks.obligation_id` (V51) | |
| Находки Radar | `radar_findings`, `radar_finding_evidence`, `radar_finding_events` (V52) | `UNIQUE(tenant_id, finding_key)`; события только добавляются; RLS |
| Настройки порогов и категория уведомлений RADAR | `system_settings` `radar.*`, `notifications_category_chk` (V53) | пороги — технические стартовые значения |
| Источник срока | `task_deadlines.source_page`, `rule_version` (V54) | |
| Правила распознавания немецких документов | `document_type_rules`, `document_type_fields.extraction_hint` (V55–V57) | русские/украинские правила удалены |
| Типы AI-задач | `ai_jobs_type_chk` (V58) | `EXPLAIN_FINDING`, `PLAN`, `EXPLAIN_DOCUMENT` |

## 4. Служебные таблицы

| Таблица | Назначение |
|---|---|
| `outbox_events` / `outbox_consumptions` | надёжная доставка доменных событий и дедупликация у потребителей |
| `idempotency_records` | повтор небезопасного запроса возвращает тот же ответ |
| `audit_events` | append-only журнал: кто, когда, что, результат, correlation ID |
| `system_settings` / `system_setting_history` | версионируемые бизнес-правила и их история |
| `ai_jobs` / `ai_model_runs` / `ai_usage_records` | очередь ИИ, трассируемость решений, расходы и бюджет |
| `retention_jobs` | отложенные действия по политикам хранения с причиной блокировки |
| `api_tokens` / `rate_limit_counters` | ограниченные по правам токены и защита от перебора |
| `document_embeddings` | семантический поиск (только при наличии `pgvector`) |

## 5. Что проверено автоматически

- Наличие всех 84 таблиц, требуемых §19 — `SchemaIT.allRequiredTablesExist`.
- Наличие индекса по `tenant_id` у ключевых таблиц — `SchemaIT.tenantScopedTablesAreIndexedByTenant`.
- Работа RLS (`USING` и `WITH CHECK`) — `SchemaIT.rowLevelSecurityIsEnforced`.
- Неизменяемость аудита — `SchemaIT.auditEventsCannotBeModifiedOrDeleted`, `AuditServiceIT`.
- Ключевые `CHECK`-ограничения — `SchemaIT.checkConstraintsRejectInvalidData`.
- Миграции на чистой базе и идемпотентность — `FlywayMigrationIT`.
