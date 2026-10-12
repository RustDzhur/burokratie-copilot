import { describe, expect, it, vi } from 'vitest';
import { createApiClient } from '../client';
import { createResources } from '../resources';
import { caseFromWire, dueInstant, taskFromWire } from '../wire/work';

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

const wireTask = {
  id: 't1',
  title: 'Bescheid prüfen',
  status: 'IN_REVIEW',
  priority: 'CRITICAL',
  dueAt: '2020-01-01T12:00:00Z',
  assigneeNames: ['Erika Mustermann'],
  caseReference: 'FALL-2026-0001',
};

describe('tasks and cases ↔ backend', () => {
  it('maps task vocabulary and computes overdue from the due instant', () => {
    expect(taskFromWire(wireTask)).toMatchObject({
      status: 'doing',
      priority: 'urgent',
      dueDate: '2020-01-01',
      assigneeName: 'Erika Mustermann',
      caseReference: 'FALL-2026-0001',
      overdue: true,
    });
    expect(taskFromWire({ ...wireTask, status: 'DONE' }).overdue).toBe(false);
  });

  it('maps case statuses, deadline and missing documents', () => {
    const wire = { id: 'c1', reference: 'FALL-1', title: 'Steuer', status: 'WAITING_FOR_DOCUMENTS', dueAt: '2026-11-01T00:00:00Z', typeName: 'Steuerbescheid' };
    expect(caseFromWire(wire, true)).toMatchObject({ status: 'waiting', nextDeadline: '2026-11-01', caseType: 'Steuerbescheid', missingDocuments: true });
  });

  it('sends the screen filters as the backend enum names', async () => {
    const { api, calls } = resourcesWith(() => json({ content: [wireTask], number: 0, size: 25, totalElements: 1, totalPages: 1 }));
    await api.tasks.list({ status: 'doing', priority: 'low' });
    const url = new URL(calls[0]?.url ?? '', 'http://x');
    expect(url.searchParams.getAll('status')).toEqual(expect.arrayContaining(['IN_PROGRESS', 'WAITING', 'IN_REVIEW']));
    expect(url.searchParams.getAll('priority')).toEqual(['LOW', 'INFORMATIONAL']);
  });

  it('creates a task with the backend field names', async () => {
    const { api, calls } = resourcesWith(() => json(wireTask, 201));
    await api.tasks.create({ title: 'X', priority: 'high', dueDate: '2026-12-24' });
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({ title: 'X', priority: 'HIGH', dueAt: dueInstant('2026-12-24') });
  });

  it('completes through the completion endpoint and changes other statuses through /status', async () => {
    const { api, calls } = resourcesWith(() => json(wireTask));
    await api.tasks.setStatus('t1', 'done');
    await api.tasks.setStatus('t1', 'blocked');
    await api.tasks.setStatus('t1', 'doing');
    expect(calls.map((call) => new URL(call.url, 'http://x').pathname)).toEqual([
      '/api/v1/tasks/t1/complete',
      '/api/v1/tasks/t1/status',
      '/api/v1/tasks/t1/status',
    ]);
    expect(JSON.parse(String(calls[1]?.init.body))).toMatchObject({ status: 'BLOCKED', reason: expect.any(String) });
    expect(JSON.parse(String(calls[2]?.init.body))).toMatchObject({ status: 'IN_PROGRESS' });
  });

  it('reads a case card and reports missing documents', async () => {
    const { api } = resourcesWith(() => json({ caseRecord: { id: 'c1', reference: 'F', title: 'T', status: 'NEW' }, requiredDocuments: ['Mietvertrag'] }));
    expect(await api.cases.get('c1')).toMatchObject({ status: 'open', missingDocuments: true });
  });

  it('opens a case with the type code the backend expects', async () => {
    const { api, calls } = resourcesWith(() => json({ id: 'c1', reference: 'F', title: 'T', status: 'NEW' }, 201));
    await api.cases.create({ title: 'T', caseType: 'TAX' });
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({ title: 'T', caseTypeCode: 'TAX' });
  });
});
