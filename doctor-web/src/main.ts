import './style.css';

const app = document.querySelector<HTMLElement>('#app')!;

function render(): void {
  const online = navigator.onLine;
  app.innerHTML = `
    <header class="top">
      <img src="/icon-192.png" alt="" width="32" height="32" />
      <h1>patnub.ai Clinic</h1>
      <span class="pill ${online ? '' : 'pill-off'}">${online ? 'Online' : 'Offline'} · works without internet</span>
    </header>
    <section class="card">
      <h2>Clinic app shell</h2>
      <p>License activation, clinician sign-in, patient check-in, note composer and QR issuing arrive in Phase 3 and 4 (build plan §0).</p>
    </section>`;
}

window.addEventListener('online', render);
window.addEventListener('offline', render);
render();

// Dev server skips the service worker so edits are never served from a stale cache.
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js');
}
