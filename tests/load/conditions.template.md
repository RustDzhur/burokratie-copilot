# Fixed test conditions — record this WITH every load-test result

Spec §27: *"Конкретные показатели производительности должны проверяться нагрузочными тестами. Для
начальной приёмки используй согласованные контрольные наборы и зафиксированные условия испытаний."*

A performance number without its conditions is not evidence. Copy this file to
`tests/load/results/<your run id>/conditions.md`, fill in **every** field (write `n/a` with a reason
if a field genuinely does not apply — do not leave it blank), and keep it next to the k6 summary
export. `run-load-test.sh` already writes a machine-generated `conditions.txt`; this file adds the
facts that are invisible from the load generator (host sizing, dataset size, feature flags …).

---

## 1. Identification

| Field | Value |
| --- | --- |
| Date and time (with timezone, e.g. `2026-10-11 14:03 Europe/Berlin`) | |
| Operator (name/role) | |
| Purpose (baseline / regression check / acceptance run / capacity planning) | |
| Result id (directory under `tests/load/results/`) | |
| Git commit SHA of the system under test | |
| Backend build: image tag or jar SHA-256 | |
| Frontend build (only for end-to-end runs) | |
| Flyway schema version (`SELECT max(version) FROM flyway_schema_history WHERE success`) | |

## 2. Environment under test

| Field | Value |
| --- | --- |
| Environment (local laptop / dev server / staging / production) | |
| Host: CPU model and core count | |
| Host: RAM | |
| Host: disk type (NVMe / SATA SSD / network volume) | |
| Operating system and kernel | |
| Containerised? (Docker Compose / Kubernetes / bare process) | |
| CPU/memory limits applied to api and worker (`BC_API_MEMORY_LIMIT`, …) | |
| JVM flags in effect (`JAVA_TOOL_OPTIONS` / `BC_JAVA_OPTS`) | |
| Number of api instances / worker instances | |

## 3. Database

| Field | Value |
| --- | --- |
| PostgreSQL version | |
| Server host (same host as the API? network hop?) | |
| `max_connections`, `shared_buffers`, `effective_cache_size` | |
| HikariCP pool size (`BC_DB_POOL_SIZE`) | |
| Dataset size: number of tenants | |
| Dataset size: number of users | |
| Dataset size: number of documents | |
| Dataset size: number of cases / tasks | |
| Total size of document blobs | |
| Database warmed before the run? (which queries / how many iterations) | |
| Vacuum/analyze state (`pg_stat_user_tables.last_analyze` fresh?) | |

## 4. Storage

| Field | Value |
| --- | --- |
| Storage backend (`BC_STORAGE_TYPE`: `local-fs` or `s3`) | |
| If S3/MinIO: endpoint, region, whether it runs on the same host | |
| If `local-fs`: filesystem type of `BC_STORAGE_PATH` | |

## 5. Feature flags that change the code path

| Field | Value |
| --- | --- |
| OCR enabled? (`BC_OCR_ENABLED`) engine + languages | |
| AI enabled? (`BC_AI_ENABLED`) provider + model | |
| Mail sync active during the run? | |
| Background worker active? (`BC_WORKER_CONCURRENCY`) | |

> Results with OCR or AI **enabled** are not comparable with results where they are disabled.
> State explicitly which mode this run used.

## 6. Load generator

| Field | Value |
| --- | --- |
| Tool and version (`k6 v0.x.y` / Locust …) | |
| Generator host: CPU/RAM | |
| Network path to the target (same host / LAN / internet, approximate RTT) | |
| Did the generator share CPU with the system under test? | |

## 7. Scenario definition

| Field | Value |
| --- | --- |
| Scenario (`smoke` / `upload-documents` / `list-documents`) | |
| Endpoint and method | |
| Endpoint path override used (if not the default) | |
| Auth mode (`none` / `token` / `cookie` / `login`) | |
| Virtual users (start → target) | |
| Ramp-up / hold / ramp-down durations | |
| Think time between iterations | |
| Payload: fixture file name, format, size, page count, whether it is a real scan | |
| Payload: synthetic or real | |
| List endpoint page size (`size=`) and page number | |
| Total iterations completed | |

## 8. Results

| Metric | Value |
| --- | --- |
| Throughput (requests/s, and documents/s for the upload scenario) | |
| Latency p50 / p90 / p95 / p99 (ms) | |
| Error rate (%) | |
| Upload throughput (bytes/s) — upload scenario | |
| List response size (bytes) — list scenario | |
| Threshold results (which passed, which failed) | |
| Raw summary export file name | |
| Observed client-side errors (timeouts, connection resets) | |

## 9. Validity and limitations

- [ ] The stack was healthy (`/actuator/health` = `UP`) before and after the run.
- [ ] The dataset size is recorded above and is representative of the target scenario.
- [ ] No other heavy job ran on the same host during the measurement.
- [ ] Tolerances are stated: these numbers are valid only for the conditions above.
- [ ] Thresholds were agreed with the product owner (state who and when), or this run is
      informational only and **must not** be used as an acceptance criterion.
- [ ] Every failed threshold is listed with a follow-up action.

Known limitations / caveats of this run:

```

```

---

### Standing caveats (keep these unless they are demonstrably no longer true)

1. **Nothing has been measured yet in the reference environment.** k6 is not installed there and
   Docker is unavailable, so no load test has been executed; the scripts and this template are
   untested against a live stack. The first run must be treated as a smoke test.
2. **Endpoint paths are provisional.** The defaults (`/api/v1/documents`) follow the API groups in
   the specification and must be confirmed against the generated OpenAPI document (`/api-docs`)
   before a result is quoted.
3. The backend is under active implementation; a result measured today is a snapshot of a specific
   commit, not a property of the product.
