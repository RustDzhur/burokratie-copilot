# Embedded API

**Languages:** [Русский](../ru/EMBEDDED_API.md) · [Deutsch](../de/EMBEDDED_API.md) · [English](EMBEDDED_API.md)

**Model:** your product calls Firmspace document recognition, agents and workflows over
HTTP. The interface stays with the client; Firmspace does the work and returns the result.
This is not a separate installation but an **integration path** into Copilot in the cloud or
into a Robots Runtime on the client's server.

---

## 1. What this is and who it is for

The Embedded API fits when the client has its own service, portal, CRM or industry
application and wants to embed document intake, recognition, agents or the ELSTER export.
The client system sends a file or a command and receives JSON; everything else happens on
Firmspace's side. The model is for developers and integrators who can write code and handle
errors. If a ready interface for employees is needed, use
[Copilot as a service](COPILOT_SAAS.md); if only legacy software must be connected, use
[Bridge only](BRIDGE_ONLY.md).

![Document path from folder to ELSTER](../../images/document-path.svg)

*Figure 2. Steps 3–5 are the Embedded API: ingest, agent, export.*

---

## 2. What you need

| Item | Requirement | Check |
|---|---|---|
| Base URL | Copilot or Runtime address (§3) | `curl -fsS "$BASE_URL/api/v1/license"` |
| API token | issued with the required scopes (§4) | `curl ... /api/v1/agents` |
| TLS | valid certificate; verification not disabled | `openssl s_client -connect <host>:443 -servername <host>` |
| `curl` | 7.76+ | `curl --version` |
| `jq` | optional | `jq --version` |
| Clock | NTP, drift below 5 minutes | `timedatectl status` |
| Network | outbound HTTPS 443 | `curl -I "$BASE_URL"` |

The examples are for `bash`/`zsh`. In PowerShell use `curl.exe` and `Invoke-RestMethod`; the
headers are the same.

---

## 3. Base URL

### 3.1 Copilot in the cloud

```bash
export BASE_URL="https://burokratie.firmspace.de"
export TOKEN='<insert your own token>'

curl -fsS "$BASE_URL/actuator/health/readiness"
# {"status":"UP"}

curl -fsS "$BASE_URL/api/v1/license" | jq '{state, plan, expiresAt}'
```

The API is always at `<BASE_URL>/api/v1`. The examples spell out the full path so nothing
has to be guessed.

### 3.2 Robots Runtime at the client

```bash
export BASE_URL="http://192.168.1.20:8080"
export TOKEN='<Runtime token>'

curl -fsS "$BASE_URL/actuator/health/readiness"
curl -fsS "$BASE_URL/api/v1/license"
```

The Runtime address is the one the installer printed as `API URL:`. If the terminal is closed:

```bash
sudo grep '^BC_PUBLIC_BASE_URL=' /opt/firmspace-runtime/runtime.env
```

The Runtime API is `<that address>/api/v1`. More in [RUNTIME.md](RUNTIME.md) §3.

### 3.3 Is the URL correct?

| Check | Expected | If not |
|---|---|---|
| `curl -I "$BASE_URL"` | `200`/`302`, no timeout | DNS, proxy, firewall |
| `curl -fsS "$BASE_URL/actuator/health/readiness"` | `{"status":"UP"}` | API is not running |
| `curl -fsS "$BASE_URL/api/v1/license"` | JSON with `state` | wrong path or a proxy strips `/api` |
| `curl -fsS "$BASE_URL/api/v1/agents"` without a token | `401` | `404` — wrong URL |

---

## 4. Where the token comes from

### 4.1 Copilot API token

1. Sign in at `https://burokratie.firmspace.de`.
2. Open the account menu at the top right → **Account settings**.
3. In the card list, choose **API tokens**.
4. Click **Create token**.
5. Enter a **name** that identifies the integration, e.g. `embedded-prod` or
   `scanner-accounting`. The name is visible in the list; the value is not.
6. Tick **only the permissions you need**:
   - **Deliver documents** — `documents:write`, required for file upload;
   - **Read documents** — `documents:read`, if the list/content must be read;
   - **View agents** — `agents.view`, for the catalogue and run journals;
   - **Run agents** — `agents.run`, to start agents and workflows;
   - `exports:read` — for the ELSTER export; some versions label the checkbox with the
     technical name, which is normal.
7. Click **Create token**.
8. In the **Token created** dialog click **Copy token** and store the value immediately in a
   password manager. The value is shown **once**; the list keeps only the prefix.

`[SCREENSHOT]` Three screens: the “API tokens” card, the creation dialog with permission
checkboxes, the “Token created” dialog with the copy button.

