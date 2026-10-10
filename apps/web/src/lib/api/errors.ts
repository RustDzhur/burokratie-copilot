/**
 * Typed API error model.
 *
 * The backend answers failures with a single envelope:
 *
 *   { "code": "DOCUMENT_NOT_FOUND", "message": "…", "correlationId": "…", "details": { … } }
 *
 * `ApiError` keeps the envelope, classifies the failure (`kind`) and exposes the
 * correlation ID so the UI can show it to the user and support can trace it.
 * `kind` drives the localized message in the interface — never the raw backend
 * text, which is written for developers.
 */

export type ApiErrorKind =
  | 'network'
  | 'timeout'
  | 'aborted'
  | 'unauthorized'
  | 'forbidden'
  | 'notFound'
  | 'conflict'
  | 'validation'
  | 'rateLimited'
  | 'server'
  | 'malformed'
  | 'unknown';

export interface ApiErrorInit {
  readonly kind: ApiErrorKind;
  readonly status?: number;
  readonly code?: string;
  readonly message?: string;
  readonly correlationId?: string;
  readonly details?: Record<string, unknown>;
  readonly fieldErrors?: Record<string, string>;
  readonly retryAfterSeconds?: number;
  readonly url?: string;
  readonly cause?: unknown;
}

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number;
  readonly code: string;
  readonly correlationId: string | null;
  readonly details: Record<string, unknown> | null;
  readonly fieldErrors: Record<string, string> | null;
  readonly retryAfterSeconds: number | null;
  readonly url: string | null;
  override readonly cause: unknown;

  constructor(init: ApiErrorInit) {
    super(init.message ?? init.kind);
    this.name = 'ApiError';
    this.kind = init.kind;
    this.status = init.status ?? 0;
    this.code = init.code ?? 'UNKNOWN';
    this.correlationId = init.correlationId ?? null;
    this.details = init.details ?? null;
    this.fieldErrors = init.fieldErrors ?? null;
    this.retryAfterSeconds = init.retryAfterSeconds ?? null;
    this.url = init.url ?? null;
    this.cause = init.cause;
  }

  /** Network hiccups, timeouts, rate limits and 5xx may be retried by the user. */
  get isRetryable(): boolean {
    return (
      this.kind === 'network' ||
      this.kind === 'timeout' ||
      this.kind === 'rateLimited' ||
      this.kind === 'server'
    );
  }

  /** `true` when the session is missing or expired and a sign-in is required. */
  get requiresAuthentication(): boolean {
    return this.kind === 'unauthorized';
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}

export function statusToKind(status: number): ApiErrorKind {
  if (status === 401) return 'unauthorized';
  if (status === 403) return 'forbidden';
  if (status === 404) return 'notFound';
  if (status === 409) return 'conflict';
  if (status === 422 || status === 400) return 'validation';
  if (status === 429) return 'rateLimited';
  if (status >= 500) return 'server';
  return 'unknown';
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

/**
 * Extracts field-level validation messages from the backend `details` object.
 * Accepted shapes (both are seen in enterprise backends):
 *   { "fieldErrors": { "email": "…" } }
 *   { "errors": [ { "field": "email", "message": "…" } ] }
 *   { "email": "…" }
 */
export function parseFieldErrors(details: unknown): Record<string, string> | undefined {
  const record = asRecord(details);
  if (!record) return undefined;

  const nested = asRecord(record.fieldErrors);
  if (nested) {
    const entries = Object.entries(nested).filter(
      (entry): entry is [string, string] => typeof entry[1] === 'string',
    );
    if (entries.length > 0) return Object.fromEntries(entries);
  }

  if (Array.isArray(record.errors)) {
    const entries: Array<[string, string]> = [];
    for (const item of record.errors) {
      const itemRecord = asRecord(item);
      const field = asString(itemRecord?.field) ?? asString(itemRecord?.path);
      const message = asString(itemRecord?.message) ?? asString(itemRecord?.defaultMessage);
      if (field && message) entries.push([field, message]);
    }
    if (entries.length > 0) return Object.fromEntries(entries);
  }

  const flat: Array<[string, string]> = [];
  for (const [key, value] of Object.entries(record)) {
    if (typeof value === 'string' && key !== 'code' && key !== 'message') flat.push([key, value]);
  }
  return flat.length > 0 ? Object.fromEntries(flat) : undefined;
}

/** Builds an `ApiError` from a parsed error envelope (or any JSON body). */
export function apiErrorFromEnvelope(
  body: unknown,
  status: number,
  url: string,
  retryAfterSeconds?: number,
): ApiError {
  const record = asRecord(body);
  const details = asRecord(record?.details) ?? asRecord(record?.errors) ?? undefined;
  return new ApiError({
    kind: statusToKind(status),
    status,
    code: asString(record?.code) ?? `HTTP_${status}`,
    message: asString(record?.message) ?? asString(record?.error) ?? `HTTP ${status}`,
    correlationId: asString(record?.correlationId) ?? asString(record?.correlationID),
    details,
    fieldErrors:
      statusToKind(status) === 'validation' ? parseFieldErrors(record?.details) : undefined,
    retryAfterSeconds,
    url,
  });
}

/** Normalizes any thrown value into an `ApiError` for uniform UI handling. */
export function toApiError(value: unknown): ApiError {
  if (isApiError(value)) return value;
  if (value instanceof DOMException && value.name === 'AbortError') {
    return new ApiError({ kind: 'aborted', message: 'Request aborted', cause: value });
  }
  if (value instanceof Error) {
    return new ApiError({ kind: 'unknown', message: value.message, cause: value });
  }
  return new ApiError({ kind: 'unknown', message: String(value), cause: value });
}
