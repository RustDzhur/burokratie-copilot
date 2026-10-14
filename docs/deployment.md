# Deployment Guide — Bürokratie-Copilot

How to run Bürokratie-Copilot in production: topology, TLS, secrets, data region, sizing,
zero-downtime releases, rollback, and — at the end — the list of things **only the system owner can
provide**.

The product processes highly sensitive personal data (GDPR/DSGVO). Treat every decision below as a
data-protection decision, not only a technical one.

> ### Verification status
> **No production deployment has been performed or rehearsed.** Docker is not installed in the
> environment where this guide was written, so the Compose stack, the images and the reverse proxy
> have been validated **statically only** (YAML parse, structural assertions in CI,
> `docker compose config` on a Docker-enabled CI runner). No image has ever been built from these
> Dockerfiles and no nginx configuration has been rendered by a running nginx.
>
> The `api` process itself **was** started successfully (JDK 21, PostgreSQL 17.11) and its health
> endpoints were verified; the backup/restore tooling **was** exercised for real (see
> `docs/runbook.md` → Verification status). Everything else in this document is a plan that must be
> rehearsed in staging before it is trusted in production.

---

## 1. Target architecture

### 1.1 Single-host (recommended first production setup)

```
                    Internet
                        │  HTTPS 443
                ┌───────▼─────────┐
                │  reverse proxy  │  nginx (container, profile "proxy")
                │  TLS, HSTS,     │  • terminates TLS
                │  rate limits    │  • /api/*      -> api:8080
                └───┬─────────┬───┘  • /actuator/*  -> api:8080 (private nets only)
                    │         │      • /           -> web:3000
        ┌───────────▼──┐   ┌──▼───────────┐
        │  web:3000    │   │  api:8080    │   Docker Compose, network "edge"
        │  Next.js     │   │  Spring Boot │
        └──────────────┘   └──┬───────┬───┘
                              │       │
                     ┌────────▼──┐ ┌──▼─────────┐
                     │ worker    │ │ postgres   │   network "internal"
                     │ (no HTTP) │ │ 17         │   (not reachable from outside)
                     └────┬──────┘ └────────────┘
                          │
                     ┌────▼──────────┐
                     │ object store  │  MinIO (self-hosted) or managed S3
                     │ S3 API 9000   │
                     └───────────────┘
```

Compose files:

- `infra/docker-compose.yml` — the dev stack (PostgreSQL, MinIO + bucket init, api, worker, web).
- `infra/docker-compose.prod.yml` — production overrides: **no published host port except the proxy**,
  resource limits, read-only root filesystems, log rotation, the `proxy` service (profile `proxy`).

```bash
docker compose --env-file infra/.env \
  -f infra/docker-compose.yml -f infra/docker-compose.prod.yml \
  --profile proxy up -d
```

Requires Compose **v2.24.2+** (the prod override uses `!reset`/`!override`). On an older Compose the
file fails loudly instead of silently publishing the database.

### 1.2 Split-host variant

Once document volume grows, split in this order — each step is independent:

| Step | Move | Why |
| --- | --- | --- |
| 1 | **Object storage** → managed S3 (EU region) | removes the largest storage growth from the host, adds durability and lifecycle rules |
| 2 | **PostgreSQL** → managed instance with PITR | point-in-time recovery, automated backups, no self-managed patching |
| 3 | **worker → its own host(s)** | OCR/AI work is CPU/burst-heavy and scales independently of the API (`docker compose up -d --scale worker=N` on a second host or a separate deployment) |
| 4 | **api → 2+ instances behind the proxy** | rolling restarts without a maintenance window |

At step 3/4 the containers need: `BC_DB_*` pointing at the database host (enable TLS on that
connection), `BC_STORAGE_TYPE=s3` with the real endpoint, and `BC_PUBLIC_BASE_URL` unchanged. The
worker must run with `BC_FLYWAY_ENABLED=false`.

---