If the token is lost: open **API tokens**, find the entry by name and `prefix`, click
**Revoke**, create a new one. The old value cannot be restored — the server stores only its
SHA-256.

Important limits:

- permissions **cannot be changed** after creation;
- at most **20 active tokens** per workspace; the 21st shows an error;
- default **60 requests per minute** per token (Runtime admin issues 120);
- creating tokens requires a role with `api_tokens.manage` (usually OWNER/ADMIN).

### 4.2 Runtime token

The installer issues the first token; the operator issues further ones through the admin API:

```bash
set -a; . /opt/firmspace-runtime/runtime.env; set +a
curl -sS -X POST \
  -H "X-Runtime-Admin-Token: $BC_RUNTIME_ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  "http://127.0.0.1:8080/api/v1/runtime/tokens" \
  -d '{"name":"embedded-prod","scopes":["documents:write","agents.view","agents.run","exports:read"]}' | jq
```

The `token` field in the response is shown once. More in [RUNTIME.md](RUNTIME.md) §7.

### 4.3 Scopes

| Scope | Opens | Does not open |
|---|---|---|
| `documents:write` | `POST /api/v1/documents:ingest` | other tenants' documents, agent runs |
| `documents:read` | `GET /api/v1/documents`, `/documents/{id}`, `/documents/{id}/content` | upload and modification |
| `agents.view` | `GET /agents`, `/agents/{id}`, `/agents/{id}/runs`, reading workflows and constellations | starting and editing |
| `agents.run` | `POST /agents/{id}/run`, `/constellations/{id}/instantiate`, `/workflows/{id}/run`, `/workflow-runs/{id}/resume` | publishing/editing workflows |
| `exports:read` | `GET /api/v1/exports/elster` | agent runs, settings |
| `settings:read` | reading settings | changing them |
| `settings:write` | changing rules, date terms, retention | token administration |

The dot spelling `documents.write` is accepted at creation and normalised to
`documents:write`. An unknown scope is a creation error, not a silently stored word.

---

## 5. First commands

### 5.1 Variables

```bash
export BASE_URL="https://burokratie.firmspace.de"
export TOKEN='<insert your own token>'
```

`BASE_URL` without a trailing `/`. With `/` at the end you get `//api/v1` and some proxies
answer `404`. Verify:

```bash
printf '%s\n' "${BASE_URL%/}"
```

### 5.2 Verify the token

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/v1/agents"
```

Expected `200`. `401` means the token is wrong or revoked. `403` means the token is valid but
lacks `agents.view`; those are different problems.

### 5.3 Agent catalogue

```bash
curl -fsS "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" | jq '.[0]'
```

Expected response (abridged):

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

### 5.4 Upload a file

```bash
curl -sS -X POST "$BASE_URL/api/v1/documents:ingest" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: invoice-2026-0004711-1" \
  -H "X-Filename: rechnung-4711.pdf" \
  -H "Content-Type: application/pdf" \
  --data-binary @rechnung-4711.pdf \
  -w '\nHTTP %{http_code}\n'
```

Expected `201`:

```json
{
  "documentId": "6f1c2a9e-6d0b-4a4d-9f2e-2a1d7f3b8c11",
  "duplicate": false,
  "message": "Dokument aufgenommen",
  "contentHash": "9f2c8a…64 hex…"
}
```

A retry with the same `Idempotency-Key` returns `200` and the same `documentId`:

```json
{
  "documentId": "6f1c2a9e-6d0b-4a4d-9f2e-2a1d7f3b8c11",
  "duplicate": true,
  "message": "Dieser Schlüssel wurde bereits verarbeitet; das vorhandene Dokument wird zurückgegeben",
  "contentHash": "9f2c8a…"
}
```

What the `curl` flags mean:

| Flag | Purpose |
|---|---|
| `-sS` | no progress bar, but show network errors |
| `-X POST` | request method |
| `-H "..."` | header; `Authorization` and `Idempotency-Key` are required |
| `--data-binary @file` | send the file unchanged |
| `-o file` | write the response to a file |
| `-w '\nHTTP %{http_code}\n'` | print the HTTP status |
| `-f` | treat HTTP ≥ 400 as an error (useful in scripts) |

Ingest rules:

- `Idempotency-Key` is mandatory. Without it: `400 INVALID_REQUEST`.
- `X-Filename` is mandatory. Without it: `400`.
- The server computes the SHA-256; the client cannot override it.
- The same file with a **different** key → `200 duplicate=true`, no second document.
- The same key while the first request is still running → `409 CONFLICT`.
- The key record lives for **24 hours**.
- `201/200` means “file stored”, not “recognised”. OCR and categories follow later if the
  instance has a processing pipeline.

### 5.5 Run an agent

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

Expected response:

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

`ok: false` is a **normal** agent answer (no data, no configured source, document not found),
not an HTTP error. Check `ok` and `summary`; do not treat a side effect as proven without
`produced` and the run journal.

`POST /agents/{id}/run` takes no idempotency key: every call creates a new `runId`. To prevent
duplicates, use a workflow with `idempotencyKey` or your own key inside `inputs`.

### 5.6 Workflow (when a multi-step process is needed)

```bash
# 1. Save a constellation as a workflow
curl -sS -X POST "$BASE_URL/api/v1/constellations/datev-pipeline/instantiate" \
  -H "Authorization: Bearer $TOKEN" | jq

