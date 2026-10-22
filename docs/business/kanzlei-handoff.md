# Kanzlei edition — где остановились и что дальше

**Дата:** 22.10.2026 · **Ветка:** `main` · **Последний коммит:** `8856531`

Документ для продолжения работы: что сделано, что проверено, что мешает и в каком порядке идти.

---

## 1. Состояние по этапам §10

| Этап | Состояние | Что готово |
|---|---|---|
| **Э0** Demo-Kanzlei | ⛔ не начат | — |
| **Э1** навигация | ✅ **готов** | 3 группы, 10 пунктов, глоссарий §7, защитный тест на ≤12 пунктов |
| **Э1** удаление модулей | 🔶 **3 из 16** | `publicstats`, `printing`, `future`; план с зависимостями — `kanzlei-deletion-plan.md` |
| **Э2** Posteingang | 🔶 **~60%** | статусы Beleg, счётчики, колонка статуса, кнопки шага, пустое состояние |
| **Э3** DATEV | ⛔ не начат | — |
| **Э4** Fristen | ✅ **готов** | цвета, 7/3/1 напоминания, порядок, ссылка на документ, «erledigt» |
| **Э5** Zuordnung | 🔶 **фундамент** | порог 0.9, ручное назначение, запрет экспорта без манданта |

---

## 2. Что сделано и чем проверено

### Э1 — навигация (`c28958b`)
Три группы: **Arbeit** (Posteingang, Belege, Freigaben, Vorgänge, Fristen), **Mandanten** (Mandanten, Briefe),
**Verwaltung** (Archiv, Berichte, Einstellungen). Подписи по глоссарию §7 — ни одного системного слова.
Тест-страж падает, если меню вырастет за 3 группы / 12 пунктов.

### Э1 — удаление модулей
Удалены полностью: `publicstats` (`483ae0d`), `printing` (`36d9fa9`), `future` (`1406026`).
У первых двух таблиц не было — DROP-миграция не нужна, это проверено, а не предположено.

### Э2 — Posteingang
- `V72__beleg_status.sql` — колонка `beleg_status` (NEU/OFFEN/ZU_PRUEFEN/EXPORTIERT/FEHLER) + индекс +
  `documents.mandant_id` (nullable = «Ohne Mandant»).
- `BelegStatusMachine` — узкие переходы, 5 юнит-тестов.
- `GET /documents/beleg-counts` — шесть счётчиков одним запросом.
- `PATCH /documents/{id}/beleg-status` — с аудитом, отказ на невозможный переход.
- Экран: счётчики, колонка статуса, кнопка одного шага, пустое состояние §6.2.

### Э4 — Fristen
Цвета по остатку (<7 / 7–14 / >14), напоминания за **7/3/1** день (ключ дедупликации содержит срок),
просроченное сверху, ссылка на исходный Beleg, «Erledigt» с подтверждением.

### Э5 — Zuordnung (начало)
- `MandantRouting` — порог 0.9, «нет догадке» при близких кандидатах, 4 теста.
- `PATCH /documents/{id}/mandant` — ручное назначение с аудитом «было → стало».
- **Запрет экспорта без манданта** в `DocumentService` (§5.2, §11.7).

---

## 3. Что НЕ сделано и почему

### Требует решения владельца

**`devices`** — противоречие в самом ТЗ: §4.3 «удалить: агенты устройств не нужны», но §9 держит
«bridge + scan» в списке **оставляемого** как путь приёма документов в Posteingang. `devices` — и есть
принимающая половина моста. Модуль не тронут до решения.

**Формат DATEV (§12.1)** — точный профиль полей, порядок и кодировка согласуются с первой канцелярией.
Выдуманный формат = файл, который не импортируется. Делать только предварительный, **явно помеченный**.

### Требует подрядной работы

**Э3 DATEV** — 1–2 недели по оценке ТЗ. Нужны: `export_batches`, `export_batch_items`, `konto_mapping`,
генератор CSV Buchungsstapel, экран Exporte.

**Э5 полностью** — сигналы маршрутизации (адресный блок, e-mail, IBAN/USt-IdNr, запомненный шаблон),
`mandant_routing_rules`, обучение на исправлениях, экран Mandanten.

**Э0 Demo-Kanzlei** — 20 фейковых Belege, 3 манданта, сценарий демо. По ТЗ **идёт первым** для продажи.

**Удаление остальных 13 модулей** — по плану в `kanzlei-deletion-plan.md`, порядок по зависимостям.

### Внешнее ограничение

**Сервер** — SSH (22/2222) закрыт, HTTP 80/443 не отвечает, секретов в GitHub нет. Деплой невозможен.
Скрипт готов: `scripts/deploy-on-host.sh` — одна команда на сервере.

---

## 3.1. Долг, который надо закрыть до удаления `parties`

**В коде сейчас два поля «чей документ»:**

| Поле | Кто пишет | Что значит |
|---|---|---|
| `documents.party_id` | существующий селектор на карточке документа (`api.documents.setParty`) | контрагент/сторона |
| `documents.mandant_id` | новый `PATCH /documents/{id}/mandant`, проверка экспорта, метка «Ohne Mandant» | мандант канцелярии (§5.2) |

По §4.3 `parties` удаляется и сливается с `contacts` → Mandanten, а по §8.3 `documents.mandant_id` — это FK на
`contacts`. Значит `party_id` **обязан** стать `mandant_id`, а не сосуществовать с ним: два поля «чей это
документ» разойдутся, и экран будет показывать один ответ, а экспорт проверять другой.

