# Managing the deliveries

**Languages:** [Русский](../ru/MANAGEMENT.md) · [Deutsch](../de/MANAGEMENT.md) · [English](MANAGEMENT.md)

**For:** contract owner, client IT administrator, implementation engineer and support. This
document covers the **shared** operations of all four models: licences, tokens, updates,
backups, logs and incidents. Installation details are in [BRIDGE_ONLY.md](BRIDGE_ONLY.md),
[RUNTIME.md](RUNTIME.md), [EMBEDDED_API.md](EMBEDDED_API.md) and
[COPILOT_SAAS.md](COPILOT_SAAS.md).

**Principle:** every secret, log and obligation has an **owner**. Without an owner it is not
an operation, it is a hope.

---

## 1. What this is and who it is for

This document is for everyone responsible for a running delivery after acceptance: the
contract owner, the client administrator and support. It explains who is accountable for
what, how to issue and revoke a licence, how to update each component, where backups and logs
live, and what to do in the first minutes of an incident. Installation instructions live in
the model documents. All commands are verified against the delivery's scripts and code;
places that need a live interface are marked `[SCREENSHOT]`.

---

## 2. What you need

### 2.1 Access and artefacts

| Item | Purpose | Where |
|---|---|---|
| `infra/runtime.env` or `<dir>/runtime.env` | DB password, AES key, admin token, licence | safe/password manager, copy in backup |
| `bridge.config.json` + `.env` | bridge token and settings | bridge machine, file `600` |
| `LICENSE_KEY`, `LICENSE_SECRET` | Runtime licence | safe |
| `instance-id` | licence binding | `runtime-data/instance-id` |
| API tokens | integrations | client secret store |
| Access to the owner cabinet | licences, enterprises, bridges | at the owner |
| Support contacts and SLA | escalation | contract |

### 2.2 Tools

| Tool | Version | Check |
|---|---|---|
| `docker` | 24+ | `docker version` |
| `docker compose` | v2 | `docker compose version` |
| `curl` | 7.76+ | `curl --version` |
| `jq` | any | `jq --version` |
| `pg_dump`/`pg_restore` | 17 (in the container) | `docker compose ... exec postgres pg_dump --version` |
| `openssl` | any | `openssl version` |

---

## 3. Who is responsible for what

| Task | Owner (Firmspace) | Client | Implementation engineer |
|---|---|---|---|
| Issue/revoke a licence | **R** | C | I |
| Keep `LICENSE_SECRET` and `runtime.env` | C | **R** | C |
| Install Runtime | C | A | **R** |
| Server, disk, network, OS | I | **R** | C |
| Update Runtime | C | A | **R** |
| Backups and restore checks | I | **R** | C |
| Issue API tokens | I | **R** | C |
| Install/update the Bridge | C | A | **R** |
| Bridge: folders, mail, database | I | **R** | C |
| Monitoring and first line | I | **R** | C |
| Security incident | **R** | **R** | C |
| Copilot cloud workspace | **R** | C | I |

`R` — does, `A` — approves, `C` — consulted, `I` — informed. Two `R`s are possible when the
responsibility is split by environment (e.g. a security incident).

---

## 4. Licences

### 4.1 Licensing model

A Runtime licence is an HMAC-signed key bound to the `instance-id`. The signature is verified
offline; in addition, an installation can ask the cabinet about revocation
(`LICENSE_CHECK_URL`, every 6 hours). Commercially the licence is **perpetual by default**:
no annual renewal. Technically that is a key **without `expiresAt`**: always
`LICENSE_ACTIVE`, with no grace period. A fixed-term licence carries `expiresAt` and goes
through grace/read-only.

`LICENSE_SECRET` is the shared signing secret. It must match at the owner and the client,
otherwise verification fails. It must not go into the Bridge, the `/runtime-admin` page or
Git.

### 4.2 Issue a licence

1. Open the owner cabinet (for licences and bridges:
   `https://agents.firmspace.de`).
2. Go to **Licences**.
3. Click **Issue**.
4. Fill in:
   - `customer` — the client's legal name;
   - `instanceId` — the value from `runtime-data/instance-id` (the client sends it at
     installation);
   - `plan` — e.g. `ENTERPRISE`;
   - `maxAgents`, `maxRunsPerMonth` — limits;
   - `features` — if needed.
5. Copy `LICENSE_KEY` and hand it over with `LICENSE_SECRET` over a secure channel.

`[SCREENSHOT]` The issue form: customer, instanceId, plan, limits, features.
The standard licence is perpetual; the form may not show a term field. If a term was agreed, Firmspace issues the key with `expiresAt`.

