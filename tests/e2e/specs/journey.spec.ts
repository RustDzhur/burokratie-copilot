import { expect, test } from '@playwright/test';
import { confirmEmail, newAddress, register, signIn, PASSWORD } from './helpers';

/**
 * Acceptance criteria of docs/task.md §34 that only a browser can prove: registration and sign-in work (the
 * session cookie and the CSRF token cross the real proxy), no button on the way is dead, and the first screens
 * show the person's own data.
 */
test.describe('first journey', () => {
  test('register, confirm, sign in, see the dashboard, create and finish a task', async ({ page }) => {
    const email = newAddress();
    await register(page, email);
    await expect(page.getByText(/Bestätigungslink|bestätigt/i).first()).toBeVisible();
    await confirmEmail(email);

    await signIn(page, email);
    // A first sign-in goes through the setup wizard.
    await page.waitForURL(/\/(setup|dashboard)/);
    await page.goto('/dashboard');
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    await expect(page.getByText('Erika Mustermann').first()).toBeVisible();

    await page.goto('/tasks');
    await page.getByRole('button', { name: 'Aufgabe anlegen' }).click();
    await page.getByLabel('Aufgabe', { exact: true }).fill('Steuerbescheid prüfen');
    await page.getByLabel('Erledigt, wenn …').fill('Bescheid ist geprüft und abgelegt');
    await page.getByRole('button', { name: 'Erstellen' }).click();

    const row = page.getByRole('row', { name: /Steuerbescheid prüfen/ });
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: 'Erledigen' }).click();
    await expect(page.getByRole('row', { name: /Steuerbescheid prüfen/ }).getByText('Erledigt')).toBeVisible();
  });

  test('a wrong password is refused and says so', async ({ page }) => {
    const email = newAddress();
    await register(page, email);
    await confirmEmail(email);
    await signIn(page, email, `${PASSWORD}-falsch`);
    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByRole('alert').or(page.getByText(/Anmeldung|Zugangsdaten|nicht authentifiziert/i)).first()).toBeVisible();
  });

  test('a page of the signed-in area without a session sends the person to sign in and back', async ({ page }) => {
    await page.goto('/tasks');
    await page.waitForURL(/\/login\?next=%2Ftasks/);
    const email = newAddress();
    await register(page, email);
    await confirmEmail(email);
    await signIn(page, email);
    await page.waitForURL(/\/(setup|tasks)/);
  });

  test('every screen of the navigation opens without an error state', async ({ page }) => {
    const email = newAddress();
    await register(page, email);
    await confirmEmail(email);
    await signIn(page, email);
    await page.waitForURL(/\/(setup|dashboard)/);

    // A hand-maintained list drifts: a screen was added and this list did not know about it, so the sweep
    // that says 'every screen' was silently checking one screen fewer - and the one it missed was broken.
    const routes = ['dashboard', 'inbox', 'documents', 'review', 'cases', 'tasks', 'calendar', 'contracts', 'invoices',
      'contacts', 'mail', 'templates', 'generated-documents', 'assistant', 'automations', 'integrations', 'scan', 'family',
      'company', 'employees', 'reports', 'archive', 'security', 'accessibility', 'notifications', 'audit',
      'settings/date-markers'];
    for (const route of routes) {
      await page.goto(`/${route}`);
      await expect(page.locator('main')).toBeVisible();
      await expect(page.getByText('Daten konnten nicht geladen werden'), `/${route}`).toHaveCount(0);
    }
  });
});

test('dashboard cards: a card link only covers its own card (hover does not flicker)', async ({ page }) => {
  const email = newAddress();
  await register(page, email);
  await confirmEmail(email);
  await signIn(page, email);
  await page.waitForURL(/\/(setup|dashboard)/);
  await page.goto('/dashboard');
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  const cards = page.locator('a[class*="cardLink"]');
  await expect(cards.first()).toBeVisible();

  // The link's stretched overlay is its ::after: a point on the page heading must not hit any card link.
  const hit = await page.evaluate(() => {
    const heading = document.querySelector('h1');
    if (!heading) return 'no heading';
    const box = heading.getBoundingClientRect();
    const element = document.elementFromPoint(box.left + 4, box.top + box.height / 2);
    return element?.closest('a[class*="cardLink"]') ? 'card link' : 'other';
  });
  expect(hit).toBe('other');

  // And a point inside the first card does hit that card's own link.
  const inside = await page.evaluate(() => {
    const link = document.querySelector('a[class*="cardLink"]');
    // The card is the positioning context of the stretched overlay, and the list item around it is taller
    // than the card because the grid row is sized by the tallest one. Measuring the card, at a point on its
    // own padding rather than over its text, is what "the card is one click target" actually means.
    const card = link?.closest('[class*="cardInteractive"]');
    if (!link || !card) return false;
    const box = card.getBoundingClientRect();
    const element = document.elementFromPoint(box.left + 8, box.top + box.height / 2);
    return element === link || link.contains(element);
  });
  expect(inside).toBe(true);
});
