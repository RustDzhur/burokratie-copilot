# Происхождение AI-выводов

Источник требований: `docs/task.md` §10.2, §32; `docs/MASTER_SPEC_BUSINESS_MEMORY_RADAR.md` §12.3. Решение: D-10.

## Существует

- `ai_jobs`: тип задачи (CLASSIFY, EXTRACT, SUMMARIZE, DRAFT, EMBED, CHAT, TRANSLATE, QUALITY_CHECK), статус, провайдер, модель, `instruction_version`, попытки, ошибка.
- `ai_model_runs`: провайдер, модель, `instruction_version`, `instruction_hash`, токены, стоимость, задержка, `response_structured`, `warnings`, `open_questions`, `requires_human_review`, `human_confirmed_by/at`.
- Ссылки: `document_extractions.ai_model_run_id`, `audit_events.ai_model_run_id`.
- Защита: `ai/AiLogRedaction` (логи без персональных данных), `ai/security/*` (инъекции, allowlist, права, подтверждение), бюджет (`AiOrchestrator`, `AiBudgetLimitTest`).
- Ответы с источниками: `ai/rag/RagService` отвергает цитаты, которых нет в выборке (`RagCitationTest`, `RagNoAnswerTest`).

## Планируется

- Типы задач `PLAN`, `EXPLAIN`, `EXPLAIN_FINDING` (расширение CHECK `ai_jobs_type_chk`).
- Находки, факты и объяснения ссылаются на `ai_model_run_id`; AI-факт — `verification=UNVERIFIED` до подтверждения человеком.
- Подпись «оценка модели, не вероятность» рядом с `confidence` во всех экранах (UI-AI-01): пороги автоматизации не калиброваны (RD-09 измерит).
- Набор оценки и метрики (RD-09): precision, recall, false positive rate, доля ответов с корректными источниками, доля отказов от неподтверждённых выводов, число повторных предупреждений. Пороговые значения записываются в `docs/testing/test-strategy.md` **только после базовых измерений**.
