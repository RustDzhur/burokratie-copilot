# Integrations — Bürokratie-Copilot

> **СТАТУС ДОКУМЕНТА (обновлено).** Раздел «Reality today» ниже был написан, когда адаптеров и половины
> API ещё не существовало, и с тех пор **устарел**: он утверждает, что нет `IntegrationController`,
> `DocumentController` и OCR. Всё три существуют и работают — проверено на живом прогоне: документ загружен
> через `POST /api/v1/documents`, фотография письма прочитана OCR (tesseract), дата и срок извлечены и
> поставлены в календарь. Актуальное состояние — в таблице ниже; исходный текст сохранён под ней как запись
> о том, что было.

The specification (§18) requires a single **Integration Hub** with per-integration status, connected
account, permissions, settings, last sync, errors, an event log, disconnect/reconnect, secure token
storage and token refresh. It also names the adapters the platform must be able to talk to:

> Microsoft 365 · Gmail / Google Workspace · calendars · bank aggregators · accounting systems · CRM ·
> e-signature · cloud storage · mail services · OCR · AI providers · e-invoicing · external APIs ·
> scanners and local devices

Two rules from the specification govern this document:

1. **"Do not declare an integration ready if only its connection screen was created."** (§18)
2. **"If a specific service cannot be connected without a key, a subscription, a hardware device or
   an agreement with a third-party provider, prepare a full adapter and documentation — but do not
   present it as a working integration."** (§37)

Everything below follows those rules: the status column states what exists **today**, verified by
reading the source and the migrations.

> ### Verification status
> **No adapter has been verified against a live provider.** No adapter code exists yet (see §1), no
> API key or contract is available, and the only external service this repository can currently talk
> to is a database and an S3-compatible object store. Any future row that claims a working
> integration must name the test that proves it.

---

## 1. Current state (verified 2026-10-11)

Checked with:

```bash
find services/api/src/main/java -name '*.java' | sort        # 47 files: identity, audit, shared kernel only
grep -rn 'adapter\|Adapter' services/api/src/main/java | wc -l
ls services/api/src/main/resources/db/migration              # V1..V15
```

| What | Reality today |
| --- | --- |
| **Интеграционный хаб (актуально)** | **Реализован.** `IntegrationController`, каталог адаптеров с честной градацией, события, подключение/отключение, шифрованное хранение токенов. |
| **Загрузка документов (актуально)** | **Реализована.** `DocumentController`, `POST /api/v1/documents`; конвейер: безопасность → хеш → дубликаты → формат → текст → OCR → язык → классификация → поля → сопоставление. |
| **OCR (актуально)** | **Реализован.** tesseract, языки de/en/ru; переносы строк сохраняются; даты цифрами и словами; фото выпрямляется. |
| **Приём почты OAuth (актуально)** | **Реализован, но требует регистрации приложения.** Microsoft 365 и Google Workspace: authorization-code + PKCE, XOAUTH2, refresh. Без client-id/secret — `503 FEATURE_DISABLED` с перечнем недостающих ключей. |
| **Адаптеры (историческая запись)** | **None.** The backend contains the identity/security core (`identity/**`), the audit trail (`audit/**`), a shared kernel (config, error handling, tenancy, correlation IDs, `shared/mail/**`) — and no integration adapter of any kind. |
| **Integration Hub API** | **Not implemented.** No `IntegrationController`, no `integration/*` service. |
| **Upload/ingest API** | **Not implemented.** No `DocumentController`; `POST /api/v1/documents` does not exist. |
| **AI provider abstraction** | **Not implemented.** Configuration only: `burokratie.ai.*` (`BC_AI_ENABLED`, `BC_AI_PROVIDER`, `BC_AI_BASE_URL`, `BC_AI_API_KEY`, `BC_AI_MONTHLY_BUDGET_EUR`), defaulting to **disabled**. No provider client, no budget enforcement code. |
| **OCR abstraction** | **Not implemented.** Configuration only: `burokratie.ocr.*` (`BC_OCR_ENABLED`, `BC_OCR_ENGINE`, `BC_OCR_LANGUAGES`, `BC_TESSERACT_PATH`), defaulting to **disabled**. The dependency list includes PDFBox/POI for text extraction from digital files, but no OCR engine integration. |
| **Object storage** | **Implemented as configuration, adapter pending.** `minio` is a declared dependency (`pom.xml`), `burokratie.storage.type` selects `local-fs` or `s3` with endpoint/region/bucket/keys (`BC_S3_*`). No `BlobStore` implementation was found in the source tree at the time of writing. |
| **Mail sending** | **Partially implemented.** `shared/mail/EmailSender` + `DefaultEmailSender` exist. With `spring.mail.host` set, mail is delivered through `JavaMailSender`; **without it the message body is written to the application log** (INFO) and `isConfigured()` returns `false` so the API can tell the operator the mail was not sent. Inbound mail (IMAP/OAuth) is **not implemented**. |
| **Schema for integrations** | **Present.** Migrations create the tables an Integration Hub will need: `integrations`, `integration_credentials`, `integration_sync_runs`, `devices`, `device_jobs`, `scan_sessions` (V10), `mail_accounts`, `mail_messages`, `mail_attachments`, `outbound_messages`, `message_delivery_events` (V9), `bank_statements`, `bank_transactions`, `financial_accounts` (V8), `webhook_endpoints`, `webhook_events`, `api_tokens` (V11), `ai_jobs`, `ai_model_runs`, `ai_usage_records`, `e_invoice_records`. |
| **Planned work** | `G-005` (integration adapters), `E-001` (Integration Hub core + token storage), `E-005` (AI provider abstraction), `B-007` (OCR adapter), `B-014` (mail ingest: IMAP + M365/Gmail OAuth) — all `TODO` in `docs/implementation-plan.md`. |

