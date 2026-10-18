#!/usr/bin/env node
/**
 * The Desktop Bridge (spec §8.2).
 *
 * <p><strong>Why this program exists.</strong> A browser cannot talk to a scanner. That is not a gap in the
 * platform — no browser can — so the part that touches the device is this program, and the platform exposes
 * the receiving end. Everything here is about one promise: a sheet that goes through a scanner appears in the
 * inbox, or the person standing at the machine is told exactly why it did not.</p>
 */

import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const run = promisify(execFile);

const API = (process.env.BC_API_URL ?? '').replace(/\/+$/, '');
const KEY = process.env.BC_DEVICE_KEY ?? '';
const VERSION = '1.0.0';

/** Scans slower than this are reported rather than silently waited for. */
const SCAN_TIMEOUT_MS = 120_000;
const POLL_MS = 5_000;

const log = (...parts) => console.log(new Date().toISOString(), ...parts);

/** Fails early and in words a person can act on: an empty URL is a setup mistake, not a runtime one. */
function requireConfiguration() {
  const missing = [];
  if (!API) missing.push('BC_API_URL');
  if (!KEY) missing.push('BC_DEVICE_KEY');
  if (missing.length > 0) {
    console.error(`Nicht konfiguriert: ${missing.join(', ')} fehlt.`);
    console.error('Setzen Sie die Variablen und starten Sie den Agenten erneut.');
    process.exit(2);
  }
}

/** A request with the device key; the answer stays raw so multipart and JSON can share the path. */
async function request(path, { method = 'GET', body, headers = {} } = {}) {
  const response = await fetch(`${API}${path}`, {
    method,
    headers: { 'X-Device-Key': KEY, ...headers },
    body,
  });
  const text = await response.text();
  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = null;
  }
  if (!response.ok) {
    // The platform's own message is the most useful one: it names the device state.
    const reason = parsed?.message ?? parsed?.code ?? text.slice(0, 200) ?? `HTTP ${response.status}`;
    throw new Error(`${response.status}: ${reason}`);
  }
  return parsed;
}

/** Which SANE devices this machine has, and why there might be none. */
async function listScanners() {
  try {
    const { stdout } = await run('scanimage', ['-L'], { timeout: 20_000 });
    const devices = stdout
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.startsWith('device '))
      .map((line) => line.replace(/^device\s+/, '').replace(/^`|'$/g, ''));
    return { available: true, devices, raw: stdout.trim() };
  } catch (error) {
    // Two very different situations produce an error here, and they need different advice.
    const missingBinary = error.code === 'ENOENT';
    return {
      available: false,
      devices: [],
      reason: missingBinary
        ? 'scanimage wurde nicht gefunden. Installieren Sie SANE (macOS: brew install sane-backends, Debian/Ubuntu: apt install sane-utils).'
        : `scanimage ist vorhanden, konnte aber nicht ausgeführt werden: ${error.message}`,
    };
  }
}

/** Runs a scan and returns the path of the produced file. */
async function scan(format = 'png') {
  const target = join(tmpdir(), `bc-scan-${Date.now()}.${format}`);
  await run('scanimage', [`--format=${format}`, '--output-file', target], { timeout: SCAN_TIMEOUT_MS });
  const bytes = await readFile(target);
  if (bytes.length === 0) {
    throw Object.assign(new Error('Der Scanner hat ein leeres Bild geliefert'), { code: 'EMPTY_SCAN' });
  }
  return { path: target, bytes, mimeType: format === 'pdf' ? 'application/pdf' : `image/${format}` };
}

/** Sends the reasons a scan did not happen in the vocabulary the platform understands. */
function errorCodeFor(error) {
  if (error.killed || /timeout/i.test(String(error.message))) return 'DEVICE_BUSY';
  if (/no documents|keine vorlage|empty|leer/i.test(String(error.message))) return 'NO_PAPER';
  if (/paper jam|stau/i.test(String(error.message))) return 'JAMMED';
  if (/not found|nicht gefunden|ENOENT/i.test(String(error.message))) return 'NO_SCANNER';
  if (error.code === 'EMPTY_SCAN') return 'NO_PAPER';
  return 'SCAN_FAILED';
}

async function reportFailure(jobId, errorCode, message) {
  const form = new FormData();
  form.set('errorCode', errorCode);
  form.set('errorMessage', message.slice(0, 500));
  await request(`/api/v1/device-agent/jobs/${jobId}/result`, { method: 'POST', body: form });
}

async function uploadScan(jobId, file) {
  const form = new FormData();
  form.set('file', new Blob([file.bytes], { type: file.mimeType }), `scan-${jobId}.png`);
  return request(`/api/v1/device-agent/jobs/${jobId}/result`, { method: 'POST', body: form });
}

/** One poll: claim, scan, upload. Returns whether a job was handled. */
async function cycle() {
  const job = await request('/api/v1/device-agent/claim', { method: 'POST' });
  if (!job || !job.jobId) return false;

  log(`Auftrag ${job.jobId} übernommen`);
  try {
    const scanResult = await scan();
    const answer = await uploadScan(job.jobId, scanResult);
    log(`Auftrag ${job.jobId} erledigt, Dokument ${answer?.documentId ?? '?'}`);
  } catch (error) {
    const code = errorCodeFor(error);
    // The scan is lost, but the reason is not: the platform records it and the job can be retried.
    log(`Auftrag ${job.jobId} fehlgeschlagen: ${code} — ${error.message}`);
    await reportFailure(job.jobId, code, error.message).catch((reportError) =>
      log(`Der Fehler konnte nicht gemeldet werden: ${reportError.message}`),
    );
  }
  return true;
}

async function diagnose() {
  requireConfiguration();
  const me = await request('/api/v1/device-agent/me');
  log(`Gerät: ${me.name} (${me.identifier})`);

  const scanners = await listScanners();
  if (!scanners.available) {
    log(`Scannen nicht möglich: ${scanners.reason}`);
  } else if (scanners.devices.length === 0) {
    log('scanimage ist vorhanden, findet aber kein Gerät.');
    log('Prüfen Sie das Kabel, ob der Scanner eingeschaltet ist, und ob er von SANE unterstützt wird.');
  } else {
    log(`Scanner gefunden: ${scanners.devices.join(', ')}`);
  }

  await request('/api/v1/device-agent/heartbeat?version=' + encodeURIComponent(VERSION) +
    '&os=' + encodeURIComponent(process.platform), { method: 'POST' }).catch(() => {});
  return scanners.available && scanners.devices.length > 0;
}

async function main() {
  const command = process.argv[2] ?? 'run';
  requireConfiguration();

  if (command === 'diagnose') {
    const ready = await diagnose();
    process.exit(ready ? 0 : 1);
  }
  if (command === 'once') {
    const handled = await cycle();
    process.exit(handled ? 0 : 0);
  }
  if (command === 'run') {
    log(`Desktop Bridge ${VERSION} startet gegen ${API}`);
    // A bridge is meant to stay up: one failed poll is a network blip, not a reason to stop.
    for (;;) {
      try {
        const handled = await cycle();
        if (!handled) await new Promise((resolve) => setTimeout(resolve, POLL_MS));
      } catch (error) {
        log(`Abfrage fehlgeschlagen: ${error.message}`);
        await new Promise((resolve) => setTimeout(resolve, POLL_MS * 2));
      }
    }
  }

  console.error(`Unbekannter Befehl: ${command}. Erlaubt: diagnose, once, run.`);
  process.exit(2);
}

main().catch((error) => {
  console.error(`Der Agent wurde beendet: ${error.message}`);
  process.exit(1);
});
