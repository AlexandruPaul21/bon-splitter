# AGENTS.md

Monorepo: `backend/` (Kotlin + Spring Boot, the Gradle project root `receipt-share-backend`) and `frontend/` (Angular 17 + PrimeNG 17). One server: the Gradle build bundles the frontend into the backend jar.

## Build & test commands

- Backend (from `backend/`): `./gradlew test` (JUnit 5), `./gradlew bootRun` (serves the whole app at `:8080`), `./gradlew bootJar` (single self-contained jar). Single test: `./gradlew test --tests "app.receiptshare.ReceiptParserTest"`.
- The Gradle build auto-builds the frontend: `processResources` depends on `stageFrontend` → `buildFrontend` (`npm run build`) → `installFrontend` (`npm install`). Node is required for any Gradle build; the steps are up-to-date-skipped when nothing changed.
- Frontend (from `frontend/`): `npm install` (not `npm ci` — see gotchas), `npm test`, `npm run build`, `npm start` (dev server `:4200`, proxies `/api` → `:8080` via `proxy.conf.json`).
- `npm test` = `tsx --test src/app/*.spec.ts` — Node's built-in test runner, NOT Karma/Jasmine. There is no lint script; `npm run build` is the typecheck (Angular AOT).
- This workspace has no JDK on PATH (no `/usr/lib/jvm`, no `/opt/jdk`). `mise` is installed: `mise install java@21` then `export JAVA_HOME=$HOME/.local/share/mise/installs/java/<version>` before any Gradle command. `tesseract` is also absent from PATH, so photo-OCR tests skip.

## Architecture

- Backend: Kotlin 1.9.25, Spring Boot 3.3, Java 21 toolchain, Gradle 8.10.2 (wrapper committed). Packages: `app.receiptshare` (Controllers, `ReceiptRepository`, `ReceiptParser`, `SplitCalculator`, `Auth`).
- Persistence is SQLite via `xerial:sqlite-jdbc` (no H2/Postgres). WAL + FK enforcement configured in `ReceiptShareApplication.dataSource`. Data dir holds `receipts.sqlite` + `files/`; never serve it statically.
- Migrations are plain SQL in `backend/src/main/resources/db/migration/`, applied by the hand-rolled `SchemaInitialization` (tracked via `schema_version` table) — NOT Flyway. `V5__discount_included.sql` is legacy: the `discount_included` column was removed in favor of net pricing; do not reintroduce it.
- Money is integer cents; percentages are basis points (10000 = 100%). No floats anywhere.
- Split model (`SplitCalculator`): item shares sum to subtotal; `subtotal − discount + tax + tip == total`; discounts prorate across gross item shares, tax/tip across net shares; largest-remainder rounding conserves every cent; only confirmed receipts affect balances.
- `ReceiptParser` targets Romanian store receipts. Discount keywords: `economisire|economisiti|reducere|promo|discount|xtra|returnare`; lines containing `economisiti` are gross-up lines (`item.amount + cents` before netting). Extraction is a guess — UI expects post-extraction editing.
- Auth: HttpOnly + SameSite=Strict session cookie; a CSRF token from `GET /api/auth/csrf` is required before every mutation; single organizer password setup (no registration); login rate-limited. Config lives in `backend/src/main/resources/application.yml`: `RECEIPT_DATA_DIR`, `TESSERACT_COMMAND`, `RECEIPT_SECURE_COOKIE`.

## CI / Docker

- `.github/workflows/docker-publish.yml`: builds and pushes `ghcr.io/<owner>/bon-splitter` on `main` (→ `latest`) and `v*` tags (→ semver tags); manual `workflow_dispatch`; **linux/amd64 only** (arm64 was dropped because QEMU-emulated `apt-get`/dpkg fails on GitHub runners).
- `Dockerfile`: build stage `gradle:8.10.2-jdk21` + Node 18 (official tarball, `TARGETARCH`-aware) runs `gradle bootJar` (rebuilds the frontend); runtime stage `eclipse-temurin:21-jre` + `tesseract-ocr`, `RECEIPT_DATA_DIR=/data`, healthcheck on `GET /api/auth/me`.

## Testing quirks

- `SampleReceiptExtractionTest` runs the real extraction pipeline against `samples/` (gitignored, though older sample files remain tracked) and needs a `tesseract` binary; it auto-skips via `assumeTrue` when either is missing.
- `ReceiptFlowTest`: `@SpringBootTest` + MockMvc; temp data dir via `@DynamicPropertySource`; `@DirtiesContext` after each method; mutations need `.with(csrf())` and the `MockHttpSession` returned by `POST /api/auth/setup`.

## Repo gotchas

- `npm ci` can fail in this workspace: the checkout is a Windows-backed mount (`C:\` under `/home/claude/workspace`) where root-owned files can be EIO-unremovable (e.g. `node_modules/.../esbuild.exe`). Use `npm install` here; `build.gradle.kts` intentionally uses `npm install` for the same reason. Docker/CI on native Linux is unaffected.
- `samples/` is gitignored; new sample receipts won't be committed, and their absence only causes the extraction test to skip.
