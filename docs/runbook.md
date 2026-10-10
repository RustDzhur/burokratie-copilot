# Runbook — Bürokratie-Copilot

Operational procedures for the Bürokratie-Copilot platform: starting and stopping the system,
checking health, reading logs, migrating the database, rolling back a release, and the backup/restore
drill. Everything destructive is guarded.

Audience: whoever operates the deployment (SRE, DevOps, the technical owner).

> ### Verification status — read first
> Every command below was **written and statically validated** in an environment with **no Docker**
> and **no k6**. Concretely:
>
> | Item | How it was verified |
> | --- | --- |
> | `infra/docker-compose.yml`, `infra/docker-compose.prod.yml` | YAML parse (PyYAML) + structural assertions in CI; `docker compose config` runs only on a Docker-enabled runner. **Never started.** |
> | `infra/docker/*.Dockerfile` | written and reviewed; **never built** — no Docker daemon available |
> | `infra/nginx/**` | template syntax reviewed against the nginx image's `envsubst` behaviour; **never rendered by a running nginx** |
> | `infra/scripts/backup.sh`, `restore.sh`, `verify-restore.sh` | **exercised for real** against PostgreSQL 17.11: dump → restore into a scratch database → 11 integrity checks (table count, 64 key tables' row counts, Flyway history, 378 foreign keys, RLS on 81 tables) → **PASS** |
> | `infra/scripts/wait-for.sh`, `check-structure.sh`, `check-no-env-committed.sh` | executed; exit codes verified |
> | `infra/scripts/validate-compose.sh` | executed: 22 static assertions on both Compose files and both Dockerfiles (services, healthcheck gating, loopback-only publishing in dev, no datastore port in prod, read-only root filesystems, immutable images, non-root users). `docker compose config` is **SKIPPED** here (no daemon) and runs only in CI |
> | `infra/scripts/bootstrap-admin.sh` | executed against a running backend: detects `POST /api/v1/auth/register`; the registration itself currently returns HTTP 403 because the API does not deliver a CSRF token (see the blocker list in `README.md`) |
> | Local (non-Docker) start of `api` | **executed** with JDK 21 + PostgreSQL 17.11: `/actuator/health` = UP, `/actuator/health/readiness` = 200 |
> | Local start of the `worker` profile | **executed and FAILED** — the worker does not start today (blocker 1 in `README.md`); the worker procedures below are therefore untested |
> | Load tests | **never executed** — see `tests/load/README.md` |
>
> Do not treat an unverified procedure as rehearsed. The first execution in a real environment is
> part of the test.

---

## 1. Service overview

| Service | Role | Port (default) | Health endpoint | Restart policy |
| --- | --- | --- | --- | --- |
| `postgres` | System of record (PostgreSQL 17) | 5432 (loopback only) | `pg_isready -U $POSTGRES_USER -d $POSTGRES_DB` | `unless-stopped` / `always` |
| `minio` | S3-compatible object storage for document blobs | 9000 (S3), 9001 (console, dev only) | `GET /minio/health/live` | `unless-stopped` |
| `minio-init` | One-shot: creates the bucket, applies a bucket policy | – | exit code 0 | `no` (must complete before `api` starts) |
| `api` | REST API `/api/v1`, serves the web UI's requests | 8080 (`BC_PORT`) | `GET /actuator/health/readiness` | `unless-stopped` |
| `worker` | Background jobs (outbox, OCR/AI, retention) — same image, no HTTP | 8081 (management only, `BC_WORKER_MANAGEMENT_PORT`) | `GET /actuator/health` | `unless-stopped` |
| `web` | Next.js frontend (standalone build) | 3000 | `GET /` | `unless-stopped` |
| `proxy` | TLS termination + reverse proxy (**prod only**, profile `proxy`) | 80, 443 | `GET /healthz` | `unless-stopped` |

Health endpoint semantics:

- `/actuator/health` — the full group: liveness + readiness + every registered indicator (database,
  disk space, …). **DOWN here means a required dependency is gone.**
- `/actuator/health/liveness` — "the process is alive". A restart makes sense when this fails.
- `/actuator/health/readiness` — "the instance can serve traffic". The Compose healthcheck of `api`
  uses this, so a container with a broken datasource is correctly marked unhealthy and is not routed
  to. **This is the probe the reverse proxy / orchestrator should use for traffic decisions.**
- `/actuator/prometheus` — metrics in Prometheus format. **Restrict access**: only from the private
  network. The provided nginx configuration already denies `/actuator/*` from public addresses.

---

## 2. Start, stop, status

### 2.1 Without Docker (development / single-host without containers)

```bash
# 1. database must be up
pg_isready -h localhost -p 5432

# 2. backend
cd services/api
BC_DB_HOST=localhost BC_DB_NAME=burokratie_dev BC_DB_USER=<user> BC_DB_PASSWORD=<password> \
BC_ENCRYPTION_KEY='<base64-32-bytes>' ./mvnw spring-boot:run

# 3. frontend (second terminal)
cd apps/web && NEXT_PUBLIC_API_BASE_URL=http://localhost:8080 pnpm dev

# 4. worker (third terminal) — SAME jar, different profile
java -jar services/api/target/burokratie-copilot-api.jar --spring.profiles.active=worker
```

Stop with `Ctrl-C` (or `kill <pid>`): the backend is configured with `server.shutdown=graceful`, so
in-flight requests are drained before the process exits.

### 2.2 With Docker Compose

```bash
ENV="--env-file infra/.env"
DEV="-f infra/docker-compose.yml"
PROD="-f infra/docker-compose.yml -f infra/docker-compose.prod.yml"

# --- dev stack ---
docker compose $ENV $DEV up -d
docker compose $ENV $DEV ps
docker compose $ENV $DEV logs -f --tail=100 api worker

# stop (keeps volumes and data)
docker compose $ENV $DEV stop
# stop and remove containers (keeps named volumes → data survives)
docker compose $ENV $DEV down

# --- production stack (proxy profile publishes 80/443) ---
docker compose $ENV $PROD --profile proxy up -d
docker compose $ENV $PROD --profile proxy ps

# rolling restart of a single service (migrations are NOT run by the worker)
docker compose $ENV $PROD up -d --no-deps api
```

**`docker compose down -v` deletes the named volumes and therefore the database and blobs.**
Never run it on an environment you care about; that is what the backup scripts are for.

Individual services:

```bash
docker compose $ENV $DEV restart api
docker compose $ENV $DEV up -d --scale worker=3      # scale background processing
docker compose $ENV $DEV exec postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
docker compose $ENV $DEV exec api sh                 # shell inside the api container (non-root)
```

### 2.3 Status checklist (30 seconds)

```bash
# 1. containers healthy?
docker compose $ENV $PROD ps            # every service must show "healthy" (or "exited (0)" for minio-init)

# 2. API readiness + details
curl -s http://127.0.0.1:8080/actuator/health/readiness; echo
curl -s http://127.0.0.1:8080/actuator/health | python3 -m json.tool | head -40

# 3. worker health (no published port in the stack — use the internal network)
docker compose $ENV $PROD exec worker curl -fsS http://127.0.0.1:8081/actuator/health; echo

# 4. database reachable and on the expected schema version
psql -h localhost -U <user> -d <db> -c \
  "SELECT max(version) AS schema_version, count(*) AS migrations
     FROM flyway_schema_history WHERE success;"

# 5. object storage
curl -fsS http://127.0.0.1:9000/minio/health/live && echo " minio OK"

# 6. recent errors
docker compose $ENV $PROD logs --since 15m api worker | grep -iE "error|exception" | tail -20
```

Static configuration check (no daemon required — safe to run before a deploy):

```bash
infra/scripts/validate-compose.sh          # YAML + structure + (if possible) docker compose config
infra/scripts/check-structure.sh --quiet   # repository layout and hygiene
```

When a check fails:

| Symptom | Likely cause | First action |
| --- | --- | --- |
| `api` unhealthy, `/actuator/health` DOWN with `db` | database down, wrong credentials, or pool exhausted | `pg_isready`, check `BC_DB_*`, look for `HikariPool` timeouts in the logs |
| `api` unhealthy, `db` UP but `diskSpace` DOWN | host disk full (10 MB threshold) | free space, check `/app/data` and the Docker volumes |
| `api` healthy but every request 401/403 | session/CSRF problem or clock drift on TOTP | see §8 and the CSRF blocker in `README.md` |
| `worker` unhealthy | today this is expected: the `worker` profile does not start (blocker 1 in `README.md`) | fix the backend before relying on background jobs |
| `minio-init` exited non-zero | MinIO did not become ready, or access-key creation failed | `docker compose logs minio-init minio` |
| `web` unhealthy | the standalone build is missing or the process cannot bind | `docker compose logs web`; the image must contain `.next/standalone` |
| 502 from the proxy | backend unhealthy, or the proxy cannot resolve the service name | `docker compose logs proxy`, check the upstream name in the nginx template |

---

## 3. Logs

| What | Where |
| --- | --- |
| `api` / `worker` | **stdout/stderr** of the container, collected by the Docker `json-file` driver (rotated: 20 MB × 5 files in prod, 10 MB × 5 in dev) → `docker compose logs`. Non-container runs: the console. |
| Spring profile `prod` | structured JSON (logstash encoder) — one JSON object per line, ready for Loki/ELK/CloudWatch |
| Spring profiles `dev`/`test` | human-readable console output |
| `postgres` | container stdout (slow queries above `log_min_duration_statement`, default 1000 ms) |
| `minio` | container stdout |
| `web` | container stdout (Next.js server logs) |
| `proxy` | access log → stdout in `bc_main` format (**URI only, no query string**, so tokens in query strings never reach the log); error log → stderr |
| Correlation | every API response carries `X-Correlation-Id`; the same value appears in the application log line, which is how you trace one request across services |

Useful queries:

```bash
docker compose $ENV $PROD logs --since 1h api | grep -i "correlationId.*<uuid>"
docker compose $ENV $PROD logs --since 1h worker | grep -iE "outbox|retry|circuit" | tail -50
docker compose $ENV $PROD logs --since 30m api | grep -c '"level":"ERROR"'
```

**What must never appear in the logs** (spec §21.1): document contents, extracted personal data,
passwords, session tokens, API keys, `BC_ENCRYPTION_KEY`, full request bodies of uploads, or
credentials in URLs. The backend ships a `LogSanitizer` (`shared/audit/LogSanitizer.java`) for this;
the proxy access log deliberately drops query strings. If you find a secret in the logs, treat it as
a leak: rotate it (§6.4 of `docs/deployment.md`) and file a defect.

---

## 4. Database migrations

Flyway migrations live in `services/api/src/main/resources/db/migration` (`V1..V15`). On startup the
application runs `migrate` with `validate-on-migrate: true`, `baseline-on-migrate: false` and
`out-of-order: false`. In the `prod` profile they are gated by `BC_FLYWAY_ENABLED`.

**Rule (spec §30): never migrate a production database without a controlled process and a verified
backup.**

### 4.1 Before a migration

```bash
# 1. announce / open the maintenance window, freeze writes if the change is not additive
# 2. take a backup and PROVE it restores
BC_ENV_FILE=infra/.env I_KNOW_THIS_IS_PRODUCTION=yes infra/scripts/backup.sh
CONFIRM=yes BC_ENV_FILE=infra/.env infra/scripts/verify-restore.sh

# 3. record the current schema version
psql "$PROD_URL" -c "SELECT max(version), count(*) FROM flyway_schema_history WHERE success;"

# 4. read what the new migrations do — do not run them blindly
ls -1 services/api/src/main/resources/db/migration | tail -5
```

### 4.2 Controlled run

Run migrations as a **discrete release step**, not as a side effect of a rolling restart:

```bash
# 1. stop the writer that is allowed to migrate (api keeps serving read traffic)
docker compose $ENV $PROD stop api

# 2. one-shot migration with only the migration profile enabled
docker compose $ENV $PROD run --rm \
  -e BC_FLYWAY_ENABLED=true -e SPRING_PROFILES_ACTIVE=prod \
  api --spring.main.web-application-type=none
#    -> exit code 0 and "Successfully applied N migration(s)" in the output

# 3. verify
psql "$PROD_URL" -c \
  "SELECT installed_rank, version, description, success, execution_time
     FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"

# 4. start the new application version (api first, then worker with BC_FLYWAY_ENABLED=false)
docker compose $ENV $PROD up -d --no-deps api
docker compose $ENV $PROD up -d --no-deps worker
```

The `worker` service runs with `BC_FLYWAY_ENABLED=false` on purpose: exactly one component may
migrate, otherwise two processes race for the Flyway lock.

### 4.3 If a migration fails

1. **Stop.** Do not re-run, do not "fix forward" on production.
2. Read the error: Flyway runs each migration in a transaction on PostgreSQL, so a failed migration
   is normally rolled back completely.
3. Check the state:
   ```bash
   psql "$PROD_URL" -c "SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
   ```
   A row with `success = false` means the schema is in an unknown state for that version.
4. Restore the pre-migration backup (§5) and roll the application back to the previous image.
5. Fix the migration in a new version and repeat §4.1 — **never edit an already-applied migration**:
   `validate-on-migrate` compares checksums and will refuse to start.

> **Known defect:** `V14__semantic_search_optional.sql` fails on a *fresh* database
> (`column does not have dimensions` when creating the HNSW index) and rolls back, so a clean install
> never reaches `V15`. See blocker 2 in `README.md`. This does not affect a database that already
> applied V14 successfully.

---

## 5. Rollback

### 5.1 Application rollback (the normal case)

```bash
# 1. back to the previous image digest; migrations stay as they are
export BC_IMAGE_TAG=<previous-git-sha-or-semver>
docker compose $ENV $PROD -e BC_FLYWAY_ENABLED=false up -d --no-deps api
docker compose $ENV $PROD -e BC_FLYWAY_ENABLED=false up -d --no-deps worker

# 2. confirm
curl -s http://127.0.0.1:8080/actuator/health/readiness; echo
docker compose $ENV $PROD ps
```

Time budget: a rolling restart of `api` and `worker` takes about 1–3 minutes (start period + health
gate). This works when the previous release is compatible with the current schema — i.e. when the
migration was **additive** (new nullable columns, new tables, new indexes).

### 5.2 Schema rollback (when compatibility is broken)

Flyway migrations are **forward-only**: there is no `undo`. A schema rollback therefore means
restoring the pre-migration backup:

```bash
# 1. stop everything that writes
docker compose $ENV $PROD stop api worker

# 2. take a forensic copy of the CURRENT (broken) state before overwriting it —
#    you will need it for the post-mortem
BC_ENV_FILE=infra/.env I_KNOW_THIS_IS_PRODUCTION=yes infra/scripts/backup.sh

# 3. restore the pre-migration dump over the production database
CONFIRM=yes FORCE=yes ALLOW_NONEMPTY=yes \
BC_ENV_FILE=infra/.env infra/scripts/restore.sh \
  infra/backups/<pre-migration-timestamp>/database.dump <production-database>

# 4. restore the object-storage half as well, otherwise document metadata points at
#    missing blobs (the dump and the snapshot from the SAME timestamp belong together)
#    mc mirror <backup>/objects/ <alias>/<bucket>     # or: aws s3 sync <backup>/objects s3://<bucket>

# 5. redeploy the previous image, then verify
export BC_IMAGE_TAG=<previous>
docker compose $ENV $PROD -e BC_FLYWAY_ENABLED=false up -d
CONFIRM=yes BC_ENV_FILE=infra/.env infra/scripts/verify-restore.sh   # against a scratch DB
```

`FORCE=yes` is required because the target looks like production, and `ALLOW_NONEMPTY=yes` because the
database contains tables. Both exist so that this cannot happen by accident.

**Data loss:** a restore loses everything written after the backup was taken. Record the exact
timestamp in the incident report, and decide explicitly whether the alternative (forward-fixing in
production) is worse.

---

## 6. Backup and restore drill

### 6.1 What is backed up

`infra/scripts/backup.sh` produces one timestamped directory per run:

```
infra/backups/<UTC timestamp>/
├── database.dump           pg_dump custom format (-Fc, level 9) — the whole database
├── database.dump.list      table-of-contents listing (proves the archive is readable)
├── database.dump.sha256    checksum
├── objects/                mirror of the S3/MinIO bucket (document blobs)
└── metadata.txt            non-secret facts: server version, table count, Flyway head, sizes, git revision
```

**Both halves are required.** A database-only restore leaves document rows pointing at blobs that no
longer exist; a blob-only restore has no metadata. They are only consistent when taken together —
hence the single timestamped directory.

### 6.2 Cadence and retention

| What | Recommendation |
| --- | --- |
| Full database + objects | **daily** (`BC_BACKUP_RETENTION_DAYS`, default 14 local) |
| Before every migration or bulk operation | always, plus a successful `verify-restore.sh` |
| Off-host copy | immediately after every backup — a backup on the same host is not a backup |
| Restore drill | **monthly**, and after every change to the backup scripts or the deployment topology |
| Retention off-host | keep at least 30 daily, 12 monthly, and the pre-migration backups indefinitely |

Schedule it (host cron example):

```cron
# daily at 02:30, log to a file, keep the last 30 days of logs
30 2 * * * cd /srv/burokratie-copilot && BC_ENV_FILE=infra/.env I_KNOW_THIS_IS_PRODUCTION=yes \
  infra/scripts/backup.sh >> /var/log/bc-backup.log 2>&1
```

Never leave backups only on the application host.

### 6.3 Running the drill (do this monthly)

```bash
# 1. identify what you are about to verify
ls -1dt infra/backups/*/ | head -3

# 2. restore it into a throwaway database and run every integrity check
CONFIRM=yes BC_ENV_FILE=infra/.env infra/scripts/verify-restore.sh

# 3. to inspect the result manually afterwards
CONFIRM=yes KEEP=yes BC_ENV_FILE=infra/.env infra/scripts/verify-restore.sh
psql -h localhost -U <user> -d bc_restore_verify_<timestamp> -c "\dt" | head
dropdb bc_restore_verify_<timestamp>          # clean up when finished
```

The drill checks, and fails loudly on any of them:

1. the dump archive is readable by `pg_restore`;
2. the restored table count matches the count recorded at backup time;
3. every key table exists and its row count matches the archive (64 tables across all domains);
4. `flyway_schema_history` is present, contiguous, free of duplicates and has the expected head
   version — no failed migration is hidden in it;
5. **no orphaned rows across all foreign keys** (378 of them in the current schema);
6. row-level security is still enabled (tenant isolation would otherwise be silently lost) and the
   `pgcrypto`/`citext` extensions are present;
7. an object-storage snapshot is part of the backup (skip explicitly with
   `SKIP_OBJECTS_CHECK=yes` and say so in the report).

Expected tail of a healthy run:

```
 checks passed      : 11
 checks failed      : 0
verify-restore.sh: PASS — the backup restored cleanly and passed every integrity check
```

### 6.4 Restoring for real

```bash
# into a NEW database (never over a live one if you can avoid it)
CONFIRM=yes BC_ENV_FILE=infra/.env infra/scripts/restore.sh \
  infra/backups/<timestamp>/database.dump burokratie_restored

# point the application at it for acceptance testing
BC_DB_NAME=burokratie_restored ... docker compose $ENV $DEV up -d api
```

Hard rules enforced by the scripts:

- a dump must be named explicitly — there is no "restore the latest" shortcut;
- `CONFIRM=yes` is mandatory; a production-looking host additionally requires `FORCE=yes`;
- an empty target is created automatically; a non-empty one is refused without `ALLOW_NONEMPTY=yes`;
- restoring a dump over **its own source database** is refused unless `ALLOW_SAME_DATABASE=yes`;
- passwords never appear in the process list or in any output (they travel through `PGPASSWORD`).

---

## 7. Routine maintenance

| Task | Cadence | Command / note |
| --- | --- | --- |
| Backup + off-host copy | daily | §6.2 |
| Restore drill | monthly | §6.3 |
| Disk space (DB, blobs, backups) | weekly | `df -h`, `docker system df`, check the `diskSpace` health indicator |
| PostgreSQL maintenance | weekly | `VACUUM (ANALYZE)` on hot tables; watch autovacuum on `documents`, `audit_events`, `outbox_events` |
| Outbox backlog | daily | alerts on the number of unprocessed `outbox_events` (`max-attempts` is 8 — a growing backlog means the worker is stuck) |
| TLS certificate renewal | 30 days before expiry | ACME/certbot; the proxy reads certificates from `infra/nginx/certs` (restart the proxy after renewal, or reload it) |
| Dependency and secret scanning | every CI run | `security` job in `.github/workflows/ci.yml` |
| Review `audit_events` growth | monthly | partition/archive strategy before the table dominates the database |
| Rotate credentials | quarterly, or on suspicion | see §8.4 |

### 7.1 Rotating secrets — what is and is not a hot operation

| Secret | Rotation |
| --- | --- |
| `POSTGRES_PASSWORD` / `BC_DB_PASSWORD` | **hot**: change the role password, update the secret, restart `api`/`worker` (brief connection errors during the restart window) |
| `BC_S3_ACCESS_KEY` / `BC_S3_SECRET_KEY` | **hot** if the object store accepts two keys at once (create the new key, roll the containers, delete the old key) |
| `BC_AI_API_KEY`, mail/SMTP credentials | **hot**: restart the affected containers |
| `BC_ENCRYPTION_KEY` | **NOT hot.** Data encrypted with the old key cannot be read with the new one. Rotation requires: decrypt-with-old → re-encrypt-with-new (a maintenance operation with a scripted migration), or re-entry of the affected credentials. Plan a window and a backup first. Losing this key makes encrypted values unrecoverable. |
| Session cookie secret / encryption of sessions | rolling all sessions invalidates every login — acceptable, but announce it |

---

## 8. Incident checklist

### 8.1 Triage order (5 minutes)

1. **Scope:** is it one user, one tenant, or everyone? Does the API answer at all?
   `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/health`
2. **Readiness vs liveness:** `/actuator/health` DOWN with the failing component named tells you
   whether it is the database, the disk or the application.
3. **Change correlation:** what was deployed or migrated in the last 24 h? (`git log --oneline -10`,
   `docker compose ps` image tags, `flyway_schema_history`)
4. **Logs:** `docker compose logs --since 30m api worker | grep -iE 'error|exception'`, and follow one
   `X-Correlation-Id` end to end.
5. **Resources:** host CPU/RAM/disk, connection-pool saturation (`HikariPool ... Timeout`),
   `pg_stat_activity` for long-running or blocked queries.
6. **External providers:** an AI/OCR/mail provider outage must degrade gracefully (feature reported as
   unavailable, work queued for retry) and must **never** be reported to the user as a success.

### 8.2 Capture for the post-mortem (before restarting anything)

- exact timestamps (UTC) and the affected tenant/user, with your timezone noted;
- the failing component and the health output at the time;
- relevant log lines including their `X-Correlation-Id`;
- image tags/digests and the git revision of the running release;
- database state: `flyway_schema_history` head, `pg_stat_activity` snapshot, any deadlock/error text;
- disk/CPU/memory at the moment of failure;
- the actions taken, in order, with timestamps — including the ones that did not help;
- whether personal data was exposed (then follow the data-protection process; GDPR/DSGVO notification
  deadlines may apply).

### 8.3 Escalation

| Severity | Meaning | Action |
| --- | --- | --- |
| S1 | Data loss, data leak, or the platform is unusable for all tenants | page the technical owner immediately, stop writes if data integrity is at risk, take a forensic backup, do not "try things" on the production database |
| S2 | A core workflow (documents, tasks, mail) is broken for many users | technical owner during working hours; mitigation = rollback (§5) |
| S3 | Single feature or provider degraded (AI/OCR/mail unavailable) | queue the work, report the degradation in the UI, fix in the next release |
| S4 | Cosmetic | normal backlog |

### 8.4 If a secret leaked

1. Assume compromise; do not wait for confirmation.
2. Rotate the affected credential (§7.1) — for `BC_ENCRYPTION_KEY` plan a window (§7.1).
3. Invalidate sessions (`sessions`/`user_sessions` rows) if a session secret or cookie material was
   exposed.
4. Review `audit_events` and `document_access_logs` for the exposure window.
5. If personal data was reachable, start the data-protection process (documentation, notification if
   required).
6. Remove the secret from git history (`git filter-repo`), force-push, and confirm with
   `infra/scripts/check-no-env-committed.sh` plus the CI `security` job.

---

## 9. First administrator

```bash
infra/scripts/bootstrap-admin.sh --check
infra/scripts/bootstrap-admin.sh --email admin@example.de --password '<12+ chars>' \
    --name "Admin" --tenant "Kanzlei Muster" --account-type COMPANY
```

The script uses the backend's own `POST /api/v1/auth/register` (which creates tenant + workspace +
`OWNER` membership) and refuses to write to the database directly.

> **Today this fails with HTTP 403** because the API does not deliver a CSRF token
> (`GET /api/v1/auth/csrf` returns 204 without `Set-Cookie: XSRF-TOKEN`), so no account can be
> created at all. See blocker 3 in `README.md`. The script detects the situation and explains it
> instead of pretending to succeed.

After a successful registration the account still needs **e-mail verification**; with no SMTP sender
configured the response says `VERIFY_EMAIL_PENDING_MAIL_CONFIGURATION` and no mail is sent. Without a
working SMTP relay (`SPRING_MAIL_HOST`, see `infra/.env.example` §7) **no account can be completed**,
so the relay credentials are a prerequisite for the first pilot.

A controlled, token-protected bootstrap flow (`POST /api/v1/onboarding`) is planned but not
implemented (implementation-plan A-018/A-024).

The four open defects that currently prevent a working end-to-end install are listed with their
evidence in `README.md` → *Known blockers*: the `worker` profile does not start, Flyway `V14` fails on
a fresh database, the CSRF token is never delivered (so registration and sign-in are unreachable),
and `apps/web/next.config.ts` lacks `output: "standalone"`.
