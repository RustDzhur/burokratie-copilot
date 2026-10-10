import { describe, expect, it, vi } from 'vitest';
import { createApiClient, normalizePage, type Page } from '../client';
import { ApiError, isApiError, toApiError } from '../errors';

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
}

const baseUrl = 'https://api.test/api/v1';

describe('api client', () => {
  it('builds the URL with query parameters and sends JSON bodies', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ id: 'd1' }));
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock });

    await client.post('/documents', { title: 'Rechnung' }, { query: { page: 2, size: 20, q: '' } });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('https://api.test/api/v1/documents?page=2&size=20');
    expect(init.method).toBe('POST');
    expect(init.body).toBe(JSON.stringify({ title: 'Rechnung' }));
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect((init.headers as Record<string, string>).Accept).toBe('application/json');
    expect(init.credentials).toBe('include');
  });

  it('appends repeated query values for arrays and skips empty values', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ items: [] }));
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock });

    await client.get('/documents', {
      query: { status: ['open', 'closed'], q: undefined, sort: 'title,asc' },
    });

    const [url] = fetchMock.mock.calls[0] as unknown as [string];
    expect(url).toContain('status=open&status=closed');
    expect(url).toContain('sort=title%2Casc');
    expect(url).not.toContain('q=');
  });

  it('adds an Authorization header when a token is available', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({}));
    const client = createApiClient({
      baseUrl,
      fetchImpl: fetchMock,
      getAuthToken: () => 'token-123',
    });

    await client.get('/auth/session');

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer token-123');
  });

  it('sends a generated Idempotency-Key when requested', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ id: 'x' }));
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock });

    await client.post('/documents', { title: 'A' }, { idempotencyKey: true });
    await client.post('/documents', { title: 'B' }, { idempotencyKey: 'fixed-key' });

    const first = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    const second = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    const firstKey = (first[1].headers as Record<string, string>)['Idempotency-Key'];
    const secondKey = (second[1].headers as Record<string, string>)['Idempotency-Key'];
    expect(firstKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
    expect(secondKey).toBe('fixed-key');
  });

  it('maps 401 to an unauthorized error, keeps the correlation ID and notifies the shell', async () => {
    const onUnauthorized = vi.fn();
    const fetchMock = vi.fn(async () =>
      jsonResponse(
        { code: 'AUTH_EXPIRED', message: 'session expired', correlationId: 'corr-42' },
        { status: 401 },
      ),
    );
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock, onUnauthorized });

    await expect(client.get('/documents')).rejects.toMatchObject({
      kind: 'unauthorized',
      status: 401,
      code: 'AUTH_EXPIRED',
      correlationId: 'corr-42',
    });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it.each([
    [403, 'forbidden'],
    [404, 'notFound'],
    [409, 'conflict'],
    [422, 'validation'],
    [429, 'rateLimited'],
    [500, 'server'],
    [503, 'server'],
    [418, 'unknown'],
  ])('maps HTTP %i to kind %s', async (status, kind) => {
    const fetchMock = vi.fn(async () => jsonResponse({ code: 'X', message: 'boom' }, { status }));
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock });

    await expect(client.get('/documents')).rejects.toMatchObject({ kind, status });
  });

  it('extracts field errors from a 422 envelope', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse(
        {
          code: 'VALIDATION_FAILED',
          message: 'invalid',
          correlationId: 'c-1',
          details: { fieldErrors: { email: 'ungültig', amount: 'zu groß' } },
        },
        { status: 422 },
      ),
    );
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock });

    try {
      await client.post('/invoices', {});
      throw new Error('expected rejection');
    } catch (error) {
      expect(isApiError(error)).toBe(true);
      const apiError = error as ApiError;
      expect(apiError.kind).toBe('validation');
      expect(apiError.fieldErrors).toEqual({ email: 'ungültig', amount: 'zu groß' });
    }
  });

  it('reads Retry-After on 429 responses', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse(
        { code: 'RATE_LIMITED', message: 'slow down' },
        { status: 429, headers: { 'Content-Type': 'application/json', 'Retry-After': '30' } },
      ),
    );
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock });

    await expect(client.get('/documents')).rejects.toMatchObject({
      kind: 'rateLimited',
      retryAfterSeconds: 30,
    });
  });

  it('classifies a rejected fetch as a network error', async () => {
    const fetchMock = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    });
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock });

    const error = await client.get('/documents').catch((caught: unknown) => caught);
    expect(isApiError(error)).toBe(true);
    expect((error as ApiError).kind).toBe('network');
    expect((error as ApiError).isRetryable).toBe(true);
  });

  it('aborts on timeout and reports a distinct kind', async () => {
    const fetchMock = vi.fn(
      (_url: string, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => {
            reject(new DOMException('Aborted', 'AbortError'));
          });
        }),
    );
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock as unknown as typeof fetch });

    await expect(client.get('/documents', { timeoutMs: 5 })).rejects.toMatchObject({
      kind: 'timeout',
    });
  });

  it('propagates an external abort as an aborted error', async () => {
    const controller = new AbortController();
    const fetchMock = vi.fn(
      (_url: string, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => {
            reject(new DOMException('Aborted', 'AbortError'));
          });
        }),
    );
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock as unknown as typeof fetch });

    const promise = client.get('/documents', { signal: controller.signal });
    controller.abort();
    await expect(promise).rejects.toMatchObject({ kind: 'aborted' });
  });

  it('reports malformed JSON bodies instead of returning undefined', async () => {
    const fetchMock = vi.fn(
      async () =>
        new Response('not-json', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock });

    await expect(client.get('/documents')).rejects.toMatchObject({ kind: 'malformed' });
  });

  it('returns undefined for 204 responses', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }));
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock });

    await expect(client.delete('/documents/1')).resolves.toBeUndefined();
  });

  it('normalizes the pagination envelope', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse({ items: [{ id: '1' }], page: 1, size: 10, totalItems: 21, totalPages: 3 }),
    );
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock });

    const page = await client.list<{ id: string }>('/documents');

    expect(page).toEqual({
      items: [{ id: '1' }],
      page: 1,
      size: 10,
      totalItems: 21,
      totalPages: 3,
    });
  });

  it('tolerates alternative list payload shapes', () => {
    const fromArray = normalizePage<number>([1, 2, 3]);
    expect(fromArray.items).toHaveLength(3);
    expect(fromArray.totalPages).toBe(1);

    const fromContent = normalizePage<{ id: string }>({
      content: [{ id: 'a' }],
      totalItems: 1,
      size: 20,
    });
    expect(fromContent.items).toEqual([{ id: 'a' }]);
    expect(fromContent.totalPages).toBe(1);
  });

  it('exposes a typed page shape consumers can rely on', () => {
    const page: Page<{ id: string }> = {
      items: [],
      page: 0,
      size: 20,
      totalItems: 0,
      totalPages: 0,
    };
    expect(page.items).toEqual([]);
  });
});

describe('error normalization', () => {
  it('converts unknown thrown values into ApiError', () => {
    const error = toApiError(new Error('kaputt'));
    expect(error.kind).toBe('unknown');
    expect(error.message).toBe('kaputt');
  });

  it('keeps existing ApiError instances untouched', () => {
    const original = new ApiError({ kind: 'conflict' });
    expect(toApiError(original)).toBe(original);
  });
});
