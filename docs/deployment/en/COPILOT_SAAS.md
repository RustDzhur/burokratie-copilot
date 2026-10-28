# Copilot as a service (cloud)

**Languages:** [Русский](../ru/COPILOT_SAAS.md) · [Deutsch](../de/COPILOT_SAAS.md) · [English](COPILOT_SAAS.md)

**Model:** a ready cloud workspace from Firmspace Copilot. The client installs no servers,
operates no PostgreSQL and updates no containers: access is over HTTPS, document intake is
configured once, and work happens in the browser.

![The four delivery models](../../images/deployment-models.svg)

*Figure 1. Copilot as a service is the fourth model: data and engine in the cloud, only a
browser at the client.*

---

## 1. What this is and who it is for

Copilot as a service is a rented, ready workspace: documents, OCR, categories, tasks,
agents, exports and audit already work without a server at the client. Management runs in the
browser at `https://burokratie.firmspace.de`; for integrations the same host serves
`/api/v1`. The model suits small and medium businesses, tax offices and teams that need a fast
start and accept processing data in the EU at Firmspace. If documents must not leave the
client environment, [Robots Runtime](RUNTIME.md) is the right model.

---

## 2. What you need

| Item | Requirement | Check |
|---|---|---|
| Browser | current Chrome, Firefox, Edge or Safari | open `https://burokratie.firmspace.de` |
| Internet | outbound HTTPS 443, no air gap | `curl -I https://burokratie.firmspace.de` |
| Email | access to the mailbox for confirmation and invitations | message from Firmspace |
| Password | 12–128 characters, not from the common-password list | at registration |
| Clock | NTP, drift below 5 minutes | `timedatectl status` |
| `curl` | 7.76+, if the API will be checked | `curl --version` |

Self-registration may be closed on a given instance. If the “Register” button is missing or
returns an error, a Firmspace administrator creates the account — normal for enterprise
deliveries.

---

## 3. Base URL

| Item | Address |
|---|---|
| Workspace | `https://burokratie.firmspace.de` |
| API | `https://burokratie.firmspace.de/api/v1` |
| Health | `https://burokratie.firmspace.de/actuator/health/readiness` |
| Instance licence status | `https://burokratie.firmspace.de/api/v1/license` |

`BASE_URL` in the examples is `https://burokratie.firmspace.de` **without** `/api/v1`; paths
are added per request. Check:

```bash
export BASE_URL="https://burokratie.firmspace.de"
curl -fsS "$BASE_URL/actuator/health/readiness"
curl -fsS "$BASE_URL/api/v1/license" | jq '{state, plan}'
```

If `curl` returns `404` for `/api/v1/license`, check that `/api/v1` is not duplicated in the
path and that no proxy strips it.

---

## 4. Registration and first sign-in

1. Open `https://burokratie.firmspace.de`.
2. Click **Register** (path `/register`).
3. Fill in: email, password (12–128 characters), name, account type, organisation name,
   language and time zone.
4. Confirm the email using the link in the message. Without confirmation, sign-in is blocked.
5. Sign in (`/login`). A short onboarding may appear on first sign-in.

Account types: private person, family, freelancer/sole trader, company, team, external
representative, high-security organisation. The type affects the workspace and plan, not the
API.

`[SCREENSHOT]` The registration screen and the confirmation email.

### 4.1 Roles

| Role | Can | For |
|---|---|---|
| `OWNER` | everything, including ownership transfer and account deletion | owner |
| `ADMIN` | everything except transfer/deletion | administrator |
| `MANAGER` | cases, tasks, documents, finance, approvals | manager |
| `EMPLOYEE` | assigned cases, documents, tasks | employee |
| `ACCOUNTANT` | finance, documents, exports | accountant |
| `LEGAL_ADVISOR` | contracts and legal cases | lawyer |
| `EXTERNAL_ADVISOR` | only assigned cases | external adviser |
| `AUDITOR` | logs and evidence without changes | audit |
| `READ_ONLY` | view only | observer |

Permissions are checked on the server. Invitees gain access only after accepting; one person
can belong to several organisations but sees only those where they are a member.

---

## 5. Documents: how they reach the workspace

### 5.1 Channels

| Channel | Where in the interface | When |
|---|---|---|
| Manual upload | **Scanning and import** → drop files | one-off |
| Document registry | **Document registry** → list and card | view/search |
| Email | **Mail centre** → connect a mailbox | regularly |
| Scan/MFP | folder or Bridge | regularly |
| API | `POST /api/v1/documents:ingest` | integration |
| Bridge | robot cabinet → connection | with legacy systems |

### 5.2 Browser upload

1. Open **Scanning and import** in the left menu.
2. Drag a PDF/image into the upload area, or choose a file.
3. Wait for “Saved. The document is being read now”.
4. Open **Document registry**: the document is in the list.
5. If the system is unsure about the type or fields, the document goes to the **Review
   centre** — confirm or correct it. This is a normal flow, not an error.

`[SCREENSHOT]` The upload area and the document card with processing status.

Default limits: file up to 100 MB, request up to 250 MB. Duplicates are detected by SHA-256:
uploading the same file again does not create a second document.

### 5.3 What happens next

1. Type, size and signature check; antivirus if enabled.
2. Text extraction: digital PDF read directly, scan via OCR.
3. Document type and field detection; low confidence goes to a human review.
4. Category, filing, deadline, task; everything is logged.
5. Unclear documents go to the **Review centre**; the human decision is stored.

