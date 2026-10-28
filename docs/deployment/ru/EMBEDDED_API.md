# Embedded API

**Языки:** [Русский](EMBEDDED_API.md) · [Deutsch](../de/EMBEDDED_API.md) · [English](../en/EMBEDDED_API.md)

**Модель:** ваш продукт вызывает распознавание документов, агентов и workflow Firmspace
по HTTP. Интерфейс остаётся у клиента; Firmspace выполняет работу и возвращает результат.
Это не отдельная установка, а **способ интеграции** с Copilot в облаке или с Robots
Runtime на сервере клиента.

---

## 1. Что это и кому подходит

Embedded API нужен, когда у клиента есть свой сервис, портал, CRM или отраслевая
программа, и он хочет встроить туда приём документов, распознавание, агентов или
ELSTER-выгрузку. Клиентская система отправляет файл или команду и получает JSON; всё
остальное — на стороне Firmspace. Модель подходит разработчикам и интеграторам, которые
готовы писать код и обрабатывать ошибки. Если нужен готовый интерфейс для сотрудника —
это [Copilot как сервис](COPILOT_SAAS.md); если нужно только связать legacy-софт —
[только мост](BRIDGE_ONLY.md).

![Путь документа от папки до ELSTER](../../images/document-path.svg)

*Схема 2. Шаги 3–5 — это и есть Embedded API: приём, агент, выгрузка.*

---

## 2. Что понадобится

| Что | Требование | Как проверить |
|---|---|---|
| Базовый URL | адрес Copilot или Runtime (§3) | `curl -fsS "$BASE_URL/api/v1/license"` |
| API-токен | выпущен с нужными scopes (§4) | `curl ... /api/v1/agents` |
| TLS | валидный сертификат; проверка не отключена | `openssl s_client -connect <host>:443 -servername <host>` |
| `curl` | 7.76+ | `curl --version` |
| `jq` | необязательно | `jq --version` |
| Часы | NTP, расхождение меньше 5 минут | `timedatectl status` |
| Сеть | исходящий HTTPS 443 | `curl -I "$BASE_URL"` |

Примеры ниже написаны для `bash`/`zsh`. В PowerShell используйте `curl.exe` и
`Invoke-RestMethod`; синтаксис заголовков тот же.

---

## 3. Базовый URL

### 3.1 Copilot в облаке

```bash
export BASE_URL="https://burokratie.firmspace.de"
export TOKEN='<вставьте свой токен>'

curl -fsS "$BASE_URL/actuator/health/readiness"
# {"status":"UP"}

curl -fsS "$BASE_URL/api/v1/license" | jq '{state, plan, expiresAt}'
```

API всегда находится по адресу `<BASE_URL>/api/v1`. В примерах ниже путь указан целиком,
чтобы не было догадок.

### 3.2 Robots Runtime на сервере клиента

```bash
export BASE_URL="http://192.168.1.20:8080"
export TOKEN='<токен Runtime>'

curl -fsS "$BASE_URL/actuator/health/readiness"
curl -fsS "$BASE_URL/api/v1/license"
```

Адрес Runtime — тот, который напечатал установщик (`API URL:`). Если терминал закрыт:

```bash
sudo grep '^BC_PUBLIC_BASE_URL=' /opt/firmspace-runtime/runtime.env
```

API Runtime — `<этот адрес>/api/v1`. Подробнее — [RUNTIME.md](RUNTIME.md) §3.

### 3.3 Как убедиться, что URL правильный

| Проверка | Ожидаемо | Если нет |
|---|---|---|
| `curl -I "$BASE_URL"` | `200`/`302`, не таймаут | DNS, прокси, firewall |
| `curl -fsS "$BASE_URL/actuator/health/readiness"` | `{"status":"UP"}` | API не запущен |
| `curl -fsS "$BASE_URL/api/v1/license"` | JSON со `state` | неверный путь или прокси режет `/api` |
| `curl -fsS "$BASE_URL/api/v1/agents"` без токена | `401` | `404` — неверный URL |

