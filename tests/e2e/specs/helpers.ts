import { randomUUID } from 'node:crypto';
import { Client } from 'pg';
import type { Page } from '@playwright/test';

export const PASSWORD = 'Test-Passwort-2026-e2e!';

export function newAddress(): string {
  return `e2e-${randomUUID().slice(0, 8)}@example.de`;
}

/** Marks the address as confirmed, the way the link in the mail would. */
export async function confirmEmail(email: string): Promise<void> {
  const url = process.env.E2E_DATABASE_URL ?? 'postgresql://burokratie:burokratie_dev_password@localhost:5432/burokratie_e2e';
  const client = new Client({ connectionString: url });
  await client.connect();
  try {
    const result = await client.query(
      "update users set status = 'ACTIVE', email_verified_at = now() where lower(email::text) = lower($1)",
      [email],
    );
    if (result.rowCount !== 1) throw new Error(`expected to confirm exactly one user for ${email}, got ${result.rowCount}`);
  } finally {
    await client.end();
  }
}

export async function register(page: Page, email: string, name = 'Erika Mustermann'): Promise<void> {
  await page.goto('/register');
  await page.getByLabel('Vor- und Nachname').fill(name);
  await page.getByLabel('E-Mail-Adresse').fill(email);
  await page.getByLabel('Passwort', { exact: true }).fill(PASSWORD);
  await page.getByLabel('Passwort wiederholen').fill(PASSWORD);
  await page.getByRole('checkbox').check();
  await page.getByRole('button', { name: 'Konto erstellen' }).click();
  await page.waitForURL('**/verify-email');
}

export async function signIn(page: Page, email: string, password = PASSWORD): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('E-Mail-Adresse').fill(email);
  await page.getByLabel('Passwort', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Anmelden' }).click();
}