**Conclusion:** today the product has **no connected integration**. Everything in the table below is
either planned, blocked on a contract, or blocked on code that does not exist yet. That is the honest
answer to §34's requirement that integrations have "real adapters or an explicitly marked connection
status".

### Recorded external dependencies from the implementation plan

| ID | Dependency | Recorded resolution |
| --- | --- | --- |
| Q-03 | M365/Gmail OAuth requires a `client_id`/`client_secret` from the owner (Azure AD app registration / Google Cloud project) | full OAuth adapters **plus an IMAP adapter that works without an app registration**; the connection status is shown honestly in the UI |
| Q-04 | Direct bank access (PSD2/FinTS) requires a licence or a provider contract | adapter **plus offline CAMT.053/MT940/CSV import**, which works without a provider |
| Q-05 | OCR quality/availability depends on Tesseract and its language data | adapter with engine detection and an honest `OCR_UNAVAILABLE` status — never a fabricated result |
| Q-06 | pgvector is not available in every deployment | semantic search degrades to PostgreSQL full-text search and is reported as unavailable |

---

## 2. Integration catalogue (§18)

Status vocabulary — used consistently:

| Status | Meaning |
| --- | --- |
| `Not implemented` | no adapter code exists |
| `Adapter implemented — not verified against provider` | code exists, tested offline only (mocks/fixtures) |
| `Implemented — verified against a real provider` | exercised against the live service; the verification date and account are recorded |
| `Requires external contract` | code may exist, but nothing works without a licence/agreement from the owner |
| `Blocked (owner action)` | cannot proceed until the owner supplies a credential or decision |

