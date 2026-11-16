# Bon Splitter

A private, single-organizer receipt splitter. Upload a PDF or phone photo, review a locally extracted draft, allocate line items to group members, and see exactly who owes whom. The app never sends receipts to an AI or OCR cloud service; group members are names, not user accounts.

## Requirements

- JDK 21 (a Gradle wrapper for 8.10.2 is committed, so no global Gradle install is needed).
- Node.js compatible with Angular 17 in `frontend/package.json` (`engines` allows 18.13+ up to Node 22; Node 18 works).
- Optional for photo/scanned-PDF extraction: local `tesseract` binary and the `eng` OCR language pack. Without it, PDF text extraction and manual editing still work.
- A private directory for the SQLite database and original files, writable by the backend process. **Never serve this directory statically.**

Verified in this container with Temurin JDK 21.0.5, Gradle wrapper 8.10.2, Node 18.20, and Tesseract 5.3: `./gradlew test` (5 calculator unit tests + 1 end-to-end auth/CSRF/confirm/balance integration test), `npm test` (4 passing), `npm run build`, and a live backend+frontend run covering text-PDF extraction, photo OCR, malformed uploads, confirmed-vs-draft balances, and restart persistence.

## Development

```bash
cd backend
./gradlew test
./gradlew bootRun
```

The backend listens on `http://localhost:8080`. On first access create an organizer password (4+ characters); no public user registration exists. Default private data location is `backend/data` when started from `backend/`. Set `RECEIPT_DATA_DIR=/absolute/private/path` in production. Optionally set `TESSERACT_COMMAND` to an absolute binary path. Set `RECEIPT_SECURE_COOKIE=true` for any HTTPS deployment so the session cookie is Secure; use a trusted reverse proxy and configure forwarded headers appropriately. The default `false` supports only local HTTP development. Never expose an HTTP deployment publicly. Login attempts are rate-limited (5 per 15 minutes per IP).

The Gradle build builds the frontend automatically and bundles it into the backend's static resources, so `bootRun` serves the whole app (UI + API) from `http://localhost:8080` — no separate frontend dev server is required. `bootJar` produces a single self-contained jar. The frontend build needs Node.js; it's skipped (up-to-date) when nothing changed. You can still run the frontend dev server below for fast UI iteration.

```bash
cd frontend
npm ci
npm test
npm start
```

Open the Angular development URL shown in the terminal (normally `http://localhost:4200`); its proxy forwards `/api` to the backend. For deployment, just build the backend: `cd backend && ./gradlew bootJar` bundles the frontend into the jar automatically (it runs `npm install`/`npm run build` in `frontend/` and stages the output under `backend/build/generated/frontend/static`, which is served at `/`). Restrict access to the organizer and use HTTPS.

## Running from IntelliJ IDEA

The repository root contains an IntelliJ project (`.idea/`) that already links the `backend` Gradle project, so opening the root folder is enough. IDEA Ultimate gives you Spring Boot and Angular helpers; IDEA Community can run both too (a plain `Application` run config + an `npm` run config).

### Prerequisites (one-time)

