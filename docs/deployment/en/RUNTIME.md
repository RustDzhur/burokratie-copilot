# Robots Runtime (on-premises)

**Languages:** [Русский](../ru/RUNTIME.md) · [Deutsch](../de/RUNTIME.md) · [English](RUNTIME.md)

**Model:** the Firmspace agent engine runs on the client's server. Externally there is only
an API; no web workspace ships with this model. Documents, metadata and logs stay inside the
client's environment unless the client enables external AI, mail or another outbound channel.

![Runtime licence flow](../../images/runtime-license.svg)

*Figure 4. The key is pasted at `/runtime-admin`, stored in `/app/data/license.key` and
checked immediately. A revocation does not arrive instantly; it comes with the periodic
cabinet check.*

---

## 1. What this is and who it is for

Robots Runtime is an API application (Spring Boot, profile `runtime`) plus PostgreSQL 17,
running on the client's server in Docker. It contains agents, workflows, document ingest,
OCR, exports, licensing and an administrative API. There is no web interface: management
runs over HTTP, and the installer creates the first token. The model suits organisations that
cannot send documents to the cloud but can operate a server, PostgreSQL and backups. If you
need a ready workspace without your own infrastructure, use
[Copilot as a service](COPILOT_SAAS.md).

---

## 2. What you need

### 2.1 Server

| Profile | CPU | RAM | Disk | When |
|---|---|---|---|---|
| Small (≤ 20 API clients, ≤ 50,000 documents) | 4 vCPU | 8 GB | 200 GB SSD | pilot, small firm |
| Medium (≤ 200 clients, ≤ 500,000 documents) | 8 vCPU | 16 GB | 1 TB SSD | production |
| Large | 16 vCPU | 32 GB | by volume + separate backup disk | many parallel runs |

These are starting points, not guarantees. Real sizing depends on OCR, file size, parallel
runs and retention.

### 2.2 Software

| Component | Requirement | Check |
|---|---|---|
| OS | Linux x86_64/arm64 | `uname -a` |
| Docker Engine | 24 or newer | `docker version --format '{{.Server.Version}}'` |
| Docker Compose | v2 | `docker compose version` |
| `curl` | 7.76+ | `curl --version` |
| `git` | for the delivery sources | `git --version` |
| `openssl` | to generate secrets | `openssl version` |
| PostgreSQL | 17, included in Compose | `docker compose ... exec postgres psql --version` |
| TLS proxy | nginx/Caddy, certificate for the domain | `openssl s_client -connect <host>:443` |
| NTP | drift below 5 minutes | `timedatectl status` |

### 2.3 Network and ports

| Direction | Port | Purpose |
|---|---|---|
| Client → proxy | 443/tcp | API over HTTPS |
| Proxy → api | 8080/tcp (internal) | forwarding |
| api → postgres | 5432/tcp (internal) | database |
| api → S3 (if used) | 443/tcp | document files |
| Monitoring → api | 8080 (internal) | `/actuator/health/readiness` |

PostgreSQL and the API port must not be published to the internet. With `local-fs`, no
outbound internet is needed for operation; it is needed for updates and support.

### 2.4 Prepare before installation

| # | Item | Who | Purpose |
|---|---|---|---|
| 1 | Domain and TLS certificate, or an internal IP | client | `BC_PUBLIC_BASE_URL` |
| 2 | `LICENSE_KEY` and `LICENSE_SECRET` (≥ 32 characters) | Firmspace | licence |
| 3 | Server with Docker 24+/Compose v2 | client | runtime stack |
| 4 | Data directory, e.g. `/opt/firmspace-runtime/data` | client | `instance-id`, files |
| 5 | Backup decision | client | PostgreSQL + data directory |
| 6 | Decision on `LICENSE_CHECK_URL` (revocation check) | Firmspace + client | enable remote revocation or leave it deliberately off |

> `LICENSE_SECRET` is not a client password. It is the shared HMAC secret that signs the
> key. Without it the Runtime cannot verify the key and stays in `LICENSE_MISSING`.

---

## 3. Base URL: where to find it

The Runtime has no fixed “cloud address”. The base URL is the address passed to the
installer in `--public-url`; the installer prints it at the end as `API URL:`.

If the terminal is closed, the address is in the environment file:

