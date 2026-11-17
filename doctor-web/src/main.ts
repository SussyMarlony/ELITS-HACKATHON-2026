import jsQR from 'jsqr';
import qrcode from 'qrcode-generator';
import type { KeyPair } from '../../contract/contract.ts';
import {
  activateLicense, checkClinician, createClinician, eraseDevice, exportBackup, getLicense, importBackup, issue, licenseActive,
  listPatients, listStaff, qrValid, readCheckIn, recordsFor, reissue, savePatient, setDisabled, signIn,
  type CheckInResult, type Session,
} from './clinic.ts';
import { draftFromNote, type Draft } from './draft.ts';
import type { ClinicRecord, License, Patient, Staff } from './store.ts';
import './style.css';

type Stage = 'history' | 'write' | 'review' | 'qr';

const st: {
  license?: License;
  staff: Staff[];
  session?: Session;
  pendingAdmin?: KeyPair; // admin key from a just-imported license, until the first account exists
  patients: Patient[];
  selected?: Patient;
  records: ClinicRecord[];
  panel: 'patient' | 'admin';
  stage: Stage;
  note: string;
  draft?: Draft;
  encounterDate: string;
  previous?: ClinicRecord;
  shown?: ClinicRecord;
  pendingCheckIn?: CheckInResult;
  query: string;
  /** Set when the idle lock signed someone out; their unfinished consultation is kept for them only. */
  lockedFrom?: string;
  loaded: boolean;
} = { staff: [], patients: [], records: [], panel: 'patient', stage: 'history', note: '', encounterDate: today(), query: '', loaded: false };

const DEBUG = new URLSearchParams(location.search).has('debug');
const IDLE_LOCK_MS = 10 * 60_000; // shared clinic device (spec v1.2)

const app = document.querySelector<HTMLElement>('#app')!;

function today() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const esc = (s: unknown) => String(s ?? '').replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
const fmtDate = (unix: number) => new Date(unix * 1000).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
const fmtDay = (iso: string) => new Date(`${iso}T00:00`).toLocaleDateString(undefined, { dateStyle: 'medium' });

