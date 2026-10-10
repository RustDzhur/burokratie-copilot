# Load tests — Bürokratie-Copilot

Performance figures for this project must come from measurable load tests whose test conditions are
recorded alongside the result (spec §27). This directory contains the k6 scenarios for the two
hot paths — **document upload** and **document list** — plus a wrapper that records the conditions
of every run.

> **Honest status: no load test has been executed yet.**
> `k6` is not installed in the environment where these scripts were written, and Docker (the
> fallback for running k6 in a container) is unavailable there as well. The scripts are syntax
> checked (`node --check`, `bash -n`) but have **never been run against a live stack**. There are
> therefore **no performance numbers for this product** — do not quote any. The first execution
> must be treated as a smoke test, and the endpoint paths must be confirmed against the generated
> OpenAPI document (`/api-docs`, Swagger UI at `/swagger-ui.html`) first, because the API is still
> being implemented.

---

## 1. Contents

| Path | Purpose |
| --- | --- |
| `k6/smoke.js` | 1 VU / 20 s sanity check: proves that the paths, auth and payload in these scripts actually work against a running stack |
| `k6/upload-documents.js` | `POST /api/v1/documents` (multipart) with a ramping VU profile; measures documents/s, bytes/s and latency |
| `k6/list-documents.js` | `GET /api/v1/documents?page=&size=` pagination read path; measures latency and response size |
| `k6/lib/config.js` | Every knob that defines a test condition (base URL, paths, auth mode, profile, thresholds) |
| `k6/lib/auth.js` | Four auth modes (`none`, `token`, `cookie`, `login`) plus the health preflight |
| `run-load-test.sh` | Runs a scenario and writes `conditions.txt`, the summary export and the full output under `results/<UTC timestamp>/` |
| `conditions.template.md` | The fixed-conditions sheet that must be completed with **every** result (spec §27) |
| `results/` | Generated output (not committed) |

## 2. Prerequisites

- A running stack: PostgreSQL 17 + object storage + the API (see the repository `README.md`).
- `k6`:

  ```bash
  # macOS
  brew install k6
  # Debian / Ubuntu (after adding the Grafana apt repository)
  sudo apt-get install k6
  # or run it as a container when Docker is available:
  docker run --rm -i grafana/k6 run - < tests/load/k6/smoke.js
  ```

- A fixture that represents real input for the upload scenario. Without `BC_LOAD_FIXTURE_DIR` the
  scripts generate a synthetic PDF — useful for smoke tests, **not comparable** with a real scanned
  document (different parser path, different OCR work).

## 3. Prepare a test dataset

The dataset size is part of the test conditions: 25 documents and 250 000 documents are not the
same test. Prepare a dedicated tenant and record what it contains:

- a tenant with a realistic number of documents, cases and users (target figures belong in
  `conditions.template.md`);
- a user that has permission to upload and list documents in that tenant;
- for `token` mode: a personal API token for that user (ADR-05);
- for `login` mode: the credentials of that user;
- for `cookie` mode: a session cookie copied from a browser session of that user.

Never point a load test at production. `run-load-test.sh` refuses a non-local `BASE_URL` unless
`I_KNOW_THIS_IS_PRODUCTION=yes` is set — and a load test against production is an outage risk unless
it is a planned, agreed exercise.

## 4. Run a scenario

Smoke test first (always):

```bash
BASE_URL=http://localhost:8080 BC_LOAD_AUTH_MODE=none \
  ./tests/load/run-load-test.sh smoke
```

Document upload (authenticated with an API token, 10 VUs, 2 minutes at peak):

```bash
BASE_URL=http://localhost:8080 \
BC_LOAD_AUTH_MODE=token BC_LOAD_TOKEN=<token> \
BC_LOAD_VUS=10 BC_LOAD_HOLD=2m \
BC_LOAD_FIXTURE_DIR=tests/load/fixtures BC_LOAD_FILE_NAME=invoice-scan-300dpi.pdf \
  ./tests/load/run-load-test.sh upload
```

Document list (page size 25, 20 VUs):

```bash
BASE_URL=http://localhost:8080 \
BC_LOAD_AUTH_MODE=login BC_LOAD_USERNAME=loadtest@example.de BC_LOAD_PASSWORD=<password> \
BC_LOAD_VUS=20 BC_LOAD_PAGE_SIZE=25 \
  ./tests/load/run-load-test.sh list
```

You can also call k6 directly — but then you are responsible for recording the conditions yourself:

