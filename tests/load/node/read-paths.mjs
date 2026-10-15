// Load run of the read paths without k6: document list, document search, contract list, radar list, future timeline.
// Plain Node (>= 20): the same measurement as the k6 scenarios, for an environment where k6 cannot be installed.
// Usage: BASE_URL=http://localhost:8080 EMAIL=... PASSWORD=... [VUS=10] [SECONDS=30] [DOCS=200] node node/read-paths.mjs
import { randomUUID } from 'node:crypto';

const BASE = process.env.BASE_URL ?? 'http://localhost:8080';
const VUS = Number(process.env.VUS ?? 10);
const SECONDS = Number(process.env.SECONDS ?? 30);
const DOCS = Number(process.env.DOCS ?? 0);
const email = process.env.EMAIL ?? `load-${randomUUID().slice(0, 8)}@example.de`;
const password = process.env.PASSWORD ?? 'EinSicheresPasswort-2026!';

const jar = new Map();
function remember(response) {
  for (const header of response.headers.getSetCookie()) {
    const [pair] = header.split(';');
    const [name, ...rest] = pair.split('=');
    jar.set(name.trim(), rest.join('='));
  }
}
const cookieHeader = () => [...jar].map(([k, v]) => `${k}=${v}`).join('; ');

async function call(method, path, body, extra = {}) {
  const headers = { Cookie: cookieHeader(), ...extra };
  if (jar.has('XSRF-TOKEN')) headers['X-XSRF-TOKEN'] = decodeURIComponent(jar.get('XSRF-TOKEN'));
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  remember(response);
  return response;
}

async function prepare() {
  remember(await fetch(`${BASE}/api/v1/auth/csrf`));
  if (!process.env.EMAIL) {
    const r = await call('POST', '/api/v1/auth/register', { email, password, displayName: 'Load', accountType: 'COMPANY' });
    if (r.status !== 201) throw new Error(`register ${r.status}`);
    console.log('REGISTERED', email, '(confirm the address in the database before logging in)');
    if (!process.env.CONFIRM_SQL_DONE) {
      console.log('Run: update users set status = \'ACTIVE\', email_verified_at = now() where email = \'' + email + '\'; then rerun with EMAIL and PASSWORD');
      process.exit(2);
    }
  }
  const login = await call('POST', '/api/v1/auth/login', { email, password });
  if (login.status !== 200) throw new Error(`login ${login.status}`);
}

const PATHS = [
  ['documents.list', '/api/v1/documents?page=0&size=25'],
  ['documents.search', '/api/v1/documents/search?q=Rechnung'],
  ['contracts.list', '/api/v1/contracts?page=0&size=25'],
  ['radar.list', '/api/v1/radar/findings'],
  ['memory.future', '/api/v1/memory/future?horizon=90'],
];

const samples = new Map(PATHS.map(([name]) => [name, []]));
const failures = new Map(PATHS.map(([name]) => [name, 0]));

async function worker(until) {
  let i = 0;
  while (Date.now() < until) {
    const [name, path] = PATHS[i++ % PATHS.length];
    const started = performance.now();
    try {
      const r = await call('GET', path);
      await r.arrayBuffer();
      samples.get(name).push(performance.now() - started);
      if (r.status >= 400) failures.set(name, failures.get(name) + 1);
    } catch {
      failures.set(name, failures.get(name) + 1);
    }
  }
}

const pct = (sorted, p) => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))] : NaN);

await prepare();
console.log(`CONDITIONS base=${BASE} vus=${VUS} seconds=${SECONDS} docs=${DOCS} node=${process.version} cpus=${(await import('node:os')).cpus().length}`);
const until = Date.now() + SECONDS * 1000;
await Promise.all(Array.from({ length: VUS }, () => worker(until)));
console.log('| path | requests | errors | p50 ms | p95 ms | p99 ms | req/s |');
console.log('|---|---:|---:|---:|---:|---:|---:|');
for (const [name] of PATHS) {
  const sorted = samples.get(name).sort((a, b) => a - b);
  console.log(`| ${name} | ${sorted.length} | ${failures.get(name)} | ${pct(sorted, 50).toFixed(1)} | ${pct(sorted, 95).toFixed(1)} | ${pct(sorted, 99).toFixed(1)} | ${(sorted.length / SECONDS).toFixed(1)} |`);
}