```bash
# installed with --dir /opt/firmspace-runtime
sudo grep '^BC_PUBLIC_BASE_URL=' /opt/firmspace-runtime/runtime.env

# installed without --dir
sudo grep '^BC_PUBLIC_BASE_URL=' /path/to/burokratie-copilot/infra/runtime.env
```

Example output:

```text
BC_PUBLIC_BASE_URL=http://192.168.1.20:8080
```

Then:

| Item | Address |
|---|---|
| Base URL | `http://192.168.1.20:8080` |
| API | `http://192.168.1.20:8080/api/v1` |
| Health | `http://192.168.1.20:8080/actuator/health/readiness` |
| Licence | `http://192.168.1.20:8080/api/v1/license` |
| Admin API | `http://192.168.1.20:8080/api/v1/runtime` |

Check:

```bash
export BASE_URL="http://192.168.1.20:8080"
curl -fsS "$BASE_URL/actuator/health/readiness"
# {"status":"UP"}

curl -fsS "$BASE_URL/api/v1/license"
```

A second source is the bootstrap response at installation (field `apiBase`); a third is the
`API URL:` line printed by the installer. There are no other places, and the address cannot
be guessed from a hostname.

---

## 4. Installation

### 4.1 Prepare the delivery directory

```bash
cd /path/to/burokratie-copilot
ls scripts/install-runtime.sh scripts/bootstrap-runtime.sh infra/docker-compose.runtime.yml
```

All three files must exist. The installer builds the image from this checkout, so the
directory must remain after installation: it holds the Compose file and the operating
scripts.

### 4.2 One command

```bash
sudo scripts/install-runtime.sh \
  --public-url http://192.168.1.20:8080 \
  --bind-address 0.0.0.0 \
  --api-port 8080 \
  --dir /opt/firmspace-runtime \
  --tenant "Muster GmbH" \
  --email admin@example.de \
  --owner-name "Admin Muster" \
  --license-key-file /root/firmspace/license.key \
  --license-secret-file /root/firmspace/license.secret \
  --non-interactive
```

What the command does, in order:

1. Checks Docker, Compose, `curl` and the Compose file.
2. Generates the database password, AES key, runtime admin token and `instance-id`.
3. Writes `/opt/firmspace-runtime/runtime.env` with mode `600`.
4. Builds the API image and starts `postgres` + `api`.
5. Calls `scripts/bootstrap-runtime.sh`: creates tenant, workspace, owner and the first API
   token, and waits for health.
6. Prints the address, IDs, licence state and **the token once**.

Installer flags (verified against argument parsing):

| Flag | Meaning | Default |
|---|---|---|
| `--public-url` | external API URL | `http://localhost:8080` or LAN IP |
| `--domain` | shorthand: `--public-url https://<domain>` | — |
| `--bind-address` | address the API binds to | `127.0.0.1` |
| `--lan` / `--localhost` | shortcut for `0.0.0.0` / `127.0.0.1` | `127.0.0.1` |
| `--api-port` / `--port` | API port | `8080` |
| `--dir` | installation root: `<dir>/runtime.env` + `<dir>/runtime-data` | — |
| `--data-dir` | data directory without `--dir` | `infra/runtime-data` |
| `--tenant` | tenant name | `Firmspace Robots Runtime` |
| `--email` | owner email; required with `--non-interactive` | — |
| `--owner-name` | owner display name | `Runtime Owner` |
| `--license-key-file` | file with the licence key | — |
| `--license-secret-file` | file with the HMAC secret (≥ 32 characters) | — |
| `--admin-token-file` | use an existing admin token instead of generating one | — |
| `--env-file` | path to the env file | `infra/runtime.env` |
| `--project-name` | Compose project name | `firmspace-robots-runtime` |
| `--no-build` | do not build the image | — |
| `--force` | overwrite the env file on a **fresh** installation | — |
| `--non-interactive` | no prompts | — |

### 4.3 Interactive mode

Without flags, the installer asks:

```text
Public API URL [http://localhost:8080]:
Bind API to [127.0.0.1] (127.0.0.1 = local only, 0.0.0.0 = LAN):
Data directory [/path/to/burokratie-copilot/infra/runtime-data]:
Tenant name [Firmspace Robots Runtime]:
Owner e-mail (for audit/token ownership):
Owner display name [Runtime Owner]:
Licence key (empty = start in LICENSE_MISSING; agents answer 402):
Licence secret (the shared HMAC secret, min 32 chars):
```