- **JDK 21** (e.g. Temurin/Adoptium). This is the Gradle toolchain, so Gradle must be able to find one.
- **Node.js 18.13 – 22** and npm (Angular 17's supported range). The project uses the Angular CLI's dev server.
- **Tesseract OCR** with the `eng` traineddata, if you want photo/scanned-PDF extraction (PDF text extraction and manual entry work without it):
  - Windows: `choco install tesseract`, or the installer from the UB Mannheim builds; the `eng` pack is included. Verify `tesseract --version` works in a terminal.
  - macOS: `brew install tesseract`.
  - Debian/Ubuntu: `sudo apt install tesseract-ocr`.
  - If the `tesseract` binary is not on `PATH`, set the `TESSERACT_COMMAND` env var to its absolute path.

### Open the project and fix the JDK

1. In IntelliJ: **File → Open** → select the repository root (or `backend/` — the root is already wired up).
2. Let the Gradle import finish. If it fails, fix the **Gradle JVM**: **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM** and pick a **JDK 21**. Recent IntelliJ bundles JBR 25, which Gradle 8.10.2 cannot run on. A JDK 21 is the simplest choice because it also satisfies the project's Java 21 toolchain — with any other JVM (8–23) Gradle would need to auto-download or find a separate JDK 21 for compilation.
3. Add JDK 21 if it isn't listed: **File → Project Structure → Platform Settings → SDKs → Add → JDK** and point it at your JDK 21 install, then set it on **Project → SDK**.

### Backend run configuration

1. **Run → Edit Configurations → + → Spring Boot** (Ultimate) or **+ → Application** (Community).
2. Fill in:
   - Main class: `app.receiptshare.ReceiptShareApplicationKt`
   - Working directory: `$MODULE_DIR$` (i.e. the `backend` folder — this is where the default `data/` directory is created)
   - Use classpath of module: `receipt-share-backend.main`
3. Optional environment variables:
   - `RECEIPT_DATA_DIR` — private data directory (SQLite + original files). Omit to keep `backend/data`.
   - `TESSERACT_COMMAND` — only if `tesseract` is not on `PATH`.
   - `RECEIPT_SECURE_COOKIE=true` — only for HTTPS deployments.
4. Run it. The API listens on `http://localhost:8080`. On first access the UI prompts you to create the organizer password.

### Frontend run configuration

The Angular app is not part of the Gradle build, so it's easiest to open it as its own IntelliJ window (or use a terminal inside the root project).

1. First install dependencies once: `npm ci` in the `frontend` folder.
2. **File → Open** → `frontend/`. Install the **Node.js** plugin if IntelliJ asks.
3. In the new window: **Settings → Languages & Frameworks → Node.js** and point the Node interpreter at a Node 18.13–22 install.
4. **Run → Edit Configurations → + → npm**, set:
   - package.json: `frontend/package.json`
   - Script: `start` (runs `ng serve --proxy-config proxy.conf.json --host 0.0.0.0`)
5. Run it. Open `http://localhost:4200`; `/api` requests are proxied to the backend on `:8080`, so keep the backend running too.

### First run

Start the backend, then the frontend, then open `http://localhost:4200`. Create a strong organizer password (12+ characters); there is no registration or member accounts. Then create a group, add people, and upload or type a receipt.

### Gotchas

- The working directory of the backend run config decides where `data/` goes. If you don't set `RECEIPT_DATA_DIR`, keep the working directory at `backend/` so the database lands in `backend/data` (git-ignored) rather than the repo root.
- If a run suddenly returns `401` after editing, the in-memory session may have been lost by a restart — just sign in again; the organizer password and data persist.
- The committed `.idea/gradle.xml` points the Gradle daemon at `$JAVA_HOME` so it never hardcodes an IntelliJ-bundled JBR. Gradle 8.10.2 cannot run on Java 24/25 (the JBR that modern IntelliJ bundles). If IntelliJ reports "Your build is currently configured to use incompatible Java … and Gradle 8.10.2", set the Gradle JVM to a JDK 21: **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM** → choose JDK 21 (add it via **File → Project Structure → SDKs → + → JDK** if it isn't listed), or set `JAVA_HOME` to a JDK 21 and re-sync. Don't switch to Gradle 9.1.0 just to run the bundled JBR — the project's Kotlin 1.9.25 plugin does not support Gradle 9.

## How splitting works

Create a group in a two-decimal currency, then add members. Upload a receipt (PDF, JPEG, PNG; max 8 MB) or create a blank one. Text PDFs are read with PDFBox; scanned PDFs (first two pages) and photos are processed by local Tesseract. The extraction is **only a guess**: fix every item, subtotal, discount, tax, tip, total, and payer. Assign each item by exact minor-unit amounts or percentages (stored as 10,000 basis points). Item amounts must sum to subtotal; `subtotal − discount + tax + tip` must equal total. On confirmation, discounts are prorated across gross item shares; tax and tip are prorated across net shares. Largest-remainder rounding conserves every cent. Only confirmed receipts affect balances (`paid − owed`); positive balances are owed money. One member pays each receipt. Receipt-wide surcharges beyond tax/tip should be entered as items. Editing a confirmed receipt is intentionally disabled.

## Backup and security

Back up the entire private data directory, including `receipts.sqlite` **and** `files/`, together while the backend is stopped (SQLite WAL may contain uncheckpointed transactions otherwise). Protect backups as carefully as receipts. The organizer's password is hashed, not recoverable; if forgotten, restoring a backup or manually resetting the stored hash is necessary. Session cookies are HttpOnly and SameSite Strict; the SPA requests a CSRF token before mutating requests. Private receipt originals are fetched only through authenticated `/api/receipts/:id/file`. Keep images and PDFs from unknown sources within server-side upload limits. No external services, CDNs, or analytics are required.