## 2. Reverse proxy and TLS

Configuration templates live in `infra/nginx/templates/` (rendered by the official nginx image's
`envsubst` entrypoint at container start) with shared include files in `infra/nginx/conf/`.

| File | Purpose |
| --- | --- |
| `10-http-common.template` | HTTP-context settings: `map`s for WebSocket upgrade and forwarded proto, rate-limit zones, log format (URI only — **no query strings**, so tokens never reach the log) |
| `20-server.template` | the two server blocks: HTTP (health probe + 308 redirect to HTTPS) and HTTPS (the application) |
| `conf/bc-proxy-params.conf` | shared `proxy_set_header` block, including `X-Forwarded-For`/`X-Forwarded-Proto`, which the backend needs (`server.forward-headers-strategy: framework`) to build correct absolute URLs and mark cookies `Secure` |

What the template already does:

- TLS 1.2/1.3, `server_tokens off`, HSTS (only over HTTPS), `X-Content-Type-Options`,
  `X-Frame-Options: DENY`, `Referrer-Policy`, a restrictive `Permissions-Policy` and a CSP;
- `/api/v1/auth/` gets the strictest rate limit (`bc_auth`), `/api/` a coarser one (`bc_api`), plus a
  per-client connection cap;
- `/actuator/*` is **denied from public addresses** (allowed from loopback and RFC1918 only);
- dotfiles are denied; `/_next/static/` is cached aggressively;
- `client_max_body_size` is driven by `BC_NGINX_CLIENT_MAX_BODY`, which must be **≥**
  `BC_MAX_REQUEST_SIZE` — otherwise nginx rejects a large upload before the application can stream it;
- uploads are proxied with `proxy_request_buffering off`, so a 100 MB document is streamed rather
  than buffered in the proxy.

### 2.1 Certificates

TLS material is mounted from the host and never baked into an image:

```
infra/nginx/certs/tls.crt
infra/nginx/certs/tls.key        # chmod 600, owned by the deploy user
```

With Let's Encrypt:

```bash
# one-off issuance (DNS must already point at the host)
docker run --rm -v "$PWD/infra/nginx/certs:/etc/letsencrypt/live/app.example.de" \
  -v /var/www/certbot:/var/www/certbot certbot/certbot certonly \
  --webroot -w /var/www/certbot -d app.example.de

# symlink or copy fullchain.pem -> tls.crt and privkey.pem -> tls.key
# renewal (cron): renew, then reload the proxy
0 3 * * * certbot renew --quiet --deploy-hook "docker compose --env-file /srv/bc/infra/.env \
  -f /srv/bc/infra/docker-compose.yml -f /srv/bc/infra/docker-compose.prod.yml \
  --profile proxy exec -T proxy nginx -s reload"
```

**Never disable certificate validation** to make something work (spec §1.2). The backend's
`/actuator/health` includes an `ssl` component for the JVM trust store — treat a failure there as a
deployment defect, not a nuisance.

### 2.2 Same-origin API (why `/api/` is proxied)

`NEXT_PUBLIC_API_BASE_URL` is inlined into the browser bundle at **build** time. Keep it the
**relative** path `/api/v1` (the default) and let the proxy route `/api/*` to the backend; without a
proxy, `next.config.ts` forwards `/api/v1/*` to `BC_API_ORIGIN`. The client reads the `XSRF-TOKEN`
cookie, which only works when page and API share an origin. Then:

- session cookies are **first-party** and work with `SameSite=Strict` (the `prod` profile forces
  secure cookies);
- there is no CORS preflight for normal application traffic;
- the backend sees `X-Forwarded-Proto: https` and issues `Secure` cookies and `https://` links.

`BC_PUBLIC_BASE_URL` must be the same public origin, because it is used in e-mails and invite links
(`burokratie.security.public-base-url`).

### 2.3 Closing public registration

