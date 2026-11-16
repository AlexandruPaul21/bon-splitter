# Bon Splitter frontend

Private, single-organizer receipt splitter built with Angular 17 and PrimeNG 17. Requires Node.js 18.13+ (or Node 20/22) and npm.

```bash
npm ci
npm start
```

Open `http://localhost:4200`. The Angular dev server proxies `/api` to `http://localhost:8080` by default. If your API listens elsewhere, change the `target` in `proxy.conf.json`. For production, deploy the `dist/tally-receipts-frontend/browser` build under the same origin as the API (or route `/api` to the backend). Cookie session and CSRF requests use same-origin URLs.

```bash
npm test
npm run build
```

The UI calls `GET /api/auth/csrf` before every POST, PUT, or DELETE, and sends the returned token in its specified header. Drafts can be created with an image/PDF multipart `file` or with an empty multipart body; incomplete drafts can be saved and resumed. Receipt totals and amount shares are sent as integer cents; percentage shares are sent as integer basis points. Only confirmed receipts are included in balances. Receipt files are retrieved as authenticated blobs instead of embedding public URLs.
