#!/usr/bin/env node
// =============================================================================
//  verify-license.mjs — check a runtime licence key the way the API does
// =============================================================================
//  This is the JavaScript twin of
//  services/api/.../license/LicenseTokenVerifier.java: same header check, same
//  constant-time HMAC comparison, same payload rules. It exists so a key minted
//  somewhere else (the owner cabinet) can be verified without starting the API:
//
//      LICENSE_SECRET='...' node scripts/license/verify-license.mjs license.token
//      LICENSE_SECRET='...' node scripts/license/verify-license.mjs --in - < license.token
//      LICENSE_SECRET='...' node scripts/license/verify-license.mjs \
//          --expect-instance "$(cat /app/data/instance-id)" --json license.token
//
//  Exit codes: 0 valid, 1 invalid, 2 usage/configuration error.
//
//  A key WITHOUT `expiresAt` is perpetual and prints `perpetual: true`.
//  A key WITH `expiresAt` keeps the legacy fixed-term semantics and prints the
//  date.
// =============================================================================

import { createHmac, timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";

const PROGRAM = "verify-license";
const TOKEN_VERSION = 1;

function usage() {
  const text = readFileSync(new URL(import.meta.url), "utf8");
  const header = text.split("\n").filter((line) => line.startsWith("//")).join("\n");
  process.stderr.write(header.replace(/^\/\/ ?/gm, "") + "\n");
}

function fail(message, code = 1) {
  process.stderr.write(`${PROGRAM}: ${message}\n`);
  process.exit(code);
}

function parseArgs(argv) {
  const options = { input: null, expectInstance: null, json: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "-h" || arg === "--help") {
      usage();
      process.exit(0);
    } else if (arg === "--in") {
      options.input = argv[++i] ?? fail("--in needs a path or -", 2);
    } else if (arg === "--expect-instance") {
      options.expectInstance = argv[++i] ?? fail("--expect-instance needs a value", 2);
    } else if (arg === "--json") {
      options.json = true;
    } else if (arg.startsWith("-") && arg !== "-") {
      fail(`unknown option: ${arg}`, 2);
    } else if (options.input === null) {
      options.input = arg;
    } else {
      fail(`unexpected argument: ${arg}`, 2);
    }
  }
  return options;
}

const options = parseArgs(process.argv.slice(2));
const secret = process.env.LICENSE_SECRET;
if (!secret || secret.length < 32) {
  fail("LICENSE_SECRET must be set to at least 32 characters", 2);
}
if (options.input === null) {
  usage();
  fail("no licence token given", 2);
}

let token;
try {
  token =
    options.input === "-"
      ? readFileSync(0, "utf8")
      : readFileSync(options.input, "utf8");
} catch (error) {
  fail(`cannot read the licence token: ${error.message}`, 2);
}
token = token.trim();
if (!token) fail("the licence token is empty");

const parts = token.split(".");
if (parts.length !== 3 || parts.some((part) => part.length === 0)) {
  fail("MALFORMED: a licence token has three dot-separated parts");
}

function decodeJson(part, label) {
  try {
    return JSON.parse(Buffer.from(part, "base64url").toString("utf8"));
  } catch {
    return fail(`MALFORMED: the ${label} is not valid base64url JSON`);
  }
}

const header = decodeJson(parts[0], "header");
if (header?.alg !== "HS256") {
  fail("UNSUPPORTED_ALGORITHM: only HS256 licences are supported");
}

const expected = createHmac("sha256", secret)
  .update(`${parts[0]}.${parts[1]}`)
  .digest();
const presented = Buffer.from(parts[2], "base64url");
if (presented.length !== expected.length || !timingSafeEqual(expected, presented)) {
  fail("BAD_SIGNATURE: the licence signature does not match");
}

const payload = decodeJson(parts[1], "payload");
if (!payload || typeof payload !== "object") {
  fail("INVALID_PAYLOAD: the licence payload is empty");
}
if (payload.v !== TOKEN_VERSION) {
  fail(`UNSUPPORTED_VERSION: unsupported licence version: ${payload.v}`);
}
for (const field of ["licenseId", "customer", "plan", "instanceId"]) {
  if (typeof payload[field] !== "string" || payload[field].trim() === "") {
    fail(`INVALID_PAYLOAD: the licence field ${field} is missing`);
  }
}
if (!Number.isInteger(payload.issuedAt) || payload.issuedAt <= 0) {
  fail("INVALID_PAYLOAD: issuedAt must be positive Unix seconds");
}
const perpetual = payload.expiresAt === undefined || payload.expiresAt === null;
if (!perpetual) {
  if (!Number.isInteger(payload.expiresAt) || payload.expiresAt <= 0) {
    fail("INVALID_PAYLOAD: expiresAt must be positive Unix seconds when present");
  }
  if (payload.expiresAt <= payload.issuedAt) {
    fail("INVALID_PAYLOAD: expiresAt must be later than issuedAt");
  }
}
if (
  !Number.isInteger(payload.maxAgents) ||
  payload.maxAgents < 0 ||
  !Number.isInteger(payload.maxRunsPerMonth) ||
  payload.maxRunsPerMonth < 0
) {
  fail("INVALID_PAYLOAD: licence limits must be non-negative integers");
}
if (Array.isArray(payload.features) === false) {
  fail("INVALID_PAYLOAD: features must be an array");
}
if (options.expectInstance !== null && payload.instanceId !== options.expectInstance) {
  fail("WRONG_INSTANCE: the licence belongs to a different installation");
}

const summary = {
  valid: true,
  licenseId: payload.licenseId,
  customer: payload.customer,
  instanceId: payload.instanceId,
  plan: payload.plan,
  maxAgents: payload.maxAgents,
  maxRunsPerMonth: payload.maxRunsPerMonth,
  features: payload.features,
  issuedAt: payload.issuedAt,
  issuedAtIso: new Date(payload.issuedAt * 1000).toISOString(),
  perpetual,
  expiresAt: perpetual ? null : payload.expiresAt,
  expiresAtIso: perpetual ? null : new Date(payload.expiresAt * 1000).toISOString(),
};

if (options.json) {
  process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`);
} else {
  process.stdout.write(
    `${PROGRAM}: valid licence ${summary.licenseId} for ${summary.customer} ` +
      `(instance ${summary.instanceId}, plan ${summary.plan}, ` +
      `${summary.perpetual ? "perpetual" : `expires ${summary.expiresAtIso}`})\n`,
  );
}
