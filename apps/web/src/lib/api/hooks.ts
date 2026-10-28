'use client';

import { useCallback, useEffect, useRef, useState, type DependencyList } from 'react';
import { isApiError, toApiError, type ApiError } from './errors';

/**
 * Loading state machine shared by every screen.
 *
 * `idle -> loading -> ready | error` (and back to loading on reload). Screens
 * must render all four states; `ResourceView` enforces that at the type level.
 */
export type ResourceState<T> =
  | { readonly status: 'loading'; readonly data: null; readonly error: null }
  | { readonly status: 'ready'; readonly data: T; readonly error: null }
  | { readonly status: 'error'; readonly data: null; readonly error: ApiError };

export interface UseApiResourceResult<T> {
  readonly state: ResourceState<T>;
  /** Re-runs the loader, e.g. from the retry button of an error state. */
  readonly reload: () => void;
  /** `true` while a reload keeps previously loaded data on screen. */
  readonly isRefreshing: boolean;
}

export interface UseApiResourceOptions {
  /** Skip loading (e.g. while a required parameter is still empty). */
  readonly skip?: boolean;
  /** Keep the previous data visible during a reload instead of switching to loading. */
  readonly keepPreviousData?: boolean;
  /** Poll interval in milliseconds. */
  readonly pollMs?: number;
}

const loadingState = { status: 'loading', data: null, error: null } as const;

/**
 * Runs an API loader and exposes the shared resource state machine.
 *
 * The loader is kept in a ref so callers can pass an inline arrow function
 * without restarting the request on every render; re-fetching is controlled
 * explicitly through `deps`.
 */
export function useApiResource<T>(
  load: (signal: AbortSignal) => Promise<T>,
  deps: DependencyList,
  options: UseApiResourceOptions = {},
): UseApiResourceResult<T> {
  const { skip = false, keepPreviousData = false, pollMs } = options;
  const [state, setState] = useState<ResourceState<T>>(loadingState);
  const [nonce, setNonce] = useState(0);
  const [isRefreshing, setIsRefreshing] = useState(false);

  const loadRef = useRef(load);
  loadRef.current = load;

  const hasDataRef = useRef(false);
  hasDataRef.current = state.status === 'ready';

  useEffect(() => {
    if (skip) return undefined;
    const controller = new AbortController();
    let active = true;

    const run = async (): Promise<void> => {
      const keepVisible = keepPreviousData && hasDataRef.current;
      if (keepVisible) setIsRefreshing(true);
      else setState(loadingState);

      try {
        const data = await loadRef.current(controller.signal);
        if (!active) return;
        setState({ status: 'ready', data, error: null });
      } catch (error) {
        if (!active) return;
        const apiError = toApiError(error);
        if (apiError.kind === 'aborted') return;
        setState({ status: 'error', data: null, error: apiError });
      } finally {
        if (active) setIsRefreshing(false);
      }
    };

    void run();

    const interval =
      pollMs && pollMs > 0 ? window.setInterval(() => void run(), pollMs) : undefined;

    return () => {
      active = false;
      controller.abort();
      if (interval) window.clearInterval(interval);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [skip, nonce, keepPreviousData, pollMs, ...deps]);

  const reload = useCallback(() => setNonce((value) => value + 1), []);

  return { state, reload, isRefreshing };
}

export type AsyncActionStatus = 'idle' | 'pending' | 'success' | 'error';

export interface UseAsyncActionResult<TArgs extends readonly unknown[], TResult> {
  readonly run: (...args: TArgs) => Promise<TResult | undefined>;
  readonly status: AsyncActionStatus;
  readonly error: ApiError | null;
  readonly reset: () => void;
}

/** Mutation helper with an explicit pending/success/error lifecycle. */
export function useAsyncAction<TArgs extends readonly unknown[], TResult>(
  action: (...args: TArgs) => Promise<TResult>,
): UseAsyncActionResult<TArgs, TResult> {
  const [status, setStatus] = useState<AsyncActionStatus>('idle');
  const [error, setError] = useState<ApiError | null>(null);
  const mountedRef = useRef(true);
  const actionRef = useRef(action);
  actionRef.current = action;

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const run = useCallback(async (...args: TArgs): Promise<TResult | undefined> => {
    setStatus('pending');
    setError(null);
    try {
      const result = await actionRef.current(...args);
      if (mountedRef.current) setStatus('success');
      return result;
    } catch (caught) {
      const apiError = toApiError(caught);
      if (mountedRef.current && apiError.kind !== 'aborted') {
        setError(apiError);
        setStatus('error');
      }
      return undefined;
    }
  }, []);

  const reset = useCallback(() => {
    setStatus('idle');
    setError(null);
  }, []);

  return { run, status, error, reset };
}

export { isApiError };
