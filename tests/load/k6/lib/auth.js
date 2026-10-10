// =============================================================================
//  lib/auth.js — authentication helpers for the k6 load tests
// =============================================================================
//  The authentication implementation of the backend is still in progress, so the
//  helpers below support four modes and degrade HONESTLY:
//
//    none   — no credentials are sent. Valid only for a local stack whose API is
//             open; every result carries a loud warning because latency and
//             error rates are not comparable with an authenticated run.
//    token  — `Authorization: Bearer <token>` using a personal API token
//             (ADR-05: server sessions for the web UI, API tokens for
//             integrations).
//    cookie — a session cookie captured from a browser session.
//    login  — POST credentials to the login endpoint and reuse the returned
//             session cookie. The cookie name defaults to `bc_session`
//             (application.yml: burokratie.security.session.cookie-name) and can
//             be overridden with BC_LOAD_COOKIE_NAME.
//
//  This module never logs a credential.
// =============================================================================

import http from 'k6/http';
import { fail } from 'k6';

import {
  AUTH_COOKIE,
  AUTH_LOGIN_PATH,
  AUTH_MODE,
  AUTH_PASSWORD,
  AUTH_TOKEN,
  AUTH_USERNAME,
  BASE_URL,
  env,
  baseHeaders,
} from './config.js';

const COOKIE_NAME = env('BC_LOAD_COOKIE_NAME', 'bc_session');

/** Session cookie captured by setup(), shared by all VUs. */
let sessionCookie = '';

/**
 * Resolve the cookie value for `cookie` mode, either from the raw cookie header
 * or from the login endpoint in `login` mode.
 *
 * @returns {string} the cookie value (may be empty in `none` mode)
 */
export function authenticate() {
  if (AUTH_MODE === 'none') {
    console.warn(
      '[load-test] AUTH MODE "none": requests are sent WITHOUT credentials. ' +
        'These numbers are only comparable with other unauthenticated runs.',
    );
    return '';
  }

  if (AUTH_MODE === 'token') {
    if (!AUTH_TOKEN) {
      fail('BC_LOAD_AUTH_MODE=token requires BC_LOAD_TOKEN');
    }
    return '';
  }

  if (AUTH_MODE === 'cookie') {
    if (!AUTH_COOKIE) {
      fail('BC_LOAD_AUTH_MODE=cookie requires BC_LOAD_COOKIE (e.g. "bc_session=…")');
    }
    // Accept either a bare value or a full "name=value" pair.
    if (AUTH_COOKIE.includes('=')) {
      const parts = AUTH_COOKIE.split(';')[0].split('=');
      return parts.slice(1).join('=');
    }
    return AUTH_COOKIE;
  }

  if (AUTH_MODE === 'login') {
    if (!AUTH_USERNAME || !AUTH_PASSWORD) {
      fail('BC_LOAD_AUTH_MODE=login requires BC_LOAD_USERNAME and BC_LOAD_PASSWORD');
    }
    const response = http.post(
      `${BASE_URL}${AUTH_LOGIN_PATH}`,
      JSON.stringify({ email: AUTH_USERNAME, password: AUTH_PASSWORD }),
      {
        headers: Object.assign(baseHeaders(), { 'Content-Type': 'application/json' }),
        tags: { name: 'auth-login' },
      },
    );
    if (response.status < 200 || response.status >= 300) {
      fail(
        `login failed with HTTP ${response.status} at ${AUTH_LOGIN_PATH} — ` +
          'verify BC_LOAD_PATH_LOGIN against the backend OpenAPI document and that the account exists',
      );
    }
    const cookies = response.cookies || {};
    if (cookies[COOKIE_NAME] && cookies[COOKIE_NAME].length > 0) {
      return cookies[COOKIE_NAME][0].value;
    }
    // Fall back to any cookie the server set, and say so.
    const names = Object.keys(cookies);
    if (names.length > 0) {
      console.warn(
        `[load-test] cookie "${COOKIE_NAME}" was not set; using "${names[0]}" instead`,
      );
      return cookies[names[0]][0].value;
    }
    fail('login succeeded but no session cookie was returned — check the auth contract');
    return '';
  }

  fail(`unknown BC_LOAD_AUTH_MODE "${AUTH_MODE}" (expected none|token|cookie|login)`);
  return '';
}

/** Store the cookie so that per-VU requests can reuse it. */
export function rememberSession(cookieValue) {
  sessionCookie = cookieValue || '';
}

/** Request headers including whatever credential this run uses. */
export function authHeaders() {
  const headers = baseHeaders();
  if (AUTH_MODE === 'token' && AUTH_TOKEN) {
    headers.Authorization = `Bearer ${AUTH_TOKEN}`;
  }
  if (sessionCookie) {
    headers.Cookie = `${COOKIE_NAME}=${sessionCookie}`;
  }
  return headers;
}

/** True when the run is authenticated (used for the warning banner). */
export function isAuthenticated() {
  return AUTH_MODE !== 'none';
}

/**
 * Cheap preflight: the API must be reachable and UP before any load is applied.
 * A load test against a broken stack produces meaningless numbers.
 *
 * @returns {{ok: boolean, detail: string}}
 */
export function preflight() {
  const response = http.get(`${BASE_URL}/actuator/health`, {
    headers: baseHeaders(),
    tags: { name: 'preflight-health' },
  });
  if (response.status !== 200) {
    return {
      ok: false,
      detail: `GET /actuator/health returned HTTP ${response.status}`,
    };
  }
  let status = 'unknown';
  try {
    status = JSON.parse(response.body).status || 'unknown';
  } catch (error) {
    return { ok: false, detail: 'GET /actuator/health did not return JSON' };
  }
  if (String(status).toUpperCase() !== 'UP') {
    return { ok: false, detail: `health status is "${status}" (expected UP)` };
  }
  return { ok: true, detail: 'UP' };
}
