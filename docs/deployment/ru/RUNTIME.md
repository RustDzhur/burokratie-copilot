# Robots Runtime (on-prem)

**Языки:** [Русский](RUNTIME.md) · [Deutsch](../de/RUNTIME.md) · [English](../en/RUNTIME.md)

**Модель:** движок агентов Firmspace работает на сервере клиента. Снаружи — только API;
веб-кабинета в поставке нет. Документы, метаданные и журналы остаются в контуре клиента,
если клиент сам не включит внешний AI, почту или другой исходящий канал.

![Поток лицензии Runtime](../../images/runtime-license.svg)

*Схема 4. Ключ вставляется на странице `/runtime-admin`, сохраняется в
`/app/data/license.key` и сразу проверяется. Отзыв приходит из кабинета не мгновенно, а
при периодической проверке.*

---

## 1. Что это и кому подходит

Robots Runtime — это API-приложение (Spring Boot, профиль `runtime`) плюс PostgreSQL 17,
которые запускаются на сервере клиента в Docker. Внутри — агенты, workflow, приём
документов, OCR, выгрузки, лицензия и административный API. Веб-интерфейса нет:
управление идёт через HTTP API, а первый токен создаёт установщик. Модель подходит тем,
кто не может отдать документы в облако, но готов обслуживать сервер, PostgreSQL и бэкапы.
Если нужен готовый кабинет без своей инфраструктуры — это
[Copilot как сервис](COPILOT_SAAS.md).

---

## 2. Что понадобится

### 2.1 Сервер

| Профиль | CPU | RAM | Диск | Когда |
|---|---|---|---|---|
| Малый (≤ 20 клиентов API, ≤ 50 000 документов) | 4 vCPU | 8 ГБ | 200 ГБ SSD | пилот, небольшая фирма |
| Средний (≤ 200 клиентов, ≤ 500 000 документов) | 8 vCPU | 16 ГБ | 1 ТБ SSD | продуктив |
| Крупный | 16 vCPU | 32 ГБ | по объёму + отдельный диск для бэкапов | много параллельных запусков |

Это стартовые оценки, не гарантии. Реальный размер зависит от OCR, размера файлов,
числа параллельных запусков и срока хранения.

### 2.2 Программное обеспечение

| Компонент | Требование | Как проверить |
|---|---|---|
| ОС | Linux x86_64/arm64 | `uname -a` |
| Docker Engine | 24 или новее | `docker version --format '{{.Server.Version}}'` |
| Docker Compose | v2 | `docker compose version` |
| `curl` | 7.76+ | `curl --version` |
| `git` | для исходников поставки | `git --version` |
| `openssl` | для генерации секретов | `openssl version` |
| PostgreSQL | 17, входит в compose | `docker compose ... exec postgres psql --version` |
| TLS-прокси | nginx/Caddy, сертификат на домен | `openssl s_client -connect <host>:443` |
| NTP | расхождение меньше 5 минут | `timedatectl status` |

### 2.3 Сеть и порты

| Направление | Порт | Зачем |
|---|---|---|
| Клиент → прокси | 443/tcp | API по HTTPS |
| Прокси → api | 8080/tcp (внутренний) | проксирование |
| api → postgres | 5432/tcp (внутренний) | база |
| api → S3 (если используется) | 443/tcp | файлы документов |
| Мониторинг → api | 8080 (внутренний) | `/actuator/health/readiness` |

Наружу не должны быть опубликованы PostgreSQL и порт API. Если используется `local-fs`,
исходящий интернет для работы не нужен; он нужен для обновлений и поддержки.

### 2.4 Что подготовить до установки

| # | Что | Кто | Зачем |
|---|---|---|---|
| 1 | Домен и TLS-сертификат или внутренний IP | клиент | `BC_PUBLIC_BASE_URL` |
| 2 | `LICENSE_KEY` и `LICENSE_SECRET` (≥ 32 символа) | Firmspace | лицензия |
| 3 | Сервер с Docker 24+/Compose v2 | клиент | runtime-стек |
| 4 | Каталог данных, например `/opt/firmspace-runtime/data` | клиент | `instance-id` и файлы |
| 5 | Решение по бэкапам | клиент | PostgreSQL + каталог данных |
| 6 | Решение по `LICENSE_CHECK_URL` (проверка отзыва) | Firmspace + клиент | включить или явно выключить дистанционный отзыв |

