/*
 * The service worker (§5.2, B-015).
 *
 * What it does is deliberately small, and what it deliberately does NOT do matters more.
 *
 * It caches the application shell — the pages and assets that are the same for everybody — so that a
 * phone that has opened the app once can open it again on a bad connection. It never caches API
 * responses: those contain a tenant's documents, and a cache is shared between whoever uses the
 * device. A platform that cached a client's letter so that it would open faster would be storing it
 * somewhere nobody agreed to.
 *
 * When there is no connection, the shell comes from the cache and the data does not: the screens say
 * they cannot reach the server rather than showing yesterday's figures as if they were today's.
 */
const SHELL_CACHE = 'bc-shell-v1';
const SHELL = ['/', '/dashboard', '/manifest.webmanifest', '/icons/icon-192.png'];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== SHELL_CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') {
    return;
  }
  const url = new URL(request.url);
  // Anything that is data stays out of the cache. This is the line that matters.
  if (url.origin !== self.location.origin || url.pathname.startsWith('/api/')) {
    return;
  }
  event.respondWith(
    fetch(request)
      .then((response) => {
        if (response.ok && response.type === 'basic') {
          const copy = response.clone();
          caches.open(SHELL_CACHE).then((cache) => cache.put(request, copy));
        }
        return response;
      })
      .catch(() => caches.match(request).then((cached) => cached ?? caches.match('/dashboard'))),
  );
});
