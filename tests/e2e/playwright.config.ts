import { defineConfig, devices } from '@playwright/test';

/**
 * Runs against an already running stack (see README.md): the web app on E2E_BASE_URL (default
 * http://localhost:3000), which proxies /api/v1 to the API, and the database the API uses (E2E_DATABASE_URL), which
 * the tests touch in exactly one place: marking a freshly registered address as confirmed, because the
 * confirmation mail is not readable from a browser.
 */
export default defineConfig({
  testDir: './specs',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'de-DE',
    // A desktop window, not the 1280x720 that Desktop Chrome defaults to. The editor's action bar - the
    // "Prüfen" and "Veröffentlichen" buttons - sits at y = 798 in this layout, so at a 720 pixel height it
    // was outside the viewport and the browser test spent sixty seconds waiting for a button that a person
    // on any normal screen would have seen. This is the smallest window the product actually targets.
    viewport: { width: 1440, height: 900 },
  },
  // The project's use wins over the block above, and devices['Desktop Chrome'] carries its own 1280x720
  // viewport, so the height has to be set here as well or it is silently overridden.
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } }],
});
