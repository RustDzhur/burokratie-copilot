#!/usr/bin/env node
// =============================================================================
//  mint-license.mjs — create a Firmspace Robots Runtime licence key
// =============================================================================
//  The key is a compact, JWT-shaped token:
//
//      base64url({"alg":"HS256","typ":"BC-LICENSE"})
//      . base64url(payload)
//      . base64url(HMAC-SHA256(secret, header + "." + payload))
//
//  The payload is the JSON below (all fields are required unless marked):
//
//      {
//        "v": 1,
//        "licenseId": "LIC-2026-0001",
//        "customer": "Muster GmbH",
//        "instanceId": "<the id printed by install-runtime.sh>",
//        "plan": "ENTERPRISE",
//        "maxAgents": 126,
//        "maxRunsPerMonth": 5000,
//        "features": ["agents", "workflow", "ingest", "exports"],
//        "issuedAt": 1761696000,          // optional; Unix seconds, default now
//        "expiresAt": 1793232000          // OPTIONAL; Unix seconds or an ISO-8601 string
//      }
//
//  EXPIRY: by default a licence is PERPETUAL. Leave expiresAt out (and do not
//  pass --expires) and the runtime treats the key as valid forever — no expiry,
//  no grace window, no renewal. Specify expiresAt / --expires only for the rare
//  contract that has a fixed term; such a key keeps the old behaviour
//  (14-day grace, then read-only).
//
//  Usage:
//      LICENSE_SECRET='...' node scripts/license/mint-license.mjs license.json
//      LICENSE_SECRET='...' node scripts/license/mint-license.mjs --in - < license.json
//      LICENSE_SECRET='...' node scripts/license/mint-license.mjs --in license.json \
//          --out license.token
//      LICENSE_SECRET='...' node scripts/license/mint-license.mjs --in license.json \
//          --expires 2027-12-31T23:59:59Z        # fixed term (optional)
//
//  Default: the licence is perpetual (no expiresAt). Pass --expires only when a
//  fixed term was agreed; the flag overrides expiresAt from the JSON input.
//
//  The secret is read ONLY from the LICENSE_SECRET environment variable. There is
//  no default and no command-line flag: a secret in argv is visible in the
//  process list of a shared machine.
//
//  IMPORTANT (also in docs/deployment/RUNTIME.md): the runtime verifies the token
//  with the same secret, so the secret has to be installed on the runtime host.
//  A customer with root access can therefore mint their own key. The licence is
//  a contractual and operational control, not DRM.
// =============================================================================

import { createHmac } from "node:crypto";
import { chmodSync, readFileSync, writeFileSync } from "node:fs";

const PROGRAM = "mint-license";

function usage() {
  const text = readFileSync(new URL(import.meta.url), "utf8");
  const header = text.split("\n").filter((line) => line.startsWith("//")).join("\n");
  process.stderr.write(header.replace(/^\/\/ ?/gm, "") + "\n");
}

function fail(message, code = 2) {
  process.stderr.write(`${PROGRAM}: ${message}\n`);
  process.exit(code);
}

function parseArgs(argv) {
  const options = { input: null, output: null, quiet: false, printPayload: false, expires: null };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "-h" || arg === "--help") {
      usage();
      process.exit(0);
    } else if (arg === "--in") {
      options.input = argv[++i] ?? fail("--in needs a path or -");
    } else if (arg === "--out") {
      options.output = argv[++i] ?? fail("--out needs a path");
    } else if (arg === "--quiet") {
      options.quiet = true;
    } else if (arg === "--print-payload") {
      options.printPayload = true;
    } else if (arg === "--expires") {
      options.expires = argv[++i] ?? fail("--expires needs a Unix timestamp or ISO-8601 date");
    } else if (arg === "--issued") {
      options.issued = argv[++i] ?? fail("--issued needs a Unix timestamp or ISO-8601 date");
    } else if (arg.startsWith("-") && arg !== "-") {
      fail(`unknown option: ${arg}`);
    } else if (options.input === null) {
      options.input = arg;
    } else {
      fail(`unexpected argument: ${arg}`);
    }
  }
  return options;
}

function base64url(value) {
  return Buffer.from(value, "utf8").toString("base64url");
}

