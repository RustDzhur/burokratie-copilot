# Agent Runtime — контракт

Единый рантайм для 126 агентов каталога (50 классических + 76 отраслевых) в Bürokratie-Copilot.

Stand: **все 126 агентов реализованы** (Manifest + Bean), **концептов не осталось**. Все 30 констелляций состоят только из реализованных шагов; это проверяет `ConstellationsImplementedIT` через живой Spring-контекст, `AgentRegistryIT` (126 манифестов ↔ 126 бинов, ни одного `CONCEPT`) и `ConstellationCatalogTest` через статусы манифестов.

## Правила

1. **Каждый агент = манифест; реализация — отдельный бин.** Манифест лежит в
   `services/api/src/main/resources/agents/<category>/<id>.json`, реализация — бин
   `de.buerokratiecopilot.agents.<category>.<AgentClass>` с интерфейсом `Agent`.
   Манифест без бина был бы честным `implemented=false` (Konzept) в каталоге и API;
   в текущем каталоге таких нет — все 126 манифестов имеют бин. Бина без манифеста
   не бывает (`AgentRunner` требует манифест).
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
`analysis`, `crm`, `integration`, `marketing`, `sales`, `hr`, `banking`, `legal`, `logistics`,
`support`, `ecommerce`, `production`, `realestate`, `hospitality`, `healthcare`, `it`, `knowledge`.
Список задан в `AgentRegistry.CATEGORIES`; неизвестная категория загружается, но пишется
в лог как ошибка и валит `AgentCatalogTest`.

Отраслевые манифесты дополнительно несут в `config`: `systems` (каналы/системы),
`version` и `status` (`ACTIVE` — есть бин, `CONCEPT` — только манифест). В этом
каталоге все 76 отраслевых манифестов имеют статус `ACTIVE`; 50 классических
манифестов поля `status` не несут и реализованы с первой волны.

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
получают их автоматически через `V73__agents.sql`.

## Триггеры (диспетчер)

`AgentDispatcher` слушает доменные события (document.created, case.created,
task.due, deadline.hit, invoice.imported, payment.matched, mail.received …) и
запускает агентов, у которых этот триггер указан в манифесте. В пилоте события
могут приходить от соответствующих сервисов через ApplicationEventPublisher.

## Журнал

`agent_runs` (V73): id, tenant_id, actor_user_id, agent_id, trigger, inputs jsonb,
ok boolean, summary text, meta jsonb (produced + доп. поля), started_at, finished_at.

## Артефакты агентов

`AgentArtifactStore` — единая точка записи результатов, у которых нет доменной таблицы:

- `store(...)` — готовый файл (фид, прайс-лист, тур-план, расчёт) кладётся в `BlobStore` и
  регистрируется строкой `generated_documents` с `subject_type = AGENT_ARTIFACT`; повторный
  запуск с тем же `sourceKey` возвращает первый артефакт, а не пишет второй.
- `put(...)` / `find(...)` — подготовленный JSON-артефакт (индекс, досье, экспозе, калькуляция)
  в таблице `agent_artifacts` (V85) с ключом `(tenant_id, artifact_kind, source_key)` и хешем
  содержимого: тот же хеш — объект переиспользуется, изменённый — заменяется на месте с
  сохранением id строки.

Оба пути идемпотентны по `sourceKey`; обе таблицы защищены RLS по тенанту. Первая волна
отраслевых агентов писала через `store`, вторая — через `put`; после слияния доступны оба
контракта, поведение идемпотентности сохранено у каждого.

## Тесты

На каждый агент — минимум один тест «рабочего поведения»: реальные входные →
проверяемый результат/побочный эффект. Мок только для внешних сетей.

Каталог дополнительно защищён на двух уровнях: `AgentCatalogTest` (классpath: 126 манифестов,
уникальные id, полная схема, все `ACTIVE`) и `AgentRegistryIT` (живой контекст: 126 бинов,
нет нереализованных id и нет дублей).

## Созвездия (workflow-пресеты)

`agent-constellations/*.json` — 30 готовых последовательностей агентов. Каждый шаг ссылается
на существующий `id` каталога; загрузчик `ConstellationCatalog` проверяет структуру и
неизвестные ссылки (fail-soft, с логом). Пресеты — код, а не данные БД; в БД лежат только
права `constellations.view` / `constellations.instantiate` (V84).