If there is no key, leave the field empty: the installation starts in `LICENSE_MISSING`,
agents answer `402`, but health, `/api/v1/license`, the admin API and ingest work. The key can
be added later (§5.3).

### 4.4 What must appear

At the end the installer prints:

```text
Firmspace Robots Runtime — installation ready
=============================================

API URL:       http://192.168.1.20:8080
Local health:  http://127.0.0.1:8080/actuator/health/readiness
Tenant:        <uuid>
Workspace:     <uuid>
Instance id:   <uuid>
Licence:       LICENSE_ACTIVE (plan ENTERPRISE, Muster GmbH)
Licence ends:  perpetual (no expiry)
Token id:      <uuid> (prefix bc_…)
Token:         <shown once>

This token is shown ONCE. The server stores only its SHA-256 hash.
```

Store `Token` in a password manager **immediately**. If the terminal is closed, a new token
can be issued through `scripts/bootstrap-runtime.sh` or the admin API (§7); no second tenant
is created.

Without a licence, a warning appears:

```text
WARNING: no valid licence is installed: agent endpoints answer 402 until LICENSE_KEY is set
```

### 4.5 Check after installation

```bash
cd /path/to/burokratie-copilot
set -a; . /opt/firmspace-runtime/runtime.env; set +a
COMPOSE=(docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml)

"${COMPOSE[@]}" ps
curl -fsS "http://127.0.0.1:${BC_API_PORT:-8080}/actuator/health/readiness"
curl -fsS "http://127.0.0.1:${BC_API_PORT:-8080}/api/v1/license"
"${COMPOSE[@]}" logs --tail=100 api
```

Expected: both containers `Up`, health `{"status":"UP"}`, licence `LICENSE_ACTIVE` or
`LICENSE_MISSING` with a clear reason.

---

## 5. Licence

### 5.1 Structure

The licence key is a compact three-part token:

```text
base64url(header).base64url(payload).base64url(HMAC-SHA256)
```

It contains `licenseId`, `customer`, `instanceId`, `plan`, `maxAgents`,
`maxRunsPerMonth`, `features`, `issuedAt` and, optionally, `expiresAt`. It is signed with
`LICENSE_SECRET` and bound to the installation's `instance-id`. The same secret is stored on
the server, so a client with root access could theoretically mint a key. The licence is a
contractual and operational control, not DRM — this limitation is stated openly.

Show `instance-id`:

```bash
cat /opt/firmspace-runtime/runtime-data/instance-id
```

### 5.2 Perpetual by default

Commercially the licence is **perpetual**: there is no annual renewal. Technically this means
the key has **no `expiresAt` field**. Such a key is always `LICENSE_ACTIVE`; it has no grace
period and no expiry warnings.

```bash
curl -fsS "$BASE_URL/api/v1/license" \
  | jq '{state, perpetual, expiresAt, daysRemaining, revokedAt}'
# perpetual: true, expiresAt: null
```

If a term was agreed as an exception, Firmspace issues a key with `expiresAt` (for example
`2027-12-31`). Only such a key passes through `LICENSE_GRACE` and `LICENSE_READ_ONLY`. The
cabinet's standard form has no term field.

### 5.3 Install or replace the key

**The main path is the admin page, with no restart.** It verifies the key before writing and
stores it at `/app/data/license.key` with mode `600`.

1. Open `http://<host>:8080/runtime-admin/` (§6).
2. Enter the admin token and click **Check**.
3. In the **Licence** block, paste the key and click **Install**.
4. Wait for **Licence installed**. The key is already active in the running API.

**Automation alternative — the env file.** It requires a restart:

1. Open the env file:

   ```bash
   sudo editor /opt/firmspace-runtime/runtime.env
   ```

2. Fill in:

   ```dotenv
   LICENSE_KEY=<key from the cabinet>
   LICENSE_SECRET=<HMAC secret, ≥ 32 characters>
   ```

3. Restart the API:

   ```bash
   cd /path/to/burokratie-copilot
   sudo docker compose --env-file /opt/firmspace-runtime/runtime.env \
     -f infra/docker-compose.runtime.yml up -d api
   ```

