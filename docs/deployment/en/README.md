# Delivery models: a guide for people without prior experience

**Languages:** [Русский](../ru/README.md) · [Deutsch](../de/README.md) · [English](README.md)

**Current as of:** 28 October 2026.
**Verified against:** `scripts/install-runtime.sh`, `scripts/bootstrap-runtime.sh`,
`infra/docker-compose.runtime.yml`, `ApiTokenController`, `RuntimeAdminController`,
`ElsterExportController`, `ApiTokenAuthenticationFilter`, `LicenseService`, and the Bridge
documentation (`firmspace-bridge`, version 0.1.0).

This section explains the **four ways to deploy Firmspace / Bürokratie-Copilot** in plain
language: what you get, where it runs, what the base URL is, and where each token comes from.
No marketing — only statements you can check with a command or see in the interface.

![Overview of the four delivery models](../../images/deployment-models.svg)

*Figure 1. The four models and the key difference: where the data lives and who operates the
server.*

---

## 1. What this is and who it is for

Firmspace can be delivered in four different ways, and they are not interchangeable.
**Bridge only** connects a client's legacy software to the platform but recognises nothing.
**Robots Runtime** brings the agent engine and the documents onto the client's server.
**Embedded API** gives your product HTTP access to recognition and agents. **Copilot as a
service** provides a ready cloud workspace with no infrastructure of your own. The choice
comes down to two questions: may documents leave the client's environment, and who will
operate the server? The sections below answer both before installation rather than after.

---

## 2. What you need

### 2.1 Minimum for every model

| Item | Requirement | Check |
|---|---|---|
| Browser | current Chrome, Firefox, Edge or Safari | open `https://burokratie.firmspace.de` |
| `curl` | 7.76 or newer | `curl --version` |
| Clock | NTP-synchronised, drift below 5 minutes | `timedatectl status` (Linux) |
| Network | outbound HTTPS 443; no inbound ports needed | `curl -I https://burokratie.firmspace.de` |
| Place for secrets | password manager or a file with mode `600` | — |

### 2.2 Additional for Runtime (on-premises)

| Item | Requirement | Check |
|---|---|---|
| OS | Linux x86_64/arm64 (recommended) | `uname -m` |
| Docker Engine | 24 or newer | `docker version --format '{{.Server.Version}}'` |
| Docker Compose | v2 | `docker compose version` |
| RAM | at least 4 GB, 8 GB recommended | `free -h` |
| Disk | at least 10 GB free, 200 GB recommended | `df -h` |
| `openssl` | any current version | `openssl version` |
| `git` | for the delivery sources | `git --version` |
| PostgreSQL | 17, included in Compose | `docker compose ... exec postgres psql --version` |

### 2.3 Additional for the Bridge

| Item | Requirement | Check |
|---|---|---|
| Docker Engine | 24 or newer | `docker version` |
| Architecture | `amd64` or `arm64` | `uname -m` |
| RAM | 512 MB plus headroom | `docker info` |
| Disk | 2 GB plus queue space | `df -h` |
| Node.js | 22 LTS — **only** for the non-Docker variant | `node -v` |
| Folder access | UID/GID 1000 inside the container | `id -u` on the host |

### 2.4 Additional for the Embedded API

| Item | Requirement | Check |
|---|---|---|
| TLS | valid certificate for the API host | `openssl s_client -connect <host>:443 -servername <host>` |
| `jq` | optional, but convenient for the examples | `jq --version` |
| Token | issued with the required `scopes` | `curl ... /api/v1/agents` |

> **Important.** All examples use placeholders only: `${BASE_URL}`, `${TOKEN}` and
> `<insert your own>`. Never put real tokens into a command line, shell history, screenshot
> or ticket.

---

## 3. Choosing a model

### 3.1 Selection table

