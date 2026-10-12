# Bürokratie-Copilot

**Bürokratie-Copilot** is an AI-assisted platform for administrative automation in Germany. It brings
documents, cases (*Vorgänge/Akten*), deadlines, contracts, invoices and communication into one
traceable workflow: a document is not merely OCR-scanned, it is classified, linked to the case it
belongs to, checked against what is already known, and turned into a task with an owner, a deadline
and an approval step.

The product is a **modular monolith**: a Spring Boot backend (Java 21) exposing a REST API under
`/api/v1`, a Next.js frontend, PostgreSQL 17 as the system of record, S3-compatible object storage for
document blobs, and a background worker that shares the backend artifact. Full requirements:
[`docs/task.md`](docs/task.md) (Russian).

> ### Honest status
> This repository is **under active construction** and is **not production-ready**. The database schema,
> identity/security core and the frontend shell exist; most business modules and every external
> provider adapter are still to be implemented. Several defects are open (see
> [Known blockers](#known-blockers)) — including two that block a first run end to end. Read the
> [status table](#status) before planning anything on top of this.

---

## 1. Prerequisites

| Requirement | Version | Why |
| --- | --- | --- |
| **JDK** | 21 (Temurin or equivalent) | `maven.compiler.release=21`; the backend does not build on older JDKs |
| **Node.js** | 22 LTS or newer (`>=20.9` declared by `apps/web`) | Next.js 15 frontend |
| **pnpm** | 9 (via `corepack enable`) | the frontend uses pnpm, not npm/yarn |
| **PostgreSQL** | 17 | extensions, row-level security, `jsonb`, `citext` — the schema depends on them |
| **Maven** | *not required* | use the bundled wrapper `services/api/mvnw` (pins Maven 3.9.14) |
| **Docker + Compose v2.24.2+** | optional | only for the containerised path; the local path needs no Docker |
| **k6** | optional | load tests in [`tests/load/`](tests/load/README.md) |
| **Tesseract** with `deu` language data | optional | only when `BC_OCR_ENABLED=true` |

Nothing in this repository contains real secrets: every credential is read from the environment, and
`infra/.env` is git-ignored and checked by CI.

## 2. Local setup **without Docker** (recommended first path)

### 2.1 Create the databases

```bash
# Development database
createdb burokratie_dev
# Integration-test database (the backend test suite needs a REAL PostgreSQL, never H2)
createdb burokratie_test
```

Create a role that owns them if you do not want to use your own account:

```bash
psql -d postgres -c "CREATE ROLE burokratie LOGIN PASSWORD '<choose-a-password>';"
psql -d postgres -c "ALTER DATABASE burokratie_dev OWNER TO burokratie;"
psql -d postgres -c "ALTER DATABASE burokratie_test OWNER TO burokratie;"
```

### 2.2 Generate the encryption key

`BC_ENCRYPTION_KEY` protects stored credentials and secrets (AES-256). Generate a real one — the
backend's production guard rejects anything that is not base64 of exactly 32 bytes:

```bash
openssl rand -base64 32        # copy the output into BC_ENCRYPTION_KEY
```

### 2.3 Configure the environment

```bash
cp infra/.env.example infra/.env
chmod 600 infra/.env
# then edit infra/.env: at minimum BC_DB_*, BC_ENCRYPTION_KEY
set -a; . infra/.env; set +a          # export it into the current shell
```

`sleep`-free alternative: export the variables inline (see below). The full list with explanations is
in [`infra/.env.example`](infra/.env.example).

### 2.4 Start the backend

```bash
cd services/api
./mvnw spring-boot:run
# or run the built artifact:
./mvnw -DskipTests package && java -jar target/burokratie-copilot-api.jar
```

With exported variables instead of an env file:

```bash
cd services/api
BC_DB_HOST=localhost BC_DB_PORT=5432 BC_DB_NAME=burokratie_dev \
BC_DB_USER=burokratie BC_DB_PASSWORD='<password>' \
BC_ENCRYPTION_KEY='<base64-32-bytes>' BC_PUBLIC_BASE_URL=http://localhost:3000 \
./mvnw spring-boot:run
```

Flyway applies `V1..V15` at startup. Verify:

```bash
curl -s http://localhost:8080/actuator/health            # -> {"status":"UP", ...}
curl -s http://localhost:8080/actuator/health/readiness   # -> 200 when the DB is reachable
# API documentation
open http://localhost:8080/swagger-ui.html
```

### 2.5 Start the frontend

```bash
cd apps/web
corepack enable            # provides pnpm
pnpm install
pnpm dev
# -> http://localhost:3000   (calls /api/v1, which Next.js forwards to http://localhost:8080;
#    set BC_API_ORIGIN to point it elsewhere)
```

### 2.6 Background worker (optional, no HTTP traffic)

The worker is the **same jar** with a different profile:

```bash
java -jar services/api/target/burokratie-copilot-api.jar --spring.profiles.active=worker
```

> **Known blocker:** the `worker` profile currently fails to start — see
> [Known blockers](#known-blockers). Do not rely on the worker until that is fixed.

## 3. Local setup **with Docker**

> **Not executed in this environment:** Docker is not installed on the machine where the container
> files were written. `infra/docker-compose*.yml` and both Dockerfiles are validated **statically**
> (YAML parse + structural assertions in CI, and `docker compose config` on a runner that has a
> daemon). No container image or Compose stack has ever been started here — treat the first
> `up` as untested.

```bash
cp infra/.env.example infra/.env && chmod 600 infra/.env
# edit infra/.env: BC_DB_PASSWORD, POSTGRES_PASSWORD, BC_S3_SECRET_KEY,
# MINIO_ROOT_PASSWORD, BC_ENCRYPTION_KEY (openssl rand -base64 32)

# start the development stack (PostgreSQL, MinIO + bucket init, api, worker, web)
docker compose --env-file infra/.env -f infra/docker-compose.yml up -d

# watch it come up
docker compose --env-file infra/.env -f infra/docker-compose.yml ps
docker compose --env-file infra/.env -f infra/docker-compose.yml logs -f api

# health
curl -s http://localhost:8080/actuator/health      # api
curl -s http://localhost:9000/minio/health/live    # object storage
# web -> http://localhost:3000
```

All published ports are bound to `127.0.0.1` in the development stack. Production adds
`infra/docker-compose.prod.yml` (no host ports except the reverse proxy, resource limits, read-only
root filesystems):

```bash
docker compose --env-file infra/.env \
  -f infra/docker-compose.yml -f infra/docker-compose.prod.yml --profile proxy up -d
```

Details: [`docs/deployment.md`](docs/deployment.md) and [`docs/runbook.md`](docs/runbook.md).

## 4. Running the tests

```bash
# Backend: unit tests (surefire) + integration tests (failsafe, needs PostgreSQL)
cd services/api
BC_TEST_DB_HOST=localhost BC_TEST_DB_PORT=5432 \
BC_TEST_DB_NAME=burokratie_test BC_TEST_DB_USER=<user> BC_TEST_DB_PASSWORD=<password> \
./mvnw verify
# reports: target/surefire-reports, target/failsafe-reports, target/site/jacoco

# Frontend
cd apps/web
pnpm install --frozen-lockfile
pnpm typecheck && pnpm lint && pnpm test && pnpm build
```

CI (`.github/workflows/ci.yml`) runs exactly these commands with a `postgres:17` service container,
plus secret scanning, dependency review, Compose validation and an image build.

Load tests (k6) live in [`tests/load/`](tests/load/README.md) — **no performance numbers exist yet**;
the scripts have never been executed against a live stack.

Repository-layout compliance with spec §2.2:

```bash
# repository layout (spec §2.2) + YAML/Compose/Dockerfile validation
infra/scripts/check-structure.sh          # add --allow-web-pending while apps/web is unfinished
infra/scripts/check-structure.sh --strict # release gate: pending items become fatal too
infra/scripts/validate-compose.sh         # static Compose/Dockerfile checks (works without Docker)
infra/scripts/check-no-env-committed.sh   # secret hygiene (also run by CI)
```

## 5. Creating the first administrator

```bash
infra/scripts/bootstrap-admin.sh --check      # is the account-creation endpoint deployed?
infra/scripts/bootstrap-admin.sh --dry-run \
    --email admin@example.de --password '<12+ chars>' \
    --name "Admin" --tenant "Kanzlei Muster" --account-type COMPANY
```

The script drives the backend's own registration endpoint
(`POST /api/v1/auth/register`, which creates the tenant, the default workspace and the `OWNER`
membership) and **never** writes to the database directly — Argon2id hashing, audit events and
membership rules must be applied by the application.

> **Known blocker:** this currently fails with HTTP 403 because the API does not deliver a CSRF token
> to clients. See [Known blockers](#known-blockers).

Follow-up: registration requires **e-mail verification**. The response's `nextStep` is either
`VERIFY_EMAIL` (a mail was sent) or `VERIFY_EMAIL_PENDING_MAIL_CONFIGURATION` (no SMTP sender
configured — no mail was sent, so the verification token must be obtained by the operator).

A controlled, token-protected bootstrap flow (`POST /api/v1/onboarding`) is planned but not
implemented (implementation-plan A-018/A-024).

## 6. Operations: backup and restore

```bash
# Backup: pg_dump custom format + object-storage snapshot, timestamped
BC_ENV_FILE=infra/.env infra/scripts/backup.sh

# Restore into a target database (explicit confirmations, refuses production targets)
CONFIRM=yes BC_ENV_FILE=infra/.env infra/scripts/restore.sh \
    infra/backups/<timestamp>/database.dump burokratie_restore_test

# Restore drill: restores the newest backup into a scratch database and
# checks table count, row counts, Flyway history, foreign keys and RLS
CONFIRM=yes BC_ENV_FILE=infra/.env infra/scripts/verify-restore.sh

# TCP readiness gate for startup scripts
infra/scripts/wait-for.sh postgres:5432 60
```

A backup that has never been restored is not a backup: `verify-restore.sh` is the drill that proves
the pipeline still works. Full procedure: [`docs/runbook.md`](docs/runbook.md).

## 7. Documentation

| Document | Content |
| --- | --- |
| [`docs/task.md`](docs/task.md) | The full specification (Russian) |
| [`docs/implementation-plan.md`](docs/implementation-plan.md) | Staged plan, ADRs, open questions, progress log |
| [`docs/architecture.md`](docs/architecture.md) | Architecture (owned by another workstream) |
| [`docs/data-model.md`](docs/data-model.md) | PostgreSQL data model (owned by another workstream) |
| [`docs/security.md`](docs/security.md) | Security concept (owned by another workstream) |
| [`docs/runbook.md`](docs/runbook.md) | Operations: start/stop, health, migrations, rollback, backup drill, incidents |
| [`docs/billing.md`](docs/billing.md) | Plans and prices, 60-day trial, what happens when access ends, payment by invoice or crypto wallet, deletion of data, the admin area |
| [`docs/deployment.md`](docs/deployment.md) | Production deployment: reverse proxy/TLS, secrets, data region, sizing, zero downtime, owner-supplied credentials |
| [`docs/integrations.md`](docs/integrations.md) | Every §18 integration with its honest status and the exact owner actions |
| [`tests/load/README.md`](tests/load/README.md) | Load tests and the conditions that must be recorded with any result |
| [`infra/.env.example`](infra/.env.example) | Every environment variable, grouped and commented |

## 8. Repository layout (spec §2.2)

```
apps/web/              Next.js frontend
services/api/          Spring Boot backend (api + worker share one artifact)
infra/                 Docker, Compose, nginx, operational scripts, env template
  docker/              api.Dockerfile, web.Dockerfile, build-context exclusions
  nginx/               reverse-proxy templates and shared includes
  scripts/             check-structure, validate-compose, check-no-env-committed,
                       backup, restore, verify-restore, wait-for, bootstrap-admin
docs/                  specification, architecture, data model, security, operations
tests/load/            k6 scenarios + fixed test conditions
.github/workflows/     CI (build, tests, security scans, Compose and image validation)
```

Run `infra/scripts/check-structure.sh` to verify it mechanically. While the frontend is still being
finished, use `--allow-web-pending` to have a not-yet-correct `apps/web` configuration reported as
PENDING rather than FAIL; a release must run the plain (or `--strict`) form.

## 9. Status

Legend: **implemented** = code exists and was exercised; **partial** = code exists but the scenario is
not verified end to end; **in progress** = actively being written; **requires external dependency** =
cannot work without a contract, credential or device from the system owner; **not started**.

| Area | Status | Evidence / note |
| --- | --- | --- |
| Repository structure, git hygiene, `.gitignore` | implemented | `infra/scripts/check-structure.sh` |
| PostgreSQL data model (84 tables of §19, Flyway V1–V15) | implemented | `SchemaIT`, `FlywayMigrationIT`; verified: 117 relations, 378 FKs, RLS on 81 tables |
| Row-level security + tenant isolation | partial | `V13` applies RLS + append-only triggers; `SchemaIT` covers isolation; application-side tenant context is new |
| Identity: registration, login, sessions, e-mail verification, password reset | partial | `AuthController`, `AuthService`, `SessionAuthenticationFilter`, `AuthFlowIT` — **registration is blocked by the CSRF defect below** |
| MFA (TOTP), API tokens, invitations | not started | `mfa_methods`, `api_tokens`, `invitations` tables exist; no implementation found |
| RBAC roles/permissions | partial | `Role`, `Permission`, `MembershipAuthorityResolver`; full permission matrix not verified |
| Audit trail (append-only, §21.4) | partial | `AuditService`, `audit_events` with append-only triggers, `AuditServiceIT` |
| Document module (upload, storage, OCR, extraction, classification) | not started | tables (`documents`, `document_blobs`, `document_extractions`, …) exist; no service/controller |
| Cases, tasks, deadlines, contracts, finance | not started | schema exists (V4–V8); no implementation |
| Workflow engine, approvals, outbox dispatcher | not started | schema exists (V6, `outbox_events`); worker role does not start (blocker below) |
| Integration Hub and all §18 adapters | requires external dependency | see [`docs/integrations.md`](docs/integrations.md) — storage (S3/MinIO) and AI/OCR configuration keys exist, no provider adapter is connected |
| AI layer (provider abstraction, budgets, prompt-injection defence) | requires external dependency | `BC_AI_*` configuration exists and defaults to disabled; no provider adapter, no API key |
| OCR (Tesseract) | requires external dependency | `BC_OCR_*` configuration exists and defaults to disabled; Tesseract + `deu` data are not installed |
| Frontend shell (design system, navigation, i18n, all main screens) | in progress | `apps/web/src/app/**` routes exist; screens are shells, most are not yet connected to the backend |
| Frontend ↔ backend integration | not started | the API modules the UI needs do not exist yet |
| Backend container image (api + worker) | partial | `infra/docker/api.Dockerfile`; never built (no Docker available) |
| Frontend container image | partial | `infra/docker/web.Dockerfile` **requires `output: "standalone"` in `apps/web/next.config.ts`, which is currently missing** |
| Docker Compose (dev + prod overrides) | partial | YAML + structure validated statically and in CI; never started |
| Production reverse proxy (nginx templates, TLS, rate limits) | partial | `infra/nginx/**`; configuration never rendered by a running nginx |
| CI pipeline (tests, security scans, Compose validation) | implemented | `.github/workflows/ci.yml`; runs on GitHub runners |
| Backup / restore / restore drill scripts | implemented | exercised against PostgreSQL 17.11: dump → restore → 11 integrity checks PASS (378 FKs, RLS on 81 tables). The artifacts from that drill were deleted afterwards, so the repository contains no database dump — reproduce it with `verify-restore.sh` (docs/runbook.md §6.3) |
| Static Compose/Dockerfile validation without Docker | implemented | `infra/scripts/validate-compose.sh` (22 assertions incl. port exposure and read-only checks); `docker compose config` still unverified here |
| Load tests (§27) | not started | k6 scripts written; **never executed** (k6 and Docker unavailable here) |
| Operational documentation | implemented | `docs/runbook.md`, `docs/deployment.md`, `docs/integrations.md`, this README |
| Production deployment | not started | no environment has been deployed |

## 10. Known blockers

Defects found by actually running the artifacts in this environment (postgres 17.11, JDK 21,
2026‑10‑11). None of them can be fixed from `infra/**`, `tests/**` or the documentation — they belong
to `services/api` and `apps/web`:

1. **The `worker` profile does not start.** With `--spring.profiles.active=worker`
   (`spring.main.web-application-type: none`) the context fails:
   `SecurityConfiguration.securityFilterChain` requires an `HttpSecurity` bean that does not exist
   outside a servlet web application. *Impact:* the Compose `worker` service crash-loops; no
   background processing (outbox, OCR, AI, retention). *Fix:* make the security configuration
   conditional (`@ConditionalOnWebApplication`) or split the servlet security chain from the worker
   context.
2. **Flyway `V14` fails on a fresh database.** `V14__semantic_search_optional.sql` creates
   `document_embeddings.embedding vector NOT NULL` without a dimension, then builds an HNSW index,
   which PostgreSQL rejects with `column does not have dimensions`. It fails on a clean install and
   **rolls back the whole migration**, so a fresh database never reaches V15. *Impact:* a clean
   install cannot be bootstrapped (this also blocked the admin bootstrap check). *Fix:* give the
   column an explicit dimension (configuration-driven, e.g. `vector(1536)`) or make the index step
   conditional.
3. **Registration is unreachable: the CSRF token is never delivered.** `GET /api/v1/auth/csrf`
   returns `204` without a `Set-Cookie: XSRF-TOKEN`, because the `CsrfToken` is never *resolved*
   during the request. Every state-changing call — including sign-in and registration from a real
   browser — is answered with HTTP `403 FORBIDDEN`. Verified with `curl` against a running build.
   *Impact:* no user can register or sign in; `infra/scripts/bootstrap-admin.sh` cannot create the
   first administrator; **no usable account exists today**. *Fix:* resolve the token in the handler
   (accept `CsrfToken` as a method parameter) so the cookie is written. Do **not** disable CSRF.
4. **`apps/web/next.config.ts` lacks `output: "standalone"`.** Without it the frontend image build
   fails at the `COPY .next/standalone` step and CI's frontend job fails on its explicit check.
   *Fix:* add `output: "standalone"` to the Next.js config.

## 11. What must come from the system owner

Even when the code above is finished, the following cannot be supplied by this repository. Each is
listed with what it unblocks in [`docs/integrations.md`](docs/integrations.md) and
[`docs/deployment.md`](docs/deployment.md):

- **Object storage credentials** (MinIO or S3-compatible: endpoint, region, bucket, access key,
  secret key) — required for any non-toy deployment; `local-fs` storage is not shared between
  containers and is lost on redeploy.
- **AI provider contract + API key** (OpenAI-compatible or a local model endpoint) and an agreed
  monthly budget in EUR (`BC_AI_MONTHLY_BUDGET_EUR`).
- **Mail delivery and OAuth app registrations**: an SMTP relay, plus an Azure AD app registration for
  Microsoft 365 and/or a Google Cloud OAuth client for Gmail/Google Workspace (redirect URIs and
  scopes are documented in `docs/integrations.md`).
- **Banking provider contract** (PSD2 licence or an aggregator agreement) — otherwise only offline
  CAMT.053/MT940/CSV import is possible.
- **E-signature provider contract and signing certificate** (qualified certificate or remote-signing
  service credentials).
- **Domain, DNS and TLS certificate** for the public URL (`BC_PUBLIC_BASE_URL`), plus a decision on
  the data region.
- **A signed DPA/AVV with every subprocessor** before any personal data leaves the deployment.

## 12. Contributing rules that are enforced, not suggested

- No fabricated functionality: a module is not "done" because a screen renders (spec §1.2, §34).
- No secrets in the repository — CI fails on a committed `.env` or private key.
- A backup is only trusted after `verify-restore.sh` has restored it successfully.
- Destructive scripts (`restore.sh`, `backup.sh`) need explicit confirmation and refuse
  production-looking targets.
- Performance claims require a load test with recorded conditions (spec §27).