**Уточнение после проверки схемы (важно):** `party_id` — это FK на таблицу **`parties`**, а `mandant_id` — на
**`contacts`**. Это **разные сущности**, поэтому «просто скопировать значения» нельзя: такой перенос вставил бы
в `mandant_id` идентификаторы, которых в `contacts` нет, и внешний ключ упал бы либо (хуже) связал документ с
чужим контактом.

**Правильный порядок (не разрывать на части):**
1. **Слить `parties` → `contacts`**: сопоставить записи по ИНН/IBAN/имени, создать недостающие контакты,
   запомнить таблицу соответствия `parties.id → contacts.id`.
2. Перенести `documents.party_id` → `documents.mandant_id` **через эту таблицу соответствия**, а не напрямую.
3. Перевести селектор на карточке на `api.documents.setMandant`.
4. Удалить `setParty`, колонку `party_id` и таблицу `parties` (§4.3).

Пункты 1–2 — миграция данных, а не удаление: это та работа, которую §4.3 называет «объединяется с contacts».

**До этого момента** UI назначения живёт в старом поле, и это осознанный долг, а не забывчивость.

## 3.2. Инвентарь читателей `parties` (шаг 1 из §4.3: «выписать все ссылки»)

Собрано `rg`, а не по памяти. Удалять модуль можно только когда этот список пуст.

**Бэкенд (10 файлов):**
`parties/AddresseeResolver.java`, `parties/PartyService.java`, `parties/PartyController.java`,
`documents/DocumentRepository.java`, `documents/web/DocumentController.java` (поле `partyId` в `DocumentResponse`),
`documents/DocumentService.java`, `documents/domain/Document.java` (поле и сеттер),
`orchestration/DocumentProcessedHandler.java`, `orchestration/CaseOrchestrator.java`,
`telegram/TelegramInformer.java`.

**Тесты (8 файлов):** `parties/AddresseeResolverTest.java`, `demo/DemoCabinetIT.java`,
`advisor/AdvisorServiceIT.java`, `invoices/outgoing/InvoicePdfRendererTest.java`,
`invoices/outgoing/InvoiceNumberingIT.java`, `contracts/ContractModelIT.java`, `shared/persistence/SchemaIT.java`,
`web/UiContractIT.java`.

**Фронтенд (6 файлов):** `app/(app)/parties/page.tsx`, `app/(app)/documents/[id]/page.tsx` (уже переведён на
contacts), `app/(app)/documents/page.tsx` (колонка «Kontakt»), `lib/api/wire/documents.ts`, `lib/api/resources.ts`
(группа `api.parties`, `setParty`), `lib/api/types.ts` (`partyId`).

**Порядок удаления (не разрывать):**
1. Убрать колонку «Kontakt» из списка документов или перевести её на `mandant_id`.
2. Убрать `partyId` из `DocumentResponse` и сущности, удалить `setParty` и `PartyService.assign`.
3. Перевести `DocumentProcessedHandler` / `CaseOrchestrator` (они ищут сторону письма) на контакты.
4. Удалить страницу `/parties`, `api.parties`, тесты.
5. Flyway `V74__drop_parties.sql`: `drop table parties;`, `drop column documents.party_id;`.
6. `rg` по `parties|party_id|partyId` → 0, кроме `parties_contact_map` (она остаётся как след слияния).

**Почему это отдельный этап, а не «удалить пакет»:** `parties` — не листовой модуль, его читают демо-данные,
советник, оркестратор и счета. Удаление пакета первым сломает их молча.

## 4. Порядок продолжения

1. **Э0 Demo-Kanzlei** — по ТЗ это то, с чем идут на встречу и продают.
2. **Э3 DATEV** (предварительный формат) — закрывает цепочку «документ → экспорт».
3. **Э5 маршрутизация** — сигналы и правила, потом экран Mandanten.
4. **Удаление модулей** — по плану, по одному коммиту.
5. **Э2 остаток** — карточка Beleg, фильтр по статусу, пакетные действия.

---

## 5. Как проверять

```bash
cd /Users/rustem/bc-work/burokratie-copilot
# бэкенд
cd services/api && export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./mvnw -B -o verify -Dit.test='DocumentUploadIT,DeadlineReminderIT' -DfailIfNoTests=false -Dsurefire.skip=true
# фронтенд
cd apps/web && export PATH="/Users/rustem/.nvm/versions/node/v22.23.3/bin:$PATH"
npx tsc --noEmit -p tsconfig.json && npx vitest run && npx next build
```

**Стенд:** `http://127.0.0.1:3000/login` · `demo@burokratie.local` / `Kabinett-Zugang-2026!`
**Важно:** образ `web` иногда падает на таймаутах npm (`ETIMEDOUT`) — не код, повторить сборку.

---

## 6. Грабли, на которые я наступал

1. **Позиционная запись `DocumentResponse`** — добавив поле, поправь и `withUploader`, иначе компилятор
   откажет (он и отказал).
2. **Ключи i18n живут в namespace экрана** — `column.document` есть и в `inbox`, и в `calendar`; правка
   «первого совпадения» ломает соседний экран.
3. **`queue`/`events` в календаре** — таблица живёт в подкомпоненте `EventList`, а не в странице.
4. **Flyway на тестовой БД** — IT сами накатывают миграции; после отката миграции запись в
   `flyway_schema_history` надо чистить вручную.
5. **Удаление пакета ≠ удаление модуля** — сначала `rg` по ссылкам, потом код, потом DROP, потом коммит.
