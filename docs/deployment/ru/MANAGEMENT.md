# Управление поставками

**Языки:** [Русский](MANAGEMENT.md) · [Deutsch](../de/MANAGEMENT.md) · [English](../en/MANAGEMENT.md)

**Для кого:** владелец контракта, IT-администратор клиента, инженер внедрения и поддержка.
Документ описывает **общие** операции для всех четырёх моделей: лицензии, токены,
обновления, бэкапы, журналы и инциденты. Детали установки — в
[BRIDGE_ONLY.md](BRIDGE_ONLY.md), [RUNTIME.md](RUNTIME.md),
[EMBEDDED_API.md](EMBEDDED_API.md), [COPILOT_SAAS.md](COPILOT_SAAS.md).

**Принцип:** у каждого секрета, журнала и обязательства есть **владелец**. Если владельца
нет, это не операция, а надежда.

---

## 1. Что это и кому подходит

Этот документ нужен тем, кто отвечает за работающую поставку после приёмки: владельцу
контракта, администратору клиента и поддержке. Он объясняет, кто за что отвечает, как
выдать и отозвать лицензию, как обновлять каждый компонент, где лежат бэкапы и журналы и
что делать в первые минуты инцидента. Здесь нет инструкций по установке — они в документах
моделей. Все команды проверены по скриптам и коду поставки; места, требующие живого
интерфейса, помечены `[СКРИНШОТ]`.

---

## 2. Что понадобится

### 2.1 Доступы и артефакты

| Что | Зачем | Где хранится |
|---|---|---|
| `infra/runtime.env` или `<dir>/runtime.env` | пароль БД, AES-ключ, admin-токен, лицензия | сейф/менеджер паролей, копия в бэкапе |
| `bridge.config.json` + `.env` | токен и настройки моста | машина моста, файл `600` |
| `LICENSE_KEY`, `LICENSE_SECRET` | лицензия Runtime | сейф |
| `instance-id` | привязка лицензии | `runtime-data/instance-id` |
| API-токены | интеграции | секрет-стор клиента |
| Доступ к кабинету владельца | лицензии, предприятия, мосты | у владельца |
| Контакты поддержки и SLA | эскалация | договор |

### 2.2 Инструменты

| Инструмент | Версия | Проверка |
|---|---|---|
| `docker` | 24+ | `docker version` |
| `docker compose` | v2 | `docker compose version` |
| `curl` | 7.76+ | `curl --version` |
| `jq` | любая | `jq --version` |
| `pg_dump`/`pg_restore` | 17 (в контейнере) | `docker compose ... exec postgres pg_dump --version` |
| `openssl` | любая | `openssl version` |

---

## 3. Кто за что отвечает

| Работа | Владелец (Firmspace) | Клиент | Инженер внедрения |
|---|---|---|---|
| Выпуск и отзыв лицензии | **R** | C | I |
| Хранение `LICENSE_SECRET` и `runtime.env` | C | **R** | C |
| Установка Runtime | C | A | **R** |
| Сервер, диск, сеть, ОС | I | **R** | C |
| Обновление Runtime | C | A | **R** |
| Бэкапы и проверка восстановления | I | **R** | C |
| Выпуск API-токенов | I | **R** | C |
| Мост: установка и обновление | C | A | **R** |
| Мост: папки, почта, БД | I | **R** | C |
| Мониторинг и первая линия | I | **R** | C |
| Инцидент безопасности | **R** | **R** | C |
| Облачный кабинет Copilot | **R** | C | I |

`R` — делает, `A` — утверждает, `C` — консультирует, `I` — информируется. Для одной
работы может быть два `R`, если обязанности разделены по контуру (например, инцидент
безопасности).

---

## 4. Лицензии

### 4.1 Модель лицензии

Лицензия Runtime — HMAC-подписанный ключ, привязанный к `instance-id`. Подпись проверяется
офлайн; дополнительно установка может спрашивать кабинет об отзыве (`LICENSE_CHECK_URL`,
раз в 6 часов). Коммерчески лицензия **бессрочная по умолчанию**: ежегодного продления нет.
Технически это ключ **без поля `expiresAt`**: он всегда `LICENSE_ACTIVE`, без grace-периода.
Срочная лицензия содержит `expiresAt` и проходит через grace/read-only.

