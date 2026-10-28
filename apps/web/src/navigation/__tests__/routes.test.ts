import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { screens } from '../screens';

/**
 * Every screen the navigation, the command palette and the dashboard cards point at must be a real page.
 * A registered screen without a page is a link that ends in a 404 (the notification centre was one).
 */
describe('screen registry', () => {
  const appDir = join(__dirname, '../../app');

  it.each(screens.map((screen) => [screen.id, screen.href] as const))('%s has a page for %s', (id, href) => {
    if (id === 'documentViewer') return; // dynamic route, covered by documents/[id]
    const folder = href === '/' ? '' : href;
    const candidates = [
      join(appDir, '(app)', folder, 'page.tsx'),
      join(appDir, '(auth)', folder, 'page.tsx'),
      join(appDir, folder, 'page.tsx'),
      join(appDir, '(public)', folder, 'page.tsx'),
    ];
    expect(candidates.some(existsSync), `${href} has no page.tsx`).toBe(true);
  });
});
