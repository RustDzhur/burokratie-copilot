# Интеграция агентов платформы с внешними системами

Как подключить агентов Bürokratie-Copilot к любой внешней среде — современному REST-сервису,
legacy-системе на старом коде, почтовому ящику, сканеру или сетевой папке. Контракт рантайма,
на который опирается этот документ: [docs/agents/RUNTIME.md](agents/RUNTIME.md); API — `/api/v1/agents`.

## Содержание

1. [Что такое агент](#1-что-такое-агент)
2. [Способы интеграции](#2-способы-интеграции): прямой REST · исходящие вебхуки · входящие триггеры · email-вход · файловый вход/scan-папка · очередь/шина (bridge) · офлайн JSON/CSV
3. [Агенты, которые приспосабливаются везде](#3-агенты-которые-приспосабливаются-везде)
4. [Безопасность](#4-безопасность)
5. [Сквозные сценарии](#5-сквозные-сценарии)
6. [Чек-лист внедрения](#6-чек-лист-внедрения)
7. [Какой агент куда](#7-какой-агент-куда)

---

## 1. Что такое агент

Агент — **манифест + Java-бин** из каталога: 126 агентов в 22 категориях, все реализованы.
Классические: `documents`, `communication`, `finance`, `processes`, `compliance`, `analysis`,
`crm`, `integration`; отраслевые: `marketing`, `sales`, `hr`, `banking`, `legal`, `logistics`,
`support`, `ecommerce`, `production`, `realestate`, `hospitality`, `healthcare`, `it`, `knowledge`.
Манифест без бина был бы честным `implemented=false` (концепт): запустить его нельзя, но каталог
и API его показывают. В текущем каталоге таких нет — у всех 126 агентов есть бин, поэтому
`implemented=true` у каждого; стережёт это `AgentRegistryIT` (126 манифестов ↔ 126 бинов).

- **Идентификатор** — `id` из манифеста (`doc-detect`, `invoice-check`, `gdpr-guard`, …).
  Манифест лежит в `services/api/src/main/resources/agents/<category>/<id>.json`: имя, функции,
  триггеры, схема входов, правила (`config`).
- **Входы** — плоская карта `inputs: { "documentId": "...", "fileName": "..." }` плюс контекст
  запуска: тенант, актор, триггер, время.
- **Выходы** — `AgentResult`: `ok` (булево), `summary` (понятный текст), `produced` (список
  произведённых объектов `kind/ref/label`), `meta`. Всё произведённое агент обязан
  зарегистрировать через `AgentContext.addProduced(...)`.
- **Журнал** — таблица `agent_runs`: `id`, `tenant_id`, `actor_user_id`, `agent_id`, `trigger`,
  `inputs` (jsonb), `ok`, `summary`, `meta` (jsonb, в т.ч. produced), `started_at`, `finished_at`.
  Каждый запуск — строка журнала, которую можно перечитать и проаудировать.

Что рантайм **гарантирует** каждому запуску:

1. **Тенантность.** Агент работает строго в контексте текущего тенанта; все чтения и записи
   проходят через RLS (`tenant_id`, политики `ENABLE`+`FORCE`, `V13`). Чужие данные агент
   не увидит и не затронет.
2. **Аудит.** Произведённые объекты попадают в `agent_runs.meta` и в журнал аудита:
   всегда можно ответить, кто запустил, что на входе и что на выходе.
3. **Идемпотентность.** Повторный запуск с теми же входами не дублирует объекты — агент
   проверяет внешний ключ/источник перед созданием. Поэтому повторная доставка безопасна.
4. **Честные отказы.** Нет заглушек «вернуть ОК»: нет данных — `ok=false` с понятным
   `summary` («Документ не найден», «Сканера нет: установите sane-utils»), а не фейковый успех.

---

## 2. Способы интеграции

Один и тот же агент доступен через разные каналы. Канал выбирает **то, что умеет внешняя
система**, а не то, что умеет агент.

### 2.1 Прямой REST

Базовый канал для любой среды, способной делать HTTP. Требует прав `agents.view`
(каталог/журнал) и `agents.run` (запуск); роли OWNER/ADMIN получают их автоматически
(`V73__agents.sql`; `V84` дополняет права OWNER/ADMIN и добавляет права на созвездия).

```bash
# Каталог: id, категория, функции, триггеры, реализован ли, число запусков
curl -s https://app.example.com/api/v1/agents -H "Authorization: Bearer $TOKEN"

# Один агент: манифест + последние запуски
curl -s "https://app.example.com/api/v1/agents/invoice-check?limit=10" \
  -H "Authorization: Bearer $TOKEN"

# Запуск: { trigger, inputs } → { runId, ok, summary, produced }
curl -s -X POST https://app.example.com/api/v1/agents/invoice-check/run \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"trigger":"manual","inputs":{"documentId":"9f2c…","strict":"true"}}'

# Журнал запусков агента
curl -s "https://app.example.com/api/v1/agents/invoice-check/runs?limit=20" \
  -H "Authorization: Bearer $TOKEN"
```

Правило: **запустили → получили `runId` → опросили журнал → обработали `ok/summary/produced`.**
`ok=false` — штатный ответ, а не ошибка HTTP; не полагайтесь на побочный эффект без проверки.

### 2.2 Исходящие вебхуки

Когда инициатор — платформа: «что-то произошло — сообщите внешней системе».

- Внешняя система регистрирует endpoint (таблицы `webhook_endpoints`/`webhook_events`, `V11`).
- Каждое событие — строка `webhook_events` со статусом доставки (`PENDING` → доставлено/ошибка).
  Это **очередь с retry**: недоставленные события остаются в очереди и могут быть перепоставлены.
- Подпись каждой доставки (та же схема, что платформа требует от отправителей, §2.3):

```
X-BC-Timestamp: 1698336000
X-BC-Signature: hex(HMAC-SHA256(secret, timestamp + "." + body))
```

Проверка на стороне получателя (Node):

```js
const crypto = require("crypto");
const ts = req.headers["x-bc-timestamp"], sig = req.headers["x-bc-signature"];
const expect = crypto.createHmac("sha256", secret).update(ts + "." + req.rawBody).digest("hex");
const ok = sig.length === expect.length && crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expect));
```

Получатель отвечает `2xx` — доставка выполнена; иначе событие остаётся в очереди и приедет
повторно. Обработчик обязан быть идемпотентным (проверка по id события/ключа), потому что
**retry гарантирован**.

### 2.3 Входящие триггеры

Когда инициатор — внешняя система: «вот документ/событие — обработайте». Основной вход —
`POST /api/v1/documents:ingest`; тело запроса — **сам файл** (не base64 в JSON),
аутентификация — API-токен со scope `documents:write` (таблица `api_tokens`, `V11`).

```bash
curl -s -X POST https://app.example.com/api/v1/documents:ingest \
  -H "Authorization: Bearer bc_tok_…" \
  -H "Idempotency-Key: ext-4711" \
  -H "X-Filename: rechnung-4711.pdf" \
  -H "Content-Type: application/pdf" \
  --data-binary @rechnung-4711.pdf
# → 201 {"documentId":"…","duplicate":false,"contentHash":"…"}
# → 200 {"duplicate":true, …} при повторе того же файла
```

- **Идемпотентность** — по `Idempotency-Key` и SHA-256 содержимого: повтор возвращает тот же
  `documentId` с `duplicate=true`, второй документ не создаётся.
- После приёма `AgentDispatcher` запускает агентов по доменному событию `document.created` —
  это и есть «входящий триггер»: внешняя система не зовёт агента напрямую, она доставляет
  **событие**, а манифестные триггеры (`document.created`, `case.created`, `task.due`,
  `deadline.hit`, `invoice.imported`, `payment.matched`, `mail.received`, …) решают, кто сработает.
- События, не являющиеся документами, проходят тем же путём: доставка через REST/шину (§2.6)
  порождает доменное событие → триггер → запуск агента.

### 2.4 Email-вход

Для сред, которые умеют только отправлять письма (старые ERP, кассы, сотрудники с почтой).
Платформа опрашивает подключённый ящик по IMAP (`mail_accounts`, `V9`); входящее письмо
порождает `mail.received`, по которому срабатывает `mail-accept`
(`"triggers": ["mail.received", "manual"]`). Дальше — обычный конвейер:

```
старая система ─SMTP─▶ ящик тенанта ─IMAP─▶ платформа ─mail.received─▶ mail-accept
                                          └─▶ doc-detect → doc-extract → invoice-check → …
```

Письмо с вложением (PDF/JPG/TIFF/PNG) или текстом → документ → агенты документов. Ответы
агента (`mail-reply`, `letter-draft`) уходят через разрешённый почтовый адаптер и только
после подтверждения (§4).

### 2.5 Файловый вход и scan-папка

Для сред без HTTP и почты (кладут файлы в папку) и для рабочих мест со сканером. Браузер
не умеет говорить со сканером, поэтому устройство обслуживает **desktop-bridge**
(`apps/desktop-bridge`).

| Источник | Как работает | Когда применять |
|---|---|---|
| `SANE` | `scanimage` сканирует (ADF/планшет), страницы уходят на платформу | Рабочее место со сканером (Linux/macOS) |
| `FOLDER` | Платформа читает файлы из настроенной папки | Windows-сканер, сетевая шара, legacy-экспорт |

Контракт устройства (`/api/v1/device-agent`):

- `POST /api/v1/device-agent/me` — устройство представляется (заголовок `X-Device-Key`; ключ
  показывается один раз при регистрации).
- `POST /api/v1/device-agent/claim` — взять следующий скан-заказ из очереди.
- `POST /api/v1/device-agent/jobs/{id}/result` — вернуть страницы (multipart) или причину,
  почему их нет. **Диагноз — тоже результат**: «сканера нет, установите sane-utils», а не «failed».

```bash
export BC_API_URL=https://app.example.com
export BC_DEVICE_KEY=…            # секрет: только из окружения, никогда в лог
node bridge.mjs diagnose          # готов ли компьютер сканировать и что делать, если нет
node bridge.mjs run               # опрос очереди и возврат сканов, пока не остановят
node bridge.mjs once              # один цикл — для cron/планировщика
```

Гарантия при обрыве связи: неудавшийся upload повторяется, файл сохраняется на диске —
**сломанное соединение не теряет скан**; принимающая сторона дедуплицирует по содержимому.

### 2.6 Очередь/шина через bridge-модуль

Для шин (ESB, сервисные шины старых систем). Платформа не зависит от конкретной шины: её роль
играют **очередь `webhook_events`** и агент `api-bridge` (модуль `bridge`, манифест
`integration/api-bridge.json`).

- `api-bridge` переносит payload **только по существующим путям передачи** — сегодня это
  исходящая очередь webhook: перенос ставит строку `PENDING` в `webhook_events` для активного
  endpoint. Маршрут, называющий провайдера без такого пути, **отклоняется с объяснением**,
  а не симулирует перенос. Правила маршрутизации — в манифесте (`invoice → webhook`,
  `document → webhook`, `payment.matched → BANK_FINTS`).
- Каждый перенос идемпотентен: повтор с тем же ключом (заданным или выведенным из
  источника+сущности+payload) возвращает существующий перенос. Каждый перенос логируется
  дважды: в `webhook_events` и в аудите.
- `data-sync` сверяет обе стороны границы (последние синхронизации, статусы доставок) и
  возвращает **план шагов**. Безопасные исправления (перезапустить упавшую синхронизацию,
  переотправить неудавшиеся доставки) — только с явным `"apply":"true"`; деструктивные шаги —
  только по подтверждению владельца (§4).

Паттерн для ESB: шина кладёт файл/событие в scan-папку (§2.5) или зовёт ingest (§2.3),
результаты получает по исходящим вебхукам (§2.2). Точкой соединения остаётся HTTP — шина
выступает адаптером.

### 2.7 Офлайн-режим: JSON/CSV для legacy

Для сред без сети (изолированные контуры, архивы) интеграция сводится к файловому обмену
через **журнал `agent_runs`** — он машиночитаем и полон.

```bash
# Экспорт наружу: JSON — результаты агента с produced-объектами
curl -s "https://app.example.com/api/v1/agents/doc-extract/runs?limit=100" \
  -H "Authorization: Bearer $TOKEN" -o export/runs-doc-extract.json

# CSV для legacy: один запуск = строка
curl -s "https://app.example.com/api/v1/agents/retention/runs?limit=100" \
  -H "Authorization: Bearer $TOKEN" \
  | jq -r '.[] | [.runId, .agentId, .trigger, .ok, .summary] | @csv' \
  > export/retention.csv
```

Импорт снаружи — файлы, которые приносит внешняя среда, кладутся в scan-папку (§2.5) или
загружаются через ingest (§2.3). Граница честности: офлайн-обмен — это **снимки журнала и
файлы на вход**, а не живой канал. Гарантии §1 действуют на обеих сторонах: импорт
идемпотентен по хешу, экспорт отдаёт полные `produced`-ссылки.

---

## 3. Агенты, которые приспосабливаются везде

Рантайм не знает о среде, потому что **среда никогда не является входом агента**. Агент видит
только контракт: манифест (что обещает), входы (что ему дали), журнал (что он произвёл).

1. **Манифесты вместо интеграционного кода.** Поведение (триггеры, правила, маршруты) — данные
   в JSON, а не код под конкретную систему. Подключить новую среду = выбрать канал из §2,
   а не переписывать агента.
2. **Правила, а не магия.** В пилоте агенты работают без внешнего AI: классификация, извлечение
   и проверки — детерминированные правила поверх модулей платформы (documents, tasks, cases,
   invoices, retention, bridge…). Результат воспроизводим, объясним, без сети к стороннему
   провайдеру.
3. **Один контракт на все каналы.** Письмо, скан, файл из папки и REST-запрос приходят к
   **одному и тому же** агенту через доменное событие. Среда — только способ доставки.

**Паттерн адаптера** — три ступени, от «срочно» к «полноценно»:

- **HTTP-обёртка перед REST.** Старая система умеет только свой протокол — перед ней ставится
  тонкая обёртка, переводящая её вызовы в `/api/v1/agents` и обратно. Платформу не трогаем.
- **Локальный агент-скрипт с тем же контрактом.** Node/Python-скрипт на машине legacy-системы
  повторяет контракт агента: те же `inputs`, тот же `{ok, summary, produced}`. Скрипт ходит
  в платформу по REST, а старая система вызывает скрипт локально (файл, stdin, локальный
  порт) — она «интегрирована», не подозревая о платформе:

```python
# adapter.py — контракт агента: inputs → {ok, summary, produced}
import sys, json, urllib.request

def run(inputs: dict) -> dict:
    req = urllib.request.Request(
        f"{BASE}/api/v1/agents/doc-detect/run",
        data=json.dumps({"trigger": "manual", "inputs": inputs}).encode(),
        headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)

if __name__ == "__main__":
    print(json.dumps(run(json.loads(sys.argv[1]))))
```

- **Полуавтоматический режим.** Где автоматике нельзя доверять: агент **готовит** — человек
  **подтверждает**. Агент возвращает план/проект (`summary` + `produced`), решение остаётся
  отдельным действием человека в модуле платформы. Адаптер ничего не исполняет, а пересылает
  результат человеку (письмо, очередь задач, экран).

---

## 4. Безопасность

**Аутентификация.** Люди — сессия платформы, права проверяются `@PreAuthorize` на контроллерах.
Машины — API-токены (`api_tokens`): значение показывается **один раз**, хранится только хеш;
у токена свои scope (`documents:write`, …), лимит запросов в минуту и срок действия.
Устройства — `X-Device-Key`: показывается один раз, читается из окружения, не логируется.

**Права.** Каталог/журнал — `agents.view`; запуск — `agents.run`. Роли OWNER/ADMIN получают их
через `V73`/`V84`; интеграционным аккаунтам выдавайте минимум — просмотр и запуск раздельно.

**Тенантность.** `tenant_id` в каждой бизнес-таблице, RLS `ENABLE`+`FORCE` (`V13`); контекст
тенанта берётся **из сессии, а не из тела запроса** — внешняя система не может «попроситься»
в чужой тенант. Токен действует в тенанте, которому выдан.

**Подписи вебхуков.** Входящие проверяются до чтения тела: `X-BC-Signature` =
`hex(HMAC-SHA256(secret, timestamp + "." + body))`, метка времени — защита от replay, сравнение —
с постоянным временем. Неподписанный запрос отклоняется, частичный приём не фиксируется.

**Что агенты НИКОГДА не делают без подтверждения.** Рантайм ограничивает агентов рамками
«подготовить и показать»; разрушительное действие всегда остаётся отдельным подтверждённым
актом человека:

| Действие | Правило | Пример в коде |
|---|---|---|
| Удаление персональных данных | Только план; стирание — отдельный акт человека в privacy-модуле | `gdpr-guard`: «The plan is the end of this agent's authority»; документ под legal hold всегда помечается заблокированным |
| Исполнение удаления по срокам хранения | Только отчёт о сроках и кандидатах; непроверенная политика не считается истёкшей | `retention`: «never schedules or executes a deletion» |
| Деструктивные внешние изменения | Отключение/удаление интеграции — только шаг плана с подтверждением владельца; безопасные исправления — только с `apply=true` | `data-sync`: «Nothing destructive ever runs here» |

Сеть наружу агенту закрыта, кроме явно разрешённых адаптеров (mail/CalDAV/API-мосты) — агент
не может «тихо» отправить данные стороннему сервису.

---

## 5. Сквозные сценарии

### 5.1 Бухгалтерия в старой системе получает задачи агентов по email

Система бухгалтерии умеет только SMTP/IMAP — интеграция через почтовый канал в обе стороны:

1. Платформа обрабатывает входящие документы (ingest/скан/письмо); агенты финансов
   (`invoice-capture`, `payment-match`, `dunning`, …) готовят задачи.
2. `task-dispatch` создаёт задачи и назначает их по ролевым правилам тенанта; без правила или
   кандидата — честно оставляет в очереди ручного распределения.
3. Адаптер (§3) забирает новые задачи из журнала и отправляет **письмо** в ящик бухгалтерии:
   тема = задача, тело = `summary` + ссылки на `produced`.
4. Ответ бухгалтерии (письмо с пометкой/вложением) приходит как `mail.received` → `mail-accept`
   → задача закрывается или уточняется.

Идемпотентность защищает сценарий: повторная доставка письма не создаёт вторую задачу
(`task-dispatch` проверяет источник), повторный ответ не дублирует закрытие.

### 5.2 Архивная система забирает результаты JSON-импортом

Архив изолирован от сети — офлайн-контур (§2.7):

1. `doc-archive`/`doc-assign` готовят документы к архивации и регистрируют `produced`.
2. Cron еженощно выгружает журнал нужных агентов в JSON-файл (`/runs?limit=…` + `jq`-маппинг)
   на шару, доступную архиву.
3. Архивная система импортирует JSON по своему регламенту; ключи связывания — `runId`,
   `documentId` из `produced`, хеши содержимого.
4. Обратный поток (архивный шифр, подтверждение) возвращается файлом в scan-папку (§2.5) —
   дедупликация по хешу гарантирует, что повторная выгрузка ничего не задвоит.

В обоих сценариях аудит сохраняется: каждый шаг агента — строка `agent_runs` с
`ok/summary/produced`, даже там, где внешняя система не ведёт собственного журнала.

---

## 6. Чек-лист внедрения

1. **Определить категорию агентов** по таблице §7 и каталогу `GET /api/v1/agents`
   (поле `implemented` — есть ли рабочая реализация, а не только манифест; в текущем каталоге
   оно `true` у всех 126 агентов).
2. **Выбрать канал** по возможностям внешней среды (§2): REST → вебхуки → email → папка →
   офлайн. Канал выбирает среда, не агент.
3. **Выдать минимальные права**: `agents.view`/`agents.run` интеграционному аккаунту; для
   входящих документов — API-токен со scope `documents:write`, лимитом и сроком.
4. **Настроить приём**: ingest-эндпоинт (заголовки `X-Filename`, `Idempotency-Key`), почтовый
   ящик (IMAP) или scan-папку/устройство.
5. **Настроить доставку результатов**: исходящие вебхуки с секретом для HMAC и идемпотентным
   обработчиком; для офлайн-контуров — ночная JSON/CSV-выгрузка.
6. **Прогнать пробный запуск вручную** (`POST …/run`) на реальных данных тенанта и прочитать
   `ok/summary/produced` — отказ честен, успех перечисляет произведённое.
7. **Проверить идемпотентность**: дважды доставить один файл/событие — второй раз должен
   вернуть существующий объект, а не дубликат.
8. **Проверить отказоустойчивость**: оборвать сеть на доставке — retry есть, потерь и дублей
   нет (скан не теряется, событие не задваивается).
9. **Включить полуавтоматический режим** там, где автоматика недопустима: агент готовит,
   человек подтверждает; деструктивные шаги — только в плане с подтверждением владельца.
10. **Закрепить аудит**: каждая операция внешней системы видна как строка `agent_runs`;
    завести мониторинг `ok=false` как штатного сигнала, а не ошибки.

---

## 7. Какой агент куда

Типовые среды по категориям каталога:

| Категория (примеры агентов) | Типовая внешняя среда | Типовой канал |
|---|---|---|
| `documents` (doc-detect, doc-ocr, doc-extract, doc-archive, doc-sign) | Сканеры/МФУ, файловые шары, архивные системы | scan-папка/desktop-bridge, ingest, офлайн JSON |
| `communication` (mail-accept, mail-reply, letter-draft, meeting-notes) | Почтовые ящики, legacy-ERP «только письма» | email-вход/выход, вебхуки |
| `finance` (invoice-capture, invoice-check, payment-match, dunning, cashflow) | Бухгалтерские системы, банки, DATEV-экспорт | REST, вебхуки, email с вложениями |
| `processes` (task-dispatch, workflow-route, approval, deadline-watch) | Системы задач/заявок, календари (CalDAV/ICS) | вебхуки (исходящие), REST, email-уведомления |
| `compliance` (gdpr-guard, retention, compliance-check, audit-trail) | Юридические/комплаенс-контуры, архивы | REST (планы и отчёты), офлайн CSV/JSON |
| `analysis` (report-daily, insight, anomaly, forecast, metric-collect) | BI/отчётные системы, дашборды руководства | REST, JSON-выгрузка |
| `crm` (crm-lead, crm-contact, crm-deal, crm-followup) | CRM/маркетинговые системы | вебхуки, REST |
| `integration` (api-bridge, data-sync, monitor-systems) | ESB/шины, межсистемные шлюзы | очередь webhook_events, REST с `apply=true` |
| `marketing` (seo-audit, sea-campaign, newsletter, lead-nurture) | CMS, Google Ads/Analytics, E-Mail-Marketing | REST, вебхуки кампаний, импорт CSV |
| `sales` (pipeline-review, offer-draft, followup-cadence) | CRM, Preislisten, Dokumentenablage | REST, вебхуки стадий сделки |
| `hr` (recruiting-screening, onboarding, time-tracking, payroll-data) | Bewerberportal, Zeiterfassung, Lohnbüro/DATEV Lohn | REST, DATEV-Export, офлайн CSV |
| `banking` (statement-import, reconcile, payment-order, fee-audit) | Bank (CAMT/MT940), Zahlungsdienstleister | CAMT-Import, REST, Freigabe-Workflow |
| `legal` (gdpr-request, contract-control, sanctions-kyc, audit-prep) | Datenschutz-, Vertrags- und Sanktionslisten | REST, Dokumenten-Upload, Audit-Export |
| `logistics` (order-intake, delivery-tracking, customs, route-planning) | ERP, Speditionen, Zoll, Lager | REST, EDI/CSV, Webhooks |
| `support` (ticket, nps, review-response, sla-report) | Helpdesk, Bewertungsportale, Umfragen | REST, E-Mail, Webhooks |
| `ecommerce` (catalog-sync, order-sync, payment-check, returns) | Shop, Marktplätze, PIM, Zahlungsanbieter | REST, Feed-Dateien (CSV/XML), Webhooks |
| `production` (work-order, material-plan, scheduling, warranty) | ERP/MES, Maschinen, Lieferanten | REST, Datei-Import, Webhooks |
| `realestate` (listing, tenant-admin, rent-dunning, maintenance) | Objektverwaltung, Portale, Dienstleister | REST, Dokumenten-Upload, E-Mail |
| `hospitality` (reservation, shift-plan, menu-cost, no-show) | Reservierungssystem, Kasse, Personalplanung | REST, ICS, Webhooks |
| `healthcare` (appointment, recall, billing, shift-handover) | Praxissoftware, Abrechnung, Terminportale | REST/Schnittstelle des Praxisystems, Freigabe |
| `it` (access-review, incident, backup-check, patch-window) | IdP/Rollen, Monitoring, Backup, Patchmanagement | REST, Webhooks, Audit-Export |
| `knowledge` (kpi-report, knowledge-base, policy-digest) | BI/Dashboard, Wissensbasis, Intranet | REST, JSON-Export |

---

Базовый контракт: [docs/agents/RUNTIME.md](agents/RUNTIME.md). Реализация API:
`services/api/.../agents/web/AgentsController.java`, приём документов: `.../ingest/`,
устройства: `.../devices/web/DeviceAgentController.java`, bridge: `.../bridge/DesktopBridge.java`
и `apps/desktop-bridge/`.
