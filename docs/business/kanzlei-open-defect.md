# Открытый дефект: клиент зовёт удалённые маршруты

**Обнаружен:** 22.10.2026, раунд 44. **Состояние:** не исправлен. **HEAD при обнаружении:** `972a47d`.

## Что сломано

`apps/web/src/lib/api/__tests__/contract.test.ts` **падает**: 34 вызова из UI не имеют маршрута в бэкенде.

```
"GET  /cases/${id}/plan"            (autopilot — удалён)
"POST /cases/${id}/plan/advance"    (autopilot)
"GET  /contracts"                   (contracts — удалён)
"GET  /memory/entities/${contactId}"(memory — удалён)
"GET  /radar/findings"              (radar — удалён)
"POST /ai/assistant/query"          (assistant — удалён)
...
```

## Причина — моя

В раундах 39–43 я удалял **бэкенд-пакеты** и проверял только `clean verify` бэкенда. Фронтенд не прогонялся.
Клиентские группы `api.contracts`, `api.memory`, `api.radar`, `api.assistant` и методы плана дел остались на месте
и указывают в пустоту.

Компилятор TypeScript этого не видит: вызовы типизированы, а маршрут живёт в строке.

## Почему это важно

Пользователь, открывший `/contracts` или карточку контакта, получит 404/500. Это ровно тот класс поломки,
против которого в проекте и написан `contract.test.ts` — он и сработал.

## Что мешает исправить сразу

`api.memory` используется **оставшимися** экранами: карточка контакта (`profile`, `contactTimeline`) и карточка
счёта (`invoice`, `documentRelationships`). То есть `memory` был не только «ИИ-кластером», но и сервисом
профиля контакта. Удалять его из клиента нельзя, пока эти экраны не переведены на `contacts`.

## План исправления (одним заходом)

1. Вернуть бэкенд `memory` **или** перевести две карточки на `api.contacts`.
   Его удаление (§4.3) отложить до этого.
2. Удалить из клиента группы `contracts`, `radar`, `assistant` и методы плана дел (`/cases/{id}/plan*`).
3. Удалить страницы и компоненты, которые их используют: `/contracts`, `/radar`, `/assistant`,
   `document-insights`, `radar-dashboard-block`.
4. Обновить `screens.test.tsx` (счётчики и ожидания) и `finance.wire.test.ts` (тесты договоров).
5. Прогнать **и** `npx vitest run`, **и** `npx next build` — не только бэкенд.

## Правило на будущее

После удаления бэкенд-пакета **всегда** прогонять фронтенд-тесты: `contract.test.ts` — единственное, что ловит
расхождение UI и API.


## Что уже проверено на практике (раунды 44–46)

Попытки удалить группы клиента «в лоб» упираются в цепочку зависимостей. Точный порядок, проверенный на ошибках:

1. **`api.cases.workspace`** тянет `/cases/${id}/plan` — план дел (autopilot). Удалять надо **внутри** метода
   `workspace`: оставить запрос `/workspace`, передать `workspaceFromWire(workspace, null)`.
2. **`workspaceFromWire` / `approvalFromWire`** импортируются из `./wire/autopilot` и нужны **оставшимся**
   группам (`cases`, `approvals`). Удалять сам файл `wire/autopilot.ts` нельзя — только методы плана.
3. **`document-insights.tsx`** и **`radar-dashboard-block.tsx`** подключены к `/dashboard` и `/documents/[id]`;
   удалять вместе с импортами в этих страницах, иначе tsc падает на «Cannot find module».
4. **`finance.wire.test.ts`**: импорт `contractFromWire` живёт в одной строке с `invoiceFromWire`,
   `decimalFromInput`, `noticeDeadline`, `paymentFromWire`. Удалять импорт целиком нельзя — выпадут нужные
   функции. Удалять только имя и два теста договоров.

**Вывод:** это одна связная правка на 6–8 файлов; дробить её на мелкие шаги дороже, чем сделать за один заход
с полным прогоном `tsc && vitest && next build`.