The client installs the key through `/runtime-admin` (**Licence → Install**) or through
`runtime.env` ([RUNTIME.md](RUNTIME.md) §5.3). The web path needs no restart and writes
`/app/data/license.key`. Check:

```bash
curl -fsS "http://127.0.0.1:8080/api/v1/license" \
  | jq '{state, perpetual, customer, expiresAt, daysRemaining, revokedAt}'
```

Expected: `state: "LICENSE_ACTIVE"`. For perpetual: `perpetual: true`, `expiresAt: null`.

### 4.3 Revoke a licence

1. Owner cabinet → **Licences**.
2. Find the entry by `licenseId` or `instanceId`.
3. Click **Revoke**, give a reason, confirm.

`[SCREENSHOT]` The licence list and revoke dialog.

**What happens technically.** About a minute after startup and then every 6 hours the installation asks the
public licence status via `LICENSE_CHECK_URL` and, on `active:false`, switches to
`LICENSE_REVOKED`: agents, workflows, exports and settings answer `402`, with no grace.
Document ingest stays available. The last answer is cached in
`/app/data/license-state.json` and survives a restart; a cabinet outage keeps the last known
state.

**Limit of the current delivery.** The Compose file does not pass `LICENSE_CHECK_URL` to the
container by default. Check:

```bash
docker exec firmspace-robots-runtime-api printenv LICENSE_CHECK_URL
```

An empty output means a cabinet revocation does not reach the installation. Pass the variable
into the `api` service environment (e.g.
`https://agents.firmspace.de/api/license/status`), restart `api`, and check
`revocationCheckedAt` in `/api/v1/license`. Do not promise instant revocation: the delay is
up to 6 hours.

### 4.4 Check term and states

| State | Meaning | Action |
|---|---|---|
| `LICENSE_ACTIVE` | key valid; always for perpetual | normal operation |
| `LICENSE_GRACE` | term expired, 14-day grace (only with `expiresAt`) | replace the key |
| `LICENSE_READ_ONLY` | grace over | replace the key; writing and runs blocked |
| `LICENSE_REVOKED` | the cabinet revoked the licence | new key; no grace |
| `LICENSE_MISSING` | no/invalid key | check `reason`, install the key |

Agent API response headers: `X-License-Status`, `X-License-Plan`, `X-License-Expires-At`,
`X-License-Warning`. They are convenient for monitoring.

### 4.5 Licence inventory

Keep a table: `licenseId`, `customer`, `instanceId`, `plan`, `expiresAt` (empty for
perpetual), issue date, issued by, revocation date, reason. Without `instanceId` you cannot prove the key was issued for that
installation.

---


![Where to get each token](../../images/token-locations.svg)

*Figure 3. Three different tokens: Copilot API, Bridge, Runtime admin. They are not interchangeable.*

## 5. Tokens and secrets

### 5.1 Inventory

| Secret | Model | Where | Issued by | Rotation | Revocation |
|---|---|---|---|---|---|
| `BRIDGE_TOKEN` | Bridge | `.env` 600 | robot cabinet | on loss/change | cabinet → bridge → Revoke |
| Copilot API token | SaaS/Embedded | secret store | cabinet → API tokens | 90 days or on loss | `POST /api/v1/api-tokens/{id}/revoke` |
| Runtime API token | Runtime | secret store | admin API/installer | 90 days or on loss | `POST /api/v1/runtime/tokens/{id}/revoke` |
| `BC_RUNTIME_ADMIN_TOKEN` | Runtime | `runtime.env` 600 | installer | on operator change/loss | replace env + restart |
| `LICENSE_KEY`/`LICENSE_SECRET` | Runtime | safe + env | owner | on reissue | replace the key |
| `LICENSE_CHECK_URL` | Runtime | `runtime.env`/Compose | Firmspace | on cabinet change | change env + restart |
| `/app/data/license.key`, `/app/data/license-state.json` | Runtime | data dir 600 | `/runtime-admin` | — | delete + new key |
| `BC_ENCRYPTION_KEY` | Runtime | env/safe | client/us | **not hot-rotated** | re-encryption |
| DB password | Runtime | `runtime.env` | installer | per policy | rebuild |

### 5.2 Rules

- One token per integration or bridge. A shared token cannot be revoked selectively.
- The token is shown once; only its SHA-256 is stored.
- Never store tokens in `bridge.config.json`, Git, chats, tickets or screenshots.
- Zero-downtime rotation: new → deploy → check `lastUsedAt` → revoke the old one.
- On a leak: revoke within 15 minutes, inspect logs, notify per §9.

### 5.3 Issuing and revoking

**Copilot API token:** cabinet → Account settings → API tokens → Create token. Revoke with
the **Revoke** button in the list.

**Runtime API token:**

