# Business Memory — проект (ПЛАНИРУЕТСЯ: BM-01…BM-07, BM-UI-01…03)

Источник требований: `docs/MASTER_SPEC_BUSINESS_MEMORY_RADAR.md` §5, версия 2.1. Решения: D-02, D-04, D-05, D-06 (`decisions.md`).

## Граница ответственности

Business Memory — **слой запросов и связей поверх существующих данных**, а не новое хранилище и не векторная база. Пакет `memory` (новый) содержит: сервис связей, сервис хронологии, сервис вопросов; он читает `contacts`, `document_extractions`, `document_matches`, `document_relations`, `contract_obligations`, `tasks`, события дел/договоров/задач/workflow и вызывает `ai/rag/RetrievalService`.

## Существует (проверено чтением кода)

| Логическая сущность ТЗ | Реализация | Чего не хватает |
|---|---|---|
| BusinessEntity | `contacts`, `contact_addresses`, `contact_relations`, `suppliers.contact_id`, `customers.contact_id` | несколько e-mail/телефонов, внешние идентификаторы, нормализация |
| BusinessRelationship | `document_matches` (CASE/CONTACT/CONTRACT/INVOICE; SUGGESTED/CONFIRMED/REJECTED; доказательства), `document_relations` | статус и доказательства у `document_relations`; цели OBLIGATION/TASK |
| BusinessFact | `document_extractions` (источник, страница, фрагмент, способ, модель, проверка) | история значений, срок действия, субъект, категории «вывод»/«гипотеза» |
| BusinessEvent | `case_events`, `contract_events`, `task_events`, `workflow_events`, `message_delivery_events`, `audit_events` | общая хронология |
| Вопросы с источниками | `ai/rag/RagService`, `RetrievalService` (проверка цитат против выборки) | выборка только по тексту документов |

## Планируется

1. `contact_identifiers` (BM-01): вид (VAT_ID, TAX_NUMBER, IBAN, EMAIL, DOMAIN, PHONE, EXTERNAL), значение, нормализованное значение; `UNIQUE(tenant_id, kind, normalized_value)`; правило: совпадение **названия** не делает субъектов одним.
2. Связи (BM-02): `document_relations` + `status`, `evidence`, `decided_by/at`; новые цели в `document_matches`. Отклонённая пара не предлагается повторно, пока оценка не выросла больше порога (значение порога — настройка).
3. Факты (BM-03): `fact_revisions`; `document_extractions.subject_type/subject_id`; `verification`: `UNVERIFIED | USER_CONFIRMED | DERIVED | HYPOTHESIS`. AI-факт всегда `UNVERIFIED`.
4. Хронология (BM-04): представление `v_business_timeline(tenant_id, subject_type, subject_id, event_time, recorded_at, kind, title, source_table, source_id)`.
5. Вопросы (BM-05): `POST /api/v1/memory/ask` — выборка из фактов, подтверждённых связей, обязательств и текстов; все данные фильтруются правами пользователя **в запросе**, а не после; ответ проверяется против выборки; при противоречии возвращается обе позиции.

## Безопасность

Каждая новая таблица: `tenant_id`, RLS `ENABLE`+`FORCE`, тест изоляции. Выборка памяти применяет правило личных документов семьи (`family/FamilyAccessService`).

## Критерии приёмки (из ТЗ v2.0 §5.6 → тесты)

| Критерий | Тест (планируется) |
|---|---|
| связанные документы одного контрагента находятся | `ContactProfileIT` |
| связь содержит происхождение и статус | `RelationshipControllerIT` |
| подтверждённый факт отличим от AI | `FactRevisionIT` |
| изменение факта сохраняет историю | `FactRevisionIT` |
| ответ AI содержит проверяемые источники | `MemoryAskIT` |
| изоляция tenant | `TenantIsolationSuiteIT` (расширение) |
| ошибочную связь можно отклонить и она не возвращается | `MatchingIT` (расширение) |
