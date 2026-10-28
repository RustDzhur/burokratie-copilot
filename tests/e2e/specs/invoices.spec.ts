import { expect, test } from '@playwright/test';
import { Client } from 'pg';
import { confirmEmail, newAddress, register, signIn } from './helpers';

/**
 * An invoice goes in as data and comes out as two documents (spec §14.2, §14.3, §28.3).
 *
 * <p><strong>Why the setup is not trivial.</strong> The invoice area belongs to the company cabinet and to a
 * paid tier, so a freshly registered account is refused with PLAN_FEATURE_REQUIRED - which is the correct
 * behaviour and not something to work around in the product. The test therefore puts the account into the
 * cabinet and tier the feature needs, and then waits: the plan is cached for sixty seconds, so a change made
 * a moment ago is not what the next request sees.</p>
 *
 * <p>The browser already proves a person can sign in, see the dashboard and finish a task. This proves the
 * other half of what a customer does: record an invoice and get the file that goes to the client.</p>
 */
test.describe('invoices', () => {
  /** How long the plan is cached in the server, plus a margin. */
  const PLAN_CACHE_MS = 62_000;

  /**
   * Puts the account into the company cabinet and removes its trial subscription.
   *
   * <p>A tenant without a subscription row is not restricted, which is the platform's own rule and the
   * cheapest honest way for a test to reach a paid area without inventing a billing state.</p>
   */
  async function enableInvoiceArea(email: string): Promise<void> {
    const url = process.env.E2E_DATABASE_URL
      ?? 'postgresql://burokratie:burokratie_dev_password@localhost:5432/burokratie_e2e';
    const client = new Client({ connectionString: url });
    await client.connect();
    try {
      await client.query(
        // users carries no tenant_id: the link is the membership, and the owner is the one who registered.
        `update tenants set account_type = 'COMPANY'
          where id = (select m.tenant_id from memberships m
                        join users u on u.id = m.user_id
                       where lower(u.email::text) = lower($1) and m.is_owner
                       limit 1)`,
        [email],
      );
      await client.query(
        `delete from subscriptions
          where tenant_id = (select m.tenant_id from memberships m
                               join users u on u.id = m.user_id
                              where lower(u.email::text) = lower($1) and m.is_owner
                              limit 1)`,
        [email],
      );
    } finally {
      await client.end();
    }
    // The plan is cached for a minute; without this the next request still sees the old tier.
    await new Promise((resolve) => setTimeout(resolve, PLAN_CACHE_MS));
  }

  const body = (number: string): string =>
    JSON.stringify({
      invoiceNumber: number,
      supplierName: 'Muster Lieferant GmbH',
      direction: 'INCOMING',
      invoiceType: 'INVOICE',
      currency: 'EUR',
      netAmount: '100.00',
      taxAmount: '19.00',
      grossAmount: '119.00',
      iban: 'DE89370400440532013000',
      issueDate: '2026-03-01',
      dueDate: '2026-03-31',
      paymentTermsDays: 30,
      lines: [
        {
          lineNumber: 1,
          description: 'Leistung',
          quantity: 1,
          unit: 'Stk',
          unitPrice: '100.00',
          netAmount: '100.00',
          taxRatePercent: '19.00',
          taxAmount: '19.00',
          grossAmount: '119.00',
        },
      ],
    });

  test('an invoice comes back as a PDF and as an XRechnung', async ({ page }) => {
    test.setTimeout(180_000);
    const email = newAddress();
    await register(page, email);
    await confirmEmail(email);
    await enableInvoiceArea(email);
    await signIn(page, email);
    await page.waitForURL(/\/(onboarding|dashboard)/);

    // The API is protected by double-submit CSRF: the token lives in a cookie and has to be echoed in the
    // header on every unsafe request. The application's own client does that; a raw request must do it too,
    // which is why this is not simply a fetch.
    await page.request.get('/api/v1/auth/csrf');
    const cookies = await page.context().cookies();
    const csrf = cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? '';

    const number = `RE-E2E-${Date.now()}`;
    const created = await page.request.post('/api/v1/invoices', {
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(csrf) },
      data: body(number),
    });
    expect(created.status(), await created.text()).toBe(201);
    const invoice = await created.json();
    expect(invoice.id).toBeTruthy();

    const pdf = await page.request.get(`/api/v1/invoices/${invoice.id}/pdf`);
    expect(pdf.status()).toBe(200);
    expect(pdf.headers()['content-type']).toContain('application/pdf');
    const pdfBytes = await pdf.body();
    expect(pdfBytes.length).toBeGreaterThan(500);
    // The file signature, not just the content type: a route returning an error page with a PDF header would
    // otherwise pass.
    expect(pdfBytes.subarray(0, 4).toString('ascii')).toBe('%PDF');

    const xml = await page.request.get(`/api/v1/invoices/${invoice.id}/xrechnung`);
    expect(xml.status()).toBe(200);
    const text = await xml.text();
    expect(text).toContain('<Invoice');
    expect(text).toContain(number);
  });
});