```bash
set -a; . /opt/firmspace-runtime/runtime.env; set +a
ADMIN=(-H "X-Runtime-Admin-Token: $BC_RUNTIME_ADMIN_TOKEN")
BASE="http://127.0.0.1:8080"

curl -fsS "${ADMIN[@]}" "$BASE/api/v1/runtime/tokens" | jq
curl -sS -X POST "${ADMIN[@]}" -H 'Content-Type: application/json' \
  "$BASE/api/v1/runtime/tokens" \
  -d '{"name":"integration-x","scopes":["documents:write"]}' | jq
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "${ADMIN[@]}" \
  "$BASE/api/v1/runtime/tokens/<TOKEN_ID>/revoke"
```

**Bridge token:** robot cabinet → Enterprises → enterprise → Connections → bridge →
Revoke. The bridge then gets `401`; create a new one and update `.env`.

### 5.4 Runtime admin token

- Generated by the installer (`openssl rand -hex 32`) and written to `runtime.env`.
- **Not printed** to the terminal. Show it:

  ```bash
  sudo grep '^BC_RUNTIME_ADMIN_TOKEN=' /opt/firmspace-runtime/runtime.env | cut -d= -f2-
  ```

- Sent only in the `X-Runtime-Admin-Token` header.
- If the value is empty, admin endpoints answer `404` (surface hidden).
- Rotation: `openssl rand -hex 32` → replace in `runtime.env` → restart `api` → check
  `GET /api/v1/runtime/status`. The old token is immediately invalid.
- Never give it to the Bridge or client software: it is an operator key.

---

## 6. Updates

### 6.1 Copilot SaaS

Firmspace updates it. The client does nothing; changes arrive with the cloud update. If the
interface changes, check that the flows in [COPILOT_SAAS.md](COPILOT_SAAS.md) still work and
update internal instructions.

### 6.2 Robots Runtime

```bash
cd /path/to/burokratie-copilot
git pull                          # or unpack a new delivery archive
docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml build
docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml up -d
curl -fsS "http://127.0.0.1:8080/actuator/health/readiness"
curl -fsS "http://127.0.0.1:8080/api/v1/license" | jq '.state'
```

Rules:

- Back up the database and `runtime-data` before updating.
- Flyway is forward-only; a schema rollback is only possible by restoring the dump.
- `LICENSE_KEY` and `instance-id` are outside the image and survive.
- Do not change `BC_ENCRYPTION_KEY` together with an update.
- Window: the API is unavailable during the restart (seconds to minutes). HA needs a second
  node; that is not part of the base delivery.
- Record the image/digest, git revision, date and operator in the ticket.

Rollback:

```bash
BC_API_IMAGE='firmspace/robots-runtime-api:<previous>' \
  docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml up -d --force-recreate api
```

If the migration is incompatible, restore the database from the backup and start the previous
image.

### 6.3 Firmspace Bridge

```bash
docker load -i firmspace-bridge-<new-version>-<architecture>.tar.gz
docker stop firmspace-bridge && docker rm firmspace-bridge
docker run -d --name firmspace-bridge --restart unless-stopped \
  -v /opt/firmspace-bridge/config:/data \
  -v firmspace-bridge-state:/data/.bridge \
  -v /srv/legacy/incoming:/data/data/incoming \
  --env-file /opt/firmspace-bridge/config/.env \
  firmspace-bridge:<new-version>
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --details
```

Read the release notes first: the configuration format may change. Do not delete the
`.bridge` volume — it holds the queue and journal.

---

## 7. Backups

### 7.1 What and how often

| Item | Why | Frequency | Retention |
|---|---|---|---|
| PostgreSQL Runtime | tenant, documents, runs, audit | daily + before updates | at least 30 days, per contract |
| `runtime-data` (blobs, instance-id) | files and licence binding | daily | same as DB |
| `runtime.env` | DB password, AES key, admin token, licence | on change | safe, separate from DB |
| Bridge config and `.env` | rebuild the bridge | on change | client secret store |
| TLS certificates and proxy config | HTTPS | on change | safe |

### 7.2 Runtime commands

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

Verify the dump without restoring:

```bash
docker run --rm -v "$BACKUP:/backup:ro" postgres:17 \
  pg_restore --list /backup/database.dump | head
```

### 7.3 Restore

```bash
"${COMPOSE[@]}" stop api
"${COMPOSE[@]}" exec -T postgres pg_restore -U "$BC_DB_USER" -d "$BC_DB_NAME" \
  --clean --if-exists --no-owner < "$BACKUP/database.dump"
sudo tar xzf "$BACKUP/runtime-data.tgz" -C "$(dirname "$BC_DATA_DIR")"
"${COMPOSE[@]}" up -d api
curl -fsS "http://127.0.0.1:8080/actuator/health/readiness"
curl -fsS "http://127.0.0.1:8080/api/v1/license" | jq '.state'
```