> `LICENSE_SECRET` — это не пароль клиента, а общий HMAC-секрет, которым подписан ключ.
> Без него Runtime не может проверить ключ и останется в `LICENSE_MISSING`.

---

## 3. Базовый URL: где его взять

У Runtime нет одного «облачного» адреса. Базовый URL — это адрес, который вы указали
установщику в `--public-url`; он же печатается в конце установки строкой `API URL:`.

Если терминал закрыт, адрес лежит в env-файле:

```bash
# если ставили с --dir /opt/firmspace-runtime
sudo grep '^BC_PUBLIC_BASE_URL=' /opt/firmspace-runtime/runtime.env

# если ставили без --dir
sudo grep '^BC_PUBLIC_BASE_URL=' /path/to/burokratie-copilot/infra/runtime.env
```

Пример вывода:

```text
BC_PUBLIC_BASE_URL=http://192.168.1.20:8080
```

Тогда:

| Что | Адрес |
|---|---|
| Базовый URL | `http://192.168.1.20:8080` |
| API | `http://192.168.1.20:8080/api/v1` |
| Health | `http://192.168.1.20:8080/actuator/health/readiness` |
| Лицензия | `http://192.168.1.20:8080/api/v1/license` |
| Админ-API | `http://192.168.1.20:8080/api/v1/runtime` |

Проверка:

```bash
export BASE_URL="http://192.168.1.20:8080"
curl -fsS "$BASE_URL/actuator/health/readiness"
# {"status":"UP"}

curl -fsS "$BASE_URL/api/v1/license"
```

Второй источник адреса — ответ bootstrap при установке: поле `apiBase`. Третий — строка
`API URL:` в выводе установщика. Других мест нет; «угадать» адрес по имени хоста нельзя.

---

## 4. Установка

### 4.1 Подготовьте каталог поставки

```bash
cd /path/to/burokratie-copilot
ls scripts/install-runtime.sh scripts/bootstrap-runtime.sh infra/docker-compose.runtime.yml
```

Все три файла должны существовать. Установщик собирает образ из этого checkout, поэтому
каталог должен оставаться на диске и после установки: в нём лежат compose-файл и скрипты
обслуживания.

### 4.2 Одна команда

```bash
sudo scripts/install-runtime.sh \
  --public-url http://192.168.1.20:8080 \
  --bind-address 0.0.0.0 \
  --api-port 8080 \
  --dir /opt/firmspace-runtime \
  --tenant "Muster GmbH" \
  --email admin@example.de \
  --owner-name "Admin Muster" \
  --license-key-file /root/firmspace/license.key \
  --license-secret-file /root/firmspace/license.secret \
  --non-interactive
```

Что делает команда, по шагам:

1. Проверяет Docker, Compose, `curl` и наличие compose-файла.
2. Генерирует пароль БД, AES-ключ, runtime admin token и `instance-id`.
3. Записывает `/opt/firmspace-runtime/runtime.env` с правами `600`.
4. Собирает образ API и поднимает `postgres` + `api`.
5. Запускает `scripts/bootstrap-runtime.sh`: создаёт tenant, workspace, owner и первый
   API-токен, ждёт health.
6. Печатает адрес, идентификаторы, состояние лицензии и **токен один раз**.

Флаги установщика (сверены с разбором аргументов):

| Флаг | Смысл | По умолчанию |
|---|---|---|
| `--public-url` | внешний URL API | `http://localhost:8080` или LAN-IP |
| `--domain` | сокращение: `--public-url https://<domain>` | — |
| `--bind-address` | адрес публикации API | `127.0.0.1` |
| `--lan` / `--localhost` | быстро выбрать `0.0.0.0` / `127.0.0.1` | `127.0.0.1` |
| `--api-port` / `--port` | порт API | `8080` |
| `--dir` | корень установки: `<dir>/runtime.env` + `<dir>/runtime-data` | — |
| `--data-dir` | каталог данных без `--dir` | `infra/runtime-data` |
| `--tenant` | имя тенанта | `Firmspace Robots Runtime` |
| `--email` | e-mail владельца; обязателен в `--non-interactive` | — |
| `--owner-name` | имя владельца | `Runtime Owner` |
| `--license-key-file` | файл с ключом лицензии | — |
| `--license-secret-file` | файл с HMAC-секретом (≥ 32 символа) | — |
| `--admin-token-file` | готовый admin-токен вместо генерации | — |
| `--env-file` | путь к env-файлу | `infra/runtime.env` |
| `--project-name` | имя compose-проекта | `firmspace-robots-runtime` |
| `--no-build` | не собирать образ | — |
| `--force` | перезаписать env на **чистой** установке | — |
| `--non-interactive` | без вопросов | — |