| Client situation | Model | Installed at the client | Where the engine runs | Where data goes | Token |
|---|---|---|---|---|---|
| Legacy software without an API; exchange only | [Bridge only](BRIDGE_ONLY.md) | Firmspace Bridge | Nowhere at the client | Only to the configured receiver | Bridge token |
| OCR, agents and logs needed; documents must not leave the client | [Robots Runtime](RUNTIME.md) | API + PostgreSQL 17 | On the client's server | Stays in the client environment | Runtime API token |
| Your product must call recognition and agents | [Embedded API](EMBEDDED_API.md) | Client code only | In SaaS or Runtime | The instance the token belongs to | Copilot or Runtime token |
| Fast start without infrastructure | [Copilot as a service](COPILOT_SAAS.md) | Nothing, browser only | At Firmspace | Firmspace cloud (EU) | Copilot API token |
| Agents plus access to legacy systems | Runtime + Bridge | API + PostgreSQL + Bridge | On the client's server | Stays in the client environment | Two tokens, one per component |
| Agents in the cloud, legacy on site | Copilot + Bridge | Bridge only | At Firmspace | Firmspace cloud | Two tokens |

> **A hybrid is not delivered.** You cannot split one engine between the client's server and
> the cloud so that both share one storage and one tenant. You can combine **whole** models:
> Runtime + Bridge, Copilot + Bridge.

### 3.2 Three questions instead of a table

1. **May documents leave the client environment?**
   - No → Runtime, or Embedded API deployed inside the client environment.
   - Yes → Copilot as a service.
2. **Who writes the calling code?**
   - Our product or an integrator → Embedded API.
   - Nobody; a ready interface is needed → Copilot as a service.
   - Only an exchange with legacy systems → Bridge only.
3. **Are OCR, categories and agents needed?**
   - No → Bridge only.
   - Yes → Runtime (on-premises) or Copilot (cloud).

---

## 4. Three addresses you need to know

This is what the previous documentation lacked: the **base URL** and **where the token comes
from**. Three addresses are enough.

| Item | Address | Check |
|---|---|---|
| Copilot in the cloud | `https://burokratie.firmspace.de` | `curl -I https://burokratie.firmspace.de` |
| Copilot API | `https://burokratie.firmspace.de/api/v1` | `curl -fsS https://burokratie.firmspace.de/api/v1/license` |
| Robot cabinet (bridges) | `https://agents.firmspace.de` | `curl -I https://agents.firmspace.de` |
| Runtime on-premises | the address printed by the installer, e.g. `http://192.168.1.20:8080` | `curl -fsS http://192.168.1.20:8080/actuator/health/readiness` |
| Runtime API | `<runtime address>/api/v1` | `curl -fsS http://192.168.1.20:8080/api/v1/license` |

If the terminal is gone, the runtime address is in the environment file:

```bash
cd /path/to/burokratie-copilot
set -a; . infra/runtime.env; set +a
printf '%s\n' "$BC_PUBLIC_BASE_URL"
```

With `--dir /opt/firmspace-runtime`, the file is `/opt/firmspace-runtime/runtime.env`. It
contains `BC_PUBLIC_BASE_URL`; that is the base URL. The API always appends `/api/v1`.

The three tokens live in three different places:

| Token | Where to get it | Where to use it |
|---|---|---|
| Copilot API | Workspace → Account settings → API tokens → Create token | Header `Authorization: Bearer ${TOKEN}` |
| Bridge | Robot cabinet → Enterprises → Connections → Add bridge | `bridge.config.json` and `.env` (`BRIDGE_TOKEN`) |
| Runtime admin | The installer creates it and writes it to `infra/runtime.env` | Header `X-Runtime-Admin-Token` |

Step-by-step with a diagram: [“Where to get each token”](../../images/token-locations.svg).

---

## 5. What each document contains

| Document | For | Contents |
|---|---|---|
| [README.md](README.md) | everyone | model selection, addresses, tokens, map of the section |
| [BRIDGE_ONLY.md](BRIDGE_ONLY.md) | client IT | arm64/amd64 image, `docker load`, `bridge.config.json` import, 10 common failures |
| [RUNTIME.md](RUNTIME.md) | client IT, operator | installation, `/runtime-admin/` page, licence, first token, backups |
| [EMBEDDED_API.md](EMBEDDED_API.md) | developer | base URL, scopes, curl, 401/402/403/409/429, idempotency, ELSTER |
| [COPILOT_SAAS.md](COPILOT_SAAS.md) | owner, administrator | registration, documents, mail, tokens, plans, data |
| [MANAGEMENT.md](MANAGEMENT.md) | owner, support | licences, updates, backups, logs, incidents, RACI |
| [FAQ.md](FAQ.md) | everyone | 25 questions: URL, tokens, licence, offline, data, reports |

