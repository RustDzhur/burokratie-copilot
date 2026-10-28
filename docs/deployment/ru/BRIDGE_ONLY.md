# Только мост (Bridge only)

**Языки:** [Русский](BRIDGE_ONLY.md) · [Deutsch](../de/BRIDGE_ONLY.md) · [English](../en/BRIDGE_ONLY.md)

**Модель:** на машине клиента работает один компонент — **Firmspace Bridge**. Он читает
файлы, письма, строки из базы или ответы HTTP из старого софта клиента и передаёт их в
кабинет Firmspace; результаты заданий может вернуть обратно. Движка агентов, OCR и
категорий в этой поставке нет. Мост не открывает входящие порты: он сам подключается к
кабинету по HTTPS и держит локальную очередь.

![Путь документа от папки до ELSTER](../../images/document-path.svg)

*Схема 2. Мост — шаг 2: он забирает документ у клиента и передаёт его дальше. Шаги 3–5
выполняются уже в Runtime или в Copilot.*

---

## 1. Что это и кому подходит

«Только мост» нужен там, где уже есть работающая система — 1С, DATEV, Lexware, SAP B1,
самописная программа, файловая шара, почтовый ящик или база Oracle/Firebird — и её нельзя
или невыгодно менять. Мост приводит данные к единой модели «документ / счёт / контакт /
задача» и доставляет их в кабинет Firmspace. Он не распознаёт scans, не ставит категории и
не запускает агентов: это транспорт и нормализация. Если после установки ожидается
«документ сам разложился по категориям», выбрана не та модель — смотрите
[Robots Runtime](RUNTIME.md) или [Copilot как сервис](COPILOT_SAAS.md).

---

## 2. Что понадобится

### 2.1 Машина и программа

| Что | Требование | Как проверить |
|---|---|---|
| ОС | Linux x86_64/arm64, macOS или Windows 10/11 с Docker | `uname -a` / `winver` |
| Docker Engine | 24 или новее | `docker version --format '{{.Server.Version}}'` |
| Архитектура | `amd64` или `arm64` | `uname -m` |
| RAM | 512 МБ + запас под файлы | `docker info` |
| Диск | 2 ГБ + место под очередь | `df -h` |
| Время | NTP, расхождение меньше 5 минут | `timedatectl status` |
| Сеть | **исходящий** HTTPS 443 к `agents.firmspace.de` | `curl -I https://agents.firmspace.de` |
| Папки | UID/GID 1000 внутри контейнера должен читать (и писать, если нужно) | `ls -ln <папка>` |

Для варианта без Docker дополнительно нужен **Node.js 22 LTS**: `node -v` должен
показать `v22.x`. Для RPA и UNC-шар нужен Windows-вариант, а не Linux-контейнер.

### 2.2 Что подготовить в кабинете

| Что | Кто даёт | Проверка |
|---|---|---|
| Токен моста и `bridge.config.json` | кабинет роботов | файл скачан, токен внутри |
| Имя моста | владелец | например «Бухгалтерия — офис» |
| Параметры адаптеров | IT клиента | пути, почтовый ящик, БД только на чтение |
| Тестовые файлы | владелец | 2–3 неконфиденциальных документа |

### 2.3 Где взять базовый URL и токен

**Базовый URL моста — это адрес кабинета роботов:** `https://agents.firmspace.de`.
Мост ходит туда исходящим HTTPS; отдельный «API моста» наружу не публикуется.

**Токен моста** выдаётся один раз при создании моста:

1. Откройте `https://agents.firmspace.de` и войдите под учётной записью владельца
   предприятия.
2. Перейдите в **Предприятия** и выберите нужное предприятие.
3. Откройте **Подключения**.
4. Нажмите **Добавить мост**.
5. Заполните имя и несекретные параметры адаптеров: папку, почту, БД, HTTP, печать,
   CSV/XML. Пароли и ключи в кабинет не вводите — они остаются на машине клиента.
6. Сохраните и **сразу скачайте** `bridge.config.json`. Токен находится внутри файла и
   показывается только один раз.
7. Если файл потерян: отзовите мост в кабинете, создайте новый и скачайте новый файл.
   Восстановить старый токен нельзя.

`[СКРИНШОТ]` Экран «Подключения → Добавить мост» и диалог скачивания
`bridge.config.json` — снять на той версии кабинета, которая установлена у клиента.

