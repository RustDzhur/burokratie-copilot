import { chromium } from '@playwright/test';
const BASE = 'http://localhost:3000';
const email = `diag-${Date.now()}@example.de`;
const browser = await chromium.launch();
const page = await browser.newPage();
page.on('console', m => { if (m.type() === 'error') console.log('CONSOLE ERROR:', m.text().slice(0,200)); });
page.on('pageerror', e => console.log('PAGE ERROR:', String(e).slice(0,300)));

await page.goto(`${BASE}/register`);
await page.getByLabel(/E-Mail/i).first().fill(email);
const pwds = page.locator('input[type=password]');
await pwds.nth(0).fill('EinSicheresPasswort-2026!');
if (await pwds.count() > 1) await pwds.nth(1).fill('EinSicheresPasswort-2026!');
await page.getByRole('button', { name: /Registrieren|Konto/i }).first().click();
await page.waitForTimeout(3000);
console.log('after register url:', page.url());
await browser.close();
