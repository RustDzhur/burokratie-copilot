import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Contract between the UI and the backend.
 *
 * Every call declared in `resources.ts` must name a route that exists in a Spring controller. The audit
 * (docs/completion-audit.md, C1) found 43 of 74 calls pointing nowhere while every other test was green,
 * because the front-end tests fake the network and the back-end tests never see the UI. This test reads
 * both sides from source, so neither can drift without a failing build.
 *
 * `KNOWN_GAPS` is the remaining debt. It may only shrink: a gap that gets fixed must be removed (the
 * second test fails otherwise), and a new call without a route fails the first.
 */

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '../../../../../..');
const controllersRoot = join(repoRoot, 'services/api/src/main/java');
const resourcesFile = join(repoRoot, 'apps/web/src/lib/api/resources.ts');

const API_PREFIX = '/api/v1';

const KNOWN_GAPS: readonly string[] = [
];

function normalize(path: string): string {
  const unified = path.replace(/\$\{[^}]*\}/g, '{}').replace(/\{[^}/]*\}/g, '{}');
  return unified.length > 1 ? unified.replace(/\/+$/, '') : unified;
}

function javaFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    return statSync(full).isDirectory() ? javaFiles(full) : full.endsWith('.java') ? [full] : [];
  });
}

/** `METHOD path` for every `@Get/Post/Put/Patch/DeleteMapping` of every controller. */
function serverRoutes(): Set<string> {
  const routes = new Set<string>();
  for (const file of javaFiles(controllersRoot)) {
    const text = readFileSync(file, 'utf8');
    if (!text.includes('@RestController') && !text.includes('@Controller')) continue;
    const base = /@RequestMapping\(\s*(?:value\s*=\s*)?(?:\{\s*)?"([^"]*)"/.exec(text)?.[1] ?? '';
    for (const match of text.matchAll(/@(?:[\w.]+\.)?(Get|Post|Put|Patch|Delete)Mapping(?:\(([^)]*)\))?/g)) {
      const path = /"([^"]*)"/.exec(match[2] ?? '')?.[1] ?? '';
      routes.add(`${(match[1] ?? '').toUpperCase()} ${normalize(base + path)}`);
    }
  }
  return routes;
}

const VERB_TO_METHOD: Record<string, string> = {
  get: 'GET',
  list: 'GET',
  post: 'POST',
  upload: 'POST',
  put: 'PUT',
  patch: 'PATCH',
  delete: 'DELETE',
};

/** `METHOD path` (path as written in resources.ts) for every `client.<verb>(...)` call. */
function uiCalls(): string[] {
  const source = readFileSync(resourcesFile, 'utf8');
  const calls: string[] = [];
  for (const match of source.matchAll(
    /client\.(get|post|put|patch|delete|list|upload|request)\s*(?:<[^()]*?>)?\(\s*([`'"])(.*?)\2(.*?)\n/g,
  )) {
    const verb = match[1] ?? '';
    const rest = match[4] ?? '';
    const method =
      verb === 'request' ? (/method:\s*'(\w+)'/.exec(rest)?.[1] ?? 'GET') : VERB_TO_METHOD[verb];
    calls.push(`${method} ${match[3] ?? ''}`);
  }
  return calls;
}

function gaps(): string[] {
  const routes = serverRoutes();
  return [
    ...new Set(
      uiCalls().filter((call) => {
        const [method, path] = call.split(' ') as [string, string];
        return !routes.has(`${method} ${normalize(API_PREFIX + path)}`);
      }),
    ),
  ].sort();
}

describe('UI ↔ API contract', () => {
  it('finds the calls and the routes it compares (guards the extraction itself)', () => {
    expect(uiCalls().length).toBeGreaterThan(60);
    expect(serverRoutes().size).toBeGreaterThan(200);
    expect(serverRoutes().has('POST /api/v1/auth/login')).toBe(true);
  });

  it('every UI call has a backend route, apart from the recorded gaps', () => {
    const unexpected = gaps().filter((call) => !KNOWN_GAPS.includes(call));
    expect(unexpected, 'calls that point at no controller route').toEqual([]);
  });

  it('records no gap that has since been fixed', () => {
    const current = new Set(gaps());
    const stale = KNOWN_GAPS.filter((call) => !current.has(call));
    expect(stale, 'remove these from KNOWN_GAPS').toEqual([]);
  });
});