Run a **restore drill** on a separate environment once a quarter. A backup that has never been
restored is a hope, not a backup.

### 7.4 SaaS

Firmspace handles backups; the client is responsible for exporting its data before
termination. Document and ELSTER export go through UI/API, not SQL. Retention after contract
end follows the contract and the retention setting.

---

## 8. Logs and monitoring

### 8.1 What to watch

| Source | What | Command/place |
|---|---|---|
| Runtime health | `UP`/`DOWN` | `curl -fsS .../actuator/health/readiness` |
| Licence | state, term | `curl -fsS .../api/v1/license` |
| Agent headers | `X-License-Status` | any `/api/v1/agents` response |
| API logs | errors, start, migrations | `docker compose ... logs -f api` |
| PostgreSQL logs | errors, disk | `docker compose ... logs postgres` |
| Metrics | `metrics`, `prometheus` (if enabled) | `/actuator/metrics` |
| Disk | free space | `df -h`, alert < 20% |
| Bridge | heartbeat, queue | cabinet + `.bridge/journal` |
| Cabinet audit | sign-ins, changes | audit UI |
| SaaS | availability | external monitoring `https://...` |

### 8.2 Alerts (minimum)

- `health/readiness` not `UP` for more than 2 minutes;
- `/api/v1/license` not `LICENSE_ACTIVE` (except agreed grace) or `LICENSE_REVOKED`;
- DB disk < 20%;
- no backup in the last 24 hours;
- bridge offline for more than 10 minutes;
- spike in `401/403/429` on the API.

---

## 9. Incidents

### 9.1 First 15 minutes

1. **Record** time, symptom and affected components.
2. **Do not restart everything**: first capture `docker logs`, `ps`, `df -h` and the last 200
   log lines.
3. **Limit the damage:** revoke the suspicious token, close unnecessary ports, replace the
   secret on a leak.
4. **Notify** the owner and support using the contacts in the contract.
5. **Record** the `correlationId` from the API response and the `documentId`/`runId` if a
   specific object is affected.

### 9.2 Common scenarios

| Symptom | Check | Action |
|---|---|---|
| API does not respond | `docker compose ps`, `logs api`, `df -h` | start the container, free disk, restore from backup |
| All agents answer `402` | `/api/v1/license`, `reason` | install/replace the key; on `LICENSE_REVOKED` install a new key |
| Token stopped working | `401` vs `403` | `401` — revoked/expired; `403` — scope missing |
| Bridge offline | `bridge doctor`, TLS, DNS | see [BRIDGE_ONLY.md](BRIDGE_ONLY.md) §7 |
| Documents do not appear | queue, ingest, OCR | check `documentId`, logs, processing status |
| Suspected token leak | logs, `lastUsedAt` | revoke, reissue, review access |

### 9.3 Data breach

1. Immediately revoke all tokens and change `BC_RUNTIME_ADMIN_TOKEN`.
2. Preserve logs and evidence; do not “clean” journals.
3. Notify the DPO/owner; deadlines and process in
   [../compliance/breach-plan.md](../../compliance/breach-plan.md).
4. Determine affected documents/tenants.
5. After remediation: review and record in the incident log.

### 9.4 Contacts

Support contacts, hours, SLA and escalation are **not in the code** and belong in the
contract. Fill the table before acceptance:

| Level | Who | Channel | Response time |
|---|---|---|---|
| First line | `<from the contract>` | `<phone/email>` | `<SLA>` |
| Engineer | `<from the contract>` | `<channel>` | `<SLA>` |
| Security/DPO | `<from the contract>` | `<channel>` | `<SLA>` |

Do not invent these values in a client document.

---

## 10. Decommissioning

1. Inform users and agree a date.
2. Export documents and ELSTER data (UI/API).
3. Revoke all API and bridge tokens.
4. Disconnect bridges, delete `.env` and configurations.
5. Stop the Runtime; take a final backup; delete data per contract.
6. Confirm deletion in writing.

---

## 11. Operations checklist

- [ ] Inventory of licences, tokens and secrets filled in.
- [ ] `runtime.env`/`.env` with mode `600`, copy in the safe.
- [ ] Backups configured and restore tested.
- [ ] Monitoring for health/licence/disk/backup enabled; alert on `LICENSE_REVOKED`.
- [ ] The `LICENSE_CHECK_URL` decision is recorded (revocation check on or off).
- [ ] Token rotation scheduled.
- [ ] Contacts and SLA recorded in the contract.
- [ ] Update and rollback procedure tested on a staging environment.
- [ ] RACI owners assigned.
