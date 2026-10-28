# Case Autopilot — проект (ПЛАНИРУЕТСЯ: CA-01…CA-08)

Источник требований: `docs/MASTER_SPEC_BUSINESS_MEMORY_RADAR.md` §7. Решение: D-03, D-09 (`decisions.md`). Отдельный пакет `autopilot` **не создаётся**: функция строится из `cases`, `workflow`, `approvals`, `ai`.

## Существует

- Дело: `cases` (статусы `NEW … CLOSED`), `case_events`, `case_type_document_requirements`, `CaseLifecycleService` (закрытие не «случайно»; `OUTCOME_ACHIEVED` при открытых обязательствах запрещено).
- Шаги и ожидание: `workflow_runs` (`case_id`, `wait_until`, `waiting_for_event`, `idempotency_key`), `workflow_run_steps` (повторы, `next_step_key`, компенсации), `WorkflowEngine`.
- Согласования: `approvals` (`automation_level` 1–4, `action_token`, `expires_at`), `payment_approvals`, `workflow/approval/ApprovalService`.
- Отправка: `mail/outbound/OutboundMessageService` (утверждение фиксирует итоговый текст; отправка берёт только его), `DeliverySemantics` (передача провайдеру ≠ доставка).
- Контроль ответа: `orchestration/FollowUpService`.

## Соответствие статусов шага

| Статус шага ТЗ v2.0 | Статус `workflow_run_steps` |
|---|---|
| PENDING | PENDING |
| READY | PENDING + зависимости выполнены (вычисляется) |
| IN_PROGRESS | RUNNING |
| WAITING_FOR_APPROVAL | WAITING + тип шага APPROVAL |
| WAITING_FOR_EXTERNAL_EVENT | WAITING + `waiting_for_event` |
| COMPLETED | SUCCEEDED |
| SKIPPED | SKIPPED |
| FAILED | FAILED |
| CANCELLED | запуск в статусе CANCELLED |

## Планируется

1. Цель и критерии (CA-01): `cases.goal`, `cases.next_action`, таблица `case_completion_criteria` (вид, описание, статус, ссылка на доказательство и её хеш).
2. План (CA-02): запуск `workflow_runs` с `case_id`; шаг не стартует, пока зависимости не выполнены; `evidence_required` у шага.
3. Предложение плана (CA-03): job_type `PLAN`; `PlanValidator` проверяет структуру и допустимость типа действия по allowlist; сохраняется только валидный план; AI не вызывает внутренние сервисы.
4. Привязка согласований (CA-04): `approvals.subject_version`, `content_hash`; исполнение сверяет хеш; несовпадение → `EXPIRED` и новая заявка.
5. Закрытие (CA-05): `COMPLETED` только при выполненных критериях; «письмо отправлено» ≠ «ответ получен» ≠ «вопрос решён».
6. Ответ контрагента (CA-06): привязка входящего письма к делу по `in_reply_to`/`thread_id` и `document_matches`; неоднозначность — предложение пользователю; событие возобновляет шаг ожидания.

## Категории действий (v2.0 §7.5) ↔ уровни (v1.0 §32)

| Категория v2.0 | Уровень v1.0 | Пример | Требование |
|---|---|---|---|
| A чтение | 1–2 | поиск документов, рекомендация | права пользователя |
| B внутренние обратимые | 3 | черновик, предложение связи, внутренняя задача | роль + настройки tenant |
| C внешние/значимые | 4 | отправка письма, платёж, подпись | явное подтверждение, привязанное к содержимому |

## Сквозной сценарий

«Неожиданное увеличение счёта» (v2.0 §8) — тест `UnexpectedInvoiceIncreaseIT` (HD-E2E-01) после RD-04, CA-03, CA-06, CA-07; без реальных внешних отправок (подставляется тестовый почтовый провайдер).