/** "23 h 59 m" left, or null when expired. */
function timeLeft(expUnix: number): string | null {
  const m = Math.floor((expUnix * 1000 - Date.now()) / 60_000);
  if (m < 0) return null;
  return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} m` : `${m} m`;
}
const by = (s: Staff | undefined) => (s ? `${s.body.name}, ${s.body.profession}` : 'Unknown clinician');

function qrSvg(text: string): string {
  const q = qrcode(0, 'M');
  q.addData(text, 'Alphanumeric');
  q.make();
  return q.createSvgTag({ cellSize: 4, margin: 4, scalable: true });
}

// ---------- rendering ----------

function render() {
  const s = st;
  let body: string;
  if (!s.loaded) body = ''; // first paint: header only, until IndexedDB answers
  else if (!s.license || (!s.staff.length && !s.pendingAdmin)) body = viewActivate();
  else if (!s.staff.length) body = viewFirstAdmin();
  else if (!s.session) body = viewSignIn();
  else body = viewHome();
  app.innerHTML = `
    <header class="top">
      <h1 class="brand"><img src="/logo-wide.png" alt="patnub.ai" width="132" height="44" /><span>Clinic</span></h1>
      <span class="pill ${navigator.onLine ? '' : 'pill-off'}">${navigator.onLine ? 'Online' : 'Offline'} · works without internet</span>
      ${s.license ? `<span class="pill ${licenseActive(s.license) ? '' : 'pill-bad'}">${esc(s.license.body.clinic_name)} · license ${licenseActive(s.license) ? 'until' : 'expired'} ${new Date(s.license.body.exp * 1000).toLocaleDateString()}</span>` : ''}
      ${s.session ? `<span class="grow"></span><span class="who">${esc(by(s.session.staff))}</span>
        ${s.session.admin ? `<button class="btn btn-sm" data-action="panel" data-panel="${s.panel === 'admin' ? 'patient' : 'admin'}">${s.panel === 'admin' ? 'Patients' : 'Clinic admin'}</button>` : ''}
        <button class="btn btn-sm" data-action="sign-out">Sign out</button>` : ''}
    </header>
    ${body}
    ${s.pendingCheckIn ? viewCheckInConfirm(s.pendingCheckIn) : ''}`;
  focusOnViewChange();
}

// Move focus to the screen's first field only when the screen changes, never on every render.
let lastView = '';
function focusOnViewChange() {
  const view = [st.loaded, !!st.license, st.staff.length > 0, !!st.session, st.panel, st.selected?.id, st.stage, !!st.pendingCheckIn].join('|');
  if (view === lastView) return;
  lastView = view;
  app.querySelector<HTMLElement>('[data-autofocus]')?.focus();
}

const hero = '<img class="hero" src="/logo-mark.png" alt="" width="56" height="56" />';

function viewActivate() {
  return `<section class="card narrow">
    ${hero}<h2>Activate this clinic</h2>
    <p>Choose the license file patnub sent to your clinic. This only needs to be done once on this device.</p>
    <label class="btn btn-primary file">Choose license file<input type="file" accept=".json,application/json" data-change="license-file" hidden></label>
    <details><summary>Restore from a backup instead</summary>${backupImportForm()}</details>
  </section>`;
}

function clinicianFields(prefix = '') {
  return `
    <label>Full name<input name="name" required autocomplete="off" data-autofocus></label>
    <div class="grid2">
      <label>Profession<input name="profession" placeholder="MD, RN, RM" required></label>
      <label>PRC license no.<input name="license_no" required></label>
    </div>
    <div class="grid2">
      <label>${prefix}Passcode<input name="passcode" type="password" required minlength="6" autocomplete="new-password"></label>
      <label>Repeat passcode<input name="confirm" type="password" required autocomplete="new-password"></label>
    </div>`;
}

function viewFirstAdmin() {
  return `<form class="card narrow" data-form="first-admin">
    ${hero}<h2>Create the first clinician account</h2>
    <p>This account is also the clinic admin. It can add other clinicians and make backups.</p>
    ${clinicianFields()}
    <button class="btn btn-primary block" data-busy="Creating account…">Create account</button>
  </form>`;
}

function viewSignIn() {
  const active = st.staff.filter((x) => !x.disabled);
  return `<form class="card narrow" data-form="sign-in">
    ${hero}<h2>Sign in</h2><p class="muted">${esc(st.license?.body.clinic_name ?? '')}</p>
    ${st.lockedFrom ? '<p class="note">Signed out after 10 minutes without activity, to protect patient records on this shared device.</p>' : ''}
    <label>Clinician<select name="staff_id">${active.map((x) => `<option value="${esc(x.staff_id)}" ${x.staff_id === st.lockedFrom ? 'selected' : ''}>${esc(by(x))}</option>`).join('')}</select></label>
    <label>Passcode<input name="passcode" type="password" required autocomplete="current-password" data-autofocus></label>
    <button class="btn btn-primary block" data-busy="Signing in…">Sign in</button>
  </form>`;
}

function viewHome() {
  return `<div class="layout">
    <aside class="card side">
      <button class="btn btn-primary block" data-action="scan">Check in patient</button>
      ${DEBUG ? `<details class="paste"><summary>Paste a check-in code (debug)</summary>
        <form data-form="paste-checkin"><textarea name="code" rows="3"></textarea><button class="btn btn-sm">Read code</button></form>
      </details>` : ''}
      <h3>Patients on this device</h3>
      ${st.patients.length > 5 ? `<input type="search" class="search" placeholder="Search by name" value="${esc(st.query)}" data-input="search" aria-label="Search patients">` : ''}
      ${st.patients.length ? '' : '<p class="muted small">No patients yet. Ask the patient to open patnub › Scan, then press Check in patient.</p>'}
      <ul class="list" id="patients">${st.patients.map((p) => `<li data-name="${esc(p.name.toLowerCase())}" ${matches(p) ? '' : 'hidden'}><button class="row ${st.selected?.id === p.id ? 'sel' : ''}" data-action="select" data-id="${esc(p.id)}" ${st.selected?.id === p.id ? 'aria-current="true"' : ''}><b>${esc(p.name)}</b><span>${esc(p.age)}${p.brgy ? ` · ${esc(p.brgy)}` : ''}</span></button></li>`).join('')}</ul>
    </aside>
    <section>${st.panel === 'admin' ? viewAdmin() : st.selected ? viewPatient(st.selected) : `<div class="card empty"><img src="/logo-mark.png" alt="" width="48" height="48"><h2>Who are you seeing?</h2><p class="muted">Choose a patient on the left, or check in a new one by scanning the code on their phone.</p></div>`}</section>
  </div>`;
}

const matches = (p: Patient) => !st.query || p.name.toLowerCase().includes(st.query.toLowerCase());

function viewPatient(p: Patient) {
  const steps = ['Write note', 'Review draft', 'Share QR'];
  const idx = { history: -1, write: 0, review: 1, qr: 2 }[st.stage];
  return `<div class="card">
    <div class="ptc"><div><h2>${esc(p.name)}</h2><p class="muted">${esc(p.age)} years${p.brgy ? ` · ${esc(p.brgy)}` : ''}</p></div>
      ${st.stage === 'history' ? `<button class="btn btn-primary" data-action="new-consult" ${licenseActive(st.license!) ? '' : 'disabled'}>New consultation</button>` : '<button class="btn btn-sm" data-action="history">Back to history</button>'}</div>
    ${idx >= 0 ? `<ol class="steps">${steps.map((x, i) => `<li class="${i < idx ? 'done' : i === idx ? 'cur' : ''}">${x}</li>`).join('')}</ol>` : ''}
    ${st.stage === 'history' ? viewHistory() : st.stage === 'write' ? viewWrite() : st.stage === 'review' ? viewReview() : viewQr(st.shown!)}
  </div>`;
}

function viewHistory() {
  if (!licenseActive(st.license!)) return '<p class="err">The clinic license has expired. Records stay readable, but new QR codes cannot be issued until it is renewed.</p>' + historyList();
  return historyList();
}

function historyList() {
  if (!st.records.length) return '<p class="muted">No records for this patient yet. Start a new consultation to write one.</p>';
  return `<h3>Records</h3><ul class="list">${st.records.map((r) => `<li class="rec ${r.status === 'SUPERSEDED' ? 'old' : ''}">
    <div class="rec-head"><b>${esc(r.copy.assessment || 'Consultation')}</b>
      <span class="tag">${r.status === 'SUPERSEDED' ? 'Replaced' : qrValid(r) ? `QR valid ${timeLeft(r.copy.exp)}` : 'QR expired'}</span></div>
    <div class="muted small">${esc(fmtDay(r.copy.encounter_date))} · version ${r.rv} · ${esc(by(st.staff.find((x) => x.staff_id === r.staff_id)))}</div>
    ${r.status === 'ISSUED' ? `<div class="actions">
      <button class="btn btn-sm" data-action="show-qr" data-key="${esc(r.key)}" data-busy="Preparing…">${qrValid(r) ? 'Show QR' : 'Make a new QR'}</button>
      <button class="btn btn-sm" data-action="correct" data-key="${esc(r.key)}">Correct</button></div>` : ''}
  </li>`).join('')}</ul>`;
}

function viewWrite() {
  return `<form data-form="write">
    <p>Write the way you normally would, in English or Taglish. patnub drafts the structured fields for you to check; nothing reaches the patient until you approve it.</p>
    <textarea name="note" rows="12" required data-autofocus data-input="note" placeholder="e.g. BP 150/95. Assessment: Stage 1 hypertension. Start Amlodipine 5 mg once daily…">${esc(st.note)}</textarea>
    <p class="muted small">The full note stays on this device. The patient only receives the fields you approve.</p>
    <div class="actions"><button class="btn btn-primary">Draft fields</button></div>
  </form>`;
}

function medRow(m: { name: string; dose: string; freq: string }) {
  return `<div class="med"><input name="med_name" value="${esc(m.name)}" placeholder="Medicine" aria-label="Medicine"><input name="med_dose" value="${esc(m.dose)}" placeholder="Dose" aria-label="Dose"><input name="med_freq" value="${esc(m.freq)}" placeholder="How often" aria-label="How often"><button type="button" class="btn btn-sm" data-action="remove-med" aria-label="Remove medicine">×</button></div>`;
}

function viewReview() {
  const d = st.draft!;
  return `<form data-form="approve" class="review">
    <aside class="source"><h3>Your note</h3><p>${esc(st.note)}</p><button type="button" class="btn btn-sm" data-action="back-write">Edit note</button></aside>
    <div class="fields">
    <p class="banner">Drafted from your note by simple rules, not by a doctor. Check every field: the patient’s assistant will treat this as your instruction.</p>
    <div class="grid2"><label>Encounter date<input type="date" name="encounter_date" value="${esc(st.encounterDate)}" required></label>
      <label>Assessment<input name="assessment" value="${esc(d.assessment)}" data-autofocus></label></div>
    <fieldset><legend>Medicines</legend><div id="meds">${d.meds.map(medRow).join('')}</div>
      <button type="button" class="btn btn-sm" data-action="add-med">Add medicine</button></fieldset>
    <div class="grid2">
      <label>Instructions · one per line<textarea name="instructions" rows="5">${esc(d.instructions.join('\n'))}</textarea></label>
      <label>Emergency warning signs · one per line<textarea name="warnings" rows="5">${esc(d.warnings.join('\n'))}</textarea></label>
    </div>
    <label>Follow-up<input name="follow_up" value="${esc(d.follow_up)}"></label>
    </div>
    <div class="approve-bar">
      <label class="check"><input type="checkbox" name="ok" required> I reviewed this summary and it matches my assessment</label>
      <button class="btn btn-primary" data-busy="Creating QR…">Approve and create QR</button>
    </div>
  </form>`;
}

function viewQr(r: ClinicRecord) {
  return `<div class="qr-wrap">
    <div class="qr">${qrSvg(r.qr)}</div>
    <div>
      <h3>Ready for ${esc(st.selected!.name)}</h3>
      <p>Ask the patient to open patnub on their phone, go to <b>Scan</b>, and scan this code. It works without internet.</p>
      <ul class="facts">
        <li>Only ${esc(st.selected!.name)}’s phone can open it.</li>
        <li>Valid for <b data-countdown="${r.copy.exp}">${timeLeft(r.copy.exp) ?? 'expired'}</b>, until ${esc(fmtDate(r.copy.exp))}.</li>
        <li>Version ${r.rv}, signed by ${esc(by(st.session!.staff))}.</li>
      </ul>
      <div class="actions start"><button class="btn btn-primary" data-action="present">Show full screen</button>
      <button class="btn" data-action="history">Done</button></div>
    </div>
  </div>`;
}

function viewCheckInConfirm(c: CheckInResult) {
  const p = c.kind === 'new-phone' ? c.candidate : c.patient;
  const text = c.kind === 'known' ? `${esc(p.name)} is already on this device.`
    : c.kind === 'new' ? `Add ${esc(p.name)}, ${esc(p.age)}${p.brgy ? `, ${esc(p.brgy)}` : ''}?`
    : `${esc(p.name)} looks like ${esc(c.existing.name)} (${esc(c.existing.age)}${c.existing.brgy ? `, ${esc(c.existing.brgy)}` : ''}) on a new phone. Confirm it is the same person before you continue: records you send will only open on the new phone.`;
  return `<div class="modal" role="dialog" aria-modal="true" aria-label="Check-in"><div class="card">
    <h3>Check-in</h3><p>${text}</p>
    <div class="actions">
      <button class="btn" data-action="checkin-cancel">Cancel</button>
      ${c.kind === 'new-phone' ? '<button class="btn" data-action="checkin-separate">Different person</button>' : ''}
      <button class="btn btn-primary" data-action="checkin-ok" data-autofocus>${c.kind === 'known' ? 'Open' : c.kind === 'new' ? 'Add patient' : 'Same person, new phone'}</button>
    </div></div></div>`;
}

function viewAdmin() {
  return `<div class="card">
    <h2>Clinicians</h2>
    <ul class="list">${st.staff.map((x) => `<li class="rec"><div><b>${esc(by(x))}</b> <span class="muted">· PRC ${esc(x.body.license_no)}${x.is_admin ? ' · admin' : ''}${x.disabled ? ' · disabled' : ''}</span></div>
      ${x.staff_id === st.session!.staff.staff_id ? '' : `<button class="btn btn-sm" data-action="toggle-staff" data-id="${esc(x.staff_id)}">${x.disabled ? 'Enable' : 'Disable'}</button>`}</li>`).join('')}</ul>
    <form data-form="add-clinician"><h3>Add clinician</h3>${clinicianFields()}
      <label class="check"><input type="checkbox" name="is_admin"> Also clinic admin</label>
      <button class="btn btn-primary" data-busy="Adding…">Add clinician</button></form>
  </div>
  <div class="card">
    <h2>Backup</h2>
    <p>Everything lives only in this browser. Save an encrypted backup to a USB stick regularly. It is locked with your passcode.</p>
    <form data-form="export"><label>Your passcode<input name="passcode" type="password" required></label><button class="btn btn-primary" data-busy="Preparing backup…">Download backup</button></form>
    <details><summary>Restore a backup (replaces everything on this device)</summary>${backupImportForm()}</details>
  </div>
  <div class="card">
    <h2>Erase this device</h2>
    <p>Deletes the license, clinicians, patients and records from this browser. Use it to reset between demos. Make a backup first if you need the data.</p>
    <button class="btn danger" data-action="erase">Erase everything</button>
  </div>`;
}

function backupImportForm() {
  return `<form data-form="import"><label>Backup file<input name="file" type="file" accept=".json" required></label>
    <label>Passcode it was saved with<input name="passcode" type="password" required></label>
    <button class="btn" data-busy="Restoring…">Restore</button></form>`;
}

// ---------- actions ----------

async function refresh() {
  st.license = await getLicense();
  st.staff = await listStaff();
  st.patients = await listPatients();
  st.loaded = true;
  if (st.selected) {
    st.selected = st.patients.find((p) => p.id === st.selected!.id);
    st.records = st.selected ? await recordsFor(st.selected.id) : [];
  }
  render();
}

/**
 * Runs an action. While it runs, [btn] is disabled and shows its data-busy label. On success the
 * screen re-renders and [done] shows as a toast; on error only a toast shows, so whatever the
 * clinician typed stays in the form.
 */
async function run(fn: () => Promise<void>, btn?: HTMLElement | null, done?: string) {
  const b = btn instanceof HTMLButtonElement ? btn : null;
  const label = b?.textContent;
  if (b) {
    b.disabled = true;
    if (b.dataset.busy) b.textContent = b.dataset.busy;
  }
  try {
    await fn();
    if (done) toast(done);
    await refresh();
  } catch (e) {
    toast(e instanceof Error ? e.message : String(e), 'error');
    if (b?.isConnected) {
      b.disabled = false;
      b.textContent = label ?? '';
    }
  }
}

// ---------- toasts ----------

const toasts = Object.assign(document.createElement('div'), { className: 'toasts' });
toasts.setAttribute('role', 'status');
toasts.setAttribute('aria-live', 'polite');
document.body.append(toasts);

function toast(text: string, kind: 'ok' | 'error' = 'ok') {
  const t = Object.assign(document.createElement('div'), { className: `toast ${kind}`, textContent: text });
  if (kind === 'error') t.setAttribute('role', 'alert');
  toasts.append(t);
  setTimeout(() => {
    t.classList.add('leaving');
    t.addEventListener('transitionend', () => t.remove(), { once: true });
    setTimeout(() => t.remove(), 400); // reduced motion: no transition fires
  }, kind === 'error' ? 6000 : 3000);
}

// ---------- guards ----------

/** True while a consultation has unsaved text. */
const dirty = () => (st.stage === 'write' || st.stage === 'review') && st.note.trim() !== '';
const leaveOk = () => !dirty() || confirm('Discard this unfinished consultation?');

function clearConsult() {
  st.stage = 'history';
  st.note = '';
  st.draft = undefined;
  st.previous = undefined;
}

function formData(form: HTMLFormElement) {
  const fd = new FormData(form);
  return (k: string) => String(fd.get(k) ?? '');
}

const actor = () => st.session?.staff.staff_id ?? 'system';

function readDraft(form: HTMLFormElement): Draft {
  const f = formData(form);
  const fd = new FormData(form);
  const col = (k: string) => fd.getAll(k).map(String);
  const names = col('med_name'), doses = col('med_dose'), freqs = col('med_freq');
  const lines = (k: string) => f(k).split('\n').map((x) => x.trim()).filter(Boolean);
  return {
    assessment: f('assessment').trim(),
    meds: names.map((name, i) => ({ name: name.trim(), dose: doses[i].trim(), freq: freqs[i].trim() })).filter((m) => m.name),
    instructions: lines('instructions'),
    warnings: lines('warnings'),
    follow_up: f('follow_up').trim(),
  };
}

app.addEventListener('submit', (ev) => {
  const form = ev.target as HTMLFormElement;
  const kind = form.dataset.form;
  if (!kind) return;
  ev.preventDefault();
  const f = formData(form);
  const btn = (ev as SubmitEvent).submitter;
  const clinician = () => ({ name: f('name'), profession: f('profession'), license_no: f('license_no'), passcode: f('passcode'), is_admin: f('is_admin') === 'on' });

  switch (kind) {
    case 'first-admin': return run(async () => {
      const c = { ...clinician(), is_admin: true };
      const problem = checkClinician(c, f('confirm'));
      if (problem) throw new Error(problem);
      await createClinician(c, st.pendingAdmin!, st.license!);
      st.pendingAdmin = undefined;
    }, btn, 'Account created. Sign in to start.');
    case 'add-clinician': return run(async () => {
      const c = clinician();
      const problem = checkClinician(c, f('confirm'));
      if (problem) throw new Error(problem);
      await createClinician(c, st.session!.admin!, st.license!, actor());
      form.reset();
    }, btn, `${f('name').trim()} can now sign in.`);
    case 'sign-in': return run(async () => {
      const s = await signIn(f('staff_id'), f('passcode'));
      if (!s) throw new Error('Wrong passcode. Try again.');
      if (st.lockedFrom !== s.staff.staff_id) { st.selected = undefined; clearConsult(); } // never show someone else's draft
      st.lockedFrom = undefined;
      st.session = s;
      touch();
    }, btn);
    case 'paste-checkin': return run(async () => { st.pendingCheckIn = await readCheckIn(f('code')); }, btn);
    case 'write': {
      st.note = f('note');
      st.draft = draftFromNote(st.note);
      if (st.previous) {
        // Correcting: start from what was approved before, not a fresh rule-based draft.
        const { assessment, meds, instructions, warnings, follow_up } = st.previous.copy;
        st.draft = { assessment, meds, instructions, warnings, follow_up };
      }
      st.stage = 'review';
      return render();
    }
    case 'approve': return run(async () => {
      st.draft = readDraft(form);
      st.encounterDate = f('encounter_date');
      st.shown = await issue(st.session!, st.license!, { patient: st.selected!, note: st.note, draft: st.draft, encounter_date: st.encounterDate, approved: f('ok') === 'on', previous: st.previous });
      st.previous = undefined;
      st.note = '';
      st.stage = 'qr';
    }, btn, 'Record approved. Show the QR to the patient.');
    case 'export': return run(async () => {
      if (!(await signIn(actor(), f('passcode')))) throw new Error('Wrong passcode.');
      const url = URL.createObjectURL(await exportBackup(f('passcode')));
      const a = Object.assign(document.createElement('a'), { href: url, download: `patnub-clinic-backup-${today()}.json` });
      a.click();
      URL.revokeObjectURL(url);
      form.reset();
    }, btn, 'Backup downloaded. Keep it on a USB stick.');
    case 'import': return run(async () => {
      const file = (form.elements.namedItem('file') as HTMLInputElement).files?.[0];
      if (!file) throw new Error('Choose a backup file.');
      await importBackup(await file.text(), f('passcode'));
      st.session = undefined;
      st.selected = undefined;
      clearConsult();
    }, btn, 'Backup restored. Sign in again.');
  }
});

app.addEventListener('input', (ev) => {
  const el = ev.target as HTMLInputElement;
  if (el.dataset.input === 'note') st.note = el.value; // survives re-renders and feeds the dirty check
  if (el.dataset.input === 'search') {
    st.query = el.value;
    st.patients.forEach((p) => {
      const li = app.querySelector<HTMLElement>(`#patients li[data-name="${CSS.escape(p.name.toLowerCase())}"]`);
      if (li) li.hidden = !matches(p);
    });
  }
});

