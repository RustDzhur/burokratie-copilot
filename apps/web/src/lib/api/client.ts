/**
 * Typed fetch wrapper for the Bürokratie-Copilot backend (`/api/v1`).
 *
 * Design goals (docs/task.md §20, §33):
 *  - one place that knows the base URL, headers, timeout and error envelope;
 *  - every failure becomes a typed `ApiError` with a `kind` the UI can localize;
 *  - no component talks to `fetch` directly, so auth, idempotency and
 *    correlation IDs stay consistent across the product.
 */

import { ApiError, apiErrorFromEnvelope, isApiError, type ApiErrorKind } from './errors';

/* -------------------------------------------------------------------------- */
/* Pagination                                                                  */
/* -------------------------------------------------------------------------- */

/** Server-side pagination envelope: `{ items, page, size, totalItems, totalPages }`. */
export interface Page<T> {
  readonly items: readonly T[];
  readonly page: number;
  readonly size: number;
  readonly totalItems: number;
  readonly totalPages: number;
}

export const emptyPage: Page<never> = {
  items: [],
  page: 0,
  size: 0,
  totalItems: 0,
  totalPages: 0,
};

export function isPage(value: unknown): value is Page<unknown> {
  if (typeof value !== 'object' || value === null) return false;
  const record = value as Record<string, unknown>;
  return Array.isArray(record.items) && typeof record.totalItems === 'number';
}

/**
 * Normalizes the pagination envelope. Accepts `items` (contract) and tolerates
 * `content`/`records` so an evolving backend cannot blank out a screen.
 */
export function normalizePage<T>(value: unknown): Page<T> {
  if (Array.isArray(value)) {
    return {
      items: value as T[],
      page: 0,
      size: value.length,
      totalItems: value.length,
      totalPages: 1,
    };
  }
  if (typeof value !== 'object' || value === null) return { ...emptyPage, items: [] };
  const record = value as Record<string, unknown>;
  const items = Array.isArray(record.items)
    ? (record.items as T[])
    : Array.isArray(record.content)
      ? (record.content as T[])
      : Array.isArray(record.records)
        ? (record.records as T[])
        : [];
  const page = typeof record.page === 'number' ? record.page : 0;
  const size = typeof record.size === 'number' ? record.size : items.length;
  const totalItems = typeof record.totalItems === 'number' ? record.totalItems : items.length;
  const totalPages =
    typeof record.totalPages === 'number'
      ? record.totalPages
      : size > 0
        ? Math.ceil(totalItems / size)
        : 1;
  return { items, page, size, totalItems, totalPages };
}

export type QueryValue =
  string | number | boolean | null | undefined | readonly (string | number)[];

export type QueryParams = Record<string, QueryValue>;

/** Standard list request: Spring-Data-compatible pagination plus search/filters. */
export interface ListParams {
  readonly page?: number;
  readonly size?: number;
  /** `field,asc` / `field,desc` — passed through unchanged. */
  readonly sort?: string;
  /** Free-text search term. */
  readonly q?: string;
  readonly [key: string]: QueryValue;
}

/* -------------------------------------------------------------------------- */
/* Client                                                                      */
/* -------------------------------------------------------------------------- */

export type ParseMode = 'json' | 'text' | 'blob' | 'none';

export interface RequestOptions {
  readonly method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  readonly query?: QueryParams;
  readonly body?: unknown;
  readonly headers?: Record<string, string>;
  readonly signal?: AbortSignal;
  readonly timeoutMs?: number;
  /** `true` generates a UUID v4; a string is sent verbatim. */
  readonly idempotencyKey?: string | true;
  readonly parse?: ParseMode;
  readonly credentials?: RequestCredentials;
}

export interface UploadOptions {
  readonly signal?: AbortSignal;
  readonly idempotencyKey?: string | true;
  readonly onProgress?: (percent: number) => void;
  readonly fieldName?: string;
  readonly fields?: Record<string, string>;
  readonly headers?: Record<string, string>;
}

export interface ApiClientOptions {
  readonly baseUrl?: string;
  readonly fetchImpl?: typeof fetch;
  readonly timeoutMs?: number;
  readonly defaultHeaders?: Record<string, string>;
  readonly getAuthToken?: () => string | null | undefined;
  /** Called on 401 so the app shell can redirect to the sign-in screen. */
  readonly onUnauthorized?: (error: ApiError) => void;
  /**
   * Double-submit CSRF protection (default on in a browser). The backend sets an `XSRF-TOKEN` cookie
   * and rejects every unsafe request that does not echo it in `X-XSRF-TOKEN`; without this the browser
   * cannot even sign in. `false` turns it off for tests and non-browser callers.
   */
  readonly csrf?: boolean;
}