`POST /api/v1/auth/register` is a **public endpoint**. Until the controlled bootstrap flow
(implementation-plan A-018/A-024) exists, an operator who does not want self-registration must close
it at the edge — for example by denying `/api/v1/auth/register` in the proxy for everyone except the
operator's address:

```nginx
location = /api/v1/auth/register {
    allow 203.0.113.7;      # the operator's address
    deny all;
    proxy_pass http://api:8080;
    include /etc/nginx/conf.d/bc-proxy-params.conf;
}
```

Note that this makes first-admin creation an explicitly operator-driven step, which is the intent.

---

## 3. Secrets management

### 3.1 Rules

1. `infra/.env` is the **only** place on the host that holds values; `chmod 600`, owned by the deploy
   user. It is git-ignored and CI fails if any `.env` file is committed.
2. Secrets are injected as **environment variables** (or secret files) at run time. They are never
   passed through Dockerfile `ARG`/`ENV`, never committed, never logged (CI asserts both properties).
3. The production override prefers a mounted secret file when one is configured
   (`SPRING_DATASOURCE_PASSWORD_FILE`), so a Docker secret / SOPS-rendered file can replace the plain
   environment variable.
4. Every secret has an owner and a rotation date. Rotation steps: `docs/runbook.md` §7.1.

### 3.2 Options

| Approach | When | Notes |
| --- | --- | --- |
| `infra/.env` (0600) + `--env-file` | single host, small team | simplest; the file is the crown jewel — back it up encrypted, never in git |
| **Docker secrets** | single host, Compose `secrets:` | mount at `/run/secrets/<name>`; point `*_FILE` variables at it |
| **SOPS + age** | git-managed configuration | encrypted values in git, decrypted at deploy time (`sops exec-env secrets.enc.yaml 'docker compose … up -d'`) |
| Cloud secret manager (AWS/GCP/Azure) | managed infrastructure | fetched at boot by an init container or the orchestrator; enables IAM-based access and audit |
| HashiCorp Vault | larger organisations | dynamic DB credentials, lease-based rotation |

### 3.3 What must be a secret

`BC_DB_PASSWORD` / `POSTGRES_PASSWORD`, `BC_ENCRYPTION_KEY`, `BC_S3_ACCESS_KEY`,
`BC_S3_SECRET_KEY`, `MINIO_ROOT_PASSWORD`, `BC_AI_API_KEY`, SMTP credentials, OAuth client secrets,
signing/HSM credentials.

`BC_ENCRYPTION_KEY` is special: it is **base64 of exactly 32 bytes** (`openssl rand -base64 32`) and
it protects other stored credentials. The `prod` profile refuses to start when it is missing or
malformed. Changing it is **not** a hot operation — data encrypted with the old key becomes
unreadable (see `docs/runbook.md` §7.1). Store a copy in an offline/escrow location; losing it is a
data-loss incident.

### 3.4 If a secret leaks

Rotate first, investigate second (`docs/runbook.md` §8.4): rotate the credential, invalidate
sessions, review `audit_events`/`document_access_logs` for the exposure window, remove it from git
history, and start the data-protection process if personal data was reachable.

---

## 4. Data region and GDPR/DSGVO

| Item | Requirement |
| --- | --- |
| Region | **EU, preferably Germany** (e.g. `eu-central-1` / Frankfurt) for the database, object storage, backups **and** the AI/OCR providers actually used |
| `BC_S3_REGION` | must match where the blobs legally live — a bucket in another jurisdiction is a transfer |
| No third-country transfer by accident | an AI provider that processes document text outside the EU is a transfer; either contract it properly (SCC/DPF + DPA) or keep `BC_AI_ENABLED=false` and use a local model |
| Encryption in transit | TLS at the proxy; TLS for database connections when the database is on another host; S3 over HTTPS |
| Encryption at rest | volume encryption on the host **plus** application-level encryption of stored credentials (`BC_ENCRYPTION_KEY`) |
| Access control | least privilege on the host, no shared accounts, SSH keys only; the containers run as non-root with read-only root filesystems |
| Logging | no personal data or document content in logs (`LogSanitizer`; the proxy access log drops query strings) |
| Retention | document retention policies in the application (`document_retention_policies`) plus host-side backup retention; deletion must be possible on request (Art. 17) |
| Data-subject rights | export/correction/deletion procedures must be executable — they are product features, not ad-hoc SQL |
| Subprocessors | a **signed DPA/AVV with every subprocessor** (hosting, object storage, mail, AI/OCR, bank aggregator) before personal data flows |
| Legal review | this project does **not** claim legal compliance. A DSGVO assessment, a records-of-processing entry and a DPIA (if applicable) are the operator's responsibility — spec §21.1 explicitly forbids declaring compliance without independent review |