`LICENSE_SECRET` — общий секрет подписи. Он должен совпадать у владельца и у клиента,
иначе ключ не проверится. Секрет не передаётся в мост, не вводится на странице
`/runtime-admin` и не хранится в git.

### 4.2 Выдать лицензию

1. Откройте кабинет владельца (для лицензий и мостов —
   `https://agents.firmspace.de`).
2. Перейдите в **Лицензии**.
3. Нажмите **Выдать**.
4. Заполните:
   - `customer` — юридическое название клиента;
   - `instanceId` — значение из `runtime-data/instance-id` (клиент присылает его при
     установке);
   - `plan` — например, `ENTERPRISE`;
   - `maxAgents`, `maxRunsPerMonth` — лимиты;
   - `features` — при необходимости.
5. Скопируйте `LICENSE_KEY` и передайте вместе с `LICENSE_SECRET` по защищённому каналу.

`[СКРИНШОТ]` Форма выдачи лицензии: customer, instanceId, plan, лимиты, features.
Стандартная лицензия бессрочная; поля срока в форме может не быть. Если срок согласован, Firmspace выпускает ключ с `expiresAt`.

Клиент устанавливает ключ через страницу `/runtime-admin` (**Лицензия → Установить**) или
через `runtime.env` ([RUNTIME.md](RUNTIME.md) §5.3). Веб-путь не требует перезапуска и
пишет `/app/data/license.key`. Проверка:

```bash
curl -fsS "http://127.0.0.1:8080/api/v1/license" \
  | jq '{state, perpetual, customer, expiresAt, daysRemaining, revokedAt}'
```

Ожидаемо `state: "LICENSE_ACTIVE"`. Для бессрочной — `perpetual: true`, `expiresAt: null`.

### 4.3 Отозвать лицензию

1. Кабинет владельца → **Лицензии**.
2. Найдите запись по `licenseId` или `instanceId`.
3. Нажмите **Отозвать**, укажите причину и подтвердите.

`[СКРИНШОТ]` Список лицензий и диалог отзыва.

**Что происходит технически.** Установка примерно через минуту после старта и затем каждые 6 часов запрашивает
публичный статус лицензии по `LICENSE_CHECK_URL` и при ответе `active:false` переходит в
`LICENSE_REVOKED`: агенты, workflow, exports и settings отвечают `402`, льготного периода
нет. Приём документов (ingest) остаётся доступным. Последний ответ кэшируется в
`/app/data/license-state.json` и переживает перезапуск; недоступность кабинета сохраняет
последнее известное состояние.

**Ограничение текущей сборки.** Compose-файл не передаёт `LICENSE_CHECK_URL` в контейнер
по умолчанию, поэтому дистанционная проверка может быть выключена. Проверьте:

```bash
docker exec firmspace-robots-runtime-api printenv LICENSE_CHECK_URL
```

Пустой вывод — отзыв из кабинета не доходит. Передайте переменную в окружение сервиса
`api` (например, `https://agents.firmspace.de/api/license/status`), перезапустите `api` и
проверьте `revocationCheckedAt` в `/api/v1/license`. Не обещайте мгновенный отзыв: задержка
— до 6 часов.

### 4.4 Проверка срока и состояний

| Состояние | Значение | Действие |
|---|---|---|
| `LICENSE_ACTIVE` | ключ действителен; бессрочный — всегда | плановое обслуживание |
| `LICENSE_GRACE` | срок истёк, идёт 14 дней grace (только с `expiresAt`) | заменить ключ |
| `LICENSE_READ_ONLY` | grace закончился | заменить ключ; запись и запуски заблокированы |
| `LICENSE_REVOKED` | кабинет отозвал лицензию | новый ключ; льготного периода нет |
| `LICENSE_MISSING` | ключа нет/неверен | проверить `reason`, установить ключ |

Заголовки ответов агентского API: `X-License-Status`, `X-License-Plan`,
`X-License-Expires-At`, `X-License-Warning`. Их удобно снимать мониторингом.

### 4.5 Инвентарь лицензий

Ведите таблицу: `licenseId`, `customer`, `instanceId`, `plan`, `expiresAt` (`—` для
бессрочной), дата выдачи, кто выдал, дата отзыва, причина. Без `instanceId` нельзя доказать, что ключ выдан именно
этой установке.

---


![Где взять токен](../../images/token-locations.svg)

