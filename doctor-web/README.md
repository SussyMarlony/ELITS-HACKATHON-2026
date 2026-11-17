# patnub.ai Clinic (doctor-web)

Offline-first web app (PWA) for clinicians. It is loaded once with internet, then works with
none (spec v1.2, build plan §0). Data lives in the browser (IndexedDB). No backend.

## What it does

1. **Activate**: import the clinic license file (`node ../contract/scripts/issue-license.ts ...`).
   The license is checked against the bundled demo root key (`../contract/demo/root_pub.txt`).
2. **First account**: the first clinician is also the clinic admin. Admins add or disable
   clinicians (doctor, nurse, midwife: one role) and make encrypted backups.
3. **Sign in** with a personal passcode. It unlocks that clinician's signing key (PBKDF2 + AES-GCM).
4. **Check in** a patient by scanning the check-in code on their phone (camera, jsQR). A known
   name on a new key is offered as "same person, new phone".
5. **Write note → rule-based draft → review → approve** (`src/draft.ts`, ported from the prototype).
   The raw note never leaves this device.
6. **Share QR**: signed by the clinician, encrypted to the patient's phone (`../contract/`).
   Valid 24 h; "Show QR" after that makes a fresh one. "Correct" issues the next version.

When the license expires, issuing stops and records stay readable.

Shared-device details: clinicians are signed out after 10 minutes without activity (their
unfinished consultation is kept for them only); leaving an unfinished note asks first; the QR
screen has a full-screen mode for the patient's camera and a live validity countdown. Add
`?debug` to the URL to show a "paste check-in code" box for testing without a camera.

| File | Role |
| --- | --- |
| `src/main.ts` | UI (no framework) |
| `src/clinic.ts` | License, clinicians, patients, issuing, backup |
| `src/store.ts` | IndexedDB |
| `src/passcode.ts` | Passcode-wrapped keys |
| `src/draft.ts` | Note → structured draft |

## Run

```sh
npm install
npm run dev       # http://localhost:5173 (no service worker in dev)
npm run build     # type-check + build to dist/
npm run preview   # serve dist/ with the service worker, to test offline
npm test          # draft parser, passcode wrap, and the contract tests
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
