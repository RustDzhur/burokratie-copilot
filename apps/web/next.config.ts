import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { NextConfig } from 'next';

/**
 * Next.js configuration for the Bürokratie-Copilot web client.
 *
 * Notes:
 * - `eslint.ignoreDuringBuilds` is enabled because linting is executed explicitly
 *   via `pnpm lint` (ESLint 9 flat config). `next build` therefore stays focused on
 *   compilation, and the four verification gates stay independent.
 * - The dev-only design-system showcase lives at `/design-system`; `/_design` is kept
 *   as a readable alias via a redirect.
 * - Security headers are applied to every response. A nonce-based CSP is intentionally
 *   not configured yet (it requires a middleware/proxy decision shared with the backend).
 * - `output: "standalone"` produces a self-contained server bundle for the container image.
 */
const securityHeaders = [
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'X-Frame-Options', value: 'DENY' },
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
  { key: 'Permissions-Policy', value: 'camera=(self), microphone=(self), geolocation=()' },
  { key: 'Cross-Origin-Opener-Policy', value: 'same-origin' },
];

/**
 * Where the backend lives, as seen from the machine running the Next.js server. The browser never uses this:
 * it calls the relative `NEXT_PUBLIC_API_BASE_URL` (`/api/v1`) and the rewrite below forwards it. One origin
 * for page and API is what keeps the session cookie first-party (`SameSite=Strict`) and the CSRF cookie
 * readable; behind the production reverse proxy `/api/*` is routed before it ever reaches this rewrite.
 * Evaluated at build time, so it is a build argument of the image.
 */
const apiOrigin = (process.env.BC_API_ORIGIN ?? 'http://localhost:8080').replace(/\/+$/, '');

const nextConfig: NextConfig = {
  reactStrictMode: true,
  // Next 15.5 runs webpack inside a separate build worker by default (no custom `webpack`
  // config here, so `experimental.webpackBuildWorker` defaults to true). On this machine
  // that worker deadlocks at the compile stage: the parent and the jest-worker child both
  // sit idle and `next build` never finishes. The worker is a build-time parallelism
  // optimisation only; compiling in the main process changes nothing at runtime.
  experimental: {
    webpackBuildWorker: false,
  },
  // Required by infra/docker/web.Dockerfile and checked by infra/scripts/check-structure.sh:
  // the production image runs `.next/standalone/server.js` instead of the full node_modules tree.
  output: 'standalone',
  // The app lives inside a larger monorepo-ish checkout; pin the tracing root so
  // Next.js does not guess it from unrelated lockfiles above this directory.
  outputFileTracingRoot: dirname(fileURLToPath(import.meta.url)),
  poweredByHeader: false,
  eslint: {
    ignoreDuringBuilds: true,
  },
  async redirects() {
    return [
      { source: '/', destination: '/login', permanent: false },
      { source: '/_design', destination: '/design-system', permanent: false },
      // UI cleanup: retired screens keep their pages on disk but their routes lead somewhere useful.
      // Scan duplicated the inbox; the assistant moved into the topbar (Iris); automations were retired.
      { source: '/scan', destination: '/inbox', permanent: false },
      { source: '/automations', destination: '/dashboard', permanent: false },
      { source: '/assistant', destination: '/dashboard', permanent: false },
    ];
  },
  async rewrites() {
    return [{ source: '/api/v1/:path*', destination: `${apiOrigin}/api/v1/:path*` }];
  },
  async headers() {
    return [
      { source: '/:path*', headers: securityHeaders },
      // The stored file of a document is shown inside the document page (a PDF or an image in a frame). That is
      // the one response the application itself frames, so it may be framed by its own origin and by nobody
      // else; a later rule overrides the header of the same name above. Every other response stays DENY.
      {
        source: '/api/v1/documents/:id/content',
        headers: [{ key: 'X-Frame-Options', value: 'SAMEORIGIN' }],
      },
    ];
  },
};

export default nextConfig;