### 4.3 Интерактивный режим

Если запустить без флагов, установщик спросит:

```text
Public API URL [http://localhost:8080]:
Bind API to [127.0.0.1] (127.0.0.1 = local only, 0.0.0.0 = LAN):
Data directory [/path/to/burokratie-copilot/infra/runtime-data]:
Tenant name [Firmspace Robots Runtime]:
Owner e-mail (for audit/token ownership):
Owner display name [Runtime Owner]:
Licence key (empty = start in LICENSE_MISSING; agents answer 402):
Licence secret (the shared HMAC secret, min 32 chars):
```

Если ключа нет, оставьте поле пустым: установка стартует в `LICENSE_MISSING`, агенты
отвечают `402`, но health, `/api/v1/license`, админ-API и ingest работают. Ключ можно
добавить позже (§5.3).

### 4.4 Что должно появиться

В конце установки выводится блок:

```text
Firmspace Robots Runtime — installation ready
=============================================

API URL:       http://192.168.1.20:8080
Local health:  http://127.0.0.1:8080/actuator/health/readiness
Tenant:        <uuid>
Workspace:     <uuid>
Instance id:   <uuid>
Licence:       LICENSE_ACTIVE (plan ENTERPRISE, Muster GmbH)
Licence ends:  perpetual (no expiry)
Token id:      <uuid> (prefix bc_…)
Token:         <показывается один раз>

This token is shown ONCE. The server stores only its SHA-256 hash.
```

Сохраните `Token` в менеджер паролей **сразу**. Если терминал закрылся, новый токен
выпускается через `scripts/bootstrap-runtime.sh` или админ-API (§7); второй tenant при
этом не создаётся.

Если лицензии нет, будет предупреждение:

```text
WARNING: no valid licence is installed: agent endpoints answer 402 until LICENSE_KEY is set
```

### 4.5 Проверка после установки

```bash
cd /path/to/burokratie-copilot
set -a; . /opt/firmspace-runtime/runtime.env; set +a
COMPOSE=(docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml)

"${COMPOSE[@]}" ps
curl -fsS "http://127.0.0.1:${BC_API_PORT:-8080}/actuator/health/readiness"
curl -fsS "http://127.0.0.1:${BC_API_PORT:-8080}/api/v1/license"
"${COMPOSE[@]}" logs --tail=100 api
```

Ожидаемо: оба контейнера `Up`, health `{"status":"UP"}`, лицензия `LICENSE_ACTIVE` или
`LICENSE_MISSING` с понятной причиной.

---

## 5. Лицензия

### 5.1 Как она устроена

Лицензионный ключ — компактный токен из трёх частей:

```text
base64url(header).base64url(payload).base64url(HMAC-SHA256)
```

Внутри: `licenseId`, `customer`, `instanceId`, `plan`, `maxAgents`, `maxRunsPerMonth`,
`features`, `issuedAt` и — необязательно — `expiresAt`. Ключ подписан `LICENSE_SECRET` и
привязан к `instance-id` конкретной установки. Тот же секрет хранится на сервере, поэтому
клиент с root-доступом теоретически может выпустить себе ключ. Лицензия — контрактный и
операционный контроль, а не DRM; это честное ограничение реализации.

Посмотреть `instance-id`:

```bash
cat /opt/firmspace-runtime/runtime-data/instance-id
```

### 5.2 Бессрочная по умолчанию

Коммерчески лицензия **бессрочная**: ежегодного продления нет. Технически это значит, что
в ключе **нет поля `expiresAt`**. Такой ключ всегда `LICENSE_ACTIVE`, у него нет grace-периода
и нет предупреждений об истечении.

```bash
curl -fsS "$BASE_URL/api/v1/license" \
  | jq '{state, perpetual, expiresAt, daysRemaining, revokedAt}'
# perpetual: true, expiresAt: null
```