| Integration | Purpose | Required credentials / contract | Current status | What the owner must do to enable it |
| --- | --- | --- | --- | --- |
| **Microsoft 365** (mail, calendar, files) | Sync mailbox and calendar, attach documents from OneDrive/SharePoint, send replies from the user's own mailbox | Azure AD **app registration**: tenant id, client id, client secret (or certificate); redirect URI; delegated Graph scopes (`Mail.Read`, `Mail.Send`, `Calendars.ReadWrite`, `Files.ReadWrite`, `offline_access`, `User.Read`); **admin consent**; a signed DPA | `Blocked (owner action)` — no adapter code | Create the app registration in the tenant's Azure AD, grant admin consent, hand over tenant/client id + secret through the secret store, and confirm which scopes are approved |
| **Gmail / Google Workspace** | Same as above for Google tenants | Google Cloud project, **OAuth consent screen** (internal or verified), OAuth client (id + secret), redirect URI, Gmail API + Calendar API enabled, scopes `gmail.readonly`/`gmail.send`/`calendar.events` (+ `drive.file` for files), a signed DPA | `Blocked (owner action)` — no adapter code | Create the project and OAuth client, publish the consent screen (or keep it internal to the Workspace domain), hand over the credentials, confirm the scopes |
| **Calendars (generic)** | Two-way deadline/appointment sync with any calendar system | CalDAV endpoint + credentials, or an ICS feed URL (read-only); for read-only feeds no contract is needed | `Not implemented` | Decide which calendar system is authoritative, then provide either CalDAV credentials or the ICS URL of the deadlines calendar |
| **Bank aggregator (PSD2/FinTS)** | Import statements and transactions, match payments to invoices | **Contract with a PSD2-licensed aggregator** (e.g. an account-information service) or a FinTS/HBCI setup with the bank; API credentials; TPP registration where applicable; a signed DPA | `Requires external contract` — no adapter code | Sign the aggregator contract, obtain the API credentials, and decide which accounts/tenants may be connected |
| **Offline bank import** (CAMT.053, MT940, CSV) | Payment matching without any provider contract | none (a file upload) | `Not implemented` — tables `bank_statements`, `bank_transactions` exist | Nothing; this is the provider-independent path that should be built first (plan Q-04). Decide which export format the bank can produce |
| **Accounting systems** (DATEV and similar) | Hand invoices/expenses to the tax advisor in the format they accept | DATEV contact + agreement on the exchange format (DATEV-Format CSV / XML, or an API product); credentials if an API is used | `Requires external contract` — no adapter code | Ask the accountant which format and channel they accept, then provide the contact, the format decision and any credentials |
| **CRM** | Link contacts, cases and documents to the customer record | Provider + API credentials (e.g. HubSpot, Salesforce, or a generic REST endpoint with a token) | `Not implemented` — no specific provider has been chosen | Name the CRM product and provide API credentials; if none exists, this integration stays out of scope |
| **E-signature** | Send documents for signature, retrieve the signed result and the audit trail | **Provider contract** (e.g. a qualified-signature service) + API credentials + a **signing certificate** (or remote-signing/HSM credentials); clarification of which signature level is legally required for which document | `Requires external contract` — no adapter code | Decide the provider and the required signature level (simple/advanced/qualified), sign the contract, provide the credentials, and store the certificate in the secret store/HSM |
| **Cloud storage** (S3-compatible, Drive, OneDrive, SharePoint) | Import documents from and export them to storage the customer already uses | For S3/MinIO: endpoint, region, bucket, access key, secret key (EU region). For Drive/OneDrive/SharePoint: the OAuth app from the M365/Google rows above | `Not implemented` — but see the note below: S3/MinIO is a **first-class deployment dependency**, not only an integration | Provide the S3/MinIO credentials (`BC_S3_*`); for Drive/OneDrive reuse the M365/Google registrations |
| **Mail services** (SMTP, IMAP, OAuth) | Send notifications/invitations; ingest incoming mail and attachments | **Outbound**: SMTP relay credentials (host, port, user, password, verified From). **Inbound**: IMAP credentials, or the M365/Google OAuth registrations | **Outbound partially implemented** (`shared/mail/**`; SMTP via `SPRING_MAIL_*`, From via `BC_MAIL_FROM`; without SMTP the body is logged and the sender reports "not configured"). **Inbound: not implemented** | Provide the SMTP relay credentials (needed for e-mail verification — without it no account is usable), and decide the inbound mailbox(es) and their credentials |
| **OCR engines** (Tesseract, external) | Turn scans and photos into text | Tesseract binary + `deu` (+ `eng`) language data in the worker image — no contract needed. An external OCR service additionally needs a contract and credentials | `Not implemented` — configuration keys exist, default disabled | Confirm the language set required by customers (German is mandatory) and decide whether a self-hosted Tesseract suffices or a paid OCR service is needed for quality/throughput |
| **AI providers** (cloud or local) | Classification, data extraction, assistant, embeddings, document generation | Provider contract + **API key** + an agreed monthly budget in EUR (`BC_AI_MONTHLY_BUDGET_EUR`). For a local model: a GPU host or an on-premise endpoint, no contract. Data-protection decision: what text may leave the EU | `Not implemented` — configuration keys exist, default disabled (`BC_AI_ENABLED=false`) | Decide the provider and the data boundary, sign the DPA, provide the API key and the budget cap. Note: enabling AI sends document text to that provider unless a local model is used |
| **E-invoicing** (XRechnung, ZUGFeRD) | Produce and validate compliant outgoing invoices, read incoming e-invoices | No contract needed for generation/validation. A **Peppol access point** contract is needed to *transmit* invoices through the Peppol network; a validation service is optional | `Not implemented` — table `e_invoice_records` exists | Decide the mandatory format and profile (XRechnung version, ZUGFeRD level, Leitweg-ID handling) with the tax advisor, and whether Peppol transmission is required |
| **External / custom APIs** | Let customers push documents in and pull data out from their own systems | Per customer: an API token (`api_tokens` table) + documented endpoints; for push notifications: a webhook endpoint (`webhook_endpoints`, `webhook_events`) plus HMAC signing | `Not implemented` — no endpoint, no token issuance API | Decide whether the public API is in scope for the pilot; the `POST /api/v1/public/**` ingest path is reserved in the security configuration but no handler exists |
| **Scanners, MFP, local devices** | Intake from office hardware: network scanners, scan-to-mail, desktop bridge for USB devices | No contract for the **scan-to-mail** path (the MFP sends to a mailbox — see the mail rows). For direct device access: a local **Desktop Bridge** component plus device support (WIA/SANE/TWAIN/network protocol) — hardware must be available for testing | `Not implemented` — tables `devices`, `device_jobs`, `scan_sessions` exist | Decide the supported models/protocols, provide access to at least one real device for testing, and decide whether the Desktop Bridge is in scope for the pilot |