```bash
k6 run tests/load/k6/upload-documents.js \
  -e BASE_URL=http://localhost:8080 -e BC_LOAD_AUTH_MODE=token -e BC_LOAD_TOKEN=<token> \
  --summary-export /tmp/upload-summary.json
```

### Useful environment variables

| Variable | Default | Meaning |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | Target backend |
| `BC_LOAD_AUTH_MODE` | `none` | `none` \| `token` \| `cookie` \| `login` |
| `BC_LOAD_TOKEN` | – | Bearer token (`token` mode) |
| `BC_LOAD_COOKIE` / `BC_LOAD_COOKIE_NAME` | – / `bc_session` | Session cookie (`cookie` mode) |
| `BC_LOAD_USERNAME`, `BC_LOAD_PASSWORD` | – | Credentials (`login` mode) |
| `BC_LOAD_TENANT` | – | Sent as `X-Tenant-Id` when set |
| `BC_LOAD_VUS`, `BC_LOAD_MAX_VUS` | `10`, `25` | Virtual users at peak |
| `BC_LOAD_RAMP_UP`, `BC_LOAD_HOLD`, `BC_LOAD_RAMP_DOWN` | `30s`, `2m`, `15s` | Load profile |
| `BC_LOAD_THINK_TIME` | `1` | Seconds between iterations |
| `BC_LOAD_PAGE_SIZE` | `25` | `size=` for the list endpoint |
| `BC_LOAD_FILE_SIZE_KB` | `512` | Synthetic payload size |
| `BC_LOAD_FIXTURE_DIR`, `BC_LOAD_FILE_NAME` | – | Real fixture location and name |
| `BC_LOAD_PATH_UPLOAD`, `BC_LOAD_PATH_LIST`, `BC_LOAD_PATH_LOGIN`, `BC_LOAD_PATH_HEALTH` | spec-aligned defaults | Endpoint overrides |
| `BC_LOAD_UPLOAD_P95_MS`, `BC_LOAD_LIST_P95_MS`, … | see `k6/lib/config.js` | Threshold overrides |
| `BC_LOAD_RESULTS_DIR` | `tests/load/results` | Where results are written |
| `I_KNOW_THIS_IS_PRODUCTION` | – | `yes` allows a non-local target |

## 5. What k6 reports, and how to keep it

k6 prints a summary at the end: `http_req_duration` (with the p95/p99 you configured),
`http_req_failed`, `checks`, the custom metrics of each scenario
(`bc_upload_latency`, `bc_upload_bytes_total`, `bc_upload_documents_total`, `bc_upload_2xx_rate`,
`bc_list_latency`, `bc_list_response_bytes`, `bc_list_requests_total`, `bc_list_2xx_rate`,
`bc_smoke_endpoint_ok_rate`) and the pass/fail state of every threshold.

`run-load-test.sh` stores, per run:

```
tests/load/results/<UTC timestamp>/
├── conditions.txt          effective conditions (no credential values, only <set:N chars>)
├── <scenario>-summary.json k6 summary export — the machine-readable result
└── <scenario>-output.txt   full k6 output including threshold results
```

Export to another sink (optional): `k6 run --out json=results/raw.json …`, or
`--out experimental-prometheus-rw` for a Prometheus remote-write endpoint.

## 6. Rules for reporting a result

1. **A result without completed conditions does not exist.** Copy
   `conditions.template.md` into the run directory and fill it in.
2. **Thresholds are not acceptance criteria until they are agreed.** The defaults in
   `k6/lib/config.js` are starting points; the product owner must confirm the target figures (and
   on which hardware) before a run can be called an acceptance test.
3. **Do not compare incomparable runs.** Different auth mode, dataset size, OCR/AI state, fixture,
   page size, host sizing or path override ⇒ a different test.
4. **Do not cherry-pick.** Report the run id, including failed thresholds and client-side errors.
5. **A green smoke test is not a performance result.** It only proves the scripts work.

## 7. Open items

- [ ] Confirm the real endpoint paths and pagination parameter names against `/api-docs`.
- [ ] Create `tests/load/fixtures/` with a representative, licence-clean document (no real personal
      data — use a synthetic or anonymised file).
- [ ] Add a mixed workload scenario (list + detail + search) once those endpoints exist; the
      current two scenarios cover upload and list only.
- [ ] Agree the acceptance thresholds with the product owner and record them here.
- [ ] Run the suite on the same hardware class as the intended production deployment and store the
      results with their condition sheets.
