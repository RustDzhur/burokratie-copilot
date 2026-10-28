// =============================================================================
//  smoke.js — 1 VU / short duration sanity check
// =============================================================================
//  Purpose: prove that the load-test scripts themselves work against a running
//  stack (paths, auth, payload) BEFORE spending time on a real load profile. A
//  load test that silently hits 404 on every request produces beautifully useless
//  numbers.
//
//  Run:
//      k6 run tests/load/k6/smoke.js -e BASE_URL=http://localhost:8080 \
//        -e BC_LOAD_AUTH_MODE=none
//
//  This scenario asserts almost nothing about performance: its thresholds are
//  loose on purpose. It DOES assert that the endpoints answer with 2xx.
// =============================================================================

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

import {
  BASE_URL,
  FILE_NAME,
  FILE_SIZE_KB,
  PATHS,
  PAGE_SIZE,
  describeConditions,
} from './lib/config.js';
import { authHeaders, authenticate, isAuthenticated, preflight, rememberSession } from './lib/auth.js';

const endpointOkRate = new Rate('bc_smoke_endpoint_ok_rate');

export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: 1,
      duration: '20s',
    },
  },
  thresholds: {
    // Loose on purpose: a smoke run must not fail because a cold JVM is slow.
    http_req_failed: ['rate<0.10'],
    checks: ['rate>0.50'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'max'],
  userAgent: 'burokratie-copilot-k6-load-test',
};

function syntheticPdf(sizeKb) {
  const header = '%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n' +
    '2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n' +
    '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]>>endobj\n';
  const footer = '\ntrailer<</Root 1 0 R>>\n%%EOF\n';
  const padTarget = Math.max(0, sizeKb * 1024 - header.length - footer.length);
  let padding = '%';
  const chunk = 'Burokratie-Copilot load-test padding 0123456789abcdef';
  while (padding.length < padTarget) {
    padding += chunk;
  }
  return header + padding.substring(0, padTarget) + footer;
}

export function setup() {
  console.log('[load-test] effective conditions:\n' + describeConditions());
  const health = preflight();
  if (!health.ok) {
    throw new Error(`preflight failed: ${health.detail}`);
  }
  rememberSession(isAuthenticated() ? authenticate() : '');
  // Health is re-checked here so the smoke report contains it as a metric too.
  const healthResponse = http.get(`${BASE_URL}/actuator/health`, {
    headers: authHeaders(),
    tags: { name: 'smoke-health' },
  });
  check(healthResponse, { 'health: status is 200': (r) => r.status === 200 });
  endpointOkRate.add(healthResponse.status === 200);
  return { payload: syntheticPdf(FILE_SIZE_KB) };
}

export default function (data) {
  group('list documents', function () {
    const response = http.get(`${BASE_URL}${PATHS.list}?page=0&size=${PAGE_SIZE}`, {
      headers: authHeaders(),
      tags: { name: 'smoke-list' },
      timeout: '30s',
    });
    endpointOkRate.add(response.status >= 200 && response.status < 300);
    check(response, {
      'list: 2xx': (r) => r.status >= 200 && r.status < 300,
      'list: not 404 (path correct)': (r) => r.status !== 404,
    });
  });

  group('upload document', function () {
    const body = {
      file: http.file(data.payload, FILE_NAME, 'application/pdf'),
      documentType: 'OTHER',
      source: 'LOAD_TEST',
    };
    const response = http.post(`${BASE_URL}${PATHS.upload}`, body, {
      headers: authHeaders(),
      tags: { name: 'smoke-upload' },
      timeout: '60s',
    });
    endpointOkRate.add(response.status >= 200 && response.status < 300);
    check(response, {
      'upload: 2xx': (r) => r.status >= 200 && r.status < 300,
      'upload: not 404 (path correct)': (r) => r.status !== 404,
    });
  });

  sleep(1);
}