app.addEventListener('change', (ev) => {
  const input = ev.target as HTMLInputElement;
  if (input.dataset.change !== 'license-file' || !input.files?.[0]) return;
  const file = input.files[0];
  void run(async () => { st.pendingAdmin = (await activateLicense(await file.text())).admin; }, null, 'License activated.');
});

app.addEventListener('click', (ev) => {
  const el = (ev.target as HTMLElement).closest<HTMLElement>('[data-action]');
  if (!el) return;
  const a = el.dataset.action;
  const record = () => st.records.find((r) => r.key === el.dataset.key)!;
  switch (a) {
    case 'sign-out':
      if (!leaveOk()) return;
      st.session = undefined; st.selected = undefined; st.panel = 'patient'; clearConsult(); return render();
    case 'panel':
      if (!leaveOk()) return;
      clearConsult(); st.panel = el.dataset.panel as 'patient' | 'admin'; return render();
    case 'select':
      if (el.dataset.id !== st.selected?.id && !leaveOk()) return;
      if (el.dataset.id !== st.selected?.id) clearConsult();
      st.selected = st.patients.find((p) => p.id === el.dataset.id); st.panel = 'patient'; return void refresh();
    case 'history':
      if (st.stage !== 'qr' && !leaveOk()) return;
      clearConsult(); return render();
    case 'present': return present(st.shown!);
    case 'new-consult': st.stage = 'write'; st.note = ''; st.previous = undefined; st.encounterDate = today(); return render();
    case 'back-write': st.stage = 'write'; return render();
    case 'correct': st.previous = record(); st.note = record().raw_note; st.encounterDate = record().copy.encounter_date; st.stage = 'write'; return render();
    case 'show-qr': return void run(async () => {
      const r = record();
      st.shown = qrValid(r) ? r : await reissue(st.session!, st.license!, r, st.selected!);
      st.stage = 'qr';
    }, el);
    case 'add-med': document.querySelector('#meds')!.insertAdjacentHTML('beforeend', medRow({ name: '', dose: '', freq: '' })); return;
    case 'remove-med': el.closest('.med')?.remove(); return;
    case 'toggle-staff': return void run(async () => {
      const x = st.staff.find((s) => s.staff_id === el.dataset.id)!;
      await setDisabled(x, !x.disabled, actor());
    }, el, 'Saved.');
    case 'scan':
      if (!leaveOk()) return;
      return void startScan();
    case 'erase':
      if (!confirm('Erase all clinic data on this device? This cannot be undone.')) return;
      return void eraseDevice().then(() => location.reload());
    case 'checkin-cancel': st.pendingCheckIn = undefined; return render();
    case 'checkin-separate': {
      const c = st.pendingCheckIn as Extract<CheckInResult, { kind: 'new-phone' }>;
      st.pendingCheckIn = { kind: 'new', patient: c.candidate };
      return render();
    }
    case 'checkin-ok': {
      const kind = st.pendingCheckIn!.kind;
      const msg = kind === 'known' ? undefined : kind === 'new' ? 'Patient added.' : 'New phone saved. Make new QRs for past records from their history.';
      return void run(async () => {
      const c = st.pendingCheckIn!;
      st.pendingCheckIn = undefined;
      let p: Patient;
      if (c.kind === 'known') p = c.patient;
      else if (c.kind === 'new') await savePatient((p = c.patient), actor());
      else await savePatient((p = { ...c.existing, pub: c.candidate.pub }), actor(), 'PATIENT_NEW_PHONE');
      st.selected = p;
      st.panel = 'patient';
      clearConsult();
    }, el, msg);
    }
  }
});

