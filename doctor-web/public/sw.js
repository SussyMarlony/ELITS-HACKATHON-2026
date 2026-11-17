// Offline support: the clinic may have no internet after the first visit (spec v1.2).
// Pages: network first, cached copy when offline, so updates arrive when online.
// Everything else (hashed build assets, icons): cache first.
const CACHE = 'patnub-clinic-v2';

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(['/', '/manifest.webmanifest', '/logo-wide.png', '/logo-mark.png'])));
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;

  const save = (res) => {
    if (res.ok) {
      const copy = res.clone();
      caches.open(CACHE).then((c) => c.put(req, copy));
    }
    return res;
  };

  if (req.mode === 'navigate') {
    event.respondWith(fetch(req).then(save).catch(() => caches.match('/')));
  } else {
    event.respondWith(caches.match(req).then((hit) => hit || fetch(req).then(save)));
  }
});