4. Verify:

   ```bash
   curl -fsS "http://127.0.0.1:8080/api/v1/license" | jq
   ```

> **Do not mix the two paths.** If `LICENSE_KEY` is set in `runtime.env`, it takes precedence
> over the file installed from the page and wins again after a restart. To manage the key via
> `/runtime-admin`, remove the `LICENSE_KEY` line and keep
> `LICENSE_KEY_FILE=/app/data/license.key` (the Compose default). `LICENSE_SECRET` is not
> entered on the page; it stays in the env file.

> **If the key will only be installed through the page.** Pass `--license-secret-file` at
> installation time but not `--license-key-file`: `LICENSE_SECRET` is needed for verification,
> while `LICENSE_KEY` stays empty. The installation starts in `LICENSE_MISSING`, and the first
> key is pasted on the page.


Check which file is used:

```bash
docker exec firmspace-robots-runtime-api printenv LICENSE_KEY_FILE
ls -l /opt/firmspace-runtime/runtime-data/license.key
```

### 5.4 States

| State | Meaning | What to do |
|---|---|---|
| `LICENSE_ACTIVE` | key valid; always for a perpetual key | work |
| `LICENSE_GRACE` | term expired, 14-day grace; only with `expiresAt` | replace the key before grace ends |
| `LICENSE_READ_ONLY` | grace over; only with `expiresAt` | reading works, writing and runs do not |
| `LICENSE_REVOKED` | the cabinet revoked the licence | install a new key; no grace period |
| `LICENSE_MISSING` | no key or invalid key | check `reason` in `/api/v1/license`, install the key |
| `LICENSE_NOT_ENFORCED` | enforcement off | development/test only; not used in Runtime |

`LICENSE_GRACE_DAYS` defaults to `14`. `LICENSE_MISSING` does not block document ingest:
documents are stored until the key arrives. `LICENSE_REVOKED` closes the agent business
surface (`402` with `LICENSE_REVOKED`) but leaves document ingest available: intake is not
execution.

### 5.5 Revoking a licence

1. Open the owner cabinet (for bridges and licences:
   `https://agents.firmspace.de`).
2. Go to **Licences**.
3. Find the licence by `licenseId` or `instanceId`.
4. Click **Revoke** and give a reason.

`[SCREENSHOT]` The licence list and revoke dialog in the owner cabinet.

**How it reaches the Runtime.** About a minute after startup and then every **6 hours** the API asks the
cabinet's public licence status (`LICENSE_CHECK_URL`), sending only `licenseId` and
`instanceId`. An `active:false` answer switches the installation to `LICENSE_REVOKED` with no
grace period. The last answer is cached in `/app/data/license-state.json`, so a restart
during a cabinet outage does not forget the revocation. If the cabinet is unreachable, the
last known state is kept: a vendor outage does not switch the customer off.

**What to verify before promising it.** The current Compose file does not pass
`LICENSE_CHECK_URL` to the container by default. Check:

```bash
docker exec firmspace-robots-runtime-api printenv LICENSE_CHECK_URL
```

An empty output means the remote check is off and a cabinet revocation does not reach this
installation. To enable it, pass the variable into the `api` service environment:

```dotenv
LICENSE_CHECK_URL=https://agents.firmspace.de/api/license/status
```

and restart `api`. Firmspace provides the exact status URL with the cabinet; the value above
is the example from the code. Without it, only key replacement and expiry (if set) apply.

### 5.6 Where the owner gets the key

