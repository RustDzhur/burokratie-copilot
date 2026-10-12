import { describe, expect, it, vi } from 'vitest';
import { createApiClient } from '../client';
import { createResources } from '../resources';
import { contractFromWire, decimalFromInput, invoiceFromWire, noticeDeadline, paymentFromWire } from '../wire/finance';

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

  it('maps bank transactions to payments', () => {
    expect(paymentFromWire({ id: 't1', booking_date: '2026-10-01', amount: '-50.00', currency: 'EUR', counterparty_name: 'Vodafone', status: 'SUGGESTED', suggested_invoice: 'R-9', purpose: 'Rechnung R-9' })).toMatchObject({ status: 'suggested', amount: -50, suggestion: 'R-9', counterparty: 'Vodafone' });
  });

  it('computes the notice deadline from the term end', () => {
    expect(noticeDeadline('2027-01-01', 3, 'MONTHS')).toBe('2026-10-01');
    expect(noticeDeadline('2027-01-31', 2, 'WEEKS')).toBe('2027-01-17');
    expect(noticeDeadline('2027-01-31', 3, 'FORTNIGHTS')).toBeNull();
    expect(noticeDeadline(null, 3, 'MONTHS')).toBeNull();
  });

  it('maps a contract to partner, status and notice', () => {
    expect(contractFromWire({ id: 'c1', title: 'Vertrag 1', contractType: 'RENT', status: 'EXPIRING', endDate: '2027-01-01', noticePeriodValue: 3, noticePeriodUnit: 'MONTHS', costAmount: 900, costCurrency: 'EUR', partnerName: 'Vermieter GmbH' })).toMatchObject({ partner: 'Vermieter GmbH', status: 'expiring', noticeDeadline: '2026-10-01', value: 900 });
  });

  it('creates a contract and then records its partner as a party', async () => {
    const { api, calls } = resourcesWith((url) => json(url.endsWith('/parties') ? {} : { id: 'c1', title: 'Miete', status: 'DRAFT' }, 201));
    const created = await api.contracts.create({ partner: 'Vermieter GmbH', contractType: 'RENT' });
    expect(calls.map((call) => new URL(call.url, 'http://x').pathname)).toEqual(['/api/v1/contracts', '/api/v1/contracts/c1/parties']);
    expect(JSON.parse(String(calls[1]?.init.body))).toMatchObject({ partyRole: 'COUNTERPARTY', partyName: 'Vermieter GmbH', primary: true });
    expect(created.partner).toBe('Vermieter GmbH');
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

  it('matches a payment without sending an amount', async () => {
    const { api, calls } = resourcesWith(() => json({}, 201));
    await api.payments.match('t1', 'i1');
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({ transactionId: 't1', invoiceId: 'i1', matchType: 'MANUAL' });
  });
});
