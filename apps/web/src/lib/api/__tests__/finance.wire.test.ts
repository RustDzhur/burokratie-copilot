import { describe, expect, it, vi } from 'vitest';
import { createApiClient } from '../client';
import { createResources } from '../resources';
import { decimalFromInput, invoiceFromWire, noticeDeadline } from '../wire/finance';

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

function resourcesWith(handler: (url: string, init: RequestInit) => Response) {
  const calls: { url: string; init: RequestInit }[] = [];
  const fetchImpl = vi.fn(async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    return handler(url, init);
  });
  const client = createApiClient({ baseUrl: '/api/v1', csrf: false, fetchImpl: fetchImpl as unknown as typeof fetch });
  return { api: createResources(client), calls };
}

describe('finance ↔ backend', () => {
  it('maps an invoice', () => {
    expect(
      invoiceFromWire({ id: 'i1', invoiceNumber: 'R-1', direction: 'INCOMING', status: 'IN_REVIEW', currency: 'EUR', grossAmount: '119.00', dueDate: '2026-11-01', supplierName: 'Stadtwerke', documentId: 'd1' }),
    ).toMatchObject({ number: 'R-1', counterparty: 'Stadtwerke', direction: 'incoming', status: 'review', amount: 119, eRechnung: true });
  });

  it('reads German and plain decimals', () => {
    expect(decimalFromInput('1.234,50')).toBe('1234.50');
    expect(decimalFromInput('1234.50')).toBe('1234.50');
    expect(decimalFromInput(' 12,5 ')).toBe('12.5');
  });

  it('computes the notice deadline from the term end', () => {
    expect(noticeDeadline('2027-01-01', 3, 'MONTHS')).toBe('2026-10-01');
    expect(noticeDeadline('2027-01-31', 2, 'WEEKS')).toBe('2027-01-17');
    expect(noticeDeadline('2027-01-31', 3, 'FORTNIGHTS')).toBeNull();
    expect(noticeDeadline(null, 3, 'MONTHS')).toBeNull();
  });

  it('lists invoices with backend filters and records them with the backend field names', async () => {
    const { api, calls } = resourcesWith(() => json({ content: [], number: 0, size: 25, totalElements: 0, totalPages: 0 }));
    await api.invoices.list({ status: 'review', direction: 'incoming' });
    const url = new URL(calls[0]?.url ?? '', 'http://x');
    expect(url.searchParams.getAll('status')).toEqual(['RECEIVED', 'IN_REVIEW']);
    expect(url.searchParams.get('direction')).toBe('INCOMING');

    const second = resourcesWith(() => json({ id: 'i1', invoiceNumber: 'R-1', direction: 'INCOMING', status: 'RECEIVED', currency: 'EUR' }, 201));
    await second.api.invoices.create({ number: 'R-1', counterparty: 'ACME', amount: '1.234,50', direction: 'incoming' });
    expect(JSON.parse(String(second.calls[0]?.init.body))).toMatchObject({ invoiceNumber: 'R-1', supplierName: 'ACME', direction: 'INCOMING', grossAmount: '1234.50' });
  });

  it('sends an invoice through its own route, idempotently, with the letter as optional input', async () => {
    const { api, calls } = resourcesWith(() => json({
      invoiceId: 'i1', documentId: 'd1', messageId: 'm1', status: 'SENT',
      providerMessageId: 'p1', description: 'accepted', to: ['kunde@example.de'], subject: 'Rechnung R-1',
      documentFilename: 'R-1.pdf',
    }));
    const result = await api.invoices.send('i1', { to: 'kunde@example.de' });

    const url = new URL(calls[0]?.url ?? '', 'http://x');
    expect(calls[0]?.init.method).toBe('POST');
    expect(url.pathname).toBe('/api/v1/invoices/i1/send');
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({ to: 'kunde@example.de' });
    // A retry after a timeout must not post a second letter to the customer.
    expect(new Headers(calls[0]?.init.headers as HeadersInit).get('Idempotency-Key')).toBeTruthy();
    expect(result.to).toEqual(['kunde@example.de']);
    expect(result.documentFilename).toBe('R-1.pdf');
  });
});