Practical check before go-live: list every network destination the stack talks to (`BC_S3_ENDPOINT`,
`BC_AI_BASE_URL`, SMTP host, mail/calendar/bank providers) and confirm each one is either inside the
EU or covered by a contract.

---

## 5. Resource sizing

Sizes assume the modular monolith in one Compose project. `docker-compose.prod.yml` already sets
limits; override them through `infra/.env` (`BC_API_MEMORY_LIMIT`, `BC_POSTGRES_MEMORY_LIMIT`, …).

| Component | Small (≤ 20 users, ~50k docs) | Medium (≤ 200 users, ~500k docs) | Large (500+ users, millions of docs) |
| --- | --- | --- | --- |
| Host | 4 vCPU / 8 GB / 200 GB SSD | 8 vCPU / 16 GB / 1 TB SSD | split hosts (see §1.2) |
| `postgres` | 1–2 vCPU, 2 GB, `shared_buffers=512MB` | 4 vCPU, 8 GB, `shared_buffers=2GB` | managed instance with PITR, 8+ vCPU, 32 GB |
| `api` | 1 vCPU, 2 GB, `BC_DB_POOL_SIZE=10` | 2 vCPU, 4 GB, pool 25 | 2× (2 vCPU, 4 GB), pool 25 each |
| `worker` | 1 vCPU, 2 GB, `BC_WORKER_CONCURRENCY=2` | 2–4 vCPU, 4 GB, concurrency 4 | 4+ vCPU, 8 GB, scale out horizontally |
| `web` | 1 vCPU, 512 MB | 1 vCPU, 1 GB | 2× (1 vCPU, 1 GB) |
| Object storage | 200 GB, lifecycle rules | 1 TB, versioning | managed S3, lifecycle + archive tier |
| Backups | 2× database size + blob growth, **off-host** | 3× | separate account/region with immutability (object lock) |

Guidance:

- **JVM heap**: the images run with `-XX:MaxRAMPercentage=75`, so the heap follows the container
  memory limit. Keep ~25 % headroom for metaspace, thread stacks, direct buffers and the page cache
  inside the container. Override with `BC_JAVA_OPTS` when profiling shows otherwise.
- **Connection pool**: keep `BC_DB_POOL_SIZE × (api instances + worker instances)` comfortably below
  PostgreSQL's `max_connections` (default 100; the Compose default raises it via
  `BC_DB_MAX_CONNECTIONS`). A pool larger than the database can serve turns latency into timeouts.
- **Uploads are streamed** (`proxy_request_buffering off`, multipart streaming in the backend), so
  concurrent large uploads do not multiply memory use — but they do consume file descriptors and
  temporary disk/tmpfs. The containers mount `/tmp` as a sized tmpfs.
- **Scale the worker first.** OCR/AI/outbox work is what makes a queue grow; the API is comparatively
  cheap. Scale PostgreSQL vertically, not horizontally, until you have measured a need.
- **Watch disk for the database**, not only for blobs: `audit_events`, `document_access_logs` and
  `outbox_events` grow with usage and are the first things to plan a retention/partitioning strategy
  for.
