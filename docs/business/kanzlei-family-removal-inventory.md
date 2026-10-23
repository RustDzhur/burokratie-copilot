# Инвентарь: удаление `family` / частного и «компанийного» контура (§4.3)

Собрано **до** удаления, как требует §4.3 ТЗ (`docs/business/tz-kanzlei-edition.md`): «выписать все
ссылки на модуль». Источник — `grep -rn` по репозиторию, не память. Точка отсчёта: `main` @ `e4ab737`.

Задача — один вид кабинета: **канцелярия**. Частный (`private`) и семейный (`family`) режимы
удаляются; экраны `family`, `company`, `employees` — тоже.

## 1. Бэкенд: пакет `family`

| Файл | Роль |
|---|---|
| `services/api/src/main/java/.../family/FamilyAccessService.java` | доступ к «личным» документам: `personal_documents`, `document_shares`, `decide/requireRead/markPersonal/share/revokeShare` |
| `services/api/src/main/java/.../family/web/FamilyController.java` | `GET/POST /api/v1/family/**` (документы, персональные, шаринг, участники) |
| `services/api/src/test/java/.../family/FamilyIsolationIT.java` | изоляция household |
| `services/api/src/test/java/.../family/web/FamilyControllerIT.java` | эндпоинты household |

Читатели вне пакета (проверено, почему пакет нельзя удалять «в лоб»):

| Файл | Что берёт | Вердикт |
|---|---|---|
| `security/TenantIsolationSuiteIT` | `FamilyAccessService` в сценарии изоляции | тест-кейс удаляется вместе с модулем |
| `scenarios/MandatoryScenariosIT` | `FamilyAccessService.requireRead` | тест-кейс удаляется вместе с модулем |
| `db/migration/V23__family_document_access.sql` | создаёт `personal_documents`, `document_shares` | **применённая миграция — не трогаем**; таблицы удаляются новой миграцией |

Других читателей таблиц `personal_documents` / `document_shares` в коде нет — проверено
`grep -rn "personal_documents\|document_shares" services/api/src/main` (все совпадения — внутри
`family/` и в самой V23).

## 2. Бэкенд: тип кабинета

| Место | Что делает | Что будет |
|---|---|---|
| `identity/auth/AuthService.java` | `command.accountType()` → `Tenant.accountType`, `Workspace.Mode`, `workspaceNameFor`, `workspaceModeFor` | один вид: `COMPANY` / `BUSINESS` / «Kanzlei» |
| `identity/auth/AuthService.java` | `SubscriptionService.planFor(accountType)` | один тариф для канцелярии |
| `identity/web/AuthController.java` | `accountType` в теле регистрации | поля нет |
| `identity/auth/AccountSetupService.java` | `ACCOUNT_TYPES`, `WORKSPACE_MODES`, `Setup.workspaceType` | тип не выбирается и не меняется мастером |
| `billing/PlanFeatures.java` | `cabinetOf()`, `Gate.cabinets`, гейт `/api/v1/family` | только tier |
| `billing/SubscriptionService.java` | `planFor(accountType)`, фильтр планов по `plans.audiences`, `alignPlanToAccountType` | один тариф, без audiences |
| `accounts/ModuleCatalogue.java` | `Module.FAMILY` («Familienbereich»), дефолты `FAMILY_HOUSEHOLD` | модуль семьи удаляется |
| `accounts/web/AccountModeController.java` | `POST /api/v1/account/type` — переключение вида кабинета | эндпоинт удаляется |
| `documents/domain/DocumentType.java`, `documents/web/DocumentController.java` | `audiences` («private,family,company») в каталоге типов | из API-выдачи убирается, фильтр снят |
| `demo/Persona.java` | `PERSON`, `FAMILY`, `FREELANCER`, `COMPANY`, `ADVISOR` | остаются только канцелярские |
| `demo/DemoService.java` | `Persona.FAMILY` → контакты домохозяйства; `Persona.ADVISOR` → клиентские кабинеты | блок FAMILY удаляется |
| `documents/sender/SenderService.java` | SQL по `parties` (таблица удалена в V74) — **демо падает 500** | переводится на `contacts` |
| `demo/DemoService.java` | `insert into contracts` (таблица удалена в V76) — **демо падает 500** | вставка удаляется |
| `ai/guide/ProductGuideService.java` | маршруты `/family`, `/company`, `/employees` в справке | убираются |

## 3. Фронтенд

