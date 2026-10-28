// =============================================================================
//  lib/config.js — shared configuration for the k6 load tests
// =============================================================================
//  Everything that defines a test condition lives here, so that two runs can be
//  compared only when their conditions really match (spec §27: performance
//  figures must come from measurable load tests with recorded conditions).
//
//  All values can be overridden through environment variables; the effective
//  values are printed at the start of every run and recorded in
//  tests/load/results/<timestamp>/conditions.txt by run-load-test.sh.
// =============================================================================

/**
 * Read an environment variable with a default.
 * @param {string} name
 * @param {string} fallback
 * @returns {string}
 */
export function env(name, fallback) {
  const value = __ENV[name];
  if (value === undefined || value === null || value === '') {
    return fallback;
  }
  return String(value);
}

/**
 * Read a numeric environment variable with a default.
 * @param {string} name
 * @param {number} fallback
 * @returns {number}
 */
export function envInt(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === null || raw === '') {
    return fallback;
  }
  const parsed = Number.parseInt(String(raw), 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

/** Read a boolean environment variable ("true"/"1"/"yes"). */
export function envBool(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === null || raw === '') {
    return fallback;
  }
  return ['true', '1', 'yes', 'on'].includes(String(raw).toLowerCase());
}

// -----------------------------------------------------------------------------
// Target
// -----------------------------------------------------------------------------
// Base URL of the backend under test. NEVER point this at production without an
// explicit acknowledgement: run-load-test.sh enforces that.
export const BASE_URL = env('BASE_URL', 'http://localhost:8080').replace(/\/+$/, '');

// -----------------------------------------------------------------------------
// Endpoint paths
// -----------------------------------------------------------------------------
// IMPORTANT: these paths MUST be verified against the backend's generated
// OpenAPI document before any run whose numbers are to be used as evidence:
//     /api-docs          (JSON)
//     /swagger-ui.html   (browsable)
// The backend is still being implemented; the defaults follow the API groups in
// spec §20 (/api/v1/...). Override with BC_LOAD_PATH_* if the real paths differ —
// and record the override in the conditions file.
export const PATHS = {
  health: env('BC_LOAD_PATH_HEALTH', '/actuator/health'),
  upload: env('BC_LOAD_PATH_UPLOAD', '/api/v1/documents'),
  list: env('BC_LOAD_PATH_LIST', '/api/v1/documents'),
};

// -----------------------------------------------------------------------------
// Authentication
// -----------------------------------------------------------------------------
// 'none'  — no credentials (only valid for a local stack without auth)
// 'token' — Authorization: Bearer <BC_LOAD_TOKEN>  (personal API token, ADR-05)
// 'cookie'— Cookie: <BC_LOAD_COOKIE>               (server session cookie)
// 'login' — POST the credentials and reuse the returned session cookie
export const AUTH_MODE = env('BC_LOAD_AUTH_MODE', 'none').toLowerCase();
export const AUTH_TOKEN = env('BC_LOAD_TOKEN', '');
export const AUTH_COOKIE = env('BC_LOAD_COOKIE', '');
export const AUTH_USERNAME = env('BC_LOAD_USERNAME', '');
export const AUTH_PASSWORD = env('BC_LOAD_PASSWORD', '');
export const AUTH_LOGIN_PATH = env('BC_LOAD_PATH_LOGIN', '/api/v1/auth/login');
export const TENANT_ID = env('BC_LOAD_TENANT', '');

// -----------------------------------------------------------------------------
// Test data
// -----------------------------------------------------------------------------
// A synthetic payload is a placeholder. Numbers obtained with a synthetic file
// are NOT comparable with numbers obtained from a real scanned document (the
// parser path differs), so the fixture identity must be recorded with the result.
export const FIXTURE_DIR = env('BC_LOAD_FIXTURE_DIR', '');
export const FILE_SIZE_KB = envInt('BC_LOAD_FILE_SIZE_KB', 512);
export const FILE_NAME = env('BC_LOAD_FILE_NAME', 'load-test-document.pdf');

// -----------------------------------------------------------------------------
// Load profile
// -----------------------------------------------------------------------------
export const RAMP_UP = env('BC_LOAD_RAMP_UP', '30s');
export const HOLD = env('BC_LOAD_HOLD', '2m');
export const RAMP_DOWN = env('BC_LOAD_RAMP_DOWN', '15s');
export const VUS = envInt('BC_LOAD_VUS', 10);
export const MAX_VUS = envInt('BC_LOAD_MAX_VUS', 25);
export const THINK_TIME = Number.parseFloat(env('BC_LOAD_THINK_TIME', '1'));
export const PAGE_SIZE = envInt('BC_LOAD_PAGE_SIZE', 25);

// -----------------------------------------------------------------------------
// Thresholds
// -----------------------------------------------------------------------------
// These are STARTING POINTS, not agreed acceptance criteria. Before a result can
// be used as acceptance evidence the figures must be agreed with the product
// owner and written into the conditions file (spec §27).
export const THRESHOLDS = {
  uploadP95Ms: envInt('BC_LOAD_UPLOAD_P95_MS', 5000),
  uploadP99Ms: envInt('BC_LOAD_UPLOAD_P99_MS', 10000),
  listP95Ms: envInt('BC_LOAD_LIST_P95_MS', 1500),
  listP99Ms: envInt('BC_LOAD_LIST_P99_MS', 3000),
  maxErrorRate: Number.parseFloat(env('BC_LOAD_MAX_ERROR_RATE', '0.01')),
  minChecksRate: Number.parseFloat(env('BC_LOAD_MIN_CHECKS_RATE', '0.99')),
};

/** Standard headers for API calls (without credentials). */
export function baseHeaders() {
  const headers = {
    Accept: 'application/json',
    'User-Agent': 'burokratie-copilot-k6-load-test',
  };
  if (TENANT_ID) {
    headers['X-Tenant-Id'] = TENANT_ID;
  }
  return headers;
}

/** Human-readable summary of the effective conditions. */
export function describeConditions() {
  return [
    `base_url=${BASE_URL}`,
    `auth_mode=${AUTH_MODE}${AUTH_MODE === 'none' ? ' (UNAUTHENTICATED — results only comparable with the same mode)' : ''}`,
    `path_upload=${PATHS.upload}`,
    `path_list=${PATHS.list}`,
    `vus=${VUS} max_vus=${MAX_VUS} ramp_up=${RAMP_UP} hold=${HOLD} ramp_down=${RAMP_DOWN}`,
    `think_time_s=${THINK_TIME} page_size=${PAGE_SIZE}`,
    `fixture_dir=${FIXTURE_DIR || '<none: synthetic file>'} file=${FILE_NAME} size_kb=${FILE_SIZE_KB}`,
    `thresholds: upload p95<${THRESHOLDS.uploadP95Ms}ms p99<${THRESHOLDS.uploadP99Ms}ms, ` +
      `list p95<${THRESHOLDS.listP95Ms}ms p99<${THRESHOLDS.listP99Ms}ms, ` +
      `error_rate<${THRESHOLDS.maxErrorRate}, checks>${THRESHOLDS.minChecksRate}`,
  ].join('\n');
}