- **Measured numbers do not exist yet.** Every figure above is a planning estimate. Real sizing must
  come from the load tests in `tests/load/` with their conditions recorded (spec §27).

---

## 6. Zero-downtime considerations

### 6.1 What is safe today

| Operation | Downtime | Notes |
| --- | --- | --- |
| `web` restart | none | stateless; the proxy retries to a healthy upstream |
| `api` rolling restart | none *if* 2+ instances | one instance drains while the other serves; requires `restart: unless-stopped` and a readiness gate |
| `worker` restart | none | jobs are retried; the outbox is durable in PostgreSQL (`outbox_events`, `max-attempts=8`) |
| Additive Flyway migration | none | new nullable columns/tables/indexes are backward compatible |
| Configuration change (env var) | none | requires a restart of the affected service only |
| Proxy reload (`nginx -s reload`) | none | graceful worker handover |

### 6.2 What costs downtime

| Operation | Why | Mitigation |
| --- | --- | --- |
| Breaking migration (drop/rename, `NOT NULL` without default, type change) | the old release cannot run against the new schema | expand/contract across two releases: add → deploy → backfill → switch → remove |
| `BC_ENCRYPTION_KEY` rotation | encrypted data must be re-encrypted | maintenance window + backup (`docs/runbook.md` §7.1) |
| Single-host disk resize | filesystem operation | plan capacity; prefer adding a second host or moving blobs to S3 |
| PostgreSQL major-version upgrade | dump/restore or `pg_upgrade` | staged rehearsal in staging with a real dump |
| Schema rollback (restore) | the database is replaced | maintenance window; see `docs/runbook.md` §5.2 |

### 6.3 Release sequence that avoids downtime

```bash
# 0. backups: DB + objects, then PROVE the backup restores
I_KNOW_THIS_IS_PRODUCTION=yes infra/scripts/backup.sh
CONFIRM=yes infra/scripts/verify-restore.sh

# 1. migrations as a discrete step (one process only)
docker compose $ENV $PROD stop api worker
docker compose $ENV $PROD run --rm -e BC_FLYWAY_ENABLED=true api \
  --spring.main.web-application-type=none
docker compose $ENV $PROD up -d --no-deps api

# 2. wait for readiness before touching anything else
until curl -fsS http://127.0.0.1:8080/actuator/health/readiness >/dev/null; do sleep 2; done

# 3. then the worker (BC_FLYWAY_ENABLED=false)
docker compose $ENV $PROD up -d --no-deps worker
docker compose $ENV $PROD up -d --no-deps web
```

In-flight uploads survive an `api` restart only if the client retries; the API is stateless, but a
half-finished multipart upload must be restarted by the client. Announce releases if uploads are
heavy.

---

## 7. Release versioning and rollback

### 7.1 Versioning

- Images are tagged with the **git SHA** and, for a release, additionally with a **semver** tag:
  `${BC_REGISTRY}/api:<sha>` → `${BC_REGISTRY}/api:1.4.0`.
- `BC_IMAGE_TAG` in `infra/.env` pins what the host runs. That value is the release record on the host.
- **Build once, promote the same digest.** Never rebuild for production: build in CI, push, then set
  `BC_IMAGE_TAG` to the exact tag/digest that was tested in staging.
- Record per release: image digest, git SHA, Flyway head version, operator, date, and the backup
  timestamp taken immediately before the migration.
- OCI labels carry `org.opencontainers.image.revision` and `.created`, so `docker image inspect`
  answers "what is running here?" without access to the registry.

### 7.2 Release checklist

- [ ] CI green on the release commit (backend tests, frontend checks, security scans)
- [ ] `infra/scripts/check-structure.sh` passes (add `--strict` for a release)
- [ ] migration reviewed; is it additive? if not, the expand/contract plan exists
- [ ] backup taken **and** `verify-restore.sh` PASS on that backup
- [ ] staging ran the identical image digest
- [ ] `infra/.env` diff reviewed (no accidental secret change)
- [ ] rollback target identified: previous digest + the pre-migration backup timestamp
- [ ] notification sent if a maintenance window is needed

