# =============================================================================
#  Bürokratie-Copilot — backend image (api + worker)
# =============================================================================
#  ONE image serves both runtime roles (spec §2.1: modular monolith with worker
#  components). The role is selected at run time:
#
#      api     docker run … ghcr.io/<org>/api:<tag>
#      worker  docker run … ghcr.io/<org>/api:<tag> --spring.profiles.active=worker
#
#  Build (context MUST be the repository root — see docker-compose.yml):
#      docker build -f infra/docker/api.Dockerfile \
#        --build-arg BUILD_REVISION="$(git rev-parse --short HEAD)" \
#        -t burokratie-copilot/api:local .
#
#  Properties: multi-stage, cached dependency layer, non-root runtime user,
#  read-only-friendly (all writable paths are /tmp or mounted volumes),
#  container-aware JVM flags, healthcheck on /actuator/health, no secrets baked
#  in (every value is injected as an environment variable at run time).
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1 — build the boot jar
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

# Build-time provenance only; never used as a runtime default.
ARG BUILD_REVISION=unknown
ARG BUILD_CREATED=unknown

# The official maven base image sets MAVEN_CONFIG=/root/.m2 to point at its local repository.
# The `mvnw` script shipped with the Maven distribution gives that variable a different meaning:
# it treats it as the path of a Maven *config file* and passes the value as a command line
# argument ("${WRAPPER_LAUNCHER} $MAVEN_CONFIG \"$@\""). Inside this image that turned
# `./mvnw -B -ntp dependency:go-offline` into `mvn /root/.m2 -B -ntp dependency:go-offline`, and
# Maven failed with:
#
#     [ERROR] Unknown lifecycle phase "/root/.m2"
#
# Clearing the variable restores the default local repository (~/.m2/repository), which is exactly
# what the BuildKit cache mount below targets. Do not remove this line without replacing it with a
# real config file path: the build breaks in a way that looks unrelated to the Dockerfile.
ENV MAVEN_CONFIG=""

WORKDIR /build

# Dependency layer: copied FIRST so that a source-only change does not
# re-download the dependency tree. The Maven repository lives in a BuildKit
# cache mount instead of an image layer, which keeps the image small; when no
# BuildKit cache backend is available the build still succeeds, only slower.
COPY services/api/pom.xml ./
COPY services/api/.mvn/ ./.mvn/
COPY services/api/mvnw services/api/mvnw.cmd ./
RUN chmod +x ./mvnw

# The project pins Maven in .mvn/wrapper/maven-wrapper.properties
# (distributionType=only-script, 3.9.14), so ./mvnw is used instead of the
# `mvn` binary that ships in the base image. This keeps local and container
# builds on the exact same Maven version (spec §30: reproducible environment).
#
# `dependency:go-offline` can miss a few plugin dependencies that are only
# resolved during later phases; that is not fatal here because the following
# layer resolves whatever is still missing before compiling. `-B` keeps the log
# readable and `-ntp` removes download progress noise.
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    ./mvnw -B -ntp dependency:go-offline

# Application sources.
COPY services/api/src ./src

# Tests are deliberately NOT run here: the integration tests (*IT) require a
# real PostgreSQL instance (spec §28.1) which is not available during an image
# build. Test execution and reporting belong to CI — see
# .github/workflows/ci.yml (job `backend`) and docs/runbook.md.
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    ./mvnw -B -ntp -DskipTests clean package \
 && cp target/burokratie-copilot-api.jar /build/app.jar

# Fail the build early if the artifact is not an executable Spring Boot jar,
# instead of discovering it when the container starts in production. The
# manifest must carry the Spring Boot launcher AND our main class.
# `unzip` is not part of the Maven or the JRE image, and adding a package to the runtime
# image just to read one file would grow the attack surface for no benefit. `jar` ships with
# the JDK that the build stage already uses and does the same job.
RUN set -eux; \
    cd /tmp; \
    jar xf /build/app.jar META-INF/MANIFEST.MF; \
    grep -q '^Main-Class: org.springframework.boot.loader' META-INF/MANIFEST.MF; \
    grep -q 'de.buerokratiecopilot.BurokratieCopilotApplication' META-INF/MANIFEST.MF; \
    rm -rf /tmp/META-INF

