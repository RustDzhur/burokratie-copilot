import { describe, expect, it, vi } from 'vitest';
import { createApiClient } from '../client';
import { createResources } from '../resources';
import { notificationFromWire } from '../wire/people';
import {
  approvalFromFields,
  documentFromWire,
  wireSort,
  wireSourcesFor,
  wireStatusesFor,
  type DocumentWire,
} from '../wire/documents';

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const wireDocument: DocumentWire = {
  id: 'd1',
  originalFilename: 'Rechnung.pdf',
  mimeType: 'application/pdf',
  sizeBytes: 1200,
  contentHash: 'abc',
  processingStatus: 'REVIEW_REQUIRED',
  reviewStatus: 'PENDING',
  source: 'SCANNER',
  documentTypeId: null,
  legalHold: false,
  receivedAt: '2026-10-01T10:00:00Z',
  // §5.1: what the extraction found for the booking, exactly as DocumentResponse sends it.
  amount: 1234.56,
  amountCurrency: 'EUR',
  documentDate: '2026-03-15',
  supplierName: 'Muster GmbH',
  deadlineDate: '2026-04-01',
  deadlineDaysLeft: 2,
};

function resourcesWith(handler: (url: string, init: RequestInit) => Response) {
  const calls: { url: string; init: RequestInit }[] = [];
  const fetchImpl = vi.fn(async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    return handler(url, init);
  });
  const client = createApiClient({
    baseUrl: '/api/v1',
    csrf: false,
    fetchImpl: fetchImpl as unknown as typeof fetch,
  });
  return { api: createResources(client), calls };
}

