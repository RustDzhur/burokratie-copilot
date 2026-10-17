import { test } from '@playwright/test';
import { confirmEmail, newAddress, register, signIn } from './helpers';

test('diagnose task dialog filled', async ({ page }) => {
  const email = newAddress();
  await register(page, email);
  await confirmEmail(email);
  await signIn(page, email);
  await page.waitForURL(/\/(onboarding|dashboard)/);
  await page.goto('/tasks');
  await page.getByRole('button', { name: 'Aufgabe anlegen' }).click();
  await page.waitForTimeout(1200);
  const title = page.getByLabel('Aufgabe', { exact: true });
  console.log('TITLE COUNT:', await title.count());
  await title.fill('Steuerbescheid prüfen');
  console.log('filled title');
  const crit = page.getByLabel('Erledigt, wenn …');
  console.log('CRIT COUNT:', await crit.count());
  await crit.fill('Bescheid ist geprüft und abgelegt');
  console.log('filled crit');
  await page.waitForTimeout(800);
  const b = page.getByRole('button', { name: 'Erstellen' });
  console.log('COUNT:', await b.count(), 'VISIBLE:', await b.first().isVisible(), 'ENABLED:', await b.first().isEnabled());
  const box = await b.first().boundingBox();
  console.log('BOX:', JSON.stringify(box));
  const el = await b.first().elementHandle();
  const covered = await page.evaluate((n) => {
    const r = n.getBoundingClientRect();
    const top = document.elementFromPoint(r.x + r.width/2, r.y + r.height/2);
    return top ? top.tagName + '.' + (top.className||'').toString().slice(0,60) : 'none';
  }, el);
  console.log('ELEMENT AT CENTER:', covered);
});