export interface ApiClient {
  readonly baseUrl: string;
  request: <T>(path: string, options?: RequestOptions) => Promise<T>;
  get: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) => Promise<T>;
  post: <T>(
    path: string,
    body?: unknown,
    options?: Omit<RequestOptions, 'method' | 'body'>,
  ) => Promise<T>;
  put: <T>(
    path: string,
    body?: unknown,
    options?: Omit<RequestOptions, 'method' | 'body'>,
  ) => Promise<T>;
  patch: <T>(
    path: string,
    body?: unknown,
    options?: Omit<RequestOptions, 'method' | 'body'>,
  ) => Promise<T>;
  delete: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) => Promise<T>;
  list: <T>(
    path: string,
    params?: ListParams,
    options?: Omit<RequestOptions, 'method' | 'body' | 'query'>,
  ) => Promise<Page<T>>;
  upload: <T>(path: string, file: Blob, options?: UploadOptions) => Promise<T>;
}

const DEFAULT_BASE_URL = '/api/v1';
const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

function readCookie(name: string): string | undefined {
  if (typeof document === 'undefined') return undefined;
  for (const part of document.cookie.split(';')) {
    const separator = part.indexOf('=');
    if (separator < 0) continue;
    if (part.slice(0, separator).trim() === name) {
      try {
        return decodeURIComponent(part.slice(separator + 1).trim());
      } catch {
        return part.slice(separator + 1).trim();
      }
    }
  }
  return undefined;
}
const DEFAULT_TIMEOUT_MS = 20_000;

function normalizeBaseUrl(baseUrl: string): string {
  const trimmed = baseUrl.trim();
  if (trimmed === '') return DEFAULT_BASE_URL;
  return trimmed.endsWith('/') ? trimmed.slice(0, -1) : trimmed;
}

function resolveBaseUrl(explicit?: string): string {
  if (explicit !== undefined) return normalizeBaseUrl(explicit);
  const fromEnv = process.env.NEXT_PUBLIC_API_BASE_URL;
  return normalizeBaseUrl(
    typeof fromEnv === 'string' && fromEnv.length > 0 ? fromEnv : DEFAULT_BASE_URL,
  );
}

function resolveDefaultTimeout(explicit?: number): number {
  if (typeof explicit === 'number') return explicit;
  const fromEnv = Number(process.env.NEXT_PUBLIC_API_TIMEOUT_MS);
  return Number.isFinite(fromEnv) && fromEnv > 0 ? fromEnv : DEFAULT_TIMEOUT_MS;
}

export function buildUrl(baseUrl: string, path: string, query?: QueryParams): string {
  const base = /^https?:\/\//i.test(path)
    ? path
    : `${baseUrl}${path.startsWith('/') ? '' : '/'}${path}`;
  if (!query) return base;
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      for (const item of value) search.append(key, String(item));
      continue;
    }
    search.append(key, String(value));
  }
  const queryString = search.toString();
  if (queryString === '') return base;
  return `${base}${base.includes('?') ? '&' : '?'}${queryString}`;
}