# ---------------------------------------------------------------------------
# Stage 2 — minimal JRE runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# OCI provenance labels (spec §30: build versioning).
ARG BUILD_REVISION=unknown
ARG BUILD_CREATED=unknown
LABEL org.opencontainers.image.title="burokratie-copilot-api" \
      org.opencontainers.image.description="Bürokratie-Copilot backend (api + worker roles)" \
      org.opencontainers.image.source="https://github.com/example-org/burokratie-copilot" \
      org.opencontainers.image.revision="${BUILD_REVISION}" \
      org.opencontainers.image.created="${BUILD_CREATED}" \
      org.opencontainers.image.licenses="Proprietary"

# curl is required by the HEALTHCHECK; dumb-init makes PID 1 forward signals to
# the JVM and reap zombies.
# tesseract (German + English) reads scans and photographs. Virus scanning is done by a separate, long-running
# clamd service (compose service `clamav`): only its client, clamdscan, is installed here, behind a wrapper that keeps
# the `clamscan <file>` calling convention. A per-upload `clamscan` reloads the whole signature database each time
# and brought the server to its knees (see infra/docker/clamd/clamd-remote-scan). The application also refuses to
# start in production without a scanner and OCR, see ProductionConfigurationGuard.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl dumb-init tesseract-ocr tesseract-ocr-deu tesseract-ocr-eng tesseract-ocr-rus tesseract-ocr-ukr \
        clamdscan; \
    rm -rf /var/lib/apt/lists/*; \
    tesseract --version; command -v clamdscan

COPY infra/docker/clamd/clamd-remote-scan /usr/local/bin/clamd-remote-scan
COPY infra/docker/clamd/clamdscan-remote.conf /etc/clamav/clamdscan-remote.conf
RUN chmod 0755 /usr/local/bin/clamd-remote-scan

# Fixed, non-privileged UID/GID so that volume permissions are predictable and
# an operator can `chown 10001:10001` a bind mount (spec §21).
ARG APP_UID=10001
ARG APP_GID=10001
RUN set -eux; \
    groupadd --gid "${APP_GID}" app; \
    useradd --uid "${APP_UID}" --gid "${APP_GID}" --create-home --shell /usr/sbin/nologin app

WORKDIR /app

# /app/data is the only writable application path: local-fs blob storage
# (BC_STORAGE_PATH), heap dumps and any scratch files. It is mounted as a volume
# or tmpfs; the container root filesystem can therefore stay read-only.
RUN set -eux; \
    mkdir -p /app/data/blobs /app/data/dumps /app/data/scan-spool; \
    chown -R app:app /app

COPY --from=build --chown=app:app /build/app.jar /app/app.jar

USER app:app

# --- runtime configuration -------------------------------------------------
# Container-aware JVM flags:
#   MaxRAMPercentage      heap = 75 % of the container memory limit
#   ExitOnOutOfMemoryError  restart instead of limping along with a broken heap
#   UseContainerSupport    explicit (default since JDK 10, kept for clarity)
# Override at run time with -e JAVA_TOOL_OPTIONS="…" (see infra/.env.example).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseContainerSupport -Djava.io.tmpdir=/tmp -Djava.security.egd=file:/dev/./urandom" \
    SPRING_PROFILES_ACTIVE=prod \
    TZ=Europe/Berlin

# No HTTP port is EXPOSEd unconditionally: the worker profile serves no
# application traffic. Both ports are documented instead.
#   api    -> 8080 (BC_PORT)
#   worker -> 8081 (BC_WORKER_MANAGEMENT_PORT, management only)
EXPOSE 8080 8081

# The readiness group aggregates the database and disk-space indicators, so a
# container with a broken datasource is correctly reported as unhealthy.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=10 \
    CMD curl -fsS "http://127.0.0.1:${BC_HEALTH_PORT:-8080}/actuator/health/readiness" >/dev/null || exit 1

# dumb-init as PID 1 → the JVM receives SIGTERM directly and Spring Boot's
# graceful shutdown (server.shutdown=graceful) can drain in-flight requests.
ENTRYPOINT ["/usr/bin/dumb-init", "--"]
CMD ["java", "-jar", "/app/app.jar"]