*Схема 3. Три разных токена: Copilot API, мост, Runtime admin. Они не взаимозаменяемы.*

## 5. Токены и секреты

### 5.1 Инвентарь

| Секрет | Модель | Где | Кто выдаёт | Ротация | Отзыв |
|---|---|---|---|---|---|
| `BRIDGE_TOKEN` | мост | `.env` 600 | кабинет роботов | при утечке/смене | кабинет → мост → Отозвать |
| Copilot API-токен | SaaS/Embedded | секрет-стор | кабинет → API-токены | 90 дней или при утечке | `POST /api/v1/api-tokens/{id}/revoke` |
| Runtime API-токен | Runtime | секрет-стор | админ-API/установщик | 90 дней или при утечке | `POST /api/v1/runtime/tokens/{id}/revoke` |
| `BC_RUNTIME_ADMIN_TOKEN` | Runtime | `runtime.env` 600 | установщик | при смене оператора/утечке | заменить env + restart |
| `LICENSE_KEY`/`LICENSE_SECRET` | Runtime | сейф + env | владелец | при перевыпуске | замена ключа |
| `LICENSE_CHECK_URL` | Runtime | `runtime.env`/compose | Firmspace | при смене кабинета | изменить env + restart |
| `/app/data/license.key`, `/app/data/license-state.json` | Runtime | каталог данных 600 | `/runtime-admin` | — | удалить + новый ключ |
| `BC_ENCRYPTION_KEY` | Runtime | env/сейф | клиент/мы | **не горячая** | перешифрование |
| Пароль БД | Runtime | `runtime.env` | установщик | по политике | пересоздание |

### 5.2 Правила

- Один токен — одна интеграция или один мост. Общий токен нельзя отозвать точечно.
- Токен показывается один раз; хранится только SHA-256.
- Не храните токены в `bridge.config.json`, git, чатах, тикетах и скриншотах.
- Ротация без простоя: новый → задеплоить → проверить `lastUsedAt` → отозвать старый.
- При утечке: отозвать в течение 15 минут, проверить журналы, уведомить по §9.

### 5.3 Выпуск и отзыв

**Copilot API-токен:** кабинет → Настройки аккаунта → API-токены → Создать токен.
Отзыв — кнопка **Отозвать** в списке.

**Runtime API-токен:**

```bash
set -a; . /opt/firmspace-runtime/runtime.env; set +a
ADMIN=(-H "X-Runtime-Admin-Token: $BC_RUNTIME_ADMIN_TOKEN")
BASE="http://127.0.0.1:8080"

curl -fsS "${ADMIN[@]}" "$BASE/api/v1/runtime/tokens" | jq
curl -sS -X POST "${ADMIN[@]}" -H 'Content-Type: application/json' \
  "$BASE/api/v1/runtime/tokens" \
  -d '{"name":"integration-x","scopes":["documents:write"]}' | jq
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "${ADMIN[@]}" \
  "$BASE/api/v1/runtime/tokens/<TOKEN_ID>/revoke"
```

**Токен моста:** кабинет роботов → Предприятия → предприятие → Подключения → мост →
Отозвать. После отзыва мост получает `401`; создайте новый и обновите `.env`.

### 5.4 Runtime admin token

- Генерируется установщиком (`openssl rand -hex 32`) и записывается в `runtime.env`.
- **Не печатается** в терминал. Посмотреть:

  ```bash
  sudo grep '^BC_RUNTIME_ADMIN_TOKEN=' /opt/firmspace-runtime/runtime.env | cut -d= -f2-
  ```

- Передаётся только в заголовке `X-Runtime-Admin-Token`.
- Если значение пустое, админ-эндпоинты отвечают `404` (поверхность скрыта).
- Смена: `openssl rand -hex 32` → заменить в `runtime.env` → перезапустить `api` →
  проверить `GET /api/v1/runtime/status`. Старый токен сразу недействителен.
- Не передавайте его в мост или клиентское ПО: это операторский ключ.

---

## 6. Обновления

### 6.1 Copilot SaaS

Обновляется Firmspace. Клиенту ничего делать не нужно; изменения приходят при обновлении
облака. Если интерфейс изменился, проверьте, что сценарии из
[COPILOT_SAAS.md](COPILOT_SAAS.md) всё ещё выполняются, и обновите внутренние инструкции.

### 6.2 Robots Runtime

