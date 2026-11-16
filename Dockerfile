# ============================================================
# Receipt Share — single self-contained image (backend + frontend)
#
# The Gradle build compiles the Kotlin backend AND builds the
# Angular frontend (npm install + npm run build), staging the
# bundle into the backend's static resources. The resulting
# boot jar serves the whole app on :8080.
#
# Build:
#   docker build -t receipt-share .
#
# Run (HTTP, local/test):
#   docker run -p 8080:8080 -v receipt-share-data:/data receipt-share
#
# Run behind HTTPS: set RECEIPT_SECURE_COOKIE=true so the session
# cookie is flagged Secure (never expose HTTP publicly).
# ============================================================

FROM gradle:8.10.2-jdk21 AS build

# Node.js for the Angular frontend build (see frontend/package.json engines).
ARG NODE_VERSION=18.20.4
ARG TARGETARCH=amd64

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates xz-utils \
    && NODE_ARCH=x64 \
    && if [ "$TARGETARCH" = "arm64" ]; then NODE_ARCH=arm64; fi \
    && curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz" -o /tmp/node.tar.xz \
    && tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 \
    && rm /tmp/node.tar.xz \
    && apt-get purge -y --auto-remove curl xz-utils \
    && rm -rf /var/lib/apt/lists/* \
    && node --version && npm --version

WORKDIR /workspace
COPY . .

WORKDIR /workspace/backend
RUN gradle --no-daemon --no-watch-fs --console=plain bootJar

# ------------------------------------------------------------

FROM eclipse-temurin:21-jre AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates tesseract-ocr \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/backend/build/libs/receipt-share-backend-*.jar app.jar

ENV RECEIPT_DATA_DIR=/data \
    TESSERACT_COMMAND=/usr/bin/tesseract \
    RECEIPT_SECURE_COOKIE=false

VOLUME /data
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS http://localhost:8080/api/auth/me >/dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