> **S3/MinIO is not "just another integration".** `BC_STORAGE_TYPE=s3` is how document blobs are
> stored; with `local-fs` the blobs live inside a container and are neither shared between `api` and
> `worker` nor durable across a redeploy. Treat the object-storage credentials as a **mandatory
> deployment input** (see `docs/deployment.md` §10, item 3).

---

## 3. Per-integration detail

### 3.1 Microsoft 365

- **Purpose:** read and send mail from the user's own mailbox, sync the calendar for deadlines, pull
  attachments from OneDrive/SharePoint. This is the integration most German business customers expect
  first.
- **Configuration that would be read** (planned; **not** read by the application today):
  `BC_M365_TENANT_ID`, `BC_M365_CLIENT_ID`, `BC_M365_CLIENT_SECRET`, and a redirect URI derived from
  `BC_PUBLIC_BASE_URL`.
- **Exchange:** OAuth 2.0 authorization-code flow with refresh tokens; Graph API calls for messages,
  attachments, events and files.
- **Permissions:** delegated, least privilege — `User.Read`, `Mail.Read`, `Mail.Send` (only if sending
  from the user's mailbox is enabled), `Calendars.ReadWrite`, `Files.ReadWrite`, `offline_access`.
  `Mail.ReadWrite` is **not** needed and should not be requested.
- **Failure modes:** expired/revoked refresh token (the integration must show "reconnect required"),
  throttling (`429` with `Retry-After` — the adapter must honour it), conditional-access policies that
  block the app, mailbox in a different tenant.
- **Owner steps:** register the app in Azure AD → add the redirect URI
  `https://<public-host>/api/v1/integrations/microsoft/callback` → create a client secret with an
  expiry the team can manage → grant admin consent for the scopes → hand over tenant/client id and
  the secret through the secret store → confirm the exchange/mailbox policy allows IMAP/Graph access.
- **Status:** `Blocked (owner action)`; no adapter code exists.

### 3.2 Gmail / Google Workspace

- **Purpose:** same as M365 for Google tenants.
- **Configuration that would be read** (planned): `BC_GOOGLE_CLIENT_ID`, `BC_GOOGLE_CLIENT_SECRET`.
- **Permissions:** `openid email profile`, `https://www.googleapis.com/auth/gmail.readonly`,
  `.../gmail.send` (only if sending is enabled), `.../calendar.events`, and `drive.file` for
  app-created files only.
- **Failure modes:** unverified consent screen (refresh tokens expire after 7 days in testing status —
  this breaks production silently, so the app **must** be published or kept internal to the domain),
  scope revocation, per-user quotas.
- **Owner steps:** create the Google Cloud project → configure the OAuth consent screen (internal to
  the Workspace domain, or verified for external users) → create an OAuth client (Web) with the
  redirect URI `https://<public-host>/api/v1/integrations/google/callback` → enable the Gmail and
  Calendar APIs → hand over the client id/secret → decide whether users may connect personal accounts
  (data-protection implication).
- **Status:** `Blocked (owner action)`; no adapter code exists.

### 3.3 Mail services (SMTP outbound, IMAP inbound) — the one partially implemented path

- **What exists today (verified in the source):**
  - `EmailSender` interface and `DefaultEmailSender` (`services/api/src/main/java/de/buerokratiecopilot/shared/mail/`).
  - Delivery uses Spring's `JavaMailSender`, i.e. the `SPRING_MAIL_*` environment variables
    (`SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`) — **not**
    `BC_MAIL_*`. The From address is `BC_MAIL_FROM`.
  - `MailConfiguration` provides the bean; `isConfigured()` returns `false` when
    `spring.mail.host` is unset.
  - **Without SMTP the message body is written to the application log** at INFO and no mail is sent.
    The API surfaces this (`VERIFY_EMAIL_PENDING_MAIL_CONFIGURATION`), so the operator is told the
    truth — but the log then contains a verification link, which must never happen in production.
- **Impact on a fresh install:** e-mail verification is mandatory for registration. Without a working
  SMTP relay **no account can be completed**, so SMTP credentials are a prerequisite for the first
  pilot, not a nice-to-have.
- **Inbound:** IMAP mailbox sync and the OAuth adapters are **not implemented** (plan B-014).
- **Owner steps:** provide the SMTP relay (host, port, username, password) and a verified From
  address; decide the inbound mailboxes; set `SPRING_MAIL_*` and `BC_MAIL_FROM` in `infra/.env`.
- **Status:** outbound `Adapter implemented — not verified against provider` (an SMTP server was never
  available in this environment); inbound `Not implemented`.

### 3.4 Banking (PSD2/FinTS) and offline statement import

- **Purpose:** import statements/transactions and match payments against invoices (`payments`,
  `payment_matches`, `bank_transactions`, `financial_accounts` tables exist).
- **Direct access reality:** PSD2 account information in the EU requires a licensed TPP or a contract
  with one. There is no free path, and screen-scraping is not an option.
- **What works without any contract:** importing **CAMT.053**, **MT940** or a **CSV** export produced
  by the bank's online portal. This should be implemented first (plan Q-04) so the finance module is
  usable immediately.
- **Never claim:** that a balance or a transaction was retrieved live when it came from an uploaded
  file. The UI must distinguish "imported file" from "connected account".
- **Owner steps:** sign an aggregator contract **or** decide the export format the bank provides; hand
  over the API credentials for the aggregator path.
- **Status:** `Requires external contract` for the aggregator; offline import `Not implemented`.

### 3.5 OCR

- **Purpose:** text from scans and photos (spec §9.2), with German as the mandatory language.
- **Configuration:** `BC_OCR_ENABLED=false` by default; `BC_OCR_ENGINE=tesseract`;
  `BC_OCR_LANGUAGES=deu+eng`; `BC_TESSERACT_PATH=tesseract`.
- **Reality:** no OCR adapter code exists; Tesseract and its language data are not installed in this
  environment. Digital PDFs and Office files can be handled by PDFBox/POI (declared dependencies)
  without OCR, but a scan needs a real OCR engine.
- **Honesty requirement (plan Q-05):** when the engine is missing, the document must be marked
  `OCR_UNAVAILABLE` — never "processed with empty text".
- **Owner steps:** confirm the language set (German mandatory, English recommended), decide whether
  self-hosted Tesseract is sufficient or an external OCR service is needed for quality/throughput,
  and (for the external path) provide the contract and credentials.
- **Status:** `Requires external dependency` (engine + language data); no adapter code.

### 3.6 AI providers

- **Purpose:** classification, structured extraction, the assistant, RAG/embeddings, document
  generation (spec §10).
- **Configuration:** `BC_AI_ENABLED=false` by default; `BC_AI_PROVIDER=none`;
  `BC_AI_BASE_URL`; `BC_AI_API_KEY`; `BC_AI_MONTHLY_BUDGET_EUR=0`.
- **Requirements on the adapter (planned, E-005):** one provider interface supporting cloud and local
  models, per-task model selection, cost accounting (`ai_usage_records`) against the monthly budget,
  timeouts, retries, a circuit breaker, PII-free logging, and the ability to disable external AI
  entirely.
- **Data-protection decision:** enabling AI sends document text to the provider. Either use a provider
  with an EU processing location and a signed DPA, or run a local model.
- **Prompt-injection caution (spec §10.3):** document content is untrusted input. Any tool-calling
  path must be sandboxed and must never let document text trigger irreversible actions.
- **Owner steps:** choose the provider (cloud or local), agree the monthly budget, sign the DPA,
  provide the API key, and confirm the data boundary.
- **Status:** `Requires external contract`; no adapter code, feature disabled by default.

### 3.7 E-invoicing (XRechnung / ZUGFeRD)

- **Purpose:** create compliant outgoing invoices, read incoming e-invoices, keep the validation
  evidence (`e_invoice_records` table exists).
- **No contract needed** for generating and validating the XML/PDF-A3 formats; a **Peppol access
  point** contract is required to transmit them through the Peppol network.
- **The rules are not invented here** (spec §1.2): the applicable XRechnung version, the ZUGFeRD
  profile, the Leitweg-ID handling and the tax rules must be confirmed by the tax advisor. Until then
  the feature must be configurable and marked "requires configuration".
- **Owner steps:** obtain the format/profile decision from the tax advisor; decide whether Peppol
  transmission is in scope and, if yes, contract an access point.
- **Status:** `Not implemented`.

### 3.8 Scanners, MFP and the Desktop Bridge

- **Purpose:** intake from office hardware (spec §8). Three realistic paths:
  1. **Scan-to-mail** — the MFP sends the scan to a mailbox; the mail ingest path picks it up
     (no device-specific code, depends on the mail integration).
  2. **Scan-to-folder/network share** — a watched folder or an upload endpoint.
  3. **Desktop Bridge** — a local component for USB/network devices using WIA/SANE/TWAIN, for
     customers whose hardware cannot send mail.
- **What exists:** tables `devices`, `device_jobs`, `scan_sessions` (V10). No bridge component, no
  device code (`apps/desktop-bridge` is an empty placeholder).
- **Honesty requirement:** a device that has never been tested against real hardware must be listed as
  unsupported. Do not present a device screen as a working integration.
- **Owner steps:** decide the supported models/protocols, configure scan-to-mail on one real MFP as
  the pilot path, and provide hardware access for testing the bridge.
- **Status:** `Not implemented`; `apps/desktop-bridge` is reserved.

### 3.9 Custom APIs, webhooks and API tokens

- **Purpose:** let a customer's own software push documents in and pull data out.
- **What exists:** `api_tokens` and `webhook_endpoints`/`webhook_events` tables (V11); the security
  configuration reserves `POST/GET /api/v1/public/**` as a permit-all area intended for
  HMAC-signed ingest (spec §7.4) — **but no handler is registered**, so the path currently returns
  404. That reservation is a plan, not a working endpoint.
- **Security requirements (planned):** per-tenant tokens with scopes, hashed at rest, revocable and
  expiring; HMAC-signed webhooks with replay protection; rate limits per token; audit events for
  every programmatic write.
- **Owner steps:** decide whether the public API is in scope for the pilot; if yes, agree the token
  and webhook contract with the customer.
- **Status:** `Not implemented`.

---

## 4. Security requirements that apply to every integration

Spec §18 and §21 apply to all of them:

| Requirement | How it must be implemented |
| --- | --- |
| Secure token storage | credentials encrypted at rest with `BC_ENCRYPTION_KEY` (AES-256) in `integration_credentials`; never in plain text, never in logs |
| No tokens in the frontend | the browser never receives a provider token; it sees only the integration's status and settings |
| Token refresh | refresh tokens are rotated and stored encrypted; a failed refresh flips the status to "reconnect required" instead of failing silently |
| Revocation | disconnecting an integration invalidates the stored credentials and stops scheduled syncs immediately |
| Per-tenant scoping | credentials belong to one tenant; a tenant can never see or use another tenant's connection (enforced by RLS as well, migration V13) |
| Audit | connect, disconnect, re-authorise, sync and every externally visible action produce an `audit_events` entry with actor, tenant and correlation id |
| Rate limits and retries | honour provider throttling (e.g. `429`/`Retry-After`); exponential backoff with jitter; no unbounded retry loops |
| Circuit breakers | a degraded provider must not exhaust application threads or the connection pool; the feature degrades and reports itself as unavailable |
| Data minimisation | request the narrowest scopes; do not copy whole mailboxes or file trees "just in case" |
| Prompt injection | content fetched from an external system is untrusted input: never let it trigger tool calls or irreversible actions without human confirmation |
| Sync observability | every sync writes an `integration_sync_runs` row with counts, duration and the error — "last successful sync" must be visible to the operator |
| Failure honesty | a provider outage is reported as a degradation; never return a fabricated result and never mark an operation successful that the provider did not confirm |

---

## 5. Governance: how a new adapter is added

1. **Contract first.** Write down the provider's API surface, the exact scopes/permissions, the
   quota/rate limits, the terms of use and the data-processing implications. Verify them against the
   provider's current documentation — do not rely on memory or on a blog post.
2. **Interface.** Implement the shared adapter interface (to be defined with E-001) with a declared
   `status()` that can only report what has actually been verified.
3. **Credentials.** Store them encrypted through the credential service; add the variable names to
   `infra/.env.example` (never a real value) and to `docs/deployment.md` §10 if the owner must supply
   them.
4. **Sync + errors.** Implement sync runs, error classification (retryable vs permanent), and the
   "reconnect required" state.
5. **Tests.** Unit tests with recorded provider responses (fixtures) plus one **manual integration
   test against a sandbox account**; record who ran it and when.
6. **Status.** Update this document — a row may only move to `Implemented — verified against a real
   provider` when that manual test exists and its date/account are recorded.
7. **Data protection.** Add the provider to the subprocessor list and confirm a signed DPA before it
   is enabled for any tenant with real data.
8. **UI.** The connection screen must show the real status, the last sync, the last error, and a
   working disconnect. A screen without a working adapter is a defect, not a feature (§18).

---

## 6. External dependencies (owner action required)

Minimum set for a first productive pilot — without these the platform cannot be used with real data:

| # | Item | Blocks | Owner action |
| --- | --- | --- | --- |
| 1 | **SMTP relay credentials** + verified From address | e-mail verification → **no account can be completed** | provide host/port/user/password and the sender address |
| 2 | **Object-storage credentials** (S3/MinIO: endpoint, region, bucket, key, secret) | durable, shared document storage | provide credentials in an EU region |
| 3 | **AI provider key + contract + monthly budget** (or a local model) | all AI features stay disabled | choose the provider, sign the DPA, provide the key |
| 4 | **OCR decision** (Tesseract language data, or an external service + contract) | scans and photos produce no text | confirm languages and quality requirements |
| 5 | **Microsoft 365 app registration** and/or **Google OAuth client** | mail/calendar/file integrations | create the registration, grant consent, hand over the ids/secret |
| 6 | **Banking contract** (PSD2/FinTS) or the decision to use offline CAMT/MT940/CSV import | automatic payment matching | sign the contract or confirm the export format |
| 7 | **Accounting export decision** (DATEV format/channel + contact) | handing data to the tax advisor | ask the accountant |
| 8 | **E-signature contract + signing certificate** | legally valid digital signing | choose the provider and signature level |
| 9 | **E-invoicing decisions** (XRechnung/ZUGFeRD profile, Peppol access point if needed) | compliant outgoing e-invoices | confirm with the tax advisor |
| 10 | **Scanner/MFP decision + hardware for testing** | intake from office hardware | choose models and provide access to one device |
| 11 | **DPA/AVV with every subprocessor** | lawful processing of personal data at all | sign the agreements |
| 12 | **Data-protection sign-off** (records of processing, DPIA if applicable) | go-live | independent review; the project does not claim compliance on its own (spec §21.1) |

---

## 7. Related documents

- [`docs/task.md`](task.md) — §7 (document intake), §9 (OCR), §10 (AI layer), §14 (finance/e-invoicing), §18 (integrations), §21 (security)
- [`docs/implementation-plan.md`](implementation-plan.md) — tasks B-007, B-014, E-001, E-005, G-005 and the recorded dependencies Q-03…Q-06
- [`docs/deployment.md`](deployment.md) — secrets management, data region, owner-supplied credentials
- [`docs/runbook.md`](runbook.md) — what to do when a provider is degraded
- [`infra/.env.example`](../infra/.env.example) — which variables the application actually reads today (and which are reserved)