### 7.3 Rollback

```bash
export BC_IMAGE_TAG=<previous-digest-or-tag>
docker compose $ENV $PROD -e BC_FLYWAY_ENABLED=false up -d --no-deps api
docker compose $ENV $PROD -e BC_FLYWAY_ENABLED=false up -d --no-deps worker
curl -fsS http://127.0.0.1:8080/actuator/health/readiness && echo "rolled back"
```

Decision criteria:

| Situation | Action |
| --- | --- |
| Functional regression, schema compatible | roll back the image (1–3 min) |
| Schema change broke the old release | roll back the image **and** restore the pre-migration backup (§5.2 in `docs/runbook.md`) |
| Data corruption | stop writes, take a forensic backup, then restore; treat as S1 (`docs/runbook.md` §8.3) |
| Security incident | roll back, rotate credentials, preserve logs for the analysis |

Flyway is forward-only: there is no `undo`. `BC_FLYWAY_ENABLED=false` prevents an older release from
attempting a migration on a newer schema.

---

## 8. Monitoring and alerting baseline

| Signal | Source | Alert |
| --- | --- | --- |
| Readiness | `GET /actuator/health/readiness` (per instance) | page after 2 consecutive failures |
| Liveness | `GET /actuator/health/liveness` | restart the container; page on a crash loop |
| Health detail | `GET /actuator/health` | warn when any component is not `UP` (the `db` and `diskSpace` components are the ones that matter) |
| HTTP 5xx rate | proxy/access logs or metrics | warn > 1 %, page > 5 % for 5 min |
| Latency | proxy `$request_time` / `$upstream_response_time` | warn when p95 exceeds the agreed threshold (see `tests/load/`) |
| Outbox backlog | `SELECT count(*) FROM outbox_events WHERE processed_at IS NULL` | warn when it grows monotonically for 15 min (worker stuck) |
| Database | managed metrics or `pg_stat_activity` | connections > 80 % of `max_connections`, long-running queries, replication lag (if any) |
| Disk | host + the `diskSpace` health indicator | warn at 80 %, page at 90 % |
| Backup age | newest directory under `BC_BACKUP_DIR` | page when the newest backup is older than 26 h |
| Restore drill age | last successful `verify-restore.sh` | warn monthly (`docs/runbook.md` §6.3) |
| Certificate expiry | ACME client / TLS probe | warn 30 days, page 7 days before expiry |
| AI spend | `ai_usage_records` vs `BC_AI_MONTHLY_BUDGET_EUR` | warn at 80 % of the monthly budget |

Metrics for scraping: `/actuator/prometheus` (Micrometer). **Restrict it**: it is not public — the
nginx template denies `/actuator/*` from public addresses; scrape it over the private network or via
a sidecar. Structured logs: the `prod` profile writes JSON (logstash encoder), one object per line —
ship them to Loki/ELK/CloudWatch. Every API response carries `X-Correlation-Id`; keep it as a log
field so one request can be traced across services.

---

## 9. First productive start (runbook order)

1. **Host**: create the user, harden SSH, install Docker Engine + Compose v2.24.2+, set the timezone
   (`TZ=Europe/Berlin` is what the app expects for display; the database stores UTC).
2. **DNS + TLS**: point the A/AAAA record at the host, issue the certificate (§2.1), place it in
   `infra/nginx/certs/`.
3. **Secrets**: `cp infra/.env.example infra/.env && chmod 600 infra/.env`, fill in every mandatory
   value (§3.3), generate `BC_ENCRYPTION_KEY` with `openssl rand -base64 32`.
4. **Object storage**: either point `BC_S3_*` at a managed bucket in the EU region, or start the
   bundled MinIO, change `MINIO_ROOT_PASSWORD`, and confirm the bucket exists
   (`docker compose logs minio-init` must end with `bucket '<name>' is ready`).
