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
    await page.waitForURL(/\/(onboarding|dashboard)/);
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
    await page.waitForURL(/\/(onboarding|tasks)/);
  });

  test('every screen of the navigation opens without an error state', async ({ page }) => {
    const email = newAddress();
    await register(page, email);
    await confirmEmail(email);
    await signIn(page, email);
    await page.waitForURL(/\/(onboarding|dashboard)/);

    const routes = ['dashboard', 'inbox', 'documents', 'review', 'cases', 'tasks', 'calendar', 'contracts', 'invoices',
      'payments', 'contacts', 'mail', 'templates', 'assistant', 'automations', 'integrations', 'scan', 'family',
      'company', 'employees', 'reports', 'archive', 'security', 'accessibility', 'notifications', 'audit'];
    for (const route of routes) {
      await page.goto(`/${route}`);
      await expect(page.locator('main')).toBeVisible();
      await expect(page.getByText('Daten konnten nicht geladen werden'), `/${route}`).toHaveCount(0);
    }
  });
});
