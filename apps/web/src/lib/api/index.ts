import { createApiClient, type ApiClient } from './client';
import { createResources, type ApiResources } from './resources';

export * from './client';
export * from './errors';
export * from './types';
export type { ApiResources } from './resources';

/**
 * Shared API client instance used by the application.
 *
 * `onUnauthorized` is intentionally not wired here: the app shell registers a
 * handler so that a 401 can redirect to the sign-in screen without the transport
 * layer knowing about routing.
 */
export const apiClient: ApiClient = createApiClient();

export const api: ApiResources = createResources(apiClient);

/** Test/DI helper: an isolated client + resource set. */
export function createApi(options?: Parameters<typeof createApiClient>[0]): {
  client: ApiClient;
  resources: ApiResources;
} {
  const client = createApiClient(options);
  return { client, resources: createResources(client) };
}