---

## 4. Где взять токен

### 4.1 Copilot API-токен

1. Войдите в `https://burokratie.firmspace.de`.
2. Откройте меню аккаунта в правом верхнем углу → **Настройки аккаунта**.
3. В списке карточек выберите **API-токены**.
4. Нажмите **Создать токен**.
5. Введите **Имя**, по которому узнаете интеграцию: например, `embedded-prod` или
   `scanner-accounting`. Имя видно в списке, значение — нет.
6. Отметьте **только нужные права**:
   - `Передача документов` — это `documents:write`, обязательно для загрузки файлов;
   - `Чтение документов` — `documents:read`, если нужно читать список/содержимое;
   - `Просмотр агентов` — `agents.view`, если нужно читать каталог и журналы;
   - `Запуск агентов` — `agents.run`, если нужно запускать агентов и workflow;
   - `exports:read` — для ELSTER-выгрузки; в некоторых версиях галочка подписана
     техническим именем, это нормально.
7. Нажмите **Создать токен**.
8. В диалоге **Токен создан** нажмите **Скопировать токен** и сразу сохраните его в
   менеджер паролей. Значение показывается **один раз**; в списке остаётся только начало
   (`prefix`).

`[СКРИНШОТ]` Три экрана: карточка «API-токены», диалог создания с галочками прав, диалог
«Токен создан» с кнопкой копирования.

Если токен потерян: откройте **API-токены**, найдите запись по имени и `prefix`, нажмите
**Отозвать**, создайте новый. Восстановить старое значение нельзя — сервер хранит только
SHA-256.

Важные ограничения:

- права после создания **не меняются**;
- максимум **20 активных токенов** на workspace; при 20 появится сообщение об ошибке;
- по умолчанию **60 запросов в минуту** на токен (Runtime-админ выпускает 120);
- создавать токены может роль с правом `api_tokens.manage` (обычно OWNER/ADMIN).

### 4.2 Токен Runtime

Токен выпускает установщик (первый) или оператор через админ-API:

```bash
set -a; . /opt/firmspace-runtime/runtime.env; set +a
curl -sS -X POST \
  -H "X-Runtime-Admin-Token: $BC_RUNTIME_ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  "http://127.0.0.1:8080/api/v1/runtime/tokens" \
  -d '{"name":"embedded-prod","scopes":["documents:write","agents.view","agents.run","exports:read"]}' | jq
```

Значение `token` в ответе показывается один раз. Подробнее — [RUNTIME.md](RUNTIME.md) §7.

### 4.3 Scopes

| Scope | Что открывает | Чего не открывает |
|---|---|---|
| `documents:write` | `POST /api/v1/documents:ingest` | чужие документы, запуск агентов |
| `documents:read` | `GET /api/v1/documents`, `/documents/{id}`, `/documents/{id}/content` | загрузку и изменение |
| `agents.view` | `GET /agents`, `/agents/{id}`, `/agents/{id}/runs`, чтение workflow и созвездий | запуск и изменение |
| `agents.run` | `POST /agents/{id}/run`, `/constellations/{id}/instantiate`, `/workflows/{id}/run`, `/workflow-runs/{id}/resume` | публикацию и редактирование workflow |
| `exports:read` | `GET /api/v1/exports/elster` | запуск агентов, настройки |
| `settings:read` | чтение настроек | изменение |
| `settings:write` | изменение правил, дат, retention | управление токенами |

Запись `documents.write` (с точкой) принимается при создании и нормализуется в
`documents:write`. Неизвестный scope — ошибка создания, а не «молча сохранённое слово».

---

## 5. Первые команды

### 5.1 Переменные

```bash
export BASE_URL="https://burokratie.firmspace.de"
export TOKEN='<вставьте свой токен>'
```

