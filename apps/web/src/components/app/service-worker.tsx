'use client';

import { useEffect } from 'react';

/**
 * Registers the service worker (§5.2, B-015).
 *
 * Registration happens after the page has loaded, and it never blocks anything: a phone that is
 * opening the app for the first time should show the app, not wait for a cache to fill.
 *
 * Nothing is registered in development. A service worker left over from a previous build serves the
 * previous shell, and the symptom is a page that will not change no matter what is edited — which
 * costs an afternoon every time.
 */
export function ServiceWorkerRegistrar() {
  useEffect(() => {
    if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) {
      return;
    }
    if (process.env.NODE_ENV !== 'production') {
      return;
    }
    const register = () => {
      navigator.serviceWorker.register('/sw.js').catch(() => {
        // Not being installable is a smaller problem than a page that will not load; a failure here is
        // reported by the absence of an install prompt and not by a broken screen.
      });
    };
    if (document.readyState === 'complete') {
      register();
      return;
    }
    window.addEventListener('load', register);
    return () => window.removeEventListener('load', register);
  }, []);

  return null;
}