Если ранее был согласован срок (исключение), Firmspace выпускает ключ с `expiresAt`
(например, `2027-12-31`). Только такой ключ проходит через `LICENSE_GRACE` и
`LICENSE_READ_ONLY`. В стандартной форме кабинета поля срока нет.

### 5.3 Установить или заменить ключ

**Основной путь — страница администратора, без перезапуска.** Она проверяет ключ до записи
и сохраняет его в `/app/data/license.key` с правами `600`.

1. Откройте `http://<host>:8080/runtime-admin/` (§6).
2. Введите admin-токен и нажмите **Проверить**.
3. В блоке **Лицензия** вставьте ключ и нажмите **Установить**.
4. Дождитесь сообщения **Лицензия установлена**. Ключ уже действует в запущенном API.

**Альтернатива для автоматизации — env-файл.** Она требует перезапуска:

1. Откройте env-файл:

   ```bash
   sudo editor /opt/firmspace-runtime/runtime.env
   ```

2. Заполните:

   ```dotenv
   LICENSE_KEY=<ключ из кабинета>
   LICENSE_SECRET=<HMAC-секрет, ≥ 32 символа>
   ```

3. Перезапустите API:

   ```bash
   cd /path/to/burokratie-copilot
   sudo docker compose --env-file /opt/firmspace-runtime/runtime.env \
     -f infra/docker-compose.runtime.yml up -d api
   ```

4. Проверьте:

   ```bash
   curl -fsS "http://127.0.0.1:8080/api/v1/license" | jq
   ```

> **Не смешивайте два пути.** Если `LICENSE_KEY` задан в `runtime.env`, он имеет приоритет
> над файлом из веб-интерфейса и снова вступит в силу после перезапуска. Для управления
> через `/runtime-admin` удалите строку `LICENSE_KEY` из env и оставьте
> `LICENSE_KEY_FILE=/app/data/license.key` (в compose это значение по умолчанию).
> `LICENSE_SECRET` через веб-интерфейс не вводится: он остаётся в env.

> **Если ключ будет устанавливаться только через страницу.** При установке передайте
> `--license-secret-file`, но не `--license-key-file`: `LICENSE_SECRET` нужен для проверки,
> а `LICENSE_KEY` останется пустым. Установка стартует в `LICENSE_MISSING`, и первый ключ
> вставляется на странице.


Проверить, какой файл используется:

```bash
docker exec firmspace-robots-runtime-api printenv LICENSE_KEY_FILE
ls -l /opt/firmspace-runtime/runtime-data/license.key
```

### 5.4 Состояния

| Состояние | Что значит | Что делать |
|---|---|---|
| `LICENSE_ACTIVE` | ключ действителен; для бессрочного — всегда | работать |
| `LICENSE_GRACE` | срок истёк, идёт 14-дневный grace; только для ключей с `expiresAt` | заменить ключ до конца grace |
| `LICENSE_READ_ONLY` | grace закончился; только для ключей с `expiresAt` | чтение работает, запись и запуски — нет |
| `LICENSE_REVOKED` | кабинет отозвал лицензию | установить новый ключ; льготного периода нет |
| `LICENSE_MISSING` | ключа нет или он неверен | проверить `reason` в `/api/v1/license`, установить ключ |
| `LICENSE_NOT_ENFORCED` | проверка выключена | только dev/test; в Runtime не используется |

`LICENSE_GRACE_DAYS` по умолчанию `14`. `LICENSE_MISSING` не блокирует приём документов
(ingest): документы сохраняются до появления ключа. `LICENSE_REVOKED` закрывает агентский
бизнес-контур (`402` с кодом `LICENSE_REVOKED`), но приём документов остаётся доступным:
это вход, а не исполнение.

### 5.5 Отзыв лицензии

1. Откройте кабинет владельца (для мостов и лицензий — `https://agents.firmspace.de`).
2. Перейдите в **Лицензии**.
3. Найдите лицензию по `licenseId` или `instanceId`.
4. Нажмите **Отозвать** и укажите причину.

`[СКРИНШОТ]` Список лицензий и диалог отзыва в кабинете владельца.