- `GET /api/v1/constellations` — список с `implementedSteps` / `conceptSteps`.
- `GET /api/v1/constellations/{id}` — шаги с именем агента и признаком реализации.
- `POST /api/v1/constellations/{id}/instantiate` — сохраняет пресет как опубликованную версию
  процесса и отвечает `{saved:true, workflowId, versionId, version, entryStep, stepCount, steps}`.
  Один шаг пресета = один шаг `AGENT`, `next` — по цепочке; повторная инстанциация публикует
  новую версию того же определения (запуски остаются на своей версии). Если шаг ссылается на
  неизвестного агента или концепт — `409 CONSTELLATION_NOT_EXECUTABLE` со списком `steps`
  (`order`, `agentId`, `title`, `reason`).

Шаги сохраняются без фиксированных входов: пресет описывает, что делает агент, но не какие
конкретные данные у него есть. Поэтому запуск пресета паузает на первом агенте, которому нужны
данные, и продолжается через `resume` с `RETRY` + `inputs`.

Полный каталог и описание пресетов: [CATALOG.md](CATALOG.md), [CONSOLATIONS.md](CONSOLATIONS.md).

## Шаг `AGENT` в процессе

`WorkflowDefinition.STEP_TYPES` содержит `AGENT` — вызов агента каталога из процесса. Конфиг:

```json
{"key":"extract","type":"AGENT","config":{
   "agentId":"doc-extract",
   "inputs":{"documentId":"..."},
   "onFailure":"PAUSE",
   "next":"check"}}
```

- `agentId` обязателен. При **публикации** версии он проверяется по `AgentRegistry`:
  неизвестный id и агент-концепт (манифест без бина) отклоняются с `UNKNOWN_AGENT`. Черновик
  проверяется только структурно (`WorkflowEditor.checkDraft`), каталожная проверка — при
  публикации, то есть в момент, когда версия становится исполняемой.
- `inputs` — необязательная карта; значения передаются агенту строками.
- `onFailure` — `PAUSE` (по умолчанию), `FAIL`, `CONTINUE`; другое значение отклоняется.
- `next` — следующий шаг; если его нет, процесс завершается (так заканчивается цепочка пресета).

Движок вызывает `AgentRunner.run(...)` от имени тенанта запуска (`RequestContext`), поэтому
RLS-политики видят правильный тенант. В `workflow_run_steps` пишутся: попытка (`attempt`),
фактические входы (`input`), `ok`, `summary`, `agentRunId` и `produced` (`output`). Сам запуск
агента попадает и в `agent_runs` с `trigger = 'workflow'` — истории процесса и агента согласованы.

Ошибка агента не роняет процесс: `ok=false` — штатный отказ агента, исключение ловится и
записывается в шаг (`error_code = AGENT_ERROR`), после чего действует `onFailure`.

| onFailure | поведение |
| --- | --- |
| `PAUSE` | `workflow_runs.status = PAUSED_FOR_HUMAN_REVIEW`, шаг остаётся текущим, `failure_code = AGENT_FAILED` |
| `FAIL` | `status = FAILED`, `finished_at` установлен, продолжение невозможно |
| `CONTINUE` | процесс идёт на `next`; шаг остаётся `FAILED` — успех не подделывается |

## API: запуск и продолжение процесса

- `POST /api/v1/workflows/{definitionId}/run` — запускает и **исполняет** цепочку до конца,
  паузы, ожидания или ошибки. Тело как у `.../runs`: `triggerType`, `triggerPayload`,
  `subjectType`, `subjectId`, `caseId`, `idempotencyKey`. Ответ:
  `{runId, status, currentStep, stepsExecuted, created, stepLimitReached}`.
- `POST /api/v1/workflow-runs/{runId}/resume` — `{decision:"APPROVE"|"RETRY"|"SKIP", inputs?}`:
  `APPROVE` и `SKIP` идут на `next` (`SKIP` помечает последнюю попытку `SKIPPED`), `RETRY`
  повторяет тот же шаг новой попыткой. Переданные `inputs` сохраняются в
  `workflow_runs.context.agentInputOverrides.<stepKey>` (переживают рестарт; повторный `RETRY`
  без входов повторяет последнюю попытку). Ответ — состояние после продолжения.
- `GET /api/v1/workflow-runs/{runId}` — статус, шаги и события; у шага дополнительно
  `ok`, `summary`, `agentRunId`, `produced` и полный `output`.
- Права: `workflows.run` (создан в V88, выдан OWNER/ADMIN/MANAGER); `workflows.execute` и
  `workflows.edit` продолжают открывать те же маршруты.

`advance` остаётся отдельным идемпотентным шагом движка (диспетчер и `workflow-route` им
пользуются); для обычного запуска он больше не нужен — `run` делает всё сам.

Защита от зацикленных определений: один вызов исполняет не более 200 шагов, затем
останавливается и отвечает `stepLimitReached=true`.