5. **Database**: start `postgres` first and confirm `pg_isready`; decide whether the first migration
   run may happen automatically (`BC_FLYWAY_ENABLED=true`) — for an empty database that is the
   normal path.
6. **Migrations + API**: bring up `api` and watch the logs until `Started BurokratieCopilotApplication`
   and a passing readiness probe. Verify the schema head:
   `SELECT max(version) FROM flyway_schema_history WHERE success;` → `15`.
7. **Worker**: bring up `worker`.
   > **Known blocker:** the `worker` profile currently fails to start
   > (`SecurityConfiguration` needs an `HttpSecurity` bean that does not exist without a servlet web
   > application). Background jobs do not run until that is fixed — see blocker 1 in `README.md`.
8. **Web + proxy**: `up -d web` then `--profile proxy up -d`; open the public URL and confirm the
   HTTP → HTTPS redirect and HSTS.
9. **First administrator**: `infra/scripts/bootstrap-admin.sh` (see below).
10. **Backups**: install the cron entry, run one backup, and run `verify-restore.sh` **before**
    announcing the system as available (`docs/runbook.md` §6).
11. **Monitoring**: wire the alerts from §8 and confirm the first scrape arrives.
12. **One-time settings**: retention policies, workspace defaults, notification preferences, and the
    integration statuses that must honestly read "not connected".

### First administrator

```bash
infra/scripts/bootstrap-admin.sh --check
infra/scripts/bootstrap-admin.sh --email admin@example.de --password '<12+ chars>' \
    --name "Admin" --tenant "Kanzlei Muster" --account-type COMPANY
```

> **Known blocker:** this returns HTTP 403 today. The API enables CSRF protection but
> `GET /api/v1/auth/csrf` does not deliver the `XSRF-TOKEN` cookie, so no client can obtain a token
> and **registration and sign-in are unreachable**. Registration also requires e-mail verification
> afterwards (`nextStep` = `VERIFY_EMAIL` or `VERIFY_EMAIL_PENDING_MAIL_CONFIGURATION` when no SMTP
> sender is configured). See blocker 3 in `README.md`.
>
> The controlled, token-protected bootstrap flow (`POST /api/v1/onboarding`) described in
> implementation-plan A-018/A-024 is **not implemented yet**. Until it is, either work around the
> CSRF defect on the backend side (the correct fix) or close public registration at the proxy (§2.3)
> and create the first account through an operator-controlled channel.

---

## 10. What the system owner must provide

Nothing in this list can be produced by the repository. Each row states what it unblocks.