`BASE_URL` — без завершающего `/`. Если поставить `/` в конце, получится `//api/v1` и
некоторые прокси ответят `404`. Проверьте:

```bash
printf '%s\n' "${BASE_URL%/}"
```

### 5.2 Проверка токена

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/v1/agents"
```

Ожидаемо `200`. `401` — токен неверный/отозван. `403` — токен валиден, но у него нет
`agents.view`; это разные проблемы.

### 5.3 Каталог агентов

```bash
curl -fsS "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" | jq '.[0]'
```

Ожидаемый ответ (сокращённо):

```json
{
  "id": "doc-detect",
  "name": "Dokument-Erkennung",
  "category": "documents",
  "description": "Erkennt Dokumenttypen und steuert die weitere Verarbeitung an.",
  "functions": ["Typ-Erkennung", "Kanal-Zuordnung", "Übergabe an Fach-Agenten"],
  "triggers": ["document.created", "manual"],
  "implemented": true,
  "runCount": 0
}
```

### 5.4 Загрузка файла

```bash
curl -sS -X POST "$BASE_URL/api/v1/documents:ingest" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: invoice-2026-0004711-1" \
  -H "X-Filename: rechnung-4711.pdf" \
  -H "Content-Type: application/pdf" \
  --data-binary @rechnung-4711.pdf \
  -w '\nHTTP %{http_code}\n'
```

Ожидаемый ответ `201`:

```json
{
  "documentId": "6f1c2a9e-6d0b-4a4d-9f2e-2a1d7f3b8c11",
  "duplicate": false,
  "message": "Dokument aufgenommen",
  "contentHash": "9f2c8a…64 hex…"
}
```

Повтор с тем же `Idempotency-Key` вернёт `200` и тот же `documentId`:

```json
{
  "documentId": "6f1c2a9e-6d0b-4a4d-9f2e-2a1d7f3b8c11",
  "duplicate": true,
  "message": "Dieser Schlüssel wurde bereits verarbeitet; das vorhandene Dokument wird zurückgegeben",
  "contentHash": "9f2c8a…"
}
```

Что означают флаги `curl`:

| Флаг | Зачем |
|---|---|
| `-sS` | без прогресс-бара, но показать ошибку сети |
| `-X POST` | метод запроса |
| `-H "..."` | заголовок; `Authorization` и `Idempotency-Key` обязательны |
| `--data-binary @file` | отправить файл как есть, без преобразования |
| `-o file` | сохранить ответ в файл |
| `-w '\nHTTP %{http_code}\n'` | напечатать HTTP-код |
| `-f` | считать HTTP ≥ 400 ошибкой (удобно в скриптах) |

Правила ingest:

- `Idempotency-Key` обязателен. Без него — `400 INVALID_REQUEST`.
- `X-Filename` обязателен. Без него — `400`.
- Хеш SHA-256 считает сервер; клиент не может его подменить.
- Тот же файл с **другим** ключом → `200 duplicate=true`, второй документ не создаётся.
- Тот же ключ, пока первый запрос выполняется → `409 CONFLICT`.
- Запись ключа живёт **24 часа**.
- Ответ `201/200` означает «файл сохранён», а не «распознан». OCR и категории появляются
  позже, если в инстансе включён конвейер.

### 5.5 Запуск агента

```bash
curl -sS -X POST "$BASE_URL/api/v1/agents/doc-detect/run" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
        "trigger": "manual",
        "inputs": {
          "fileName": "rechnung-4711.pdf",
          "documentId": "6f1c2a9e-6d0b-4a4d-9f2e-2a1d7f3b8c11"
        }
      }' | jq