For engineers and operations, additionally: [../deployment.md](../../deployment.md) (self-hosted
stack), [../runbook.md](../../runbook.md) (operations), [../security.md](../../security.md)
(security), [../compliance/README.md](../../compliance/README.md) (GDPR).

---

## 6. How to read these documents

- **Placeholders.** `${BASE_URL}` is the API address without a trailing `/`. `${TOKEN}` is
  the secret from the workspace. `<insert your own>` is a value you cannot invent.
- **Paths.** Commands run from the delivery root `burokratie-copilot` unless stated
  otherwise. Paths inside the Bridge container are **container** paths, not host paths.
- **Expected result.** Every command is followed by what should appear. Anything else is not
  a cosmetic issue; it is a reason to stop and check the step.
- **Screenshots.** Places that need a picture of the live interface are marked
  `[SCREENSHOT]`. This delivery contains no such pictures: the diagrams in `docs/images`
  show the path, but do not replace checking your specific cabinet version.
- **Versions.** The Bridge in the examples is `0.1.0`. The Runtime image carries the tag you
  built or received; record it in the acceptance report.

---

## 7. What is verified and what needs a decision

**Verified against code and scripts on 28 October 2026:**

- Runtime installer: flags, generation of `BC_RUNTIME_ADMIN_TOKEN`, writing
  `infra/runtime.env` with mode `600`, calling the bootstrap;
- bootstrap: tenant/workspace/owner creation, one-time token output, checking
  `/actuator/health/readiness`, printing the `API URL`;
- licence: HMAC token, binding to `instance-id`, a perpetual key without `expiresAt`,
  states `LICENSE_ACTIVE`, `LICENSE_GRACE`, `LICENSE_READ_ONLY`, `LICENSE_REVOKED`,
  `LICENSE_MISSING`;
- `/runtime-admin/` page and `POST /api/v1/runtime/license`: install a key without a restart,
  file `/app/data/license.key`, operator warnings;
- Runtime admin API: `POST /api/v1/runtime/bootstrap`, `POST /api/v1/runtime/tokens`,
  `POST /api/v1/runtime/tokens/{id}/revoke`, `GET /api/v1/runtime/status`;
- Copilot tokens: `GET/POST /api/v1/api-tokens`, `POST /api/v1/api-tokens/{id}/revoke`,
  scopes, a limit of 20 active tokens, 60 requests per minute;
- ingest: `POST /api/v1/documents:ingest`, mandatory `Idempotency-Key`, SHA-256, statuses
  201/200/400/409;
- ELSTER: `GET /api/v1/exports/elster?year=&format=json|csv`, CSV columns;
- Bridge: CLI `init`, `import`, `test`, `doctor`, `status`, `run`, `/data/...` paths,
  `BRIDGE_TOKEN`.

**Open or requiring a live check:**

1. Exact Bridge archive names and the download link — Firmspace hands these over with the
   delivery; there is no public image registry.
2. Remote revocation: the code reads `LICENSE_CHECK_URL` and asks the cabinet every 6 hours,
   but the current Compose file does not pass the variable into the container. Check
   `printenv LICENSE_CHECK_URL`; without it a cabinet revocation does not reach the
   installation.
3. Commercial licence terms (term, plan, price) are a contract decision. A perpetual licence
   has **no** `expiresAt`; a fixed-term licence carries the date.
4. Support contacts, hours and SLA are not set in this delivery and belong in the contract.
5. An end-to-end production run of each model at the client site before acceptance.

---

## 8. Where to go next

1. Choose a model using §3 and record it in the contract or acceptance report.
2. Open the matching document and follow the steps in order.
3. Verify the base URL and token using §4 — before the first command.
4. If something fails, start with [FAQ.md](FAQ.md), then the “If it does not work” section of
   the model document.
5. For operations, use [MANAGEMENT.md](MANAGEMENT.md).