**Как это доходит до Runtime.** Примерно через минуту после старта и затем каждые **6 часов** API спрашивает
публичный статус лицензии в кабинете (`LICENSE_CHECK_URL`), передавая только `licenseId` и
`instanceId`. Ответ `active:false` переводит установку в `LICENSE_REVOKED` без grace-периода.
Последний ответ кэшируется в `/app/data/license-state.json`, поэтому перезапуск во время
недоступности кабинета не «забывает» отзыв. Если кабинет недоступен, сохраняется последнее
известное состояние — сбой кабинета не выключает клиента.

**Что проверить до обещания клиенту.** В текущей сборке compose-файл не передаёт
`LICENSE_CHECK_URL` в контейнер по умолчанию. Проверьте:

```bash
docker exec firmspace-robots-runtime-api printenv LICENSE_CHECK_URL
```

Если команда пуста, дистанционная проверка выключена, и отзыв в кабинете не действует на
эту установку. Чтобы включить, передайте переменную в окружение сервиса `api`:

```dotenv
LICENSE_CHECK_URL=https://agents.firmspace.de/api/license/status
```

и перезапустите `api`. Точный URL статуса выдаёт Firmspace вместе с кабинетом; приведён
пример из кода. Без этого остаются только замена ключа и истечение срока (если он задан).

### 5.6 Откуда владелец берёт ключ

1. В кабинете владельца: **Лицензии → Выдать**.
2. Указать `customer`, `instanceId` (из `runtime-data/instance-id`), план и лимиты.
3. Для бессрочной лицензии `expiresAt` не задаётся. Если срок согласован, ключ с `expiresAt` выпускает Firmspace (в стандартной форме кабинета поля срока может не быть).
4. Скопировать `LICENSE_KEY` и передать вместе с `LICENSE_SECRET` по защищённому каналу.
5. Клиент устанавливает ключ через `/runtime-admin` (§6) или через `runtime.env` (§5.3).

`[СКРИНШОТ]` Форма выдачи лицензии: customer, instanceId, plan, лимиты, features.

---

## 6. Веб-интерфейс администратора

### 6.1 Что это

Адрес со слешем в конце важен: страница подключает `styles.css` и `app.js` относительно
каталога `/runtime-admin/`. Без слеша контроллер страницу отдаст, но стили и скрипт могут
не загрузиться.

В поставке есть страница администратора: `http://<host>:8080/runtime-admin/`. Она входит в
runtime-профиль и работает без браузерной сессии: каждая команда на странице отправляет
заголовок `X-Runtime-Admin-Token` в `/api/v1/runtime/**`, где и происходит авторизация.
Страница не содержит данных до входа; токен хранится только в `sessionStorage` вкладки и
не пишется в `localStorage`.

Что на странице:

- **Статус установки**: версия, `instanceId`, health, признак настройки, tenant, workspace,
  e-mail владельца, число активных токенов.
- **Лицензия**: вставить ключ, установить, увидеть состояние, срок, предупреждения.
- **Первичная настройка**: создать предприятие, workspace, владельца и первый токен.
- **API-токены**: выпустить и отозвать, скопировать значение один раз.
- **Подключить мост**: готовый блок `forward.copilot` и Docker-команды.

### 6.2 Пошагово

1. Откройте `http://<host>:8080/runtime-admin/`.
2. В поле **Admin-токен** вставьте `BC_RUNTIME_ADMIN_TOKEN` из `runtime.env` и нажмите
   **Проверить**. Появится **Статус установки**.
3. Блок **Лицензия**: вставьте ключ в поле **Лицензионный ключ**, нажмите **Установить**.
   Должно появиться **Лицензия установлена**; ниже — состояние, файл `/app/data/license.key`
   и примечания для администратора.
4. Если установка ещё не настроена, блок **Первичная настройка**: заполните **Название
   предприятия**, **E-mail владельца**, при необходимости **Имя владельца** и нажмите
   **Создать установку**. Токен показывается один раз — скопируйте его.
5. Блок **API-токены**: введите **Имя токена**, отметьте scopes (`documents:write` для моста,
   `agents.run` для запусков, `settings:write` только доверенным интеграциям), нажмите
   **Создать токен** и сразу скопируйте значение.
6. Блок **Подключить мост** показывает `forward.copilot` и команды; замените тег образа на
   свою версию.

`[СКРИНШОТ]` Четыре экрана: вход по admin-токену, статус, установка лицензии, первичная
настройка и токены.