| # | Item | Why it is needed | Blocked without it |
| --- | --- | --- | --- |
| 1 | **Domain + DNS + TLS certificate** (and a decision on the public hostname) | `BC_PUBLIC_BASE_URL`, invite/e-mail links, HSTS, cookie `Secure` | no usable production URL |
| 2 | **PostgreSQL instance** (or the host for the bundled one) with backups/PITR and an EU region choice | system of record | nothing runs |
| 3 | **Object-storage credentials**: endpoint, region, bucket, access key, secret key (MinIO self-hosted or managed S3) | document blobs; `local-fs` is not shared between containers and is lost on redeploy | documents cannot be stored durably |
| 4 | **AI provider contract + API key** (OpenAI-compatible endpoint or a local model) and an agreed monthly EUR budget | classification, extraction, assistant, embeddings | all AI features stay disabled (`BC_AI_ENABLED=false`) |
| 5 | **OCR**: Tesseract with `deu` language data inside the worker image, or an external OCR engine + contract | text from scans and photos | `BC_OCR_ENABLED=false`; scanned documents get no text |
| 6 | **SMTP relay credentials** (host, port, user, password, verified From address) | verification, invitation, password-reset and notification mails | users cannot verify their e-mail — accounts stay unusable |
| 7 | **Microsoft 365 registration** (Azure AD app: tenant id, client id, secret, redirect URI, Graph scopes for mail/calendar/files) | mailbox and calendar sync | M365 integration cannot be connected |
| 8 | **Google Workspace / Gmail OAuth client** (client id, secret, consent-screen configuration, Gmail/Calendar scopes) | Gmail and Google Calendar sync | Google integration cannot be connected |
| 9 | **Banking provider contract** (PSD2 licence or an aggregator agreement incl. credentials) | automatic account/transaction import | only offline CAMT.053/MT940/CSV import is possible |
| 10 | **Accounting-system access** (e.g. DATEV contact + the agreed export format/credentials) | handing data to the tax advisor | no automated accounting export |
| 11 | **E-signature provider contract + signing certificate** (qualified certificate or remote-signing service credentials, HSM where required) | legally valid signatures | signing stays manual/external |
| 12 | **E-invoicing decisions** (XRechnung/ZUGFeRD version, validation service, Peppol access point if needed) | compliant outgoing invoices | e-invoicing cannot be verified as compliant |
| 13 | **Scanner/device decisions** (supported models, network scan-to-mail setup, Desktop Bridge rollout) | document intake from MFP/scanner | manual upload only |
| 14 | **Signed DPA/AVV with every subprocessor** (hosting, storage, mail, AI/OCR, bank, accounting) | DSGVO Art. 28 | personal data may not be processed at all |
| 15 | **Data-protection sign-off**: records of processing, DPIA if applicable, retention rules, data-subject-request process | spec §21.1 explicitly forbids claiming compliance without independent review | no lawful production use |
| 16 | **Operating decisions**: maintenance windows, on-call, severity definitions, who may approve irreversible actions | spec §21.4/§32 (human approval for irreversible and financial actions) | nobody is accountable for incidents |
| 17 | **Budget/resource decision** (§5 sizing) and the choice of single-host vs split deployment | capacity planning | unpredictable behaviour under load |

---

## 11. Related documents

- [`docs/runbook.md`](runbook.md) — day-to-day operations, migrations, rollback, backup drill, incidents
- [`docs/integrations.md`](integrations.md) — every integration, its honest status, and the exact owner steps
- [`README.md`](../README.md) — local setup (with and without Docker), tests, status table, known blockers
- [`infra/.env.example`](../infra/.env.example) — every environment variable with its meaning
- [`tests/load/README.md`](../tests/load/README.md) — how performance figures must be produced and recorded


## AI-чтение, Telegram и состояние worker (дополнение 2026-10-15)

* **AI-чтение рукописи и объяснения** включает `scripts/enable-ai.sh` (спрашивает ключ скрытым вводом, проверяет связь и перезапускает только `api` и `worker`; `--eu` — европейский адрес OpenAI, `--anthropic`, `--off`). Месячный предел и дневное число автоматических чтений — в `infra/.env` (`BC_AI_MONTHLY_BUDGET_EUR`, `BC_AI_AUTO_READ_DAILY_LIMIT`).
* **Telegram-информатор:** `scripts/enable-telegram.sh` (токен бота от @BotFather), затем в интерфейсе Integrations → Telegram → «Подключить».
* **Worker не имеет веб-сервера**, поэтому его состояние проверяется не по HTTP: профиль `worker` каждые 15 секунд обновляет файл `/tmp/worker-heartbeat`, проверка контейнера — «файл обновлялся менее 60 секунд назад» (`infra/docker-compose*.yml`, `WorkerHeartbeat`). Контейнер `bc-worker` в состоянии `unhealthy` теперь означает, что планировщик действительно остановился.
* **Мёртвые события очереди:** `GET /api/v1/admin/outbox/dead-letters` показывает, `POST /api/v1/admin/outbox/requeue` возвращает в работу события тех типов, для которых есть обработчик (только администратор платформы).
* **Радар** выполняет ежечасный обход арендаторов (`burokratie.radar.sweep-interval-ms`, отключение `burokratie.radar.sweep-enabled=false`).