> **Не путайте токены.** Токен моста — не Copilot API-токен и не runtime admin token.
> Он работает только с кабинетом роботов и лежит в `.env` как `BRIDGE_TOKEN`.

---

## 3. Установка Docker-варианта

### 3.1 Где взять архив образа

Публичного реестра образов нет. Архив передаётся вместе с поставкой: ссылкой на файл,
через файлообмен или на носителе. Имя файла содержит версию и архитектуру, например
`firmspace-bridge-<версия>-amd64.tar.gz` или `firmspace-bridge-<версия>-arm64.tar.gz`.
Точное имя и контрольную сумму выдаёт Firmspace; не скачивайте образы из случайных
источников.

`[СКРИНШОТ]` Страница выдачи архива в кабинете или письмо с ссылкой — если используется
именно этот канал поставки.

Проверьте скачанный файл до загрузки:

```bash
ls -lh firmspace-bridge-*.tar.gz
shasum -a 256 firmspace-bridge-*.tar.gz      # macOS
sha256sum firmspace-bridge-*.tar.gz          # Linux
```

Сверьте сумму с той, что указана в акте или письме. Если суммы нет — запросите её.

### 3.2 Определите архитектуру

```bash
uname -m
# x86_64        -> amd64
# aarch64/arm64 -> arm64
```

### 3.3 Загрузите образ

```bash
docker load -i firmspace-bridge-<версия>-<архитектура>.tar.gz
```

Ожидаемый вывод: строка `Loaded image: firmspace-bridge:<версия>`.

Проверьте, что загрузилась именно нужная архитектура:

```bash
docker image inspect firmspace-bridge:0.1.0 \
  --format '{{.Os}}/{{.Architecture}}'
# linux/amd64  или  linux/arm64
```

Если архитектура не совпадает с `uname -m`, образ не запустится: возьмите архив для
своей платформы. Имя файла — не доказательство; доказательство — `docker image inspect`.

### 3.4 Разложите файлы

```bash
sudo mkdir -p /opt/firmspace-bridge/config /opt/firmspace-bridge/data
sudo chown -R 1000:1000 /opt/firmspace-bridge
cd /opt/firmspace-bridge
```

Скопируйте скачанный `bridge.config.json` в `config/`:

```bash
cp ~/Downloads/bridge.config.json ./config/bridge.config.download.json
ls -l ./config/
```

### 3.5 Импортируйте конфигурацию

Импорт приводит файл кабинета к формату моста, переносит токен в `.env` и оставляет в
конфиге только `${BRIDGE_TOKEN}`:

```bash
docker run --rm -it \
  -v "$PWD/config:/data" \
  firmspace-bridge:0.1.0 \
  import --from /data/bridge.config.download.json \
         --out /data/bridge.config.json \
         --force
```

Что должно появиться: сообщение об импорте и список того, что нужно уточнить вручную
(SQL-запрос, сценарий RPA, маппинг колонок). Проверьте файлы:

```bash
ls -l config/bridge.config.json config/.env
chmod 600 config/.env
grep -c 'BRIDGE_TOKEN' config/.env
```

В `config/.env` должен быть `BRIDGE_TOKEN=...`. В `config/bridge.config.json` токена быть
не должно — только `${BRIDGE_TOKEN}`. Если в конфиге остался настоящий токен, удалите его
и перезапустите импорт.

Дополнительные секреты (пароль почты, БД, API legacy-системы) импорт оставляет
плейсхолдерами. Заполните их в `config/.env`:

```bash
sudo editor config/.env
chmod 600 config/.env
```

### 3.6 Проверьте до запуска

```bash
docker run --rm -it \
  -v "$PWD/config:/data" \
  -v "$PWD/data:/data/data" \
  firmspace-bridge:0.1.0 \
  test --config /data/bridge.config.json --details
```

Ожидаемый результат: по каждому адаптеру `ok` и понятная причина, если `ok=false`.
Код возврата `0` — успех, `1` — ошибка.

Диагностика окружения:

```bash
docker run --rm -it \
  -v "$PWD/config:/data" \
  firmspace-bridge:0.1.0 \
  doctor --config /data/bridge.config.json
```

`doctor` проверяет Node, конфиг, токен, TLS, права, диск и адаптеры. Это первая команда
при любой проблеме.

### 3.7 Запустите мост

