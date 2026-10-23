import { expect, test } from '@playwright/test';
import { confirmEmail, newAddress, register, signIn } from './helpers';

test('a process is drawn, connected, checked and published in the editor', async ({ page }) => {
  const email = newAddress();
  await register(page, email);
  await confirmEmail(email);
  await signIn(page, email);
  await page.waitForURL(/\/(setup|dashboard)/);

  await page.goto('/automations');
  await page.getByRole('button', { name: 'Automatisierung anlegen' }).click();
  await page.getByRole('textbox', { name: 'Automatisierung' }).fill('Eingang prüfen');
  await page.getByRole('button', { name: 'Erstellen' }).click();
  await page.getByRole('link', { name: 'Eingang prüfen' }).click();
  await page.waitForURL(/\/automations\/[0-9a-f-]+$/);

  await page.getByRole('button', { name: 'ACTION', exact: true }).click();
  await page.getByRole('button', { name: 'TERMINATE', exact: true }).click();
  await expect(page.getByRole('button', { name: /^action \(ACTION\)/ })).toBeVisible();

  // Select the action and let it lead to the terminator.
  await page.getByRole('button', { name: /^action \(ACTION\)/ }).focus();
  await page.getByLabel('Weiter zu').selectOption('terminate');

  await page.getByRole('button', { name: 'Prüfen' }).click();
  await expect(page.getByText('Der Prozess ist in sich schlüssig.')).toBeVisible();
  await page.getByRole('button', { name: 'Veröffentlichen' }).click();
  await expect(page.getByText(/Version \d+ veröffentlicht/)).toBeVisible();

  // The saved graph comes back after a reload, with its connection.
  await page.reload();
  await expect(page.getByRole('button', { name: /^terminate \(TERMINATE\)/ })).toBeVisible();
});
