import { describe, expect, it, vi } from 'vitest';
import { createApiClient, normalizePage, type Page } from '../client';
import { ApiError, apiErrorFromEnvelope, isApiError, toApiError } from '../errors';

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
    const client = createApiClient({ baseUrl, csrf: false, fetchImpl: fetchMock });

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
    const client = createApiClient({ baseUrl, csrf: false, fetchImpl: fetchMock });

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
      csrf: false,
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
    const client = createApiClient({ baseUrl, csrf: false, fetchImpl: fetchMock });

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
    const client = createApiClient({ baseUrl, csrf: false, fetchImpl: fetchMock, onUnauthorized });

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
    const client = createApiClient({ baseUrl, csrf: false, fetchImpl: fetchMock });

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
    const client = createApiClient({ baseUrl, csrf: false, fetchImpl: fetchMock });

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
    const client = createApiClient({ baseUrl, csrf: false, fetchImpl: fetchMock });

    await expect(client.get('/documents')).rejects.toMatchObject({
      kind: 'rateLimited',
      retryAfterSeconds: 30,
    });
  });

  it('classifies a rejected fetch as a network error', async () => {
    const fetchMock = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    });
    const client = createApiClient({ baseUrl, csrf: false, fetchImpl: fetchMock });

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
    const client = createApiClient({ baseUrl, csrf: false, fetchImpl: fetchMock as unknown as typeof fetch });

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
    const client = createApiClient({ baseUrl, csrf: false, fetchImpl: fetchMock as unknown as typeof fetch });

    const promise = client.get('/documents', { signal: controller.signal });
    controller.abort();
    await expect(promise).rejects.toMatchObject({ kind: 'aborted' });
  });

  it('reports malformed JSON bodies instead of returning undefined', async () => {
    const fetchMock = vi.fn(
      async () =>
        new Response('not-json', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    const client = createApiClient({ baseUrl, csrf: false, fetchImpl: fetchMock });

    await expect(client.get('/documents')).rejects.toMatchObject({ kind: 'malformed' });
  });

  it('returns undefined for 204 responses', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }));
    const client = createApiClient({ baseUrl, csrf: false, fetchImpl: fetchMock });

    await expect(client.delete('/documents/1')).resolves.toBeUndefined();
  });

  it('normalizes the pagination envelope', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse({ items: [{ id: '1' }], page: 1, size: 10, totalItems: 21, totalPages: 3 }),
    );
    const client = createApiClient({ baseUrl, csrf: false, fetchImpl: fetchMock });

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

  it('reports an unconfirmed address as itself, not as a lack of permission', () => {
    // The backend answers 403 EMAIL_NOT_VERIFIED when the password was right and the address was never
    // confirmed. 403 alone means "forbidden", so the sign-in screen told people they had no access - and on a
    // deployment without SMTP nobody can confirm an address at all.
    const error = apiErrorFromEnvelope(
      { code: 'EMAIL_NOT_VERIFIED', message: 'The e-mail address has not been confirmed yet' },
      403,
      '/api/v1/auth/login',
    );

    expect(error.kind).toBe('emailNotVerified');
    expect(error.status).toBe(403);
  });

  it('still reports a plain 403 as forbidden', () => {
    const error = apiErrorFromEnvelope({ code: 'FORBIDDEN', message: 'Nope' }, 403, '/api/v1/x');
    expect(error.kind).toBe('forbidden');
  });
});

describe('api client — CSRF double submit', () => {
  const clearCookie = (): void => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  };

  /** A fetch that behaves like the backend: `/auth/csrf` sets the cookie, everything else answers 200. */
  function backendLikeFetch(): ReturnType<typeof vi.fn> {
    return vi.fn(async (url: string) => {
      if (url.endsWith('/auth/csrf')) {
        document.cookie = 'XSRF-TOKEN=token-from-server; path=/';
        return new Response(null, { status: 204 });
      }
      return jsonResponse({ ok: true });
    });
  }

  it('fetches the token before the first unsafe request and echoes it in X-XSRF-TOKEN', async () => {
    clearCookie();
    const fetchMock = backendLikeFetch();
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock as unknown as typeof fetch });

    await client.post('/auth/login', { email: 'a@b.de', password: 'x' });

    const urls = fetchMock.mock.calls.map((call) => call[0] as string);
    expect(urls).toEqual([`${baseUrl}/auth/csrf`, `${baseUrl}/auth/login`]);
    const [, init] = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('token-from-server');
    clearCookie();
  });

  it('does not call /auth/csrf again once the cookie exists, and never for safe methods', async () => {
    document.cookie = 'XSRF-TOKEN=existing; path=/';
    const fetchMock = backendLikeFetch();
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock as unknown as typeof fetch });

    await client.get('/auth/me');
    await client.post('/tasks', { title: 'A' });
    await client.delete('/tasks/1');

    expect(fetchMock.mock.calls.map((call) => call[0] as string)).toEqual([
      `${baseUrl}/auth/me`,
      `${baseUrl}/tasks`,
      `${baseUrl}/tasks/1`,
    ]);
    const [, init] = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('existing');
    clearCookie();
  });

  it('lets concurrent unsafe requests share a single token round trip', async () => {
    clearCookie();
    const fetchMock = backendLikeFetch();
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock as unknown as typeof fetch });

    await Promise.all([client.post('/a', {}), client.post('/b', {}), client.put('/c', {})]);

    const csrfCalls = fetchMock.mock.calls.filter((call) => (call[0] as string).endsWith('/auth/csrf'));
    expect(csrfCalls).toHaveLength(1);
    clearCookie();
  });

  it('still sends the request when the token endpoint is unreachable, so the caller sees the real answer', async () => {
    clearCookie();
    const fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/auth/csrf')) throw new TypeError('offline');
      return jsonResponse({ ok: true });
    });
    const client = createApiClient({ baseUrl, fetchImpl: fetchMock as unknown as typeof fetch });

    await expect(client.post('/documents', {})).resolves.toEqual({ ok: true });
    const [, init] = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBeUndefined();
  });
});

describe('normalizePage — Spring Data envelopes', () => {
  it('reads the flat Spring Page: content, number, size, totalElements, totalPages', () => {
    const page = normalizePage<string>({
      content: ['a', 'b'],
      number: 2,
      size: 2,
      totalElements: 11,
      totalPages: 6,
    });
    expect(page).toEqual({ items: ['a', 'b'], page: 2, size: 2, totalItems: 11, totalPages: 6 });
  });

  it('reads the nested DTO form: page.{size,number,totalElements,totalPages}', () => {
    const page = normalizePage<string>({
      content: ['a'],
      page: { size: 25, number: 0, totalElements: 1, totalPages: 1 },
    });
    expect(page).toEqual({ items: ['a'], page: 0, size: 25, totalItems: 1, totalPages: 1 });
  });

  it('still reads the contract names', () => {
    const page = normalizePage<string>({ items: ['a'], page: 1, size: 5, totalItems: 6, totalPages: 2 });
    expect(page).toEqual({ items: ['a'], page: 1, size: 5, totalItems: 6, totalPages: 2 });
  });
});