```bash
cd /path/to/burokratie-copilot
git pull                          # или распакуйте новый архив
docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml build
docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml up -d
curl -fsS "http://127.0.0.1:8080/actuator/health/readiness"
curl -fsS "http://127.0.0.1:8080/api/v1/license" | jq '.state'
```

Правила:

- Перед обновлением — бэкап БД и `runtime-data`.
- Flyway forward-only; откат схемы — только восстановлением из дампа.
- `LICENSE_KEY` и `instance-id` лежат вне образа и сохраняются.
- `BC_ENCRYPTION_KEY` не меняйте вместе с обновлением.
- Окно: API недоступен на время рестарта (секунды–минуты). Для HA нужен второй инстанс,
  это не входит в базовую поставку.
- Зафиксируйте в тикете образ/дайджест, git revision, дату и оператора.

Откат:

```bash
BC_API_IMAGE='firmspace/robots-runtime-api:<previous>' \
  docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml up -d --force-recreate api
```

Если миграция несовместима — восстановить БД из бэкапа, затем запустить прежний образ.

### 6.3 Firmspace Bridge

```bash
docker load -i firmspace-bridge-<новая-версия>-<архитектура>.tar.gz
docker stop firmspace-bridge && docker rm firmspace-bridge
docker run -d --name firmspace-bridge --restart unless-stopped \
  -v /opt/firmspace-bridge/config:/data \
  -v firmspace-bridge-state:/data/.bridge \
  -v /srv/legacy/incoming:/data/data/incoming \
  --env-file /opt/firmspace-bridge/config/.env \
  firmspace-bridge:<новая-версия>
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --details
```

Перед обновлением прочитайте примечания к версии: формат конфига может измениться. Том
`.bridge` не удаляйте — там очередь и журнал.

---

## 7. Бэкапы

### 7.1 Что и как часто

| Что | Почему | Частота | Хранение |
|---|---|---|---|
| PostgreSQL Runtime | тенант, документы, запуски, аудит | ежедневно + перед обновлением | 30 дней минимум, по договору |
| `runtime-data` (blobs, instance-id) | файлы и привязка лицензии | ежедневно | как БД |
| `runtime.env` | пароль БД, AES-ключ, admin-токен, лицензия | при изменении | сейф, отдельно от БД |
| Конфиг моста и `.env` | восстановление моста | при изменении | секрет-стор клиента |
| TLS-сертификаты и конфиг прокси | HTTPS | при изменении | сейф |

### 7.2 Команды Runtime

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

Проверка дампа без восстановления:

```bash
docker run --rm -v "$BACKUP:/backup:ro" postgres:17 \
  pg_restore --list /backup/database.dump | head
```

### 7.3 Восстановление

```bash
"${COMPOSE[@]}" stop api
"${COMPOSE[@]}" exec -T postgres pg_restore -U "$BC_DB_USER" -d "$BC_DB_NAME" \
  --clean --if-exists --no-owner < "$BACKUP/database.dump"
sudo tar xzf "$BACKUP/runtime-data.tgz" -C "$(dirname "$BC_DATA_DIR")"
"${COMPOSE[@]}" up -d api
curl -fsS "http://127.0.0.1:8080/actuator/health/readiness"
curl -fsS "http://127.0.0.1:8080/api/v1/license" | jq '.state'
```

Раз в квартал проводите **учебное восстановление** на отдельном стенде. Бэкап, который
никогда не восстанавливали, — это надежда, а не бэкап.

### 7.4 SaaS

Бэкапы делает Firmspace; клиент отвечает за выгрузку своих данных перед расторжением.
Экспорт документов и ELSTER — через UI/API, не через SQL. Срок хранения после окончания
договора — по договору и настройке retention.

---

## 8. Журналы и мониторинг

### 8.1 Что смотреть

| Источник | Что | Команда/место |
|---|---|---|
| Health Runtime | `UP`/`DOWN` | `curl -fsS .../actuator/health/readiness` |
| Лицензия | состояние, срок | `curl -fsS .../api/v1/license` |
| Заголовки агентов | `X-License-Status` | любой ответ `/api/v1/agents` |
| Логи API | ошибки, старт, миграции | `docker compose ... logs -f api` |
| Логи PostgreSQL | ошибки, диск | `docker compose ... logs postgres` |
| Метрики | `metrics`, `prometheus` (если включено) | `/actuator/metrics` |
| Диск | свободное место | `df -h`, алерт < 20 % |
| Мост | heartbeat, очередь | кабинет + `.bridge/journal` |
| Аудит кабинета | входы, изменения | UI аудита |
| SaaS | доступность | внешний мониторинг `https://...` |

