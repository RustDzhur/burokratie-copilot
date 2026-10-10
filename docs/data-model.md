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
