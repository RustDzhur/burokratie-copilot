# Agent Runtime — контракт

Единый рантайм для 50 агентов каталога Firmspace (agents.json сайта) в Bürokratie-Copilot.

## Правила

1. **Каждый агент = манифест + Java-реализация.** Манифест лежит в
   `services/api/src/main/resources/agents/<category>/<id>.json`, реализация — бин
   `de.buerokratiecopilot.agents.<category>.<AgentClass>` с интерфейсом `Agent`.
2. Агент **рабочий**: `run()` делает реальную работу поверх существующих модулей
   (documents, tasks, cases, calendar, mail, invoices, retention, privacy, reports,
   bridge…). Никаких заглушек «вернуть ОК». Если данных нет — честный `ok=false`
   с понятным `summary`, а не фейковый успех.
3. Агент **идемпотентен**: повторный запуск с теми же входными не дублирует объекты
   (проверка по внешнему ключу/источнику перед созданием).
4. Агент пишет всё, что произвёл, через `AgentContext.addProduced(kind, ref, label)` —
   это попадает в `agent_runs.meta` и в аудит.
5. Агент не имеет права на сеть наружу, кроме явно разрешённых адаптеров
   (mail/CalDAV/API-мосты), и работает в контексте текущего тенанта (RLS).

## Манифест (JSON)

```json
{
  "id": "doc-detect",
  "name": "Dokument-Erkennung",
  "category": "documents",
  "description": "Erkennt Dokumenttypen und steuert die weitere Verarbeitung an.",
  "functions": ["Typ-Erkennung", "Kanal-Zuordnung", "Übergabe an Fach-Agenten"],
  "triggers": ["document.created", "manual"],
  "inputs": { "documentId": "uuid (optional)", "fileName": "string", "mimeType": "string", "textHint": "string" },
  "config": { "...": "правила конкретного агента" }
}
```

Категории: `documents`, `communication`, `finance`, `processes`, `compliance`,
`analysis`, `crm`, `integration`.

## Интерфейс

```java
public interface Agent {
    String id();
    AgentResult run(AgentContext context);
}
```

- `AgentContext`: `tenantId()`, `actorUserId()`, `trigger()`, `now()`, `inputs()`,
  `addProduced(kind, ref, label)`, `produced()`.
- `AgentResult`: `boolean ok, String summary, List<Produced> produced, Map<String,String> meta`.

## API

- `GET /api/v1/agents` — каталог: id, name, category, description, functions, triggers,
  `implemented` (есть ли Java-бин), число запусков.
- `GET /api/v1/agents/{id}` — манифест + последние запуски.
- `POST /api/v1/agents/{id}/run` — тело `{ "trigger": "manual", "inputs": {...} }` → `{ runId, ok, summary, produced }`.
- `GET /api/v1/agents/{id}/runs?limit=20` — журнал запусков.

Права: `agents.view` (каталог/журнал), `agents.run` (запуск). Роли OWNER/ADMIN
получают их автоматически через `V70__agents.sql`.

## Триггеры (диспетчер)

`AgentDispatcher` слушает доменные события (document.created, case.created,
task.due, deadline.hit, invoice.imported, payment.matched, mail.received …) и
запускает агентов, у которых этот триггер указан в манифесте. В пилоте события
могут приходить от соответствующих сервисов через ApplicationEventPublisher.

## Журнал

`agent_runs` (V70): id, tenant_id, actor_user_id, agent_id, trigger, inputs jsonb,
ok boolean, summary text, meta jsonb (produced + доп. поля), started_at, finished_at.

## Тесты

На каждый агент — минимум один тест «рабочего поведения»: реальные входные →
проверяемый результат/побочный эффект. Мок только для внешних сетей.