describe('documents ↔ backend', () => {
  it('maps pipeline stages and sources to the screen vocabulary', () => {
    expect(documentFromWire(wireDocument)).toMatchObject({
      id: 'd1',
      title: 'Rechnung.pdf',
      status: 'needs_review',
      channel: 'scan',
      sizeBytes: 1200,
      amount: 1234.56,
      amountCurrency: 'EUR',
      documentDate: '2026-03-15',
      supplierName: 'Muster GmbH',
      deadlineDate: '2026-04-01',
      deadlineDaysLeft: 2,
    });
    // An answer without the fields is not an error: the adapter makes them null and the screen a dash.
    const withoutBookingData: DocumentWire = {
      ...wireDocument,
      amount: undefined,
      amountCurrency: undefined,
      documentDate: undefined,
      supplierName: undefined,
      deadlineDate: undefined,
      deadlineDaysLeft: undefined,
    };
    expect(documentFromWire(withoutBookingData)).toMatchObject({
      amount: null,
      documentDate: null,
      supplierName: null,
      deadlineDate: null,
      deadlineDaysLeft: null,
    });
    expect(wireStatusesFor('processing')).toEqual(
      expect.arrayContaining(['OCR_PENDING', 'EXTRACTING', 'CLASSIFYING', 'MATCHING']),
    );
    expect(wireSourcesFor('scan')).toEqual(['SCANNER']);
    expect(wireSort('title,asc')).toBe('originalFilename,asc');
    expect(wireSort('nonsense')).toBeUndefined();
  });

  it('sends screen filters as backend filters and reads the Spring page', async () => {
    const { api, calls } = resourcesWith(() =>
      json({ content: [wireDocument], number: 0, size: 25, totalElements: 41, totalPages: 2 }),
    );

    const page = await api.documents.list({ status: 'processing', channel: 'email', sort: 'title,asc', q: 'x', belegStatus: 'NEU', mandantId: 'NONE' });

    const url = new URL(calls[0]?.url ?? '', 'http://x');
    expect(url.pathname).toBe('/api/v1/documents');
    expect(url.searchParams.getAll('status')).toContain('EXTRACTING');
    expect(url.searchParams.getAll('source')).toEqual(['EMAIL']);
    expect(url.searchParams.get('sort')).toBe('originalFilename,asc');
    // The queue's own filters go to the server untouched: "NONE" is a value there, not an absent client.
    expect(url.searchParams.get('belegStatus')).toBe('NEU');
    expect(url.searchParams.get('mandantId')).toBe('NONE');
    expect(page.totalItems).toBe(41);
    expect(page.totalPages).toBe(2);
    expect(page.items[0]?.status).toBe('needs_review');
  });

  it('sends a bulk action and reads both halves of the answer', async () => {
    const { api, calls } = resourcesWith(() =>
      json({
        updated: 1,
        skipped: [{ id: 'd2', reason: 'Ein Beleg kann nicht von EXPORTIERT nach OFFEN wechseln.' }],
      }),
    );

    const result = await api.documents.batch(['d1', 'd2'], 'ACCEPT');

    const url = new URL(calls[0]?.url ?? '', 'http://x');
    expect(url.pathname).toBe('/api/v1/documents/batch');
    expect(calls[0]?.init.method).toBe('POST');
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({ ids: ['d1', 'd2'], action: 'ACCEPT' });
    expect(result.updated).toBe(1);
    expect(result.skipped[0]?.reason).toContain('EXPORTIERT');
  });

  it('sends the client with an assignment and leaves it out of the other actions', async () => {
    const { api, calls } = resourcesWith(() => json({ updated: 2, skipped: [] }));

    await api.documents.batch(['d1', 'd2'], 'ASSIGN_MANDANT', 'm-1');
    await api.documents.batch(['d1'], 'MARK_ERROR');

    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({
      ids: ['d1', 'd2'],
      action: 'ASSIGN_MANDANT',
      mandantId: 'm-1',
    });
    expect(JSON.parse(String(calls[1]?.init.body))).toEqual({ ids: ['d1'], action: 'MARK_ERROR' });
  });

  it('opens a document with its fields and tolerates a missing audit permission', async () => {
    const { api } = resourcesWith((url) => {
      if (url.includes('/audit/events')) return json({ code: 'FORBIDDEN' }, 403);
      if (url.endsWith('/fields')) {
        return json({
          fields: [
            { fieldKey: 'invoice.total', rawValue: '12,50', normalizedValue: '12.50', confidence: 0.91, sourcePage: 1, sourceSnippet: 'Summe 12,50', verified: false },
          ],
        });
      }
      return json(wireDocument);
    });

    const detail = await api.documents.get('d1');

    expect(detail.fields?.[0]).toMatchObject({ name: 'invoice.total', value: '12.50', confidence: 0.91 });
    expect(detail.fields?.[0]?.source).toContain('p. 1');
    expect(detail.history).toEqual([]);
  });

  it('reads what the platform did with a document from /documents/{id}/pipeline', async () => {
    const { api, calls } = resourcesWith(() =>
      json({
        documentId: 'd1',
        processingStatus: 'READY',
        steps: [{ step: 'CLASSIFY', status: 'SUCCEEDED', attempt: 1, startedAt: null, finishedAt: null, error: null }],
        outcome: { result: 'TASK_CREATED', explanation: 'Mahnung erkannt.', next: 'Die Aufgabe wartet auf Bearbeitung.' },
      }),
    );
    const flow = await api.documents.pipeline('d1');
    expect(calls[0]?.url).toContain('/documents/d1/pipeline');
    expect(flow.outcome.result).toBe('TASK_CREATED');
    expect(flow.steps[0]?.step).toBe('CLASSIFY');
  });

  it('shows the text the platform wrote for a notification, and falls back to the key only without one', () => {
    const base = { id: 'n1', category: 'TASK', read: false, createdAt: '2026-10-14T10:00:00Z' };
    expect(
      notificationFromWire({
        ...base,
        titleKey: 'document.action_required',
        parameters: { title: 'Mahnung prüfen', body: 'Frist laut Dokument: 2026-10-28' },
      }),
    ).toMatchObject({ title: 'Mahnung prüfen', body: 'Frist laut Dokument: 2026-10-28' });
    expect(notificationFromWire({ ...base, titleKey: 'task.assigned' }).title).toBe('Assigned');
  });

  it('archives through the status endpoint and uploads the file as multipart `file`', async () => {
    const { api, calls } = resourcesWith(() => json(wireDocument));
    await api.documents.archive('d1');
    const call = calls[0];
    expect(call?.init.method).toBe('POST');
    expect(call?.url).toContain('/documents/d1/status?status=ARCHIVED');
  });

  it('decides the open review of a document: reject carries the reason as the note', async () => {
    const { api, calls } = resourcesWith((url) =>
      url.endsWith('/decision')
        ? json({})
        : json({ documentId: 'd1', filename: 'x', openReviews: [{ id: 'r9', status: 'PENDING' }], values: [], matches: [] }),
    );

    await api.review.reject('d1', 'unleserlich');

    const decision = calls.find((call) => call.url.endsWith('/reviews/r9/decision'));
    expect(JSON.parse(String(decision?.init.body))).toEqual({ decision: 'REJECTED', note: 'unleserlich' });
  });

  it('confirming fields sends them under changes.fieldValues', () => {
    expect(approvalFromFields([{ name: 'invoice.total', value: '12.50' }])).toEqual({
      decision: 'APPROVED',
      changes: { fieldValues: { 'invoice.total': '12.50' } },
    });
  });

  it('refuses to decide a document that has no open review', async () => {
    const { api } = resourcesWith(() => json({ documentId: 'd1', filename: 'x', openReviews: [], values: [], matches: [] }));
    await expect(api.review.confirm('d1', { fields: [] })).rejects.toMatchObject({ status: 409 });
  });

  it('builds review items from the queue and each document detail', async () => {
    const { api } = resourcesWith((url) =>
      url.endsWith('/reviews')
        ? json([{ reviewId: 'r1', documentId: 'd1', filename: 'Brief.pdf', type: 'FIELD_CONFIRMATION', reason: 'low', createdAt: '2026-10-02T08:00:00Z' }])
        : json({
            documentId: 'd1',
            filename: 'Brief.pdf',
            openReviews: [{ id: 'r1', status: 'PENDING' }],
            values: [{ fieldKey: 'date', value: '01.10.2026', normalizedValue: '2026-10-01', confidence: 0.5, page: 2, snippet: 'vom 1.10.', verified: false }],
            matches: [{ matchType: 'DUPLICATE', targetId: 'd0', status: 'PROPOSED' }],
          }),
    );

    const queue = await api.review.queue({ size: 50 });

    expect(queue.items).toHaveLength(1);
    expect(queue.items[0]).toMatchObject({ documentId: 'd1', title: 'Brief.pdf', possibleDuplicateOf: 'd0' });
    expect(queue.items[0]?.fields[0]).toMatchObject({ name: 'date', value: '2026-10-01' });
  });
});