```

Ожидаемый ответ:

```json
{
  "runId": "1d9b7c44-2a6e-4f1e-9b0a-7c3d5e8f1a22",
  "agentId": "doc-detect",
  "ok": true,
  "summary": "Dokumenttyp erkannt: Rechnung",
  "produced": [
    { "kind": "document", "ref": "6f1c2a9e-…", "label": "Rechnung" }
  ],
  "meta": {}
}
```

`ok: false` — **штатный** ответ агента (нет данных, не настроен источник, документ не
найден), а не HTTP-ошибка. Проверяйте `ok` и `summary`; не считайте побочный эффект
доказанным без `produced` и журнала.

`POST /agents/{id}/run` не принимает ключ идемпотентности: каждый вызов создаёт новый
`runId`. Если нужна защита от дублей, используйте workflow с `idempotencyKey` или свой
ключ в `inputs`.

### 5.6 Workflow (если нужен процесс из нескольких шагов)

```bash
# 1. Сохранить созвездие как workflow
curl -sS -X POST "$BASE_URL/api/v1/constellations/datev-pipeline/instantiate" \
  -H "Authorization: Bearer $TOKEN" | jq

# 2. Запустить workflow (ключ идемпотентности — в теле)
curl -sS -X POST "$BASE_URL/api/v1/workflows/<WORKFLOW_ID>/run" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "triggerType": "API",
        "triggerPayload": {"source": "embedded"},
        "idempotencyKey": "datev-2026-10-27-001"
      }' | jq

# 3. Прочитать состояние
curl -fsS "$BASE_URL/api/v1/workflow-runs/<RUN_ID>" \
  -H "Authorization: Bearer $TOKEN" | jq

# 4. Решение на паузе: APPROVE | RETRY | SKIP
curl -sS -X POST "$BASE_URL/api/v1/workflow-runs/<RUN_ID>/resume" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE"}' | jq
```

Статусы: `PENDING`, `RUNNING`, `WAITING`, `WAITING_APPROVAL`,
`PAUSED_FOR_HUMAN_REVIEW`, `COMPLETED`, `FAILED`, `CANCELLED`, `COMPENSATED`.
`resume` работает только для `PAUSED_FOR_HUMAN_REVIEW`; повторный вызов после решения —
`409`.

### 5.7 ELSTER-выгрузка

```bash
# JSON (по умолчанию): строки, настройки, итоги, предупреждения
curl -fsS "$BASE_URL/api/v1/exports/elster?year=2025" \
  -H "Authorization: Bearer $TOKEN" \
  | jq '{year, summary: .summary | {rows, expensesNetto, expensesUst, warnings}}'

# CSV для Excel: разделитель «;», десятичная запятая
curl -fsS "$BASE_URL/api/v1/exports/elster?year=2025&format=csv" \
  -H "Authorization: Bearer $TOKEN" \
  -o elster-euer-2025.csv
