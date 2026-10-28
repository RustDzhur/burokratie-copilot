# FAQ: 25 questions about the delivery models

**Languages:** [Русский](../ru/FAQ.md) · [Deutsch](../de/FAQ.md) · [English](FAQ.md)

## What this is and who it is for

This FAQ answers the questions that come up before choosing a model and in the first days
after installation: what the base URL is, where the token comes from, what to do on an error,
and where the data goes. It is written for owners, administrators and the first support line,
not for core developers. The answers are short, but each contains a command or an exact
interface path so it can be checked. For a full procedure, every answer links to the model
document.

## What you need

| Item | Requirement | Check |
|---|---|---|
| Browser | current Chrome, Firefox, Edge or Safari | open the workspace |
| `curl` | 7.76+ | `curl --version` |
| Access | to the workspace or the Runtime server | login / SSH |
| Secret | token or `runtime.env` at hand | do not paste it into chat |
| Clock | NTP, drift below 5 minutes | `timedatectl status` |

Short answers for clients, sales and the first support line. When an answer starts with “it
depends”, it says what it depends on. All commands run on a machine with `curl`; secrets are replaced by
placeholders.

![The four delivery models](../../images/deployment-models.svg)

*Figure 1. Four models: choose by data location and responsibility for the server.*

---

### 1. What is the base URL?

There are two kinds, and they are not interchangeable:

- **Copilot in the cloud:** `https://burokratie.firmspace.de`; the API is the same host plus
  `/api/v1`, i.e. `https://burokratie.firmspace.de/api/v1`.
- **Runtime on-premises:** the address printed by the installer as `API URL:`, e.g.
  `http://192.168.1.20:8080`; the API is `<that address>/api/v1`.

If the terminal is closed, the runtime address is in the env file:

```bash
sudo grep '^BC_PUBLIC_BASE_URL=' /opt/firmspace-runtime/runtime.env
```

Check: `curl -fsS "$BASE_URL/actuator/health/readiness"` → `{"status":"UP"}`.

---

### 2. Where does the token come from?

There are three tokens in three places:

| Token | Where |
|---|---|
| Copilot API | Workspace → Account settings → API tokens → Create token |
| Bridge | Robot cabinet `https://agents.firmspace.de` → Enterprises → Connections → Add bridge → download `bridge.config.json` |
| Runtime admin | Created by the installer; lives in `runtime.env` as `BC_RUNTIME_ADMIN_TOKEN` |

`[SCREENSHOT]` The “API tokens” card and the “Token created” dialog — capture once and keep in the internal guide.

Step by step with a diagram: [README.md](README.md) §4 and
[“Where to get each token”](../../images/token-locations.svg).

---

### 3. What if the token is lost?

It cannot be restored: the server stores only the SHA-256. Revoke the old one and create a new
one.

- Copilot: **API tokens** → find by name/`prefix` → **Revoke** → **Create token**.
- Runtime: `POST /api/v1/runtime/tokens/<id>/revoke`, then a new token via
  `POST /api/v1/runtime/tokens`.
- Bridge: revoke the bridge in the cabinet, create a new one, download the new
  `bridge.config.json`, import it, restart the container.

After revocation the old token answers `401` on the next request.

---

### 4. Can a token be recovered from the database?

No. Only the hash is stored. “Show it again” is technically impossible, not a missing button.
Put the value into a password manager at creation time.

---

### 5. What happens if the licence is revoked?

The owner revokes it in the cabinet (**Licences → Revoke**). About a minute after startup and then every
6 hours the Runtime asks the public status via `LICENSE_CHECK_URL`; on `active:false` it
switches to `LICENSE_REVOKED`: agents and workflows answer `402`, with no grace period. The
check works only if `LICENSE_CHECK_URL` reaches the container (verify with
`printenv LICENSE_CHECK_URL`; the current Compose file does not pass it by default). Without
it, a revocation reaches the installation only when the key is replaced. More in
[RUNTIME.md](RUNTIME.md) §5.5.