### 6.3 То же самое через API

Админ-API принимает заголовок `X-Runtime-Admin-Token`, а не Bearer-токен.

```bash
cd /path/to/burokratie-copilot
set -a; . /opt/firmspace-runtime/runtime.env; set +a
ADMIN=(-H "X-Runtime-Admin-Token: $BC_RUNTIME_ADMIN_TOKEN")
BASE="http://127.0.0.1:${BC_API_PORT:-8080}"

# 1. Состояние: bootstrapped, version, tenant, license, adminTokenConfigured
curl -fsS "${ADMIN[@]}" "$BASE/api/v1/runtime/status" | jq

# 2. Установить лицензию без перезапуска
curl -sS -X POST "${ADMIN[@]}" -H 'Content-Type: application/json' \
  "$BASE/api/v1/runtime/license" \
  -d '{"licenseKey":"<ключ из кабинета>"}' | jq

# 3. Список токенов без секретов
curl -fsS "${ADMIN[@]}" "$BASE/api/v1/runtime/tokens" | jq

# 4. Новый токен
curl -sS -X POST "${ADMIN[@]}" -H 'Content-Type: application/json' \
  "$BASE/api/v1/runtime/tokens" \
  -d '{"name":"bridge-office-1","scopes":["agents.view","agents.run","documents:write"]}' | jq

# 5. Отзыв токена
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "${ADMIN[@]}" \
  "$BASE/api/v1/runtime/tokens/<TOKEN_ID>/revoke"
```

Ожидаемые ответы:

- `GET /status` → `bootstrapped`, `version`, `tenantId`, `workspaceId`, `license.state`,
  `adminTokenConfigured`.
- `POST /license` → `installed:true`, `keyFile`, `state`, `warnings`, `license`.
- `POST /tokens` → HTTP `201`, поля `id`, `token`, `prefix`, `scopes`,
  `rateLimitPerMinute`.
- `POST /tokens/{id}/revoke` → HTTP `204`.

Если `BC_RUNTIME_ADMIN_TOKEN` пуст, админ-эндпоинты отвечают `404` (поверхность скрыта).
Если токен неверный — `401`.

---

## 7. Первый вход, предприятие и токены

### 7.1 Что уже создал установщик

При первой установке bootstrap создаёт в одной транзакции:

- tenant с указанным названием;
- workspace по умолчанию;
- owner без пароля (идентичность для аудита и владения токенами, не логин);
- первый API-токен со scopes `agents.view`, `agents.run`, `documents:write`;
- лимит `120` запросов в минуту для этого токена.

Повторный `POST /api/v1/runtime/bootstrap` вернёт `409`: установка уже
забутстраплена. Новый токен выпускайте через `/api/v1/runtime/tokens`.

### 7.2 Если нужно создать предприятие вручную

```bash
curl -sS -X POST "${ADMIN[@]}" -H 'Content-Type: application/json' \
  "$BASE/api/v1/runtime/bootstrap" \
  -d '{
        "tenantName": "Muster GmbH",
        "ownerEmail": "admin@example.de",
        "ownerName": "Admin Muster"
      }' | jq
```

Ответ `201` содержит `tenantId`, `workspaceId`, `ownerUserId`, `tokenId`, `token`,
`prefix`, `scopes`, `rateLimitPerMinute`, `apiBase`. Поле `token` существует только в
этом ответе.

### 7.3 Первый API-токен вручную

```bash
curl -sS -X POST "${ADMIN[@]}" -H 'Content-Type: application/json' \
  "$BASE/api/v1/runtime/tokens" \
  -d '{"name":"ingest-scanner","scopes":["documents:write"]}' | jq
```

Проверка токеном:

```bash
export TOKEN='<токен из ответа>'
curl -fsS "$BASE/api/v1/agents" -H "Authorization: Bearer $TOKEN" | jq '.[0]'
```

### 7.4 Scopes

| Scope | Что открывает |
|---|---|
| `documents:write` | `POST /api/v1/documents:ingest`, а также создание/проверка документов |
| `documents:read` | чтение документов (`GET /api/v1/documents...`) |
| `agents.view` | каталог агентов, журналы запусков, чтение workflow и созвездий |
| `agents.run` | запуск агентов, workflow, инстанцирование созвездий, `resume` |
| `exports:read` | `GET /api/v1/exports/elster` |
| `settings:read` | чтение настроек |
| `settings:write` | изменение правил, дат и retention |