```

Ожидаемый JSON (сокращённо):

> Значения в строке — пример. Конкретную строку EÜR, Kennzahl und DATEV-Konto bestimmt der Kategorienkatalog der Instanz.

```json
{
  "year": 2025,
  "settings": {
    "kleinunternehmer": false,
    "sollIst": "SOLL",
    "wirtschaftsjahr": 2025,
    "bewirtungSplit": true
  },
  "summary": {
    "rows": 42,
    "expensesNetto": 12840.55,
    "expensesUst": 2439.70,
    "expensesBrutto": 15280.25,
    "warnings": [
      {
        "code": "GIFT_OVER_50_SINGLE",
        "message": "Geschenk über 50,00 € netto: kein Betriebsausgaben- und kein Vorsteuerabzug (Kz 164).",
        "documentId": "6f1c2a9e-…",
        "details": {}
      }
    ]
  },
  "rows": [
    {
      "categoryCode": "bewirtung",
      "euerLine": "63",
      "euerKz": "175",
      "datevSkr03": "4650",
      "datevSkr04": "6650",
      "bookingKind": "AUSGABE",
      "netto": 100.00,
      "ust": 19.00,
      "brutto": 119.00,
      "vorsteuerAbziehbar": true,
      "documentId": "6f1c2a9e-…",
      "date": "2025-03-14"
    }
  ]
}
```

CSV-колонки фиксированы:

```text
category_code;euer_line;euer_kz;datev_skr03;datev_skr04;booking_kind;netto;ust;brutto;vorsteuer_abziehbar;bewirtung_abziehbar_70;bewirtung_nicht_abziehbar_30;document_id;date
```

Правила:

- `year` необязателен; без него берётся `Wirtschaftsjahr` из настроек, иначе текущий год.
- `format` — `json` или `csv`; иное → `400`.
- Пустой год — `200` с пустым списком и нулями, не ошибка.
- В CSV нет предупреждений; они есть только в JSON (`summary.warnings`).
- Это не налоговая консультация: перед подачей бухгалтер проверяет правила из
  [../ELSTER_README.md](../../ELSTER_README.md).

---

## 6. Ошибки: что значит и что делать

Единый конверт ошибки:

```json
{
  "code": "INVALID_REQUEST",
  "message": "Ohne Dateinamen lässt sich das Dokument später nicht wiederfinden",
  "correlationId": "b1f0…",
  "path": "/api/v1/documents:ingest",
  "timestamp": "2026-10-27T20:00:00Z"
}
```

| HTTP | `code` (пример) | Когда | Что делать |
|---|---|---|---|
| `400` | `INVALID_REQUEST` | нет `Idempotency-Key`/`X-Filename`, пустое тело, плохой `year`/`format` | исправить запрос; не повторять |
| `401` | `UNAUTHENTICATED` | токен отсутствует, неизвестен, отозван или просрочен | выпустить новый токен; проверить `Bearer` |
| `402` | `LICENSE_MISSING` | в Runtime нет действующей лицензии | установить ключ; health и `/license` доступны |
| `402` | `LICENSE_READ_ONLY` | лицензия истекла и grace закончился | заменить ключ; чтение работает, запись нет |
| `402` | `LICENSE_REVOKED` | лицензию отозвали в кабинете | установить новый ключ; льготного периода нет |
| `402` | `LICENSE_RUN_LIMIT` | исчерпан `maxRunsPerMonth` | ждать новый месяц или изменить лицензию |
| `402` | `SUBSCRIPTION_REQUIRED`, `PLAN_LIMIT_REACHED` | SaaS: тариф не позволяет | продлить тариф/сменить план |
| `403` | `FORBIDDEN` | у токена нет нужного scope | выдать правильный scope и новый токен |
| `404` | `NOT_FOUND` | агент/запуск не найден; админ-API скрыт | проверить id и базовый URL |
| `409` | `CONFLICT` | ключ ещё обрабатывается; `resume` не на паузе; bootstrap уже был | прочитать тело, действовать по коду |
| `413` | `PAYLOAD_TOO_LARGE` | файл больше лимита | уменьшить файл или поднять лимит |
| `422` | `VALIDATION_FAILED`, `FILE_REJECTED` | невалидные поля, файл отклонён | исправить данные, не повторять |
| `429` | `RATE_LIMITED` | превышен лимит токена или общий | backoff с jitter, см. §7 |
| `502` | `DEPENDENCY_UNAVAILABLE` | недоступно хранилище/внешняя система | повторить с backoff, сообщить `correlationId` |
| `500` | `INTERNAL_ERROR` | непредвиденная ошибка | повторить с тем же `Idempotency-Key`, сообщить `correlationId` |

Особенности, которые важно знать:

- На агентском API `402` приходит **раньше** `401`: без лицензии даже запрос без токена
  получит `LICENSE_MISSING`, чтобы оператор видел настоящую причину.
- На `documents:ingest` `LICENSE_READ_ONLY` блокирует приём, а `LICENSE_MISSING` — нет:
  документы принимаются до появления ключа.
- `Retry-After` гарантирован общим лимитом; на лимите конкретного токена заголовка может
  не быть — ориентируйтесь на `429` и backoff.
- Тело ошибки не содержит SQL, stack trace или данных другого тенанта.

---

## 7. Идемпотентность и лимиты

### 7.1 Идемпотентность

| Операция | Ключ | Поведение |
|---|---|---|
| `documents:ingest` | `Idempotency-Key` + SHA-256 | тот же ключ → тот же `documentId`, `duplicate=true`; тот же контент с другим ключом → `duplicate=true`; ключ в обработке → `409`; запись 24 ч |
| `workflows/{id}/run` | `idempotencyKey` в теле | тот же ключ → существующий `runId`, `created=false` |
| `workflow-runs/{id}/resume` | нет | повтор после решения → `409`; сначала прочитать статус |
| `agents/{id}/run` | нет | каждый вызов — новый `runId` |

Рекомендуемый ключ:

```text
Idempotency-Key = "<system>:<external-id>:<attempt-scope>"
```

Ключ должен быть стабильным для одной и той же попытки и разным для нового документа. Не
используйте случайный UUID на каждый retry: это создаст лишние записи, хотя хеш и спасёт
от второго документа.

### 7.2 Лимиты

| Лимит | Значение | Где задаётся |
|---|---|---|
| Запросов на токен | 60/мин (Copilot), 120/мин (Runtime admin grant) | `api_tokens.rate_limit_per_minute` |
| Общий лимит на пользователя/IP | 600/мин на инстанс | `burokratie.security.rate-limit` |
| Активных токенов | 20 на workspace | `MAX_ACTIVE_TOKENS_PER_WORKSPACE` |
| Размер загрузки | 100 МБ по умолчанию | `BC_MAX_UPLOAD_SIZE` |
| Размер запроса | 250 МБ по умолчанию | `BC_MAX_REQUEST_SIZE` |
| Запись идемпотентности | 24 ч | `idempotency_records.expires_at` |
| Запуски агентов | `maxRunsPerMonth` лицензии или тариф | лицензия/план |

Рекомендации:

- таймаут клиента ≥ 60 с для большого файла;
- при `429` и `5xx` — экспоненциальный backoff с jitter, 5–8 попыток;
- при `400/401/403/404` — не повторять;
- один документ — один поток, без параллельных ingest с разными ключами.

---

## 8. Безопасность и ротация

- Токен — только на сервере. Не в браузере, не в мобильном приложении, не в URL, не в
  логах, не в git.
- Отдельный токен для test и prod; отдельный на каждую интеграцию.
- Минимальные scopes: ingest — только `documents:write`; чтение — `documents:read`;
  запуск — `agents.run`.
- Ротация без простоя: создать новый → задеплоить → убедиться, что `lastUsedAt` нового
  растёт → отозвать старый.
- При утечке: отозвать, выпустить новый, проверить `agent_runs`/аудит, уведомить по
  процедуре инцидента.
- Тенант берётся из токена; подмена `tenantId` в теле не работает.

---

## 9. Чек-лист интеграции

- [ ] Базовый URL проверен (`/api/v1/license` отвечает JSON).
- [ ] Токен выпущен на нужный workspace, scopes минимальны.
- [ ] `curl /api/v1/agents` с токеном → `200`.
- [ ] `documents:ingest` с реальным файлом → `201` и `documentId`.
- [ ] Повтор с тем же ключом → `200` и тот же `documentId`.
- [ ] `agents/doc-detect/run` → `ok: true/false`, обрабатывается как бизнес-ответ.
- [ ] `exports/elster?format=csv` с `exports:read` → файл.
- [ ] Ошибки `401/402/403/409/429/5xx` обрабатываются по таблице §6.
- [ ] Backoff и таймауты настроены; нет retry на `400/401/403/404`.
- [ ] `correlationId` логируется.
- [ ] Ротация токена отрепетирована.