describe('documentFromWire: sender and uploader', () => {
  it('carries the sender read from the letter and the person who uploaded the document', () => {
    const summary = documentFromWire({
      id: 'd1', originalFilename: 'Brief.pdf', processingStatus: 'READY', source: 'UPLOAD', receivedAt: '2026-10-15T08:00:00Z',
      sender: 'Stadtwerke Musterstadt GmbH', senderContactId: 'c1', senderSource: 'LETTER', uploadedByName: 'Erika Mustermann',
      senderDetails: { address: 'Hauptstraße 12, 12345 Musterstadt', phone: '0123 / 456 789-0', email: 'service@stadtwerke.example' },
    });
    expect(summary.senderName).toBe('Stadtwerke Musterstadt GmbH');
    expect(summary.sender).toMatchObject({ address: 'Hauptstraße 12, 12345 Musterstadt', phone: '0123 / 456 789-0', source: 'LETTER' });
    expect(summary.senderContactId).toBe('c1');
    expect(summary.uploadedByName).toBe('Erika Mustermann');
  });

  it('has no sender block when nothing was recognised, instead of an empty one', () => {
    const summary = documentFromWire({ id: 'd2', originalFilename: 'x.pdf', processingStatus: 'READY', source: 'UPLOAD', receivedAt: '2026-10-15T08:00:00Z' });
    expect(summary.senderName).toBeNull();
    expect(summary.sender).toBeNull();
    expect(summary.uploadedByName).toBeNull();
  });
});