```bash
docker run -d --name firmspace-bridge \
  --restart unless-stopped \
  -v "$PWD/config:/data" \
  -v firmspace-bridge-state:/data/.bridge \
  -v /srv/legacy/incoming:/data/data/incoming \
  -v /srv/legacy/outgoing:/data/data/outgoing \
  --env-file config/.env \
  firmspace-bridge:0.1.0
```

Что означают параметры:

| Параметр | Зачем |
|---|---|
| `-d` | запуск в фоне |
| `--restart unless-stopped` | мост поднимается после перезагрузки |
| `-v "$PWD/config:/data"` | конфиг и `.env` внутри контейнера |
| `-v firmspace-bridge-state:/data/.bridge` | очередь, журнал и состояние переживают пересоздание контейнера |
| `-v /srv/legacy/incoming:/data/data/incoming` | папка клиента; путь **внутри** контейнера должен совпадать с конфигом |
| `--env-file config/.env` | `BRIDGE_TOKEN` и секреты адаптеров |

Проверьте, что контейнер жив и мост видит кабинет:

```bash
docker ps --filter name=firmspace-bridge
docker logs --tail=50 firmspace-bridge
docker exec firmspace-bridge \
  node dist/cli/index.js status --config /data/bridge.config.json --json
```

В `status` должны быть `lastHeartbeatOk=true`, растущий `uptime` и пустой или небольшой
`queue.total`. В кабинете мост появляется в списке подключений со статусом «онлайн» и
временем последней связи.

`[СКРИНШОТ]` Карточка моста в кабинете: статус, время heartbeat, очередь.

---

## 4. Куда класть файлы

| Что | На хосте | В контейнере |
|---|---|---|
| Конфиг | `/opt/firmspace-bridge/config/bridge.config.json` | `/data/bridge.config.json` |
| Секреты | `/opt/firmspace-bridge/config/.env` (600) | `/data/.env` |
| Очередь, журнал, lock | том `firmspace-bridge-state` | `/data/.bridge` |
| Папка «входящие» клиента | `/srv/legacy/incoming` | `/data/data/incoming` |
| Папка «исходящие» клиента | `/srv/legacy/outgoing` | `/data/data/outgoing` |
| Spool печати (если есть) | `/srv/legacy/spool` | `/data/data/spool` |

Правило: **в конфиге указываются пути контейнера**, а не хоста. Если в кабинете был
указан `/srv/legacy/incoming`, после импорта проверьте, что в `bridge.config.json` стоит
`data/incoming` или `/data/data/incoming`.

Права: контейнер работает под `node`, UID/GID 1000. Если папка принадлежит другому
пользователю, мост её не прочитает:

```bash
ls -ln /srv/legacy/incoming
sudo chown -R 1000:1000 /srv/legacy/incoming
```

---

## 5. Что появится в кабинете

1. Мост в списке подключений предприятия: имя, версия, платформа, последний heartbeat.
2. Адаптеры с состоянием `running` / `error` и временем последнего события.
3. Очередь и задания: `ingest`, `export`, `run-agent`, `test-connection`.
4. Журнал событий и результатов; ошибки видны в кабинете и в локальном журнале
   `.bridge/journal/YYYY-MM-DD.jsonl`.
5. Задания, которые мост забирает из кабинета, и результаты, которые он туда отправляет.

Если через 2–3 минуты моста в кабинете нет, начните с §7.

---

## 6. Обновление и резервная копия

### 6.1 Обновление

```bash
# 1. Скачайте архив новой версии и проверьте сумму
docker load -i firmspace-bridge-<новая-версия>-<архитектура>.tar.gz

# 2. Остановите и удалите старый контейнер, том состояния сохраните
docker stop firmspace-bridge
docker rm firmspace-bridge

# 3. Запустите новый образ с теми же томами
docker run -d --name firmspace-bridge \
  --restart unless-stopped \
  -v /opt/firmspace-bridge/config:/data \
  -v firmspace-bridge-state:/data/.bridge \
  -v /srv/legacy/incoming:/data/data/incoming \
  -v /srv/legacy/outgoing:/data/data/outgoing \
  --env-file /opt/firmspace-bridge/config/.env \
  firmspace-bridge:<новая-версия>
```

Перед обновлением прочитайте примечания к версии: формат конфига может измениться.
Если `bridge test` после обновления падает с `CONFIG_INVALID`, вернитесь на предыдущий
образ и сообщите в поддержку.

### 6.2 Резервная копия

