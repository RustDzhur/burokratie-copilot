import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * A module marked 'use client' is a boundary: a server component cannot call what it exports. `buttonClass` once
 * was imported that way by the landing and not-found pages and failed only at runtime, which no build, lint rule
 * or unit test noticed. This reads the sources instead.
 */
function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    return statSync(full).isDirectory() ? files(full) : /\.(tsx?)$/.test(name) ? [full] : [];
  });
}

describe('server components and client modules', () => {
  const root = join(__dirname, '../../..');
  const all = files(root).filter((file) => !file.includes('__tests__'));
  const isClient = (file: string): boolean => /^\s*['"]use client['"]/.test(readFileSync(file, 'utf8'));

  it('no server module imports buttonClass from the client button module', () => {
    const offenders = all.filter((file) => {
      if (isClient(file)) return false;
      return /import\s*\{[^}]*\bbuttonClass\b[^}]*\}\s*from\s*['"][^'"]*ui\/button['"]/.test(readFileSync(file, 'utf8'));
    });
    expect(offenders).toEqual([]);
  });

  it('button.tsx is a client module and does not re-export the class factory', () => {
    const button = readFileSync(join(root, 'components/ui/button.tsx'), 'utf8');
    expect(button).toMatch(/^'use client'/);
    expect(button).not.toMatch(/export\s*\{\s*buttonClass\s*\}/);
  });
});