// ---------- camera check-in ----------

async function startScan() {
  let stream: MediaStream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
  } catch {
    return toast('Could not open the camera. Allow camera access for this site and try again.', 'error');
  }
  const modal = document.createElement('div');
  modal.className = 'modal';
  modal.setAttribute('role', 'dialog');
  modal.setAttribute('aria-modal', 'true');
  modal.setAttribute('aria-label', 'Scan check-in code');
  modal.innerHTML = `<div class="card"><h3>Scan the patient’s check-in code</h3>
    <div class="viewfinder"><video playsinline muted></video><span></span></div>
    <p class="muted small">On the patient’s phone: patnub › Scan. Hold it about 20 cm from the camera.</p>
    <div class="actions"><button class="btn" data-stop>Cancel</button></div></div>`;
  document.body.append(modal);
  const video = modal.querySelector('video')!;
  video.srcObject = stream;
  await video.play();
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
  let stopped = false;
  const stop = () => {
    stopped = true;
    stream.getTracks().forEach((t) => t.stop());
    modal.remove();
  };
  modal.querySelector('[data-stop]')!.addEventListener('click', stop);
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { stop(); document.removeEventListener('keydown', onKey); } };
  document.addEventListener('keydown', onKey);

  const tick = () => {
    if (stopped) return;
    if (video.readyState >= 2) {
      canvas.width = video.videoWidth;
      canvas.height = video.videoHeight;
      ctx.drawImage(video, 0, 0);
      const hit = jsQR(ctx.getImageData(0, 0, canvas.width, canvas.height).data, canvas.width, canvas.height, { inversionAttempts: 'dontInvert' });
      if (hit?.data) {
        stop();
        void run(async () => { st.pendingCheckIn = await readCheckIn(hit.data); });
        return;
      }
    }
    requestAnimationFrame(tick);
  };
  tick();
}