### 8.2 Алерты (минимум)

- `health/readiness` не `UP` дольше 2 минут;
- `/api/v1/license` не `LICENSE_ACTIVE` (кроме согласованного grace) или `LICENSE_REVOKED`;
- диск БД < 20 %;
- бэкап не создан за последние 24 часа;
- мост не выходит на связь > 10 минут;
- всплеск `401/403/429` на API.

---

## 9. Инциденты

### 9.1 Первые 15 минут

1. **Зафиксируйте** время, симптом, затронутые компоненты.
2. **Не перезапускайте** всё подряд: сначала снимите `docker logs`, `ps`, `df -h` и
   последние 200 строк логов.
3. **Ограничьте ущерб:** отзовите подозрительный токен, закройте лишние порты, при
   утечке — замените секрет.
4. **Сообщите** владельцу и поддержке по контактам из договора.
5. **Запишите** `correlationId` из ответа API и `documentId`/`runId`, если проблема в
   конкретном объекте.

### 9.2 Типовые сценарии

| Симптом | Что проверить | Действие |
|---|---|---|
| API не отвечает | `docker compose ps`, `logs api`, `df -h` | поднять контейнер, освободить диск, восстановить из бэкапа |
| Все агенты отвечают `402` | `/api/v1/license`, `reason` | установить/заменить ключ; при `LICENSE_REVOKED` — новый ключ |
| Токен перестал работать | `401` vs `403` | `401` — отозван/просрочен; `403` — нет scope |
| Мост не выходит на связь | `bridge doctor`, TLS, DNS | см. [BRIDGE_ONLY.md](BRIDGE_ONLY.md) §7 |
| Документы не появляются | очередь, ingest, OCR | проверить `documentId`, логи, статус обработки |
| Подозрение на утечку токена | журналы, `lastUsedAt` | отозвать, выпустить новый, проверить доступы |

### 9.3 Утечка данных

1. Немедленно отозвать все токены и сменить `BC_RUNTIME_ADMIN_TOKEN`.
2. Сохранить логи и доказательства, не «чистить» журналы.
3. Уведомить DPO/владельца; сроки и порядок — в
   [../compliance/breach-plan.md](../../compliance/breach-plan.md).
4. Проверить, какие документы/тенанты затронуты.
5. После устранения — разбор и запись в журнал инцидентов.

### 9.4 Контакты

Контакты поддержки, часы работы, SLA и порядок эскалации **не заданы в коде** и
фиксируются в договоре. Заполните таблицу до приёмки:

| Уровень | Кто | Канал | Время реакции |
|---|---|---|---|
| Первая линия | `<из договора>` | `<телефон/почта>` | `<SLA>` |
| Инженер | `<из договора>` | `<канал>` | `<SLA>` |
| Безопасность/DPO | `<из договора>` | `<канал>` | `<SLA>` |

Не выдумывайте эти значения в документе для клиента.

---

## 10. Вывод из эксплуатации

1. Предупредить пользователей и согласовать дату.
2. Выгрузить документы и ELSTER-данные (UI/API).
3. Отозвать все API-токены и токены мостов.
4. Отключить мосты, удалить `.env` и конфиги.
5. Остановить Runtime; снять финальный бэкап; удалить данные по договору.
6. Подтвердить удаление письменно.

---

## 11. Чек-лист эксплуатации

- [ ] Инвентарь лицензий, токенов и секретов заполнен.
- [ ] `runtime.env`/`.env` с правами `600`, копия в сейфе.
- [ ] Бэкапы настроены и восстановление проверено.
- [ ] Мониторинг health/лицензии/диска/бэкапа включён; алерт на `LICENSE_REVOKED`.
- [ ] Решение по `LICENSE_CHECK_URL` зафиксировано (проверка отзыва включена или выключена).
- [ ] Ротация токенов запланирована.
- [ ] Контакты и SLA зафиксированы в договоре.
- [ ] Порядок обновления и отката проверен на стенде.
- [ ] Ответственные по RACI назначены.