/** Unix seconds from a number or an ISO-8601 string. */
function epochSeconds(value, field) {
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value) || value <= 0) {
      fail(`${field} must be a positive integer (Unix seconds)`);
    }
    return value;
  }
  if (typeof value === "string" && value.trim() !== "") {
    const parsed = Date.parse(value);
    if (Number.isNaN(parsed)) {
      fail(`${field} is not a valid ISO-8601 timestamp: ${value}`);
    }
    return Math.floor(parsed / 1000);
  }
  fail(`${field} is required (Unix seconds or ISO-8601)`);
}

function positiveInteger(value, field, { allowZero = false } = {}) {
  if (!Number.isInteger(value) || value < (allowZero ? 0 : 1)) {
    fail(`${field} must be an integer ${allowZero ? ">= 0" : "> 0"}`);
  }
  return value;
}

function text(value, field) {
  if (typeof value !== "string" || value.trim() === "") {
    fail(`${field} is required`);
  }
  return value.trim();
}

const options = parseArgs(process.argv.slice(2));
const secret = process.env.LICENSE_SECRET;
if (!secret || secret.length < 32) {
  fail("LICENSE_SECRET must be set to at least 32 characters (never pass it as an argument)");
}

let raw;
try {
  raw = options.input === null || options.input === "-"
    ? readFileSync(0, "utf8")
    : readFileSync(options.input, "utf8");
} catch (error) {
  fail(`cannot read the JSON input: ${error.message}`, 1);
}

let input;
try {
  input = JSON.parse(raw);
} catch (error) {
  fail(`the input is not valid JSON: ${error.message}`);
}
if (input === null || typeof input !== "object" || Array.isArray(input)) {
  fail("the input must be a JSON object");
}

const v = input.v ?? 1;
if (v !== 1) {
  fail("v must be 1 (the only licence version this product understands)");
}
const issuedAtValue = options.issued ?? input.issuedAt;
const issuedAt = issuedAtValue === undefined
  ? Math.floor(Date.now() / 1000)
  : epochSeconds(issuedAtValue, "issuedAt");

// Perpetual by default: expiresAt is optional and is omitted from the payload
// entirely when it is not supplied. A key without it never expires.
const expiresAtValue = options.expires ?? input.expiresAt;
const expiresAt = expiresAtValue === undefined || expiresAtValue === null
  ? null
  : epochSeconds(expiresAtValue, "expiresAt");
if (expiresAt !== null && expiresAt <= issuedAt) {
  fail("expiresAt must be later than issuedAt");
}

const features = input.features ?? [];
if (!Array.isArray(features) || features.some((f) => typeof f !== "string" || f.trim() === "")) {
  fail("features must be an array of non-empty strings");
}

const payload = {
  v: 1,
  licenseId: text(input.licenseId, "licenseId"),
  customer: text(input.customer, "customer"),
  instanceId: text(input.instanceId, "instanceId"),
  plan: text(input.plan, "plan"),
  maxAgents: positiveInteger(input.maxAgents, "maxAgents", { allowZero: true }),
  maxRunsPerMonth: positiveInteger(input.maxRunsPerMonth, "maxRunsPerMonth", { allowZero: true }),
  features: features.map((f) => f.trim()),
  issuedAt,
  ...(expiresAt === null ? {} : { expiresAt }),
};

const header = base64url(JSON.stringify({ alg: "HS256", typ: "BC-LICENSE" }));
const body = base64url(JSON.stringify(payload));
const signingInput = `${header}.${body}`;
const signature = createHmac("sha256", secret).update(signingInput).digest("base64url");
const token = `${signingInput}.${signature}`;

if (options.output) {
  try {
    writeFileSync(options.output, `${token}\n`, { mode: 0o600 });
    // `mode` only applies when the file is created; an existing file keeps its
    // permissions, so a licence written over a world-readable file must be
    // tightened explicitly.
    chmodSync(options.output, 0o600);
  } catch (error) {
    fail(`cannot write ${options.output}: ${error.message}`, 1);
  }
} else {
  process.stdout.write(`${token}\n`);
}

if (!options.quiet) {
  const expiry = payload.expiresAt === undefined
    ? "perpetual (no expiresAt)"
    : `expires ${new Date(payload.expiresAt * 1000).toISOString()}`;
  const target = options.output ? options.output : "<stdout>";
  process.stderr.write(
    `${PROGRAM}: ${payload.licenseId} for ${payload.customer} ` +
    `(instance ${payload.instanceId}, plan ${payload.plan}, ${expiry}) -> ${target}\n`,
  );
}
if (options.printPayload) {
  process.stderr.write(`${JSON.stringify(payload, null, 2)}\n`);
}