1. In the owner cabinet: **Licences → Issue**.
2. Enter `customer`, `instanceId` (from `runtime-data/instance-id`), plan and limits.
3. For a perpetual licence, no `expiresAt` is set. If a term was agreed, Firmspace issues
   the key with `expiresAt` (the cabinet's standard form may not show a term field).
4. Copy `LICENSE_KEY` and hand it over with `LICENSE_SECRET` over a secure channel.
5. The client installs the key through `/runtime-admin` (§6) or `runtime.env` (§5.3).

`[SCREENSHOT]` The issue form: customer, instanceId, plan, limits, features.

---

## 6. Admin web interface

### 6.1 What it is

The trailing slash matters: the page loads `styles.css` and `app.js` relative to the
`/runtime-admin/` directory. Without it the controller still returns the page, but the style
and script may fail to load.

The delivery includes an admin page: `http://<host>:8080/runtime-admin/`. It belongs to the
runtime profile and works without a browser session: every action sends the
`X-Runtime-Admin-Token` header to `/api/v1/runtime/**`, where authorisation happens. The page
holds no data before sign-in; the token is kept only in the tab's `sessionStorage`, never in
`localStorage`.

The page provides:

- **Installation status**: version, `instanceId`, health, setup state, tenant, workspace,
  owner email, active tokens.
- **Licence**: paste, install, and see state, term and warnings.
- **First-time setup**: create enterprise, workspace, owner and the first token.
- **API tokens**: issue, revoke, copy the value once.
- **Connect the bridge**: a ready `forward.copilot` block and Docker commands.

### 6.2 Step by step

1. Open `http://<host>:8080/runtime-admin/`.
2. In the **Admin token** field paste `BC_RUNTIME_ADMIN_TOKEN` from `runtime.env` and click
   **Check**. The **Installation status** appears.
3. **Licence** block: paste the key into **Licence key** and click **Install**. Wait for
   **Licence installed**; below it you see the state, the file `/app/data/license.key` and
   notes for the operator.
4. If the installation is not set up yet, use **First-time setup**: fill **Enterprise name**,
   **Owner e-mail**, optionally **Owner name**, and click **Create installation**. The token
   is shown once — copy it.
5. **API tokens** block: enter a **Token name**, tick the scopes (`documents:write` for the
   bridge, `agents.run` for runs, `settings:write` only for trusted integrations), click
   **Create token** and copy the value immediately.
6. **Connect the bridge** shows `forward.copilot` and the commands; replace the image tag with
   your version.

`[SCREENSHOT]` Four screens: admin-token sign-in, status, licence installation, first-time
setup and tokens.

### 6.3 The same through the API

The admin API takes the `X-Runtime-Admin-Token` header, not a Bearer token.

```bash
cd /path/to/burokratie-copilot
set -a; . /opt/firmspace-runtime/runtime.env; set +a
ADMIN=(-H "X-Runtime-Admin-Token: $BC_RUNTIME_ADMIN_TOKEN")
BASE="http://127.0.0.1:${BC_API_PORT:-8080}"

# 1. State: bootstrapped, version, tenant, licence, adminTokenConfigured
curl -fsS "${ADMIN[@]}" "$BASE/api/v1/runtime/status" | jq

# 2. Install a licence without a restart
curl -sS -X POST "${ADMIN[@]}" -H 'Content-Type: application/json' \
  "$BASE/api/v1/runtime/license" \
  -d '{"licenseKey":"<key from the cabinet>"}' | jq

# 3. Token list without secrets
curl -fsS "${ADMIN[@]}" "$BASE/api/v1/runtime/tokens" | jq

# 4. New token
curl -sS -X POST "${ADMIN[@]}" -H 'Content-Type: application/json' \
  "$BASE/api/v1/runtime/tokens" \
  -d '{"name":"bridge-office-1","scopes":["agents.view","agents.run","documents:write"]}' | jq

# 5. Revoke a token
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "${ADMIN[@]}" \
  "$BASE/api/v1/runtime/tokens/<TOKEN_ID>/revoke"
```

Expected responses:

- `GET /status` → `bootstrapped`, `version`, `tenantId`, `workspaceId`, `license.state`,
  `adminTokenConfigured`.
- `POST /license` → `installed:true`, `keyFile`, `state`, `warnings`, `license`.
- `POST /tokens` → HTTP `201` with `id`, `token`, `prefix`, `scopes`,
  `rateLimitPerMinute`.
- `POST /tokens/{id}/revoke` → HTTP `204`.

If `BC_RUNTIME_ADMIN_TOKEN` is empty, the admin endpoints answer `404` (surface hidden). If
the token is wrong, they answer `401`.

---

## 7. First access, enterprise and tokens

### 7.1 What the installer already created

On the first run, the bootstrap creates in one transaction:

- a tenant with the given name;
- a default workspace;
- an owner without a password (identity for audit and token ownership, not a login);
- the first API token with scopes `agents.view`, `agents.run`, `documents:write`;
- a limit of `120` requests per minute for that token.

A second `POST /api/v1/runtime/bootstrap` returns `409`: the installation is already
bootstrapped. Issue new tokens through `/api/v1/runtime/tokens`.

### 7.2 Creating an enterprise manually

```bash
curl -sS -X POST "${ADMIN[@]}" -H 'Content-Type: application/json' \
  "$BASE/api/v1/runtime/bootstrap" \
  -d '{
        "tenantName": "Muster GmbH",
        "ownerEmail": "admin@example.de",
        "ownerName": "Admin Muster"
      }' | jq
```

The `201` response contains `tenantId`, `workspaceId`, `ownerUserId`, `tokenId`, `token`,
`prefix`, `scopes`, `rateLimitPerMinute`, `apiBase`. The `token` field exists only in this
response.

### 7.3 Creating the first API token manually

```bash
curl -sS -X POST "${ADMIN[@]}" -H 'Content-Type: application/json' \
  "$BASE/api/v1/runtime/tokens" \
  -d '{"name":"ingest-scanner","scopes":["documents:write"]}' | jq
```

Verify with the token:

```bash
export TOKEN='<token from the response>'
curl -fsS "$BASE/api/v1/agents" -H "Authorization: Bearer $TOKEN" | jq '.[0]'
```

### 7.4 Scopes

| Scope | Opens |
|---|---|
| `documents:write` | `POST /api/v1/documents:ingest`, plus document creation/review |
| `documents:read` | reading documents (`GET /api/v1/documents...`) |
| `agents.view` | agent catalogue, run journals, reading workflows and constellations |
| `agents.run` | running agents, workflows, instantiating constellations, `resume` |
| `exports:read` | `GET /api/v1/exports/elster` |
| `settings:read` | reading settings |
| `settings:write` | changing rules, date terms and retention |

Rules:

- One token per integration. Do not give `settings:write` to a bridge or scanner.
- Permissions cannot be changed after creation: revoke and create a new token.
- At most **20 active tokens** per workspace.
- The token is shown once; only its SHA-256 is stored. If lost: revoke and create a new one.
- The tenant comes from the token; a header cannot override it.

---

## 8. Checking that everything works

```bash
export BASE_URL="http://192.168.1.20:8080"
export TOKEN='<your token>'

# licence
curl -fsS "$BASE_URL/api/v1/license" | jq '{state, perpetual, plan, expiresAt, revokedAt}'

# agent catalogue
curl -fsS "$BASE_URL/api/v1/agents" -H "Authorization: Bearer $TOKEN" | jq '.[0]'

# ingest a file
curl -sS -X POST "$BASE_URL/api/v1/documents:ingest" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: smoke-$(date +%s)" \
  -H "X-Filename: rechnung.pdf" \
  -H "Content-Type: application/pdf" \
  --data-binary @rechnung.pdf | jq

# run an agent
curl -sS -X POST "$BASE_URL/api/v1/agents/doc-detect/run" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"trigger":"manual","inputs":{"fileName":"rechnung.pdf"}}' | jq
```

Expected: licence `LICENSE_ACTIVE`, catalogue as an array, ingest `201` with `documentId`,
run `ok: true` or `ok: false` with a clear `summary`. `ok: false` is a normal agent answer,
not an HTTP error.

---

## 9. Connecting the Bridge to the Runtime

There are two different paths; do not confuse them.

### 9.1 Bridge as a document source (forward)

The Bridge can additionally send a captured file to a Copilot-compatible ingest. This works
with the Runtime too:

```jsonc
{
  "console": {
    "url": "https://agents.firmspace.de",
    "token": "${BRIDGE_TOKEN}"
  },
  "forward": {
    "copilot": {
      "enabled": true,
      "url": "http://192.168.1.20:8080",
      "token": "${RUNTIME_INGEST_TOKEN}",
      "timeoutMs": 30000
    }
  }
}
```

- `url` — the Runtime base URL; the Bridge appends `/api/v1/documents:ingest`.
- `token` — a Runtime API token with scope `documents:write`.
- Delivery is best-effort: a Runtime failure does not lose the file locally.
- The event must contain `contentBase64`; for `folder`/`email` enable `includeContent: true`.

### 9.2 Direct calls from your own code

Without the Bridge, call ingest directly — the contract is in
[EMBEDDED_API.md](EMBEDDED_API.md). For the Runtime, the base URL is the address from §3.

> The runtime profile has no console endpoints `/api/bridge/*`. The Bridge cannot replace the
> robot cabinet: it still needs `console.url` for heartbeat and jobs. A direct
> “Bridge ↔ Runtime” connection without a cabinet is not part of this delivery.

---

## 10. Update, backup, restore

### 10.1 Update

```bash
cd /path/to/burokratie-copilot
git pull                      # or unpack a new delivery archive
docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml build
docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml up -d
curl -fsS "http://127.0.0.1:8080/actuator/health/readiness"
```

Back up before updating. Flyway is forward-only: a schema rollback is only possible by
restoring the dump. The licence and `instance-id` live outside the image and survive.

### 10.2 Backup

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

`runtime.env` contains the database password, AES key and admin token; the licence key and
state live in `runtime-data` (`license.key`, `license-state.json`). Encrypt the backup before
it leaves the server.

### 10.3 Restore

```bash
"${COMPOSE[@]}" stop api
"${COMPOSE[@]}" exec -T postgres pg_restore -U "$BC_DB_USER" -d "$BC_DB_NAME" \
  --clean --if-exists --no-owner < database.dump
sudo tar xzf runtime-data.tgz -C "$(dirname "$BC_DATA_DIR")"
"${COMPOSE[@]}" up -d api
curl -fsS "http://127.0.0.1:8080/actuator/health/readiness"
curl -fsS "http://127.0.0.1:8080/api/v1/license" | jq '.state'
```

More in [MANAGEMENT.md](MANAGEMENT.md) and [../runbook.md](../../runbook.md).

---

## 11. If it does not work

| Symptom | Likely cause | What to do |
|---|---|---|
| Installer: `docker daemon is not reachable` | Docker not started or no permission | `sudo systemctl start docker`; check the `docker` group |
| Installer: `runtime.env already exists` | installation already exists | for a new token use `scripts/bootstrap-runtime.sh`; `--force` only on a fresh install |
| `API did not become healthy in 240s` | API did not start, DB unreachable | `docker compose ... logs --tail=120 api`; `ps`; check the DB password |
| `/actuator/health/readiness` = 503 | migrations/DB/disk | API logs, `df -h`, `docker compose ... ps` |
| agents answer `402 LICENSE_MISSING` | no key | §5.3; check `reason` in `/api/v1/license` |
| agents answer `401` | token wrong/revoked | issue a new token; check `Authorization: Bearer` |
| agents answer `403` | token lacks a scope | grant `agents.view`/`agents.run` |
| agents answer `402 LICENSE_REVOKED` | the cabinet revoked the licence | install a new key; check `LICENSE_CHECK_URL` and `revokedAt` |
| `/runtime-admin` answers `404` | the API is not running the runtime profile | check `SPRING_PROFILES_ACTIVE=runtime` and the API logs |
| admin API answers `404` | `BC_RUNTIME_ADMIN_TOKEN` empty | check the env file and restart the API |
| admin API answers `401` | wrong admin token | take the full value from `runtime.env` |
| port already in use | another process on 8080 | `--api-port 8081` or free the port |
| API cannot write `/app/data` | directory permissions | `sudo chown -R 10001:10001 /opt/firmspace-runtime/runtime-data` |

---

## 12. Acceptance checklist

- [ ] Docker 24+ and Compose v2 verified with commands.
- [ ] `--public-url` matches the address clients actually use.
- [ ] TLS proxy in front of the API; port 8080 not published to the internet.
- [ ] `runtime.env` has mode `600` and is included in backups.
- [ ] `instance-id` saved; the licence is bound to it.
- [ ] `http://<host>:8080/runtime-admin/` opens; the admin token is accepted.
- [ ] `GET /api/v1/license` → `LICENSE_ACTIVE`; for perpetual `perpetual: true`,
      `expiresAt: null`.
- [ ] Key installed through the page or the env file; `/app/data/license.key` has mode `600`.
- [ ] The `LICENSE_CHECK_URL` decision is recorded: revocation check on or deliberately off.
- [ ] Bootstrap created exactly one tenant; `bootstrapped: true`.
- [ ] First API token in a secret store, not a ticket.
- [ ] `POST /documents:ingest` and `POST /agents/doc-detect/run` verified.
- [ ] Database and `runtime-data` backed up, restore tested.
- [ ] Monitoring for health/licence/disk configured.
- [ ] Server owner and update procedure recorded.