# 2. Start the workflow (idempotency key in the body)
curl -sS -X POST "$BASE_URL/api/v1/workflows/<WORKFLOW_ID>/run" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "triggerType": "API",
        "triggerPayload": {"source": "embedded"},
        "idempotencyKey": "datev-2026-10-27-001"
      }' | jq

# 3. Read the state
curl -fsS "$BASE_URL/api/v1/workflow-runs/<RUN_ID>" \
  -H "Authorization: Bearer $TOKEN" | jq

# 4. Decision at a pause: APPROVE | RETRY | SKIP
curl -sS -X POST "$BASE_URL/api/v1/workflow-runs/<RUN_ID>/resume" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE"}' | jq
```

Statuses: `PENDING`, `RUNNING`, `WAITING`, `WAITING_APPROVAL`,
`PAUSED_FOR_HUMAN_REVIEW`, `COMPLETED`, `FAILED`, `CANCELLED`, `COMPENSATED`. `resume` works
only for `PAUSED_FOR_HUMAN_REVIEW`; a second call after the decision returns `409`.

### 5.7 ELSTER export

```bash
# JSON (default): rows, settings, totals, warnings
curl -fsS "$BASE_URL/api/v1/exports/elster?year=2025" \
  -H "Authorization: Bearer $TOKEN" \
  | jq '{year, summary: .summary | {rows, expensesNetto, expensesUst, warnings}}'

# CSV for Excel: semicolon separator, decimal comma
curl -fsS "$BASE_URL/api/v1/exports/elster?year=2025&format=csv" \
  -H "Authorization: Bearer $TOKEN" \
  -o elster-euer-2025.csv
