// =============================================================================
//  upload-documents.js — document upload scenario (spec §7.1, §27)
// =============================================================================
//  Measures: multipart upload throughput (documents/s and bytes/s) and latency
//  for POST <PATHS.upload>, with a fixed, recorded payload.
//
//  Run:
//      k6 run tests/load/k6/upload-documents.js \
//        -e BASE_URL=http://localhost:8080 \
//        -e BC_LOAD_AUTH_MODE=token -e BC_LOAD_TOKEN=... \
//        -e BC_LOAD_VUS=10 -e BC_LOAD_HOLD=2m \
//        --summary-export tests/load/results/manual/upload-summary.json
//
//  Prefer tests/load/run-load-test.sh, which records the conditions alongside the
//  result. A number without its conditions is not evidence (spec §27).
// =============================================================================

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';

import {
  BASE_URL,
  FILE_NAME,
  FILE_SIZE_KB,
  FIXTURE_DIR,
  HOLD,
  MAX_VUS,
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
const uploadLatency = new Trend('bc_upload_latency', true);
const uploadedBytes = new Counter('bc_upload_bytes_total');
const uploadedDocs = new Counter('bc_upload_documents_total');
const acceptedResponses = new Rate('bc_upload_2xx_rate');

let payload = null;
let payloadSize = 0;

/**
 * Build the multipart payload.
 *
 * With BC_LOAD_FIXTURE_DIR the oldest file from that directory is used — a REAL
 * representative document (scanned PDF, photographed invoice, ...). Without it a
 * synthetic PDF of BC_LOAD_FILE_SIZE_KB is generated. The two are NOT
 * interchangeable for comparison purposes: record which one was used.
 */
function buildPayload() {
  if (FIXTURE_DIR) {
    // k6 can only read files that exist INSIDE its script bundle: pass the
    // directory with `k6 run --include-system-env-vars` plus `open()`, or copy
    // the fixture next to the script. The supported approach here is
    // `http.file()` with a path relative to the k6 working directory, which is
    // why run-load-test.sh runs k6 from the repository root.
    try {
      const data = open(`${FIXTURE_DIR}/${FILE_NAME}`, 'b');
      return { data: data, size: data.length, source: `file:${FIXTURE_DIR}/${FILE_NAME}` };
    } catch (error) {
      // Fall through to the synthetic payload, but say so loudly: a silent
      // substitution would invalidate the comparison with earlier runs.
      console.warn(
        `[load-test] fixture ${FIXTURE_DIR}/${FILE_NAME} could not be opened (${error}); ` +
          'falling back to a SYNTHETIC payload — record this in the conditions file',
      );
    }
  }
  return {
    data: syntheticPdf(FILE_SIZE_KB),
    size: FILE_SIZE_KB * 1024,
    source: `synthetic:${FILE_SIZE_KB}KB`,
  };
}

/**
 * A minimal but structurally valid PDF with a comment padding block, so that the
 * upload path receives something a real PDF parser will accept. It is NOT a
 * substitute for a representative scanned document.
 */
function syntheticPdf(sizeKb) {
  const header = '%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n' +
    '2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n' +
    '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]>>endobj\n';
  const footer = '\ntrailer<</Root 1 0 R>>\n%%EOF\n';
  const padTarget = Math.max(0, sizeKb * 1024 - header.length - footer.length);
  // A PDF comment line (`%`) is ignored by parsers, which keeps the file valid
  // while reaching the requested size.
  let padding = '%';
  const chunk = 'Burokratie-Copilot load-test padding 0123456789abcdef';
  while (padding.length < padTarget) {
    padding += chunk;
  }
  return header + padding.substring(0, padTarget) + footer;
}

export const options = {
  scenarios: {
    upload: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: RAMP_UP, target: VUS },
        { duration: HOLD, target: VUS },
        { duration: RAMP_DOWN, target: 0 },
      ],
      gracefulRampDown: '30s',
      gracefulStop: '2m',
    },
  },
  thresholds: {
    // See lib/config.js — these are starting points, NOT agreed acceptance
    // criteria. They must be agreed with the product owner before being used as
    // acceptance evidence.
    bc_upload_latency: [`p(95)<${THRESHOLDS.uploadP95Ms}`, `p(99)<${THRESHOLDS.uploadP99Ms}`],
    bc_upload_2xx_rate: [`rate>${1 - THRESHOLDS.maxErrorRate}`],
    http_req_failed: [`rate<${THRESHOLDS.maxErrorRate}`],
    checks: [`rate>${THRESHOLDS.minChecksRate}`],
  },
  // Uploads are heavier than typical HTTP calls: give each VU room.
  maxRedirects: 0,
  discardResponseBodies: false,
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
      `preflight failed: ${health.detail}. Start the stack (see README.md) or point BASE_URL at a running environment. ` +
        'Refusing to apply load to an environment that is not UP.',
    );
  }
  const cookie = authenticate();
  rememberSession(cookie);
  payload = buildPayload();
  payloadSize = payload.size;
  console.log(`[load-test] payload: ${payload.source} (${payloadSize} bytes)`);
  return { payloadSource: payload.source, payloadSize: payloadSize };
}

export default function () {
  group('upload document', function () {
    const body = {
      file: http.file(payload.data, FILE_NAME, 'application/pdf'),
      // Optional metadata: harmless when the API ignores unknown fields, and
      // required by some implementations for the document type.
      documentType: 'OTHER',
      source: 'LOAD_TEST',
    };

    const response = http.post(`${BASE_URL}${PATHS.upload}`, body, {
      headers: authHeaders(),
      tags: { name: 'upload-document' },
      timeout: '120s',
    });

    uploadLatency.add(response.timings.duration);
    acceptedResponses.add(response.status >= 200 && response.status < 300);
    if (response.status >= 200 && response.status < 300) {
      uploadedDocs.add(1);
      uploadedBytes.add(payloadSize);
    }

    check(response, {
      'upload: status is 2xx': (r) => r.status >= 200 && r.status < 300,
      'upload: 401/403 would mean the credential is wrong': (r) => r.status !== 401 && r.status !== 403,
      'upload: 413 must not happen (payload within BC_MAX_UPLOAD_SIZE)': (r) => r.status !== 413,
      // A 404 means the path is wrong — the most common cause of a meaningless run.
      'upload: path exists (not 404)': (r) => r.status !== 404,
    });

    if (response.status === 404) {
      console.error(
        `[load-test] HTTP 404 on POST ${PATHS.upload} — verify the path against the backend OpenAPI document (/api-docs) and override BC_LOAD_PATH_UPLOAD`,
      );
    }
  });

  if (THINK_TIME > 0) {
    sleep(THINK_TIME);
  }
}

export function teardown(data) {
  console.log(
    `[load-test] finished. payload=${data.payloadSource} size=${data.payloadSize}B. ` +
      'Complete tests/load/conditions.template.md before using these numbers as evidence.',
  );
}
