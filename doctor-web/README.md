# patnub.ai Clinic (doctor-web)

Offline-first web app (PWA) for clinicians. It is loaded once with internet, then works with
none (spec v1.2, build plan §0). Data will live in the browser (IndexedDB). No backend.

## Run

```sh
npm install
npm run dev       # http://localhost:5173 (no service worker in dev)
npm run build     # type-check + build to dist/
npm run preview   # serve dist/ with the service worker, to test offline
```

Requires Node 20+.

## Test offline

1. `npm run build && npm run preview`, open the URL once.
2. DevTools > Application > Service workers: `sw.js` is activated.
3. DevTools > Network > Offline, then reload. The page still loads and the pill says Offline.

## Deploy (Cloudflare Pages)

Connect the repo in the Cloudflare dashboard with:

| Setting | Value |
| --- | --- |
| Root directory | `doctor-web` |
| Build command | `npm run build` |
| Build output directory | `dist` |

Static files only; no Workers or Functions needed.

## Offline caching

`public/sw.js` is hand-written: pages are network-first with a cached fallback, everything else
is cache-first. Bump `CACHE` in `sw.js` to force clients to drop old files.
