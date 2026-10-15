# Контрольный список релиза (HD-REL-01)

Каждый пункт ссылается на команду или файл проекта. Что здесь не выполнено, отмечается в записи релиза как невыполненное;
пункт не вычёркивается «по умолчанию». Подробности процедур — `docs/deployment.md` §6–§7 и `docs/runbook.md`.

## 1. До слияния в `main`

Автодеплой (`scripts/auto-deploy.sh`) берёт всё, что попало в `origin/main`, поэтому проверки делаются **до** push.

- [ ] Backend: `cd services/api && ./mvnw -B verify` — зелёный. Сборку и тесты запускать в каталоге вне iCloud (например,
      `~/bc-work`): в `~/Documents` синхронизация файлов подвешивает Maven.
- [ ] Frontend: `cd apps/web && pnpm typecheck && pnpm lint && pnpm test && pnpm build` — все четыре команды завершаются кодом 0.
- [ ] `bash infra/scripts/check-structure.sh --strict` — без ошибок.
- [ ] `bash scripts/check-doc-references.sh` — `OK` (каждое имя теста, названное в `docs/requirements-matrix.md` и `docs/final-report.md`, существует).
- [ ] `bash infra/scripts/check-no-env-committed.sh` — в репозитории нет `.env` с секретами.
- [ ] Новые миграции: только новые файлы `services/api/src/main/resources/db/migration/V<n>__*.sql`, применённые миграции не
      изменены; миграция аддитивна, иначе есть план expand/contract (`docs/deployment.md` §6.2).
- [ ] У каждой новой таблицы есть `ENABLE` + `FORCE ROW LEVEL SECURITY` и политика по `current_tenant_id()`, а у нового эндпоинта —
      тест на чужой tenant (ожидается 404).
- [ ] CI на коммите зелёный (`.github/workflows/ci.yml`: backend `./mvnw -B -ntp verify`, frontend typecheck/lint/test/build).
      В CI добавлены (задача HD-CI): блокирующий OWASP dependency-check (CVSS ≥ 7) и Trivy по образу api (HIGH/CRITICAL с
      исправлением); job `e2e` на Playwright уже был. Пока первый зелёный прогон не подтверждён, они считаются непроверенными.

## 2. Резервная копия перед миграцией

- [ ] `I_KNOW_THIS_IS_PRODUCTION=yes infra/scripts/backup.sh`
- [ ] `CONFIRM=yes infra/scripts/verify-restore.sh` — PASS на только что снятой копии. Автодеплой делает дамп БД сам перед
      обновлением, но проверка восстановления — отдельный шаг и вручную.

## 3. Выкладка

- [ ] Нормальный путь: push в `origin/main`; на сервере `scripts/auto-deploy.sh` (cron, раз в минуту) собирает изменившееся,
      перезапускает и ждёт `healthy` у `bc-api` и `bc-web`; при неуспехе возвращает прежние образы и не повторяет тот коммит.
      Журнал: `deploy.log` в корне развёртывания.
- [ ] Ручной путь (если автодеплой отключён): последовательность из `docs/deployment.md` §6.3 — миграции отдельным шагом,
      затем `api`, потом `worker` (`BC_FLYWAY_ENABLED=false`), затем `web`.
- [ ] Записать в журнал релиза: git SHA, версия Flyway (`V<n>` последней миграции), оператор, дата, время резервной копии.

## 4. Проверка после выкладки

- [ ] `curl -fsS https://copilot.firmspace.eu/api/v1/auth/csrf -o /dev/null -w "%{http_code}\n"` → `204`.
- [ ] `docker inspect -f '{{.State.Health.Status}}' bc-api bc-web bc-worker` → у всех `healthy`. Для `bc-worker` проверка —
      свежесть файла `/tmp/worker-heartbeat` (`docs/deployment.md`, раздел «AI-чтение, Telegram и состояние worker»);
      `unhealthy` означает, что планировщик остановился.
- [ ] Очередь: `GET /api/v1/admin/outbox/dead-letters` пуста или каждое событие объяснено; возвращать в работу —
      `POST /api/v1/admin/outbox/requeue` (только администратор платформы).
- [ ] Сквозная проверка вручную: вход → загрузка PDF → документ получает тип и поля (экран «Документы» → карточка → процесс
      обработки) → при необходимости задача/дело. Если ИИ-ключ не включён, чтение рукописи не работает — это ожидаемо
      (`scripts/enable-ai.sh`, `scripts/check-ai.sh`).
- [ ] `docker compose … logs --since 15m api worker | grep -iE "error|exception"` — нет новых ошибок (`docs/runbook.md`).

## 5. Откат

- [ ] Определена цель отката: предыдущий образ + время резервной копии до миграции (`docs/deployment.md` §7.3).
- [ ] Схема не менялась несовместимо → вернуть образы (`BC_IMAGE_TAG`, `BC_FLYWAY_ENABLED=false`). Менялась → вернуть образы и
      восстановить копию до миграции (`infra/scripts/restore.sh`). Flyway вперёд-только: `undo` нет.
- [ ] Учебный прогон отката выполняется отдельно (задача HD-OPS-03) и записывается в `docs/runbook.md` с датой; пока записи нет,
      откат считается **непроверенным**.

## 6. Что решает владелец, а не проверка

- [ ] Секреты и ключи (`infra/.env`, `docs/deployment.md` §3): ключ ИИ, токен Telegram-бота, Google OAuth — не в репозитории.
- [ ] Закрыта ли публичная регистрация (`docs/deployment.md` §2.3).
- [ ] Сроки хранения и юридические сроки: платформа их не придумывает; значения задаёт ответственный.