```bash
cd /opt/firmspace-bridge
tar czf "bridge-backup-$(date +%F).tgz" \
  config/bridge.config.json config/.env
docker run --rm -v firmspace-bridge-state:/src -v "$PWD:/backup" \
  alpine tar czf /backup/bridge-state-$(date +%F).tgz -C /src .
```

`config/.env` содержит токен и пароли. Шифруйте копию перед выносом с машины, не кладите
её в общий диск и не отправляйте в тикет.

---

## 7. Если не работает: 10 типовых ошибок

Сначала всегда три команды:

```bash
docker ps -a --filter name=firmspace-bridge
docker logs --tail=100 firmspace-bridge
docker exec firmspace-bridge node dist/cli/index.js doctor --config /data/bridge.config.json
```

### 7.1 `docker load`: файл не найден или «invalid tar header»

**Причина:** скачан не тот файл, архив повреждён или это не `docker save`.
**Диагностика:**

```bash
file firmspace-bridge-*.tar.gz
shasum -a 256 firmspace-bridge-*.tar.gz
docker load -i firmspace-bridge-*.tar.gz
```

**Что делать:** скачать заново, сверить контрольную сумму. Не распаковывайте архив
вручную и не переименовывайте `.tar` в `.tar.gz`.

### 7.2 Контейнер сразу завершается (`Exited (1)`)

**Причина:** нет конфига, неверный путь или синтаксис JSON.
**Диагностика:**

```bash
docker logs firmspace-bridge
docker run --rm -it -v /opt/firmspace-bridge/config:/data \
  firmspace-bridge:0.1.0 test --config /data/bridge.config.json --details
```

**Что делать:** проверить, что `/data/bridge.config.json` существует и это валидный JSON.
Сообщение `CONFIG_INVALID` указывает путь к полю.

### 7.3 `CONFIG_INVALID`

**Причина:** опечатка в ключе, неверный тип, неизвестное поле (схема отвергает лишние
ключи), незаполненный плейсхолдер.
**Диагностика:**

```bash
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --details
```

**Что делать:** открыть `bridge.config.json`, исправить указанное поле. Не добавляйте
ключи «на всякий случай»: zod-схема их не примет. После правки — `docker restart
firmspace-bridge`.

### 7.4 `ALREADY_RUNNING`

**Причина:** на тот же `.bridge` уже смотрит другой процесс, либо остался lock от
убитого процесса.
**Диагностика:**

```bash
docker ps -a --filter name=firmspace-bridge
docker exec firmspace-bridge ls -l /data/.bridge/bridge.lock
ps aux | grep -i bridge
```

**Что делать:** остановить второй экземпляр. Если процесса нет, а lock остался — удалить
только lock:

```bash
docker exec firmspace-bridge rm -f /data/.bridge/bridge.lock
docker restart firmspace-bridge
```

Не удаляйте каталог `.bridge` целиком: там очередь и журнал.

### 7.5 `CONSOLE_AUTH_FAILED` (HTTP 401)

**Причина:** токен неверный, отозван или в `.env` лежит плейсхолдер.
**Диагностика:**

```bash
docker exec firmspace-bridge sh -c 'grep -c "^BRIDGE_TOKEN=." /data/.env'
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --details
docker exec firmspace-bridge node dist/cli/index.js \
  doctor --config /data/bridge.config.json
```

`grep -c` должен вернуть `1`. Если `doctor`/`test` показывают `CONSOLE_AUTH_FAILED`,
токен не принят.
**Что делать:** отозвать мост в кабинете, создать новый, скачать новый
`bridge.config.json`, повторить импорт, перезапустить контейнер. Старый токен не
восстанавливается.

### 7.6 `CONSOLE_UNREACHABLE`

**Причина:** DNS, прокси, firewall или неверный `console.url`.
**Диагностика:**

```bash
docker exec firmspace-bridge sh -c 'getent hosts agents.firmspace.de || nslookup agents.firmspace.de'
curl -vI https://agents.firmspace.de
docker exec firmspace-bridge node dist/cli/index.js \
  status --config /data/bridge.config.json --json
```

**Что делать:** проверить исходящий 443, настройки прокси (`HTTPS_PROXY`), запись
`console.url`. Мост работает с локальной очередью и при недоступном кабинете, но задания
не приходят, пока связь не восстановится.

### 7.7 `CONSOLE_TLS_ERROR`

**Причина:** корпоративный CA не доверен контейнеру, истёк сертификат или не совпадает
имя.
**Диагностика:**