Honestly: OCR makes mistakes on poor scans, handwriting and unusual tables. The platform
shows confidence and sends items to review; “100% automatic” is not promised. AI features can
be switched off — then OCR and rules work, but not AI explanations.

---

## 6. Where the API token comes from

A token is needed when an external system (your product, a scanner, a bridge, a script) must
deliver documents or run agents.

1. Sign in at `https://burokratie.firmspace.de`.
2. Open the account menu at the top right → **Account settings**.
3. Choose the **API tokens** card.
4. Click **Create token**.
5. Enter a **name** (e.g. `embedded-prod`).
6. Tick the permissions:
   - **Deliver documents** (`documents:write`) — for upload;
   - **Read documents** (`documents:read`) — if reading is needed;
   - **View agents** (`agents.view`) — catalogue and journals;
   - **Run agents** (`agents.run`) — starting;
   - `exports:read` — for the ELSTER export (may appear under its technical name).
7. Click **Create token**.
8. In the **Token created** dialog click **Copy token** and save the value.

The value is shown **once**. The list shows name, `prefix`, permissions, creation date, last
use and status; not the token itself.

`[SCREENSHOT]` The “API tokens” card, the creation dialog, the “Token created” dialog.

If the token is lost: **API tokens** → find by name → **Revoke** → create a new one. Recovery
is impossible; the server stores only the SHA-256.

Limits: at most 20 active tokens per workspace; 60 requests per minute by default;
permissions cannot be changed after creation; creating requires `api_tokens.manage`.

![Where to get each token](../../images/token-locations.svg)

*Figure 3. Three different tokens: Copilot API, Bridge, Runtime admin. They are not
interchangeable.*

---

## 7. ELSTER export

1. Open **Reports and analytics**.
2. Find the **Expenses and ELSTER mapping** block.
3. Enter the year in the year field.
4. Click **Export CSV** — `elster-<year>.csv` downloads in the browser.

The table on screen and the CSV contain the same rows: the screen and the file cannot tell
different stories. A document without a category, line or date is not hidden; it appears with
a warning.

`[SCREENSHOT]` The “Expenses and ELSTER mapping” block with totals and the export button.

CSV format: `;` separator, decimal comma, fixed header:

```text
category_code;euer_line;euer_kz;datev_skr03;datev_skr04;booking_kind;netto;ust;brutto;vorsteuer_abziehbar;bewirtung_abziehbar_70;bewirtung_nicht_abziehbar_30;document_id;date
```

This is not tax advice: before filing, the accountant checks rules and categories. More in
[../ELSTER_README.md](../../ELSTER_README.md).

---

## 8. Data, retention, plan

### 8.1 Where data goes

Documents, metadata and logs are stored in the Firmspace cloud instance. The region follows
the contract; the target region is the EU. Data leaves only through enabled channels: email,
external AI provider, integrations. Subprocessor list and safeguards:
[../compliance/README.md](../../compliance/README.md),
[../compliance/subprocessors.md](../../compliance/subprocessors.md),
[../security.md](../../security.md).

### 8.2 Retention

| Item | Default | Where changed |
|---|---|---|
| Trial period | 60 days | `billing.trial_days` |
| Retention after end | 100 days | `billing.retention_days` |
| Document retention in the plan | per contract | plan/settings |

After the trial, access is restricted; data is kept for the stated period and then deleted
unless agreed otherwise. Exact periods for a specific client are in the contract, not in this
table.

### 8.3 Plan

Plans and prices are a commercial decision, not a technical constant. The delivery contains
starting values in migrations, but the public offer and SLA are agreed in the contract. Do not
promise a specific price before it is fixed.

---

## 9. Security and limits

- TLS is mandatory; do not disable certificate verification.
- Password 12–128 characters, not from the common-password list.
- Sign-in can be protected with a second factor (MFA) if the instance offers it.
- Tokens are for server-side integrations only; never give them to a browser or mobile app.
- Sessions expire; after a password change, old sessions may be terminated.
- Export and deletion go through platform procedures, not SQL.
- No operation without internet, no air gap, no access to server/DB, no “own AI provider in
  one click”.
- No direct DATEV API and no legal/tax advice.

---

## 10. If it does not work

| Symptom | Cause | What to do |
|---|---|---|
| No confirmation email | spam, wrong address, closed registration | check spam, resend, ask the administrator |
| Sign-in rejected after registration | email not confirmed | use the link in the message |
| “No access” on API tokens | missing `api_tokens.manage` | ask OWNER/ADMIN for the role |
| Token creation “20 active” | workspace limit | revoke an unused token |
| Upload does not start | file > 100 MB or a blocked type | reduce the file; check the format |
| Document “being read” too long | OCR queue/load | wait, check the status, contact support with `documentId` |
| ELSTER export empty | no documents in the year or no categories | check the year and categories; an empty year is `200`, not an error |
| API `401` | token wrong/revoked | create a new token |
| API `403` | missing scope | create a new token with the scope |
| API `402` | plan/licence | check the plan, ask the owner |

---

## 11. Rollout checklist

- [ ] Account created, email confirmed, roles assigned.
- [ ] Document intake channels defined.
- [ ] A test document uploaded and visible in the registry.
- [ ] The Review centre tested on an unclear document.
- [ ] A separate API token for each integration.
- [ ] Token in a secret store, not a ticket.
- [ ] ELSTER export checked for a test year.
- [ ] Retention period and plan recorded in the contract.
- [ ] Account and support owner named.
