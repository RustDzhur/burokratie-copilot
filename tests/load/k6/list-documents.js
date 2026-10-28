// =============================================================================
//  list-documents.js — document list endpoint scenario (spec §27: pagination)
// =============================================================================
//  Measures read-path latency and response size for GET <PATHS.list>, with a
//  fixed page size. The list endpoint is the hottest read path in the product
//  (Inbox, document search), so its p95 is a useful regression signal.
//
//  Run:
//      k6 run tests/load/k6/list-documents.js \
//        -e BASE_URL=http://localhost:8080 \
//        -e BC_LOAD_AUTH_MODE=token -e BC_LOAD_TOKEN=... \
//        -e BC_LOAD_PAGE_SIZE=25 -e BC_LOAD_VUS=20 \
//        --summary-export tests/load/results/manual/list-summary.json
//
//  The dataset size (how many documents the tenant actually has) is part of the
//  test conditions and must be recorded — 25 documents and 250 000 documents are
//  not the same test even with identical scripts.
// =============================================================================

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';

import {
  BASE_URL,
  HOLD,
  MAX_VUS,
  PAGE_SIZE,
  PATHS,
  RAMP_DOWN,
  RAMP_UP,
  THINK_TIME,
  THRESHOLDS,
  VUS,
  describeConditions,
} from './lib/config.js';
import { authHeaders, authenticate, isAuthenticated, preflight, rememberSession } from './lib/auth.js';

// --- custom metrics ----------------------------------------------------------
const listLatency = new Trend('bc_list_latency', true);
const listResponseBytes = new Trend('bc_list_response_bytes');
const listRequests = new Counter('bc_list_requests_total');
const listOkRate = new Rate('bc_list_2xx_rate');

export const options = {
  scenarios: {
    list: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: RAMP_UP, target: VUS },
        { duration: HOLD, target: VUS },
        { duration: RAMP_DOWN, target: 0 },
      ],
      gracefulRampDown: '15s',
      gracefulStop: '30s',
    },
  },
  thresholds: {
    // STARTING POINTS, not agreed acceptance criteria — see lib/config.js.
    bc_list_latency: [`p(95)<${THRESHOLDS.listP95Ms}`, `p(99)<${THRESHOLDS.listP99Ms}`],
    bc_list_2xx_rate: [`rate>${1 - THRESHOLDS.maxErrorRate}`],
    http_req_failed: [`rate<${THRESHOLDS.maxErrorRate}`],
    checks: [`rate>${THRESHOLDS.minChecksRate}`],
  },
  // Discard bodies after measuring: a big listing must not become a memory test
  // of the load generator (spec §27: do not hold whole documents in memory).
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  userAgent: 'burokratie-copilot-k6-load-test',
};

export function setup() {
  console.log('[load-test] effective conditions:\n' + describeConditions());
  if (!isAuthenticated()) {
    console.warn('[load-test] UNAUTHENTICATED run — see the notice above.');
  }
  const health = preflight();
  if (!health.ok) {
    throw new Error(
      `preflight failed: ${health.detail}. Refusing to apply load to an environment that is not UP.`,
    );
  }
  rememberSession(authenticate());
  return { pageSize: PAGE_SIZE };
}

export default function () {
  group('list documents', function () {
    // `page`/`size` follow the pagination convention required by spec §27. The
    // parameter names must be verified against the backend OpenAPI document.
    const url = `${BASE_URL}${PATHS.list}?page=0&size=${PAGE_SIZE}`;
    const response = http.get(url, {
      headers: authHeaders(),
      tags: { name: 'list-documents' },
      timeout: '60s',
    });

    listRequests.add(1);
    listLatency.add(response.timings.duration);
    listResponseBytes.add(response.body ? response.body.length : 0);
    listOkRate.add(response.status >= 200 && response.status < 300);

    check(response, {
      'list: status is 2xx': (r) => r.status >= 200 && r.status < 300,
      'list: path exists (not 404)': (r) => r.status !== 404,
      'list: 401/403 would mean the credential is wrong': (r) => r.status !== 401 && r.status !== 403,
    });

    if (response.status === 404) {
      console.error(
        `[load-test] HTTP 404 on GET ${PATHS.list} — verify the path against the backend OpenAPI document (/api-docs) and override BC_LOAD_PATH_LIST`,
      );
    }
  });

  if (THINK_TIME > 0) {
    sleep(THINK_TIME);
  }
}

export function teardown(data) {
  console.log(
    `[load-test] finished with page size ${data.pageSize}. ` +
      'Record the tenant dataset size in tests/load/conditions.template.md — the same script against a different dataset is a different test.',
  );
}
