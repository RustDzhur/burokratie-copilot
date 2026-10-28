# =============================================================================
#  Bürokratie-Copilot — frontend image (Next.js standalone)
# =============================================================================
#  Build (context MUST be the repository root — see docker-compose.yml):
#      docker build -f infra/docker/web.Dockerfile \
#        --build-arg NEXT_PUBLIC_API_BASE_URL=/api/v1 \
#        --build-arg BUILD_REVISION="$(git rev-parse --short HEAD)" \
#        -t burokratie-copilot/web:local .
#
#  IMPORTANT — apps/web/next.config.* MUST set:
#      output: "standalone"
#  Without it `.next/standalone` does not exist and this build fails at the
#  `COPY --from=build .../standalone` step. The frontend is owned by another
#  agent; this requirement is reported to the project owner and documented in
#  README.md / docs/deployment.md.
#
#  Properties: multi-stage, pnpm via corepack, frozen lockfile in CI/local
#  builds, non-root runtime user, healthcheck, no secrets in the image.
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1 — build the Next.js application
# ---------------------------------------------------------------------------
FROM node:22-alpine AS build

# The lockfile is v9.0; installing with "pnpm@latest" resolved to pnpm 12 in CI and
# `pnpm install --frozen-lockfile` failed against a lockfile of a different major. The version is
# pinned and kept in sync with the "packageManager" field of apps/web/package.json - a floating
# tool version is incompatible with the reproducible build required by spec §30.
ARG PNPM_VERSION=9.15.0

ARG BUILD_REVISION=unknown
ARG BUILD_CREATED=unknown
# NEXT_PUBLIC_* variables are INLINED into the client bundle at build time, so
# they must be present here and not only at container start.
ARG NEXT_PUBLIC_API_BASE_URL=/api/v1
# Where next.config.ts forwards /api/v1/* (evaluated at build time).
ARG BC_API_ORIGIN=http://api:8080

ENV NEXT_PUBLIC_API_BASE_URL="${NEXT_PUBLIC_API_BASE_URL}" \
    BC_API_ORIGIN="${BC_API_ORIGIN}" \
    NEXT_TELEMETRY_DISABLED=1 \
    CI=true \
    # corepack must not prompt for confirmation when it downloads pnpm.
    COREPACK_ENABLE_DOWNLOAD_PROMPT=0

# libc6-compat is required by some native modules used by Next.js on Alpine.
RUN set -eux; \
    apk add --no-cache libc6-compat; \
    corepack enable; \
    corepack prepare pnpm@${PNPM_VERSION} --activate

WORKDIR /app

# Dependency layer: the manifests are copied first so that changing application
# code does not invalidate the (slow) dependency installation layer.
COPY apps/web/package.json ./
COPY apps/web/pnpm-lock.yaml* ./

# A reproducible image MUST be built from a committed lockfile. Failing loudly
# here is better than silently resolving floating versions.
RUN set -eux; \
    if [ ! -f pnpm-lock.yaml ]; then \
        echo "ERROR: apps/web/pnpm-lock.yaml is missing." >&2; \
        echo "       Run 'cd apps/web && pnpm install' and commit the lockfile." >&2; \
        exit 1; \
    fi; \
    pnpm install --frozen-lockfile

# Application sources.
COPY apps/web/ ./

# `public/` is copied into the runtime image below. Next.js treats it as optional, so a project
# without static assets is valid - but a missing directory would make the runtime COPY fail with
# "failed to compute cache key: /app/public: not found", which looks like a Dockerfile bug rather
# than a missing directory. Creating it here keeps the build independent of that detail.
RUN mkdir -p /app/public

# Disable Next.js anonymous telemetry inside the image layer (belt and braces —
# NEXT_TELEMETRY_DISABLED is already set).
RUN set -eux; \
    pnpm build; \
    if [ ! -f .next/standalone/server.js ]; then \
        echo "ERROR: .next/standalone/server.js was not produced." >&2; \
        echo "       Set 'output: \"standalone\"' in apps/web/next.config.* (see header)." >&2; \
        exit 1; \
    fi

# ---------------------------------------------------------------------------
# Stage 2 — slim runtime on the standalone output
# ---------------------------------------------------------------------------
FROM node:22-alpine AS runtime

ARG BUILD_REVISION=unknown
ARG BUILD_CREATED=unknown
LABEL org.opencontainers.image.title="burokratie-copilot-web" \
      org.opencontainers.image.description="Bürokratie-Copilot web frontend (Next.js standalone)" \
      org.opencontainers.image.source="https://github.com/example-org/burokratie-copilot" \
      org.opencontainers.image.revision="${BUILD_REVISION}" \
      org.opencontainers.image.created="${BUILD_CREATED}" \
      org.opencontainers.image.licenses="Proprietary"

# wget (busybox) is used by the HEALTHCHECK; tini reaps zombies and forwards
# signals to the Node process.
RUN set -eux; \
    apk add --no-cache tini

ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    # Bind to every interface INSIDE the container; host exposure is controlled
    # by the published port mapping (loopback in dev, the reverse proxy in prod).
    HOSTNAME=0.0.0.0 \
    TZ=Europe/Berlin

WORKDIR /app

# The image ships the unprivileged `node` user (uid 1000) from the official
# base image; the standalone output is chowned to it.
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
COPY --from=build --chown=node:node /app/public ./public

USER node

EXPOSE 3000

HEALTHCHECK --interval=20s --timeout=5s --start-period=40s --retries=5 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/" || exit 1

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["node", "server.js"]
