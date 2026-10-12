import { createApiClient, type ApiClient } from './client';
import type { ApiError } from './errors';
import { createResources, type ApiResources } from './resources';

export * from './client';
export * from './errors';
export * from './types';
export type { ApiResources } from './resources';

type UnauthorizedListener = (error: ApiError) => void;
const unauthorizedListeners = new Set<UnauthorizedListener>();

/**
 * Subscribes to "the session is no longer valid" (a 401 from any call). The app shell uses it to send the
 * person to the sign-in screen, so the transport layer does not need to know about routing. Returns the
 * function that unsubscribes.
 */
export function onUnauthorized(listener: UnauthorizedListener): () => void {
  unauthorizedListeners.add(listener);
  return () => {
    unauthorizedListeners.delete(listener);
  };
}

/** Shared API client instance used by the application. */
export const apiClient: ApiClient = createApiClient({
  onUnauthorized: (error) => unauthorizedListeners.forEach((listener) => listener(error)),
});

export const api: ApiResources = createResources(apiClient);

/** Test/DI helper: an isolated client + resource set. */
export function createApi(options?: Parameters<typeof createApiClient>[0]): {
  client: ApiClient;
  resources: ApiResources;
} {
  const client = createApiClient(options);
  return { client, resources: createResources(client) };
}