Правила:

- Один токен — одна интеграция. Не выдавайте `settings:write` мосту или сканеру.
- Права нельзя изменить после создания: отзовите и создайте новый.
- Максимум **20 активных токенов** на workspace.
- Токен показывается один раз; в базе хранится только SHA-256. Потеряли — отозвать и
  выпустить новый.
- Тенант берётся из токена; подменить его заголовком нельзя.

---

## 8. Проверка «всё работает»

```bash
export BASE_URL="http://192.168.1.20:8080"
export TOKEN='<ваш токен>'

# лицензия
curl -fsS "$BASE_URL/api/v1/license" | jq '{state, perpetual, plan, expiresAt, revokedAt}'

# каталог агентов
curl -fsS "$BASE_URL/api/v1/agents" -H "Authorization: Bearer $TOKEN" | jq '.[0]'

# приём файла
curl -sS -X POST "$BASE_URL/api/v1/documents:ingest" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: smoke-$(date +%s)" \
  -H "X-Filename: rechnung.pdf" \
  -H "Content-Type: application/pdf" \
  --data-binary @rechnung.pdf | jq

# запуск агента
curl -sS -X POST "$BASE_URL/api/v1/agents/doc-detect/run" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"trigger":"manual","inputs":{"fileName":"rechnung.pdf"}}' | jq
```

Ожидаемо: лицензия `LICENSE_ACTIVE`, каталог — массив агентов, ingest — `201` с
`documentId`, запуск — `ok: true` или `ok: false` с понятным `summary`. `ok: false` —
штатный ответ агента, а не HTTP-ошибка.

---

## 9. Подключение моста к Runtime

Есть два разных пути, их нельзя путать.

### 9.1 Мост как источник документов (forward)

Мост умеет после захвата файла отправить его в Copilot-совместимый ingest. Это работает и
с Runtime:

```jsonc
{
  "console": {
    "url": "https://agents.firmspace.de",
    "token": "${BRIDGE_TOKEN}"
  },
  "forward": {
    "copilot": {
      "enabled": true,
      "url": "http://192.168.1.20:8080",
      "token": "${RUNTIME_INGEST_TOKEN}",
      "timeoutMs": 30000
    }
  }
}
```

- `url` — базовый URL Runtime; мост сам добавляет `/api/v1/documents:ingest`.
- `token` — API-токен Runtime со scope `documents:write`.
- Отправка best-effort: сбой Runtime не теряет файл локально.
- Нужен `contentBase64` в событии: для `folder`/`email` включите `includeContent: true`.

### 9.2 Прямой вызов из вашего кода

Если мост не нужен, вызывайте ingest напрямую — контракт в
[EMBEDDED_API.md](EMBEDDED_API.md). Для Runtime базовый URL — адрес из §3.

> В runtime-профиле нет эндпоинтов консоли `/api/bridge/*`. Мост не может «заменить»
> кабинет роботов: ему всё равно нужен `console.url` для heartbeat и заданий. Прямая
> связка «мост ↔ Runtime» без кабинета в эту поставку не входит.

---

## 10. Обновление, бэкап, восстановление

### 10.1 Обновление

```bash
cd /path/to/burokratie-copilot
git pull                      # или распакуйте новый архив поставки
docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml build
docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml up -d
curl -fsS "http://127.0.0.1:8080/actuator/health/readiness"
```

Перед обновлением сделайте бэкап. Flyway работает forward-only: откат схемы — только
восстановлением из дампа. Лицензия и `instance-id` лежат вне образа и сохраняются.

### 10.2 Бэкап

```bash
cd /path/to/burokratie-copilot
set -a; . /opt/firmspace-runtime/runtime.env; set +a
COMPOSE=(docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml)

BACKUP=/srv/backups/firmspace-runtime/$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$BACKUP"

"${COMPOSE[@]}" exec -T postgres pg_dump -U "$BC_DB_USER" -d "$BC_DB_NAME" -Fc \
  > "$BACKUP/database.dump"

sudo tar czf "$BACKUP/runtime-data.tgz" \
  -C "$(dirname "$BC_DATA_DIR")" "$(basename "$BC_DATA_DIR")"

sudo install -m 600 /opt/firmspace-runtime/runtime.env "$BACKUP/runtime.env"
sha256sum "$BACKUP"/* > "$BACKUP/SHA256SUMS"
```