| Файл | Что | Что будет |
|---|---|---|
| `app/(app)/family/page.tsx`, `family.module.css` | экран семейного кабинета | удалить |
| `app/(app)/company/page.tsx`, `company.module.css` | экран-кокпит компании | удалить |
| `app/(app)/employees/page.tsx` | экран сотрудников и ролей | удалить |
| `navigation/screens.ts` | `ScreenId family/company/employees`, `WorkspaceKind`, `audience`, фильтр `screensVisibleFor` | экраны и тип кабинета удалить, фильтр — только `minTier`/`requiredPermission` |
| `lib/api/types.ts` | `SessionUser.workspaceType`, `PlanSummary.audiences`, `DocumentType.audiences` | убрать |
| `lib/api/resources.ts` | `ACCOUNT_TYPE_TO_BACKEND`, `workspaceTypeFromBackend`, `RegisterPayload.accountType`, `OnboardingPayload.workspaceType`, `api.account.setType` | убрать |
| `app/(auth)/register/page.tsx` | `RadioGroup` выбора типа кабинета | убрать |
| `app/onboarding/page.tsx` | `workspaceType` + `RadioGroup` | убрать |
| `app/(app)/billing/page.tsx` | селектор «вид кабинета» + `switchCabinet` | убрать |
| `components/app/plan-grid.tsx` | фильтр тарифов по `cabinet`/`audiences` | убрать |
| `components/app/sidebar.tsx` | бейдж вида кабинета | убрать |
| `app/(app)/documents/page.tsx` | фильтр типов документов по `audiences` | убрать |
| `app/(app)/dashboard/page.tsx` | ссылка на `/company` | ведёт на `/approvals` |
| `lib/tour/steps.ts` | шаги тура `FAMILY`/`COMPANY` | убрать |
| `i18n/messages/{de,en,ru}` (core) | `nav.items.family/company/employees`, `cabinet.*` | убрать |
| `i18n/messages/{de,en,ru}` (screens) | namespace `family`, `company`, `employees`, `register.typePrivate/typeFamily/typeBusiness`, `onboarding.step.workspace.type*` | убрать |

## 4. Тесты, которые закрепляли три вида кабинета

- Бэкенд: `DemoCabinetIT` (persona `family`), `BillingLifecycleIT` (`FAMILY_HOUSEHOLD` → `PERSONAL`),
  `LegalIT` (`PRIVATE_PERSON` не получает AVV), `AuthFlowIT` (`PRIVATE_PERSON`), `TenantIsolationSuiteIT`,
  `MandatoryScenariosIT`, `AccountModeIT`, `AccountModeControllerIT` (переключение типа).
- Фронтенд: `navigation/__tests__/screens.test.tsx` (три вида, 45 экранов),
  `components/app/__tests__/sidebar-personalization.test.tsx`, `lib/api/__tests__/session-user.test.ts`,
  `components/app/__tests__/demo.test.tsx` (пять персон), фикстуры `workspaceType` в тестах настроек,
  `document-type-changer`/`type-chooser` (поле `audiences`).

## 5. Что сделано по каждому пункту

| Пункт | Итог |
|---|---|
| пакет `family` | удалён: `FamilyAccessService`, `FamilyController`, `FamilyIsolationIT`, `FamilyControllerIT` |
| таблицы `personal_documents`, `document_shares` | удалены в `V83__drop_family_document_access.sql`; других читателей не было |
| тип кабинета в коде | `WorkspaceKind`/`audience`/`workspaceType` удалены из реестра экранов, сессии, регистрации, мастера, тарифов, документов |
| регистрация | тип не спрашивается и не принимается: `AuthService` создаёт один кабинет (`COMPANY` / `BUSINESS` / «Kanzlei») |
| регистрация, тело запроса | поле `accountType` удалено из `RegisterRequest`; старое поле игнорируется (проверено `KanzleiRoutesIT`) |
| мастер первого запуска | тип не выбирается и не меняется (`AccountSetupService`) |
| операторский провижининг | `OnboardingService` больше не принимает тип: тоже одна канцелярия |
| переключение кабинета | `POST /api/v1/account/type`, `GET /api/v1/account/types`, `GET /api/v1/account/modules/{key}/types` удалены |
| тариф «Familie» | строка в `plans` оставлена (FK из `subscriptions`), `is_active=false`; из выдачи API убран вместе с полем `audiences` |
| тариф «Personal» | `is_active=false` миграцией: это тариф частного/семейного контура; строка и подписки сохранены |
| демо | персоны `PERSON` и `FAMILY` удалены вместе с их примерами (19 → 13), `ADVISOR` переименована в «Kanzlei» |
| демо, сломанные вставки | убраны `insert into parties`/`contracts` (таблицы удалены в V74/V76) — демо снова открывается |
| экраны | `/family`, `/company`, `/employees` удалены вместе с i18n-namespace `family`, `company`, `employees`, `parties` |
| фильтр меню | `screensVisibleFor` — только `minTier` и `requiredPermission`; 42 экрана, 11 пунктов в 3 группах |
| `audiences` в каталогах | убрано из API тарифов и типов документов, фильтры сняты; колонки в БД оставлены как данные |

## 6. Что осталось и почему

| Осталось | Почему |
|---|---|
| Колонки `tenants.account_type`, `workspaces.mode` и их CHECK-ограничения | NOT NULL, нужны каждой строке; миграция нормализует старые значения, но не дропает колонки |
| Значения `Workspace.Mode.PRIVATE/FAMILY` в enum | их знает CHECK-ограничение БД; ни один код их больше не создаёт, V83 переводит существующие строки в `BUSINESS` |
| `ModuleCatalogue` с дефолтами для `PRIVATE_PERSON`/`FAMILY_HOUSEHOLD` | это каталог модулей для легаси-строк; `Module.FAMILY` удалён, новых таких типов не создаётся |
| Строка тарифа `FAMILY` в `plans` | FK `subscriptions.plan_code`; `is_active=false` |
| `plans.audiences`, `document_types.audiences` | оставлены как данные каталога; DROP колонки — отдельная работа по каталогу |
| `ReportService.Audience.INDIVIDUAL` и экран «Berichte» | это разрез отчётности, а не вид кабинета; по §5.9 отчёты переделываются отдельным этапом |
| Роль `FAMILY_MEMBER` в `roles` | это роль, а не вид кабинета; на неё могут ссылаться существующие membership_roles |