// ---------- full-screen QR for the patient ----------

function present(r: ClinicRecord) {
  const prev = document.activeElement as HTMLElement | null;
  const el = document.createElement('div');
  el.className = 'present';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'true');
  el.setAttribute('aria-label', 'QR code for the patient');
  el.tabIndex = -1;
  el.innerHTML = `<div class="qr">${qrSvg(r.qr)}</div>
    <p><b>${esc(st.selected?.name ?? '')}</b> · patnub › Scan › Scan a record</p>
    <p class="muted">Tap anywhere or press Esc to close</p>`;
  const close = () => { el.remove(); document.removeEventListener('keydown', onKey); prev?.focus(); };
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
  el.addEventListener('click', close);
  document.addEventListener('keydown', onKey);
  document.body.append(el);
  el.focus();
}

// Esc dismisses the check-in confirmation.
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && st.pendingCheckIn) {
    st.pendingCheckIn = undefined;
    render();
  }
});

// ---------- idle lock and live countdowns ----------

let lastActivity = Date.now();
const touch = () => { lastActivity = Date.now(); };
['pointerdown', 'keydown'].forEach((t) => document.addEventListener(t, touch, { passive: true }));

setInterval(() => {
  if (st.session && Date.now() - lastActivity > IDLE_LOCK_MS) {
    st.lockedFrom = st.session.staff.staff_id;
    st.session = undefined;
    document.querySelector('.present')?.remove();
    render();
  }
  app.querySelectorAll<HTMLElement>('[data-countdown]').forEach((n) => {
    n.textContent = timeLeft(Number(n.dataset.countdown)) ?? 'expired';
  });
}, 30_000);

window.addEventListener('online', render);
window.addEventListener('offline', render);
render();
void refresh();

// Dev server skips the service worker so edits are never served from a stale cache.
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js');
}