`runtime.env` содержит пароль БД, AES-ключ и admin-токен; ключ лицензии и её состояние
лежат в `runtime-data` (`license.key`, `license-state.json`). Шифруйте бэкап перед выносом
с сервера.

### 10.3 Восстановление

```bash
"${COMPOSE[@]}" stop api
"${COMPOSE[@]}" exec -T postgres pg_restore -U "$BC_DB_USER" -d "$BC_DB_NAME" \
  --clean --if-exists --no-owner < database.dump
sudo tar xzf runtime-data.tgz -C "$(dirname "$BC_DATA_DIR")"
"${COMPOSE[@]}" up -d api
curl -fsS "http://127.0.0.1:8080/actuator/health/readiness"
curl -fsS "http://127.0.0.1:8080/api/v1/license" | jq '.state'
```

Подробнее — [MANAGEMENT.md](MANAGEMENT.md) и [../runbook.md](../../runbook.md).

---

## 11. Если не работает

| Симптом | Вероятная причина | Что сделать |
|---|---|---|
| Установщик: `docker daemon is not reachable` | Docker не запущен или нет прав | `sudo systemctl start docker`; проверить группу `docker` |
| Установщик: `runtime.env already exists` | установка уже была | для нового токена — `scripts/bootstrap-runtime.sh`; `--force` только на чистой установке |
| `API did not become healthy in 240s` | API не стартовал, БД недоступна | `docker compose ... logs --tail=120 api`; `ps`; проверить пароль БД |
| `/actuator/health/readiness` = 503 | миграции/БД/диск | логи api, `df -h`, `docker compose ... ps` |
| агенты отвечают `402 LICENSE_MISSING` | нет ключа | §5.3, проверить `reason` в `/api/v1/license` |
| агенты отвечают `401` | токен неверный/отозван | выпустить новый токен, проверить `Authorization: Bearer` |
| агенты отвечают `403` | у токена нет scope | выдать `agents.view`/`agents.run` |
| агенты отвечают `402 LICENSE_REVOKED` | кабинет отозвал лицензию | установить новый ключ; проверить `LICENSE_CHECK_URL` и `revokedAt` |
| страница `/runtime-admin` отвечает `404` | API запущен не в runtime-профиле | проверить `SPRING_PROFILES_ACTIVE=runtime` и логи api |
| админ-API отвечает `404` | `BC_RUNTIME_ADMIN_TOKEN` пуст | проверить env и перезапустить api |
| админ-API отвечает `401` | неверный admin-токен | взять значение из `runtime.env` целиком |
| порт занят | другой процесс на 8080 | `--api-port 8081` или освободить порт |
| API не пишет в `/app/data` | права на каталог | `sudo chown -R 10001:10001 /opt/firmspace-runtime/runtime-data` |

---

## 12. Чек-лист приёмки

- [ ] Docker 24+ и Compose v2 проверены командами.
- [ ] `--public-url` совпадает с адресом, по которому клиенты реально ходят.
- [ ] TLS-прокси стоит перед API; порт 8080 не опубликован в интернет.
- [ ] `runtime.env` имеет права `600` и лежит в бэкапе.
- [ ] `instance-id` сохранён; лицензия привязана к нему.
- [ ] `http://<host>:8080/runtime-admin/` открывается; admin-токен принимается.
- [ ] `GET /api/v1/license` → `LICENSE_ACTIVE`; для бессрочной `perpetual: true`,
      `expiresAt: null`.
- [ ] Ключ установлен через страницу или env; `/app/data/license.key` с правами `600`.
- [ ] Решение по `LICENSE_CHECK_URL` зафиксировано: проверка отзыва включена или явно
      выключена.
- [ ] Bootstrap создал ровно один tenant; `bootstrapped: true`.
- [ ] Первый API-токен сохранён в секрет-сторе, не в тикете.
- [ ] `POST /documents:ingest` и `POST /agents/doc-detect/run` проверены.
- [ ] Бэкап БД и `runtime-data` снят, восстановление проверено.
- [ ] Мониторинг health/лицензии/диска настроен.
- [ ] Ответственный за сервер и порядок обновления зафиксированы.