```bash
openssl s_client -connect agents.firmspace.de:443 \
  -servername agents.firmspace.de -showcerts </dev/null
```

**Что делать:** положить корпоративный CA в файл и указать его в конфиге:

```jsonc
"console": {
  "url": "https://agents.firmspace.de",
  "tls": { "rejectUnauthorized": true, "caFile": "/data/ca.pem" }
}
```

Смонтировать файл в контейнер и перезапустить. Не отключайте `rejectUnauthorized` в
продуктивной среде.

### 7.8 `PATH_NOT_ALLOWED` или файлы не забираются

**Причина:** путь в job вышел за `allowedRoots`, путь хоста не смонтирован, нет прав
UID 1000, файл ещё меняется (`stabilityMs`).
**Диагностика:**

```bash
docker exec firmspace-bridge ls -ln /data/data/incoming
docker inspect firmspace-bridge --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}'
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --details
```

**Что делать:** добавить корень в `security.allowedRoots`, проверить, что путь в
конфиге — контейнерный, выдать UID 1000 чтение/запись:

```bash
sudo chown -R 1000:1000 /srv/legacy/incoming
```

### 7.9 `EMAIL_AUTH_FAILED` / `EMAIL_CONNECTION_FAILED`

**Причина:** обычный пароль вместо app-password, 2FA, неверный порт/TLS, OAuth2-токен
просрочен.
**Диагностика:**

```bash
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --adapter <id> --details
openssl s_client -connect imap.example.de:993 -servername imap.example.de </dev/null
```

**Что делать:** для 993 — `secure: true`; для 587 — `secure: false` и
`requireTls: true`; для Gmail — app-password или OAuth2. Проверить вход в веб-почту и
что IMAP включён.

### 7.10 `DB_CONNECT_FAILED` / `ODBC_DRIVER_MISSING`

**Причина:** нет сети до БД, неверный логин, не установлен драйвер, read-guard отверг
запрос.
**Диагностика:**

```bash
docker exec firmspace-bridge node -e '
const net = require("net");
const s = net.connect(5432, "db.example.de", () => { console.log("open"); s.end(); });
s.on("error", (e) => console.log("ERR", e.code));
'
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --adapter <id> --details
```

Ожидаемо `open`; `ERR ETIMEDOUT` или `ECONNREFUSED` — сеть/firewall. Для Oracle/MSSQL
проверьте `ODBC`/драйвер; для PostgreSQL/MySQL драйвер уже в образе.
**Что делать:** открыть порт БД для машины моста, использовать пользователя **только на
чтение**, убрать DML/DDL из запроса (read-guard), проверить `odbcinst -q -d` на хосте.

> Если ошибка не из этого списка, выполните `doctor` и `status --json`, сохраните
> `.bridge/journal` за последние сутки и передайте в поддержку. Не отправляйте `.env` и
> `bridge.config.json` с секретами.

---

## 8. Безопасность и ограничения

- Мост не открывает входящие порты. Все соединения — исходящие HTTPS 443.
- В кабинете хранится только SHA-256 токена моста; сам токен — в `.env` с правами 600.
- Пароли адаптеров остаются на машине клиента; в кабинете — только несекретные параметры.
- Read-only по умолчанию: фоновые записи запрещены, запись возможна только заданием и
  только при `security.allowJobWrites=true`.
- `allowedRoots` не даёт job'у выйти за разрешённые каталоги; симлинки не обходят allowlist.
- Гарантия доставки — **at-least-once**. Адаптеры, которые пишут наружу, должны быть
  идемпотентны.
- Мост не умеет OCR, категории, агентов, UI и ELSTER-выгрузку. Это не его работа.

---

## 9. Чек-лист приёмки

- [ ] Архитектура образа совпадает с `uname -m` (`docker image inspect`).
- [ ] `bridge.config.json` импортирован; в нём нет настоящего токена.
- [ ] `.env` существует, права `600`, `BRIDGE_TOKEN` заполнен.
- [ ] `bridge test --details` — все нужные адаптеры `ok`.
- [ ] `bridge doctor` — без критичных ошибок.
- [ ] Контейнер запущен с `--restart unless-stopped`.
- [ ] В кабинете мост «онлайн», heartbeat свежий.
- [ ] Тестовый файл прошёл путь: папка → событие → кабинет.
- [ ] Резервная копия конфига и `.env` снята и зашифрована.
- [ ] Ответственный за мост и порядок обновления зафиксированы.
