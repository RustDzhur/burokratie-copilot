import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The installable-app files (§5.2, B-015).
 *
 * A manifest with a wrong path or a missing icon is not a manifest: the browser silently declines to
 * offer the installation, and the person who was told the app works on a telephone finds that it does
 * not. These checks run without a browser, which is why they can run at all here.
 */
const root = process.cwd();
const manifestPath = join(root, 'public', 'manifest.webmanifest');
const manifest = JSON.parse(readFileSync(manifestPath, 'utf-8')) as {
  name: string;
  short_name: string;
  start_url: string;
  display: string;
  theme_color: string;
  background_color: string;
  lang: string;
  icons: { src: string; sizes: string; type: string; purpose?: string }[];
  shortcuts?: { name: string; url: string }[];
};

describe('web app manifest', () => {
  it('names the application and says how it is displayed', () => {
    expect(manifest.name).toBe('Bürokratie-Copilot');
    expect(manifest.short_name.length).toBeLessThanOrEqual(12);
    expect(['standalone', 'fullscreen', 'minimal-ui']).toContain(manifest.display);
  });

  it('starts inside the application and stays there', () => {
    expect(manifest.start_url.startsWith('/')).toBe(true);
    expect(manifest.start_url).not.toContain('http');
  });

  it('declares the language and the colours of the shell', () => {
    expect(manifest.lang).toBe('de');
    expect(manifest.theme_color).toMatch(/^#[0-9a-f]{6}$/i);
    expect(manifest.background_color).toMatch(/^#[0-9a-f]{6}$/i);
  });

  it('has the sizes a telephone asks for', () => {
    const sizes = manifest.icons.map((icon) => icon.sizes);
    expect(sizes).toContain('192x192');
    expect(sizes).toContain('512x512');
  });

  it('offers a maskable icon, so it is not cropped into a shape of somebody else’s choosing', () => {
    expect(manifest.icons.some((icon) => icon.purpose === 'maskable')).toBe(true);
  });

  it('points at icons that exist', () => {
    for (const icon of manifest.icons) {
      expect(icon.type).toBe('image/png');
      expect(existsSync(join(root, 'public', icon.src.replace(/^\//, '')))).toBe(true);
    }
  });

  it('offers a shortcut that goes straight to capture', () => {
    const capture = manifest.shortcuts?.find((shortcut) => shortcut.url.includes('capture'));
    expect(capture).toBeDefined();
    expect(capture?.url.startsWith('/')).toBe(true);
  });
});

describe('service worker', () => {
  const worker = readFileSync(join(root, 'public', 'sw.js'), 'utf-8');

  it('exists and handles fetch', () => {
    expect(worker).toContain("addEventListener('fetch'");
    expect(worker).toContain("addEventListener('install'");
  });

  it('never caches API responses', () => {
    // The line that matters: a cache is shared between whoever uses the device, and those responses
    // belong to one tenant.
    expect(worker).toContain("startsWith('/api/')");
  });

  it('ignores anything that is not a GET', () => {
    expect(worker).toContain("request.method !== 'GET'");
  });
});