export function createIdempotencyKey(): string {
  const cryptoApi = globalThis.crypto;
  if (cryptoApi && typeof cryptoApi.randomUUID === 'function') {
    return cryptoApi.randomUUID();
  }
  return `idem-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function isAbortError(error: unknown): boolean {
  return (
    (error instanceof DOMException && error.name === 'AbortError') ||
    (error instanceof Error && error.name === 'AbortError')
  );
}

function parseRetryAfter(response: Response): number | undefined {
  const header = response.headers.get('Retry-After');
  if (!header) return undefined;
  const seconds = Number(header);
  if (Number.isFinite(seconds) && seconds >= 0) return seconds;
  const date = Date.parse(header);
  if (Number.isNaN(date)) return undefined;
  return Math.max(0, Math.round((date - Date.now()) / 1000));
}

function inferParseMode(response: Response, requested?: ParseMode): ParseMode {
  if (requested) return requested;
  if (response.status === 204 || response.status === 205) return 'none';
  const contentType = response.headers.get('Content-Type') ?? '';
  if (contentType.includes('json')) return 'json';
  if (contentType.startsWith('text/')) return 'text';
  return 'json';
}

async function readBody(response: Response, mode: ParseMode): Promise<unknown> {
  if (mode === 'none') return undefined;
  if (mode === 'blob') return response.blob();
  if (mode === 'text') return response.text();
  const text = await response.text();
  if (text.trim() === '') return undefined;
  try {
    return JSON.parse(text) as unknown;
  } catch (error) {
    throw new ApiError({
      kind: 'malformed',
      status: response.status,
      message: 'Response body is not valid JSON',
      url: response.url,
      cause: error,
    });
  }
}

export function createApiClient(options: ApiClientOptions = {}): ApiClient {
  const baseUrl = resolveBaseUrl(options.baseUrl);
  const defaultTimeoutMs = resolveDefaultTimeout(options.timeoutMs);
  const defaultHeaders = options.defaultHeaders ?? {};
  const csrfEnabled = options.csrf ?? typeof document !== 'undefined';
  let csrfInFlight: Promise<void> | undefined;

  /**
   * Makes sure the CSRF cookie exists before an unsafe request. Concurrent callers share one round trip.
   * A failure here is not fatal: the request is sent anyway and the server's own answer is what the
   * caller sees, instead of a second, more confusing error.
   */
  async function ensureCsrfToken(doFetch: typeof fetch): Promise<string | undefined> {
    const existing = readCookie(CSRF_COOKIE);
    if (existing) return existing;
    csrfInFlight ??= doFetch(buildUrl(baseUrl, '/auth/csrf'), {
      method: 'GET',
      credentials: 'include',
      cache: 'no-store',
    })
      .then(() => undefined)
      .catch(() => undefined)
      .finally(() => {
        csrfInFlight = undefined;
      });
    await csrfInFlight;
    return readCookie(CSRF_COOKIE);
  }

  async function request<T>(path: string, requestOptions: RequestOptions = {}): Promise<T> {
    const url = buildUrl(baseUrl, path, requestOptions.query);
    const method = requestOptions.method ?? 'GET';
    const timeoutMs = requestOptions.timeoutMs ?? defaultTimeoutMs;

    const headers: Record<string, string> = {
      Accept: 'application/json',
      ...defaultHeaders,
      ...requestOptions.headers,
    };

    const token = options.getAuthToken?.();
    if (token) headers.Authorization = `Bearer ${token}`;

    if (requestOptions.idempotencyKey) {
      headers['Idempotency-Key'] =
        requestOptions.idempotencyKey === true
          ? createIdempotencyKey()
          : requestOptions.idempotencyKey;
    }

    let body: BodyInit | undefined;
    if (requestOptions.body !== undefined) {
      if (
        typeof requestOptions.body === 'string' ||
        requestOptions.body instanceof FormData ||
        requestOptions.body instanceof Blob ||
        requestOptions.body instanceof URLSearchParams
      ) {
        body = requestOptions.body;
      } else {
        body = JSON.stringify(requestOptions.body);
        if (!headers['Content-Type']) headers['Content-Type'] = 'application/json';
      }
    }

    const controller = new AbortController();
    let timedOut = false;
    const timer =
      timeoutMs > 0
        ? setTimeout(() => {
            timedOut = true;
            controller.abort();
          }, timeoutMs)
        : undefined;
    const onExternalAbort = (): void => controller.abort();
    if (requestOptions.signal) {
      if (requestOptions.signal.aborted) controller.abort();
      else requestOptions.signal.addEventListener('abort', onExternalAbort);
    }

    const doFetch: typeof fetch | undefined =
      options.fetchImpl ?? globalThis.fetch?.bind(globalThis);
    if (!doFetch) {
      if (timer) clearTimeout(timer);
      throw new ApiError({
        kind: 'network',
        message: 'No fetch implementation available in this environment',
        url,
      });
    }

    if (csrfEnabled && !SAFE_METHODS.has(method) && !headers[CSRF_HEADER]) {
      const csrfToken = await ensureCsrfToken(doFetch);
      if (csrfToken) headers[CSRF_HEADER] = csrfToken;
    }

    let response: Response;
    try {
      response = await doFetch(url, {
        method,
        headers,
        body,
        signal: controller.signal,
        credentials: requestOptions.credentials ?? 'include',
        cache: 'no-store',
      });
    } catch (error) {
      if (timedOut) {
        throw new ApiError({
          kind: 'timeout',
          message: `Request timed out after ${timeoutMs} ms`,
          url,
          cause: error,
        });
      }
      if (isAbortError(error) || requestOptions.signal?.aborted === true) {
        throw new ApiError({ kind: 'aborted', message: 'Request aborted', url, cause: error });
      }
      throw new ApiError({
        kind: 'network',
        message: error instanceof Error ? error.message : 'Network request failed',
        url,
        cause: error,
      });
    } finally {
      if (timer) clearTimeout(timer);
      requestOptions.signal?.removeEventListener('abort', onExternalAbort);
    }

    if (!response.ok) {
      const retryAfterSeconds = parseRetryAfter(response);
      let payload: unknown;
      try {
        payload = await readBody(
          response,
          inferParseMode(response, response.status === 404 ? 'json' : undefined),
        );
      } catch {
        payload = undefined;
      }
      const error = apiErrorFromEnvelope(payload, response.status, url, retryAfterSeconds);
      if (error.kind === 'unauthorized') options.onUnauthorized?.(error);
      throw error;
    }

    return (await readBody(response, inferParseMode(response, requestOptions.parse))) as T;
  }

  async function list<T>(
    path: string,
    params: ListParams = {},
    listOptions: Omit<RequestOptions, 'method' | 'body' | 'query'> = {},
  ): Promise<Page<T>> {
    const payload = await request<unknown>(path, { ...listOptions, query: params });
    return normalizePage<T>(payload);
  }

  async function upload<T>(path: string, file: Blob, uploadOptions: UploadOptions = {}): Promise<T> {
    const url = buildUrl(baseUrl, path);
    const fetchForCsrf: typeof fetch | undefined = options.fetchImpl ?? globalThis.fetch?.bind(globalThis);
    if (csrfEnabled && fetchForCsrf) await ensureCsrfToken(fetchForCsrf);
    const form = new FormData();
    form.append(uploadOptions.fieldName ?? 'file', file);
    for (const [key, value] of Object.entries(uploadOptions.fields ?? {})) form.append(key, value);

    return new Promise<T>((resolve, reject) => {
      if (typeof XMLHttpRequest === 'undefined') {
        reject(new ApiError({ kind: 'network', message: 'Uploads require XMLHttpRequest', url }));
        return;
      }
      const xhr = new XMLHttpRequest();
      xhr.open('POST', url, true);
      xhr.withCredentials = true;
      xhr.responseType = 'text';
      xhr.setRequestHeader('Accept', 'application/json');
      const token = options.getAuthToken?.();
      if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`);
      const csrfToken = csrfEnabled ? readCookie(CSRF_COOKIE) : undefined;
      if (csrfToken) xhr.setRequestHeader(CSRF_HEADER, csrfToken);
      if (uploadOptions.idempotencyKey) {
        xhr.setRequestHeader(
          'Idempotency-Key',
          uploadOptions.idempotencyKey === true
            ? createIdempotencyKey()
            : uploadOptions.idempotencyKey,
        );
      }
      for (const [key, value] of Object.entries(uploadOptions.headers ?? {})) {
        xhr.setRequestHeader(key, value);
      }

      const abortHandler = (): void => xhr.abort();
      uploadOptions.signal?.addEventListener('abort', abortHandler);

      xhr.upload.onprogress = (event) => {
        if (!uploadOptions.onProgress || !event.lengthComputable) return;
        uploadOptions.onProgress(Math.round((event.loaded / event.total) * 100));
      };

      xhr.onload = () => {
        uploadOptions.signal?.removeEventListener('abort', abortHandler);
        const text = typeof xhr.response === 'string' ? xhr.response : '';
        let payload: unknown;
        if (text.trim() !== '') {
          try {
            payload = JSON.parse(text) as unknown;
          } catch {
            payload = undefined;
          }
        }
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve(payload as T);
          return;
        }
        reject(apiErrorFromEnvelope(payload, xhr.status, url));
      };

      xhr.onerror = () => {
        uploadOptions.signal?.removeEventListener('abort', abortHandler);
        reject(new ApiError({ kind: 'network', message: 'Upload failed', url }));
      };

      xhr.onabort = () => {
        uploadOptions.signal?.removeEventListener('abort', abortHandler);
        reject(new ApiError({ kind: 'aborted', message: 'Upload aborted', url }));
      };

      xhr.send(form);
    });
  }

  return {
    baseUrl,
    request,
    get: (path, requestOptions) => request(path, { ...requestOptions, method: 'GET' }),
    post: (path, body, requestOptions) =>
      request(path, { ...requestOptions, method: 'POST', body }),
    put: (path, body, requestOptions) => request(path, { ...requestOptions, method: 'PUT', body }),
    patch: (path, body, requestOptions) =>
      request(path, { ...requestOptions, method: 'PATCH', body }),
    delete: (path, requestOptions) => request(path, { ...requestOptions, method: 'DELETE' }),
    list,
    upload,
  };
}

/** Maps an error to the catalogue key used by the UI (`errors.<kind>`). */
export function errorMessageKey(kind: ApiErrorKind): `errors.${ApiErrorKind}` {
  return `errors.${kind}`;
}

export { ApiError, isApiError };