```

Expected JSON (abridged):

> The values in the row are an example. The actual EÜR line, Kennzahl and DATEV account come from the instance's category catalogue.

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

The CSV columns are fixed:

```text
category_code;euer_line;euer_kz;datev_skr03;datev_skr04;booking_kind;netto;ust;brutto;vorsteuer_abziehbar;bewirtung_abziehbar_70;bewirtung_nicht_abziehbar_30;document_id;date
```

Rules:

- `year` is optional; without it the `Wirtschaftsjahr` from settings is used, otherwise the
  current calendar year.
- `format` is `json` or `csv`; anything else → `400`.
- An empty year is `200` with an empty list and zero totals, not an error.
- CSV contains no warnings; they exist only in JSON (`summary.warnings`).
- This is not tax advice: before filing, the accountant checks the rules in
  [../ELSTER_README.md](../../ELSTER_README.md).

---

## 6. Errors: meaning and action

Unified error envelope:

```json
{
  "code": "INVALID_REQUEST",
  "message": "Ohne Dateinamen lässt sich das Dokument später nicht wiederfinden",
  "correlationId": "b1f0…",
  "path": "/api/v1/documents:ingest",
  "timestamp": "2026-10-27T20:00:00Z"
}
```

| HTTP | `code` (example) | When | What to do |
|---|---|---|---|
| `400` | `INVALID_REQUEST` | missing `Idempotency-Key`/`X-Filename`, empty body, bad `year`/`format` | fix the request; do not retry |
| `401` | `UNAUTHENTICATED` | token missing, unknown, revoked or expired | issue a new token; check `Bearer` |
| `402` | `LICENSE_MISSING` | Runtime has no valid licence | install the key; health and `/license` stay reachable |
| `402` | `LICENSE_READ_ONLY` | licence expired and grace is over | replace the key; reading works, writing does not |
| `402` | `LICENSE_REVOKED` | the cabinet revoked the licence | install a new key; no grace period |
| `402` | `LICENSE_RUN_LIMIT` | `maxRunsPerMonth` exhausted | wait for the next month or change the licence |
| `402` | `SUBSCRIPTION_REQUIRED`, `PLAN_LIMIT_REACHED` | SaaS plan does not allow it | renew/change the plan |
| `403` | `FORBIDDEN` | token lacks the required scope | grant the scope and create a new token |
| `404` | `NOT_FOUND` | agent/run not found; admin API hidden | check the id and base URL |
| `409` | `CONFLICT` | key still processing; `resume` not paused; bootstrap already done | read the body and act on the code |
| `413` | `PAYLOAD_TOO_LARGE` | file above the limit | reduce the file or raise the limit deliberately |
| `422` | `VALIDATION_FAILED`, `FILE_REJECTED` | invalid fields, file rejected | fix the data; do not retry |
| `429` | `RATE_LIMITED` | token or global limit exceeded | jittered backoff, §7 |
| `502` | `DEPENDENCY_UNAVAILABLE` | storage/external system unreachable | retry with backoff, report `correlationId` |
| `500` | `INTERNAL_ERROR` | unexpected error | retry with the same `Idempotency-Key`, report `correlationId` |

Important details:

- On the agent API, `402` arrives **before** `401`: without a licence, even a call without a
  token gets `LICENSE_MISSING`, so the operator sees the real cause.
- On `documents:ingest`, `LICENSE_READ_ONLY` blocks intake, while `LICENSE_MISSING` does not:
  documents are accepted until the key arrives.
- `Retry-After` is guaranteed by the general limit; on a per-token limit the header may be
  absent. Rely on `429` and backoff.
- The error body contains no SQL, stack trace or other tenant's data.

---

## 7. Idempotency and limits

### 7.1 Idempotency

| Operation | Key | Behaviour |
|---|---|---|
| `documents:ingest` | `Idempotency-Key` + SHA-256 | same key → same `documentId`, `duplicate=true`; same content with another key → `duplicate=true`; key in progress → `409`; record 24 h |
| `workflows/{id}/run` | `idempotencyKey` in the body | same key → existing `runId`, `created=false` |
| `workflow-runs/{id}/resume` | none | repeat after a decision → `409`; read the status first |
| `agents/{id}/run` | none | every call creates a new `runId` |

Recommended key:

```text
Idempotency-Key = "<system>:<external-id>:<attempt-scope>"
```

The key must be stable for one attempt and different for a new document. Do not use a random
UUID per retry: that creates extra records even though the hash prevents a second document.

### 7.2 Limits

| Limit | Value | Where set |
|---|---|---|
| Requests per token | 60/min (Copilot), 120/min (Runtime admin) | `api_tokens.rate_limit_per_minute` |
| Total per user/IP | 600/min per instance | `burokratie.security.rate-limit` |
| Active tokens | 20 per workspace | `MAX_ACTIVE_TOKENS_PER_WORKSPACE` |
| Upload size | 100 MB by default | `BC_MAX_UPLOAD_SIZE` |
| Request size | 250 MB by default | `BC_MAX_REQUEST_SIZE` |
| Idempotency record | 24 h | `idempotency_records.expires_at` |
| Agent runs | `maxRunsPerMonth` of the licence or plan | licence/plan |

Recommendations:

- client timeout ≥ 60 s for a large file;
- on `429` and `5xx`, exponential backoff with jitter, 5–8 attempts;
- on `400/401/403/404`, do not retry;
- one document, one thread; no parallel ingests with different keys.

---

## 8. Security and rotation

- Token only on the server. Not in a browser, mobile app, URL, logs or Git.
- Separate tokens for test and production; one per integration.
- Minimal scopes: ingest only `documents:write`; reading `documents:read`; running
  `agents.run`.
- Zero-downtime rotation: create new → deploy → confirm `lastUsedAt` of the new token moves →
  revoke the old one.
- On a leak: revoke, issue a new token, inspect `agent_runs`/audit, follow the incident
  process.
- The tenant comes from the token; a `tenantId` in the body is ignored.

---

## 9. Integration checklist

- [ ] Base URL verified (`/api/v1/license` returns JSON).
- [ ] Token issued for the right workspace; scopes minimal.
- [ ] `curl /api/v1/agents` with the token → `200`.
- [ ] `documents:ingest` with a real file → `201` and `documentId`.
- [ ] Retry with the same key → `200` and the same `documentId`.
- [ ] `agents/doc-detect/run` → `ok: true/false`, handled as a business answer.
- [ ] `exports/elster?format=csv` with `exports:read` → file.
- [ ] Errors `401/402/403/409/429/5xx` handled per §6.
- [ ] Backoff and timeouts configured; no retry on `400/401/403/404`.
- [ ] `correlationId` is logged.
- [ ] Token rotation rehearsed.