---

### 6. Does it work without the internet?

- **Runtime:** yes, for normal operation. Outbound access is needed only if the client
  enables external AI, mail or updates. An air gap is possible, but updates and support need
  their own process.
- **Copilot SaaS:** no, it is a cloud service.
- **Bridge:** keeps working with the local queue while the cabinet is unreachable, but jobs
  and heartbeat need connectivity.

---

### 7. Where does the data go?

- **Bridge only:** stays with the client; it goes only to the configured receiver.
- **Runtime:** stays in the client environment; outward only if the client enables external
  AI, mail or an integration.
- **Embedded API:** to the instance the token belongs to (SaaS or the client's Runtime).
- **Copilot SaaS:** to the Firmspace cloud instance, region per contract (target EU).

Subprocessor list: [../compliance/subprocessors.md](../../compliance/subprocessors.md).

---

### 8. How do I verify a document reached the report?

Three levels:

1. Ingest response: HTTP `201` and `documentId`.
2. Document registry: the document is visible with category and fields.
3. Report: **Reports and analytics** → **Expenses and ELSTER mapping** → a row with that
   `document_id`; in the CSV, the `document_id` column.

If the document exists but no row appears, check the year, category and date. A document
without a category/date is not hidden; it appears with a warning. An empty year is `200` with
zeros, not an error.

---

### 9. How do the four models differ?

| Model | Engine | Data | UI |
|---|---|---|---|
| Bridge only | none | at the client | none |
| Robots Runtime | on the client's server | at the client | `/runtime-admin` page |
| Embedded API | SaaS or Runtime | wherever the instance is | the client's |
| Copilot SaaS | in the cloud | in the cloud | ready workspace |

Detailed table: [README.md](README.md) §3.

---

### 10. Do I need my own server?

No for Copilot SaaS and Embedded API in the cloud. Yes for Runtime and for the Bridge
(machine/container at the client). Runtime minimum: 4 vCPU, 8 GB RAM, 200 GB SSD; Bridge:
512 MB RAM and 2 GB disk.

---

### 11. Which versions are required?

| Component | Version | Check |
|---|---|---|
| Docker Engine | 24+ | `docker version --format '{{.Server.Version}}'` |
| Docker Compose | v2 | `docker compose version` |
| Node.js (Bridge without Docker) | 22 LTS | `node -v` |
| PostgreSQL | 17 (in the container) | `docker compose ... exec postgres psql --version` |
| `curl` | 7.76+ | `curl --version` |

---

### 12. Why does the API answer 401?

`401 UNAUTHENTICATED` means the token is missing, unknown, revoked or expired. Check:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/v1/agents"
```

If `401`, issue a new token. If `403`, the token is valid but lacks a scope.

---

### 13. Why does the API answer 402?

`402` is about the licence/plan, not a password:

- `LICENSE_MISSING` — the Runtime has no key;
- `LICENSE_READ_ONLY` — the key expired and grace is over;
- `LICENSE_REVOKED` — the cabinet revoked the licence;
- `LICENSE_RUN_LIMIT` — the monthly run allowance is exhausted;
- `SUBSCRIPTION_REQUIRED`/`PLAN_LIMIT_REACHED` — the SaaS plan.

Check `curl -fsS "$BASE_URL/api/v1/license" | jq` and [RUNTIME.md](RUNTIME.md) §5.

---

### 14. Why does the API answer 403?

The token is valid but lacks the required scope. ELSTER needs `exports:read`, running needs
`agents.run`, upload needs `documents:write`. Permissions cannot be changed after creation:
create a new token with the right set.

---

### 15. Why does the API answer 429?

A limit is exhausted: by default 60 requests per minute per token (120 for a token issued by
the Runtime admin) or 600 per minute per user/IP. Use jittered backoff; do not retry
immediately. `Retry-After` may not always be present.

---

### 16. What are scopes, and what is the minimum?

A scope is a token permission. The minimum depends on the task:

- file upload — `documents:write`;
- reading documents — `documents:read`;
- catalogue/journals — `agents.view`;
- running — `agents.run`;
- ELSTER — `exports:read`.

Do not give `settings:write` to a scanner or bridge. One token, one task.

---

### 17. How many tokens can be created?

At most **20 active** per workspace. The 21st returns an error/`409`; revoke an unused one.
Use a separate token for test and production.

---

### 18. Can Runtime and Bridge be combined?

Yes, as two whole models: Runtime on the client's server and Bridge on the machine with the
legacy software. Data stays in the client environment. This is not a “hybrid”: the engine is
not split between the client and the cloud.

---

### 19. Can Copilot and Bridge be combined?

Yes: Copilot in the cloud and Bridge at the client. Documents reach the cloud through the
configured channels. The Bridge remains transport, not an engine.

---

### 20. How is each model updated?

- **Copilot SaaS:** automatically; the client does nothing.
- **Runtime:** `git pull` → `docker compose build` → `up -d`; back up first.
- **Bridge:** load the new image, recreate the container with the same volumes, run
  `bridge test`.
- **Embedded API:** updated together with the instance it connects to.

Details: [MANAGEMENT.md](MANAGEMENT.md) §6.

---

### 21. Where are the backups?

- **Runtime:** PostgreSQL (`pg_dump -Fc`), `runtime-data`, `runtime.env`. Commands in
  [MANAGEMENT.md](MANAGEMENT.md) §7.
- **Bridge:** `config/bridge.config.json`, `config/.env`, the `.bridge` volume.
- **Copilot SaaS:** Firmspace backs up; the client exports its data before termination.
- **Embedded API:** the responsibility of the instance owner (SaaS or Runtime).

---

### 22. How long is data retained?

It depends on the model and contract. Copilot SaaS defaults to a 60-day trial and 100-day
retention after the end; exact periods are in the contract and retention settings. In Runtime,
instance settings and the contract define the period; backups follow the client's policy.
Legal documents: [../compliance/retention.md](../../compliance/retention.md).

---

### 23. Does Runtime have a web interface?

Yes. Open `http://<host>:8080/runtime-admin/`, paste `BC_RUNTIME_ADMIN_TOKEN` from
`runtime.env` and click **Check**. The page shows the installation status, installs a licence,
performs the first-time enterprise setup and manages API tokens. It needs no browser session:
every action sends `X-Runtime-Admin-Token`, and the token lives only in the tab's
`sessionStorage`. Step by step in [RUNTIME.md](RUNTIME.md) §6.

---

### 24. Can the licence be checked without a token?

Yes. `GET /api/v1/license` is public: the installer, monitoring and the first operator must
see the state before any token exists. The response contains no key, signature or secret —
only state, plan, term and `instanceId`.

```bash
curl -fsS "$BASE_URL/api/v1/license" | jq '{state, plan, expiresAt, daysRemaining}'
```

---

### 25. What do I do in an incident, and where do I report it?

1. Record time and symptom, capture logs, do not restart everything.
2. Revoke suspicious tokens; on a leak, rotate secrets.
3. Notify the owner and support using the contacts **in the contract**.
4. For a data breach, follow [../compliance/breach-plan.md](../../compliance/breach-plan.md).

Contacts, hours and SLA are not set in this delivery; they are in the contract. Do not use
invented support addresses.

---

## Further reading

- [README.md](README.md) — model selection and base URLs.
- [RUNTIME.md](RUNTIME.md) — installation, licence, tokens.
- [EMBEDDED_API.md](EMBEDDED_API.md) — HTTP integration.
- [BRIDGE_ONLY.md](BRIDGE_ONLY.md) — Bridge and 10 common failures.
- [COPILOT_SAAS.md](COPILOT_SAAS.md) — cloud workspace.
- [MANAGEMENT.md](MANAGEMENT.md) — operations and incidents.
