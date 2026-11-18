import jsQR from 'jsqr';
import qrcode from 'qrcode-generator';
import type { KeyPair } from '../../contract/contract.ts';
import { AuthThrottle } from './auth-guard.ts';
import {
  activateLicense, changeOwnPasscode, checkClinician, createClinician, eraseDevice, exportBackup, getLicense, importBackup, issue,
  licenseActive, listAllRecords, listAudit, listPatients, listStaff, qrValid, readCheckIn, recordLockAudit, recordsFor, reissue,
  savePatient, setDisabled, signIn, unlockAdmin, updateOwnProfile, updateStaffProfileByAdmin, wipeKey,
  type CheckInResult, type Session,
} from './clinic.ts';
import { draftFromNote, type Draft } from './draft.ts';
import { attachDragDismiss, syncSegmentedPill } from './spring.ts';
import type { AuditEvent, ClinicRecord, License, Patient, Staff } from './store.ts';
import './style.css';

type Stage = 'history' | 'write' | 'review' | 'qr';

const st: {
  license?: License;
  staff: Staff[];
  session?: Session;
  /** Unwrapped only after explicit step-up passcode entry; wiped on exit or 2m idle. */
  adminKey?: KeyPair;
  pendingAdmin?: KeyPair; // admin key from a just-imported license, until the first account exists
  patients: Patient[];
  allRecords: ClinicRecord[];
  auditLog: AuditEvent[];
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
  profileOpen: boolean;
  profileTab: 'profile' | 'passcode';
  editingStaffId?: string;
  query: string;
  historyFilter: 'all' | 'current';
  /** Set when the idle/manual lock signed someone out; their unfinished consultation is kept for them only. */
  lockedFrom?: string;
  loaded: boolean;
} = {
  staff: [], patients: [], allRecords: [], auditLog: [], records: [],
  panel: 'patient', stage: 'history', note: '', encounterDate: today(), profileOpen: false, profileTab: 'profile', query: '', historyFilter: 'all', loaded: false,
};

const DEBUG = new URLSearchParams(location.search).has('debug');
const IDLE_LOCK_MS = 10 * 60_000; // shared clinic device (spec v1.2)
const ADMIN_IDLE_MS = 2 * 60_000; // step-up admin key expires quickly if unattended
const signInThrottle = new AuthThrottle();
const adminThrottle = new AuthThrottle();
const profileThrottle = new AuthThrottle();
const app = document.querySelector<HTMLElement>('#app')!;
let modalDragCleanup: (() => void) | null = null;
let prevModalOpen = false;
let profileTrigger: HTMLElement | null = null;

function today() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const esc = (s: unknown) => String(s ?? '').replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
const plural = (n: number, one: string, many = `${one}s`) => (n === 1 ? one : many);
const numFmt = new Intl.NumberFormat();
const fmtNum = (n: number) => numFmt.format(n);
const fmtDate = (unix: number) => new Date(unix * 1000).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
const fmtDay = (iso: string) => new Date(`${iso}T00:00`).toLocaleDateString(undefined, { dateStyle: 'medium' });
const fmtRelTime = (ms: number) => {
  const mins = Math.max(0, Math.floor((Date.now() - ms) / 60_000));
  if (mins < 1) return 'Just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return new Date(ms).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
};

/** "23 h 59 m" left, or null when expired. */
function timeLeft(expUnix: number): string | null {
  const m = Math.floor((expUnix * 1000 - Date.now()) / 60_000);
  if (m < 0) return null;
  return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} m` : `${m} m`;
}

function daysLeft(expUnix: number): number {
  return Math.max(0, Math.ceil((expUnix * 1000 - Date.now()) / 86_400_000));
}

const by = (s: Staff | undefined) => (s ? `${s.body.name}, ${s.body.profession}` : 'Unknown clinician');

function firstGrapheme(s: string): string {
  const clean = s.replace(/^[^\p{L}\p{N}]+/u, '');
  const src = clean || s;
  return [...src][0] ?? '';
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return '?';
  if (parts.length === 1) return [...parts[0]].slice(0, 2).join('').toUpperCase() || '?';
  const a = firstGrapheme(parts[0]);
  const b = firstGrapheme(parts[parts.length - 1]);
  return (a + b).toUpperCase() || '?';
}

function hueFor(name: string): number {
  let h = 178;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360;
  return h;
}

function avatarHtml(name: string, size: 'sm' | 'md' | 'lg' = 'md'): string {
  return `<span class="avatar avatar-${size}" style="--hue:${hueFor(name)}" aria-hidden="true">${esc(initials(name))}</span>`;
}

function greeting(): string {
  const h = new Date().getHours();
  if (h < 12) return 'Good morning';
  if (h < 18) return 'Good afternoon';
  return 'Good evening';
}

function qrSvg(text: string): string {
  const q = qrcode(0, 'M');
  q.addData(text, 'Alphanumeric');
  q.make();
  return q.createSvgTag({ cellSize: 4, margin: 4, scalable: true });
}

// Inline icons (offline-safe, zero network font requests)
const ico = {
  camera: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg>',
  search: '<svg class="ico ico-search" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/></svg>',
  plus: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>',
  qr: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><path d="M14 14h3v3h-3zM20 14v3M14 20h6M20 20v.01"/></svg>',
  upload: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>',
  eye: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>',
  eyeOff: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9.88 9.88a3 3 0 1 0 4.24 4.24"/><path d="M10.73 5.08A10.43 10.43 0 0 1 12 5c7 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68"/><path d="M6.61 6.61A13.526 13.526 0 0 0 2 12s3 7 10 7a9.74 9.74 0 0 0 5.39-1.61"/><line x1="2" y1="2" x2="22" y2="22"/></svg>',
  shield: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>',
  clock: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15.5 14"/></svg>',
  expand: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>',
  back: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>',
  lock: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>',
  home: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg>',
  check: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>',
  alert: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>',
  close: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>',
  edit: '<svg class="ico" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>',
};

const TEMPLATES: Array<{ label: string; note: string }> = [
  {
    label: 'Hypertension',
    note: 'Patient seen for recurring headaches for 2 weeks with BP 150/95 on two readings. Assessment: Stage 1 hypertension. Start Amlodipine 5 mg once daily in the morning. Reduce salt intake, especially instant noodles and bagoong. Walk 30 minutes a day. Follow up in 4 weeks with the BP log. Seek emergency care if severe headache, chest pain, blurred vision, or weakness on one side.',
  },
  {
    label: 'Cough / URI',
    note: 'Patient with 3 days of dry cough and mild sore throat, afebrile, clear lungs. Assessment: Acute upper respiratory tract infection. Take Paracetamol 500 mg every 6 hours as needed for fever or body ache. Drink plenty of warm water and rest. Follow up in 5 days if cough persists. Seek emergency care if difficulty breathing, high fever, or chest pain.',
  },
  {
    label: 'Gastroenteritis (Taglish)',
    note: 'Assessment: Acute gastroenteritis. Uminom ng ORS pagkatapos ng bawat pagdumi. Kumain ng malambot na pagkain at iwasan muna ang gatas o kape. Balik po after 3 days kung hindi gumaling. Seek emergency care if signs of dehydration, bloody stool, or persistent vomiting.',
  },
  {
    label: 'Wound care',
    note: 'Superficial laceration on right shin, cleaned with saline, no active bleeding. Assessment: Minor skin laceration. Keep the wound clean and dry, change dressing once daily. Take Paracetamol 500 mg every 6 hours as needed for pain. Follow up in 3 days for wound check. Seek emergency care if spreading redness, pus, swelling, or fever.',
  },
];

// ---------- rendering ----------

function render() {
  modalDragCleanup?.();
  modalDragCleanup = null;

  const s = st;
  let body: string;
  if (!s.loaded) body = ''; // first paint: header only, until IndexedDB answers
  else if (!s.license || (!s.staff.length && !s.pendingAdmin)) body = viewActivate();
  else if (!s.staff.length) body = viewFirstAdmin();
  else if (!s.session) body = viewSignIn();
  else body = viewHome();

  const licActive = s.license ? licenseActive(s.license) : false;
  const showTopbar = !s.loaded || !!s.session;
  app.innerHTML = `
    ${showTopbar ? `<header class="topbar">
      ${s.session ? `
        <button type="button" class="brand brand-btn" data-action="home" title="Return to clinic overview">
          <img src="/logo-wide.png" alt="patnub.ai" width="124" height="40" />
          <span class="brand-sub">Clinic</span>
          ${s.license ? `<span class="clinic-label" title="${esc(s.license.body.clinic_name)}">${esc(s.license.body.clinic_name)}</span>` : ''}
        </button>
      ` : `
        <div class="brand">
          <img src="/logo-wide.png" alt="patnub.ai" width="124" height="40" />
          <span class="brand-sub">Clinic</span>
        </div>
      `}
      ${!navigator.onLine ? '<span class="pill pill-off">Offline</span>' : ''}
      ${s.license && !licActive ? '<span class="pill pill-bad">License expired</span>' : ''}
      <span class="grow"></span>
      ${s.session ? `
        ${s.panel === 'admin' ? `
          <span class="pill ${s.adminKey ? 'pill-off' : ''}">${ico.lock} ${s.adminKey ? 'Admin unlocked · auto-locks 2m' : 'Admin verification required'}</span>
          <button type="button" class="btn btn-sm btn-soft" data-action="exit-admin">${ico.back} Back to patients</button>
        ` : s.session.canAdmin ? `
          <button type="button" class="btn btn-sm btn-ghost" data-action="open-admin" title="Requires admin passcode">${ico.lock} Clinic admin</button>
        ` : ''}
        <button type="button" class="user-chip user-chip-btn" data-action="open-profile" aria-label="Edit profile and passcode" title="Edit profile &amp; passcode">
          ${avatarHtml(s.session.staff.body.name, 'sm')}
          <span class="who">${esc(by(s.session.staff))}</span>
          ${ico.edit}
        </button>
        <button type="button" class="btn btn-sm" data-action="sign-out" title="Lock workstation">${ico.lock} Lock</button>
      ` : ''}
    </header>` : ''}
    ${body}
    ${s.pendingCheckIn ? viewCheckInConfirm(s.pendingCheckIn) : s.profileOpen && s.session ? viewProfileModal(s.session) : ''}`;

  syncSegmentedPill(app.querySelector('.modal .seg') ?? app.querySelector('.seg'));
  const sheet = app.querySelector<HTMLElement>('.modal .card');
  const backdrop = app.querySelector<HTMLElement>('.modal');
  const modalOpen = Boolean(sheet && backdrop);
  if (sheet && backdrop) {
    modalDragCleanup = attachDragDismiss({
      sheet,
      backdrop,
      animateEntrance: !prevModalOpen,
      onDismiss: () => {
        if (st.pendingCheckIn) {
          st.pendingCheckIn = undefined;
          render();
        } else {
          closeProfileModal();
        }
      },
    });
  }
  prevModalOpen = modalOpen;
  focusOnViewChange();
}

// Move focus to the screen's first field only when the screen changes, never on every render.
let lastView = '';
function focusOnViewChange() {
  const view = [st.loaded, !!st.license, st.staff.length > 0, !!st.session, st.panel, !!st.adminKey, st.selected?.id, st.stage, !!st.pendingCheckIn, st.profileOpen, st.profileTab, st.editingStaffId].join('|');
  if (view === lastView) return;
  lastView = view;
  app.querySelector<HTMLElement>('[data-autofocus]')?.focus();
}

function closeProfileModal() {
  st.profileOpen = false;
  render();
  profileTrigger?.focus();
  profileTrigger = null;
}

function authShell(cardHtml: string) {
  const licActive = st.license ? licenseActive(st.license) : true;
  const statusPills = [
    !navigator.onLine ? '<span class="pill pill-off">Offline</span>' : '',
    st.license && !licActive ? '<span class="pill pill-bad">License expired</span>' : '',
  ].filter(Boolean).join('');
  return `<div class="auth-shell">
    <aside class="auth-stage">
      <div class="stage-inner">
        <div class="stage-brand">
          <img class="stage-logo" src="/logo-wide.png" alt="patnub.ai" width="152" height="48" />
          <span class="stage-sub">Clinic</span>
          ${statusPills ? `<div class="stage-pills">${statusPills}</div>` : ''}
        </div>
        <h2>Send visit notes straight to your patient’s phone.</h2>
        <p class="lead">Write your note in English or Taglish. Once you review and sign it, patnub seals the summary in a QR code that opens only on that patient’s phone.</p>
      </div>
    </aside>
    <div class="auth-panel"><div class="auth-inner">${cardHtml}</div></div>
  </div>`;
}

function passwordInput(name: string, attrs = '') {
  return `<div class="pw-wrap">
    <input name="${name}" type="password" autocapitalize="none" autocorrect="off" spellcheck="false" ${attrs}>
    <button type="button" class="pw-eye" data-toggle-pw aria-pressed="false" aria-label="Show passcode" title="Show or hide passcode">${ico.eye}</button>
  </div>`;
}

function viewActivate() {
  return authShell(`
    <div class="setup-rail"><span class="on">1 · Clinic license</span><i></i><span>2 · First clinician</span></div>
    <h2>Activate this clinic</h2>
    <p class="muted">Choose or drop the signed license file from patnub. You only need to do this once on this device.</p>
    <label class="dropzone" data-drop="license">
      <span class="dropzone-icon">${ico.upload}</span>
      <b>Drop license.json here, or click to browse</b>
      <span>Checked offline against the patnub root key</span>
      <input type="file" accept=".json,application/json" data-change="license-file">
    </label>
    <details><summary>Restore from an encrypted backup instead</summary>${backupImportForm()}</details>
  `);
}

function clinicianFields(prefix = '') {
  return `
    <label>Full name<input name="name" required autocomplete="off" autocapitalize="words" placeholder="Dr. Maria Santos" data-autofocus></label>
    <div class="grid2">
      <label>Profession<input name="profession" autocapitalize="characters" placeholder="MD, RN, RM" required></label>
      <label>PRC license no.<input name="license_no" inputmode="numeric" autocapitalize="none" autocorrect="off" placeholder="0123456" required></label>
    </div>
    <div class="grid2">
      <label>${prefix}Passcode${passwordInput('passcode', 'required minlength="6" autocomplete="new-password"')}</label>
      <label>Repeat passcode${passwordInput('confirm', 'required autocomplete="new-password"')}</label>
    </div>`;
}

function viewFirstAdmin() {
  return authShell(`<form data-form="first-admin">
    <div class="setup-rail"><span class="done">✓ License verified</span><i></i><span class="on">2 · First clinician</span></div>
    <h2>Create the first clinician</h2>
    <p class="muted">This account is also the clinic admin for <b>${esc(st.license?.body.clinic_name ?? '')}</b>, so you can add staff and export backups.</p>
    ${clinicianFields()}
    <div class="actions"><button class="btn btn-primary block" data-busy="Creating account…">Create account &amp; continue</button></div>
  </form>`);
}

function viewSignIn() {
  const active = st.staff.filter((x) => !x.disabled);
  return authShell(`<form data-form="sign-in">
    <h2>Sign in to ${esc(st.license?.body.clinic_name ?? 'Clinic')}</h2>
    <p class="muted">Pick your name and enter your passcode to open clinic records.</p>
    ${st.lockedFrom ? '<p class="note">Signed out after 10 minutes without activity to protect patient privacy on this shared device.</p>' : ''}
    <label>Clinician<select name="staff_id">${active.map((x) => `<option value="${esc(x.staff_id)}" ${x.staff_id === st.lockedFrom ? 'selected' : ''}>${esc(by(x))}</option>`).join('')}</select></label>
    <label>Passcode${passwordInput('passcode', 'required autocomplete="current-password" data-autofocus')}</label>
    <div class="actions"><button class="btn btn-primary block" data-busy="Unlocking…">Unlock workspace</button></div>
  </form>`);
}

function countByPatient(records: ClinicRecord[]): Map<string, number> {
  const map = new Map<string, number>();
  for (const r of records) {
    if (r.status === 'ISSUED') map.set(r.patient_id, (map.get(r.patient_id) ?? 0) + 1);
  }
  return map;
}

function viewHome() {
  const counts = countByPatient(st.allRecords);
  const onOverview = st.panel === 'patient' && !st.selected;
  const visibleMatches = st.patients.filter(matches).length;
  return `<div class="workspace">
    <aside class="sidebar">
      <button type="button" class="btn btn-primary block" data-action="scan">${ico.camera} Check in patient</button>
      <button type="button" class="nav-row ${onOverview ? 'sel' : ''}" data-action="home" ${onOverview ? 'aria-current="page"' : ''}>
        ${ico.home}<span>Clinic overview</span>
      </button>
      <div class="search-box">
        ${ico.search}
        <input type="search" placeholder="Find patient or barangay…" value="${esc(st.query)}" data-input="search" enterkeyhint="search" aria-label="Search patients">
        <span class="kbd-hint" aria-hidden="true">/</span>
        <button type="button" class="search-clear" data-action="clear-search" aria-label="Clear search">×</button>
      </div>
      <div class="side-head">
        <span class="eyebrow">Patients</span>
        <span class="count-badge" id="patient-count-badge">${fmtNum(st.query ? visibleMatches : st.patients.length)}</span>
      </div>
      ${st.patients.length ? '' : '<p class="muted small" style="padding:4px 4px 0">No patients checked in yet. Ask a patient to open patnub › Scan, then click <b>Check in patient</b>.</p>'}
      <ul class="patient-list" id="patients">${st.patients.map((p) => {
        const n = counts.get(p.id) ?? 0;
        const sel = st.panel === 'patient' && st.selected?.id === p.id;
        return `<li data-patient-id="${esc(p.id)}" ${matches(p) ? '' : 'hidden'}>
          <button type="button" class="pt-row ${sel ? 'sel' : ''}" data-action="select" data-id="${esc(p.id)}" title="${esc(p.name)}" ${sel ? 'aria-current="true"' : ''}>
            ${avatarHtml(p.name, 'md')}
            <span class="pt-meta">
              <b>${esc(p.name)}</b>
              <span>${esc(p.age)} yrs${p.brgy ? ` · ${esc(p.brgy)}` : ''}</span>
            </span>
            ${n > 0 ? `<span class="pt-count" title="${n} issued ${plural(n, 'record')}">${n}</span>` : ''}
          </button>
        </li>`;
      }).join('')}</ul>
      <div class="search-empty" id="patients-empty" ${st.patients.length > 0 && visibleMatches === 0 ? '' : 'hidden'}>
        <p class="muted small">No patients match “<b id="patients-empty-q">${esc(st.query)}</b>”.</p>
        <button type="button" class="btn btn-sm" data-action="clear-search">Clear search</button>
      </div>
      ${DEBUG ? `<details class="paste"><summary>Paste check-in code (debug)</summary>
        <form data-form="paste-checkin"><textarea name="code" rows="3" placeholder="PATNUB:CI1:..."></textarea><button class="btn btn-sm block">Read code</button></form>
      </details>` : ''}
    </aside>
    <main class="canvas canvas-enter">${st.panel === 'admin' ? (st.adminKey ? viewAdmin() : viewAdminGate()) : st.selected ? viewPatient(st.selected) : viewDashboard()}</main>
  </div>`;
}

function viewDashboard() {
  const issued = st.allRecords.filter((r) => r.status === 'ISSUED');
  const todayIso = today();
  const todayCount = issued.filter((r) => r.copy.encounter_date === todayIso).length;
  const validQrs = issued.filter((r) => qrValid(r)).length;
  const licDays = st.license ? daysLeft(st.license.body.exp) : 0;
  const activeClinicians = st.staff.filter((x) => !x.disabled).length;
  const latestByPatient = new Map<string, ClinicRecord>();
  for (const r of issued) {
    if (!latestByPatient.has(r.patient_id)) latestByPatient.set(r.patient_id, r);
  }
  const recentPatients = [...st.patients]
    .sort((a, b) => (latestByPatient.get(b.id)?.created_at ?? b.created_at) - (latestByPatient.get(a.id)?.created_at ?? a.created_at))
    .slice(0, 5);
  const leadPatient = recentPatients[0];

  return `
    <section class="dash-hero">
      <div>
        <span class="eyebrow">${esc(st.license?.body.clinic_name ?? 'Clinic')} · ${esc(fmtDay(todayIso))}</span>
        <h2>${greeting()}, ${esc(st.session!.staff.body.name)}</h2>
        <p>${st.patients.length ? 'Pick a patient from the sidebar to open their chart, or check in someone new.' : 'Ready to start. Scan a patient’s check-in code from their phone to create their first record.'}</p>
      </div>
      <div class="dash-actions">
        <button type="button" class="btn btn-primary" data-action="scan">${ico.camera} Check in patient</button>
        ${leadPatient ? `<button type="button" class="btn btn-soft btn-open-pt" data-action="select" data-id="${esc(leadPatient.id)}" title="Open ${esc(leadPatient.name)}"><span>Open ${esc(leadPatient.name)}</span></button>` : ''}
      </div>
    </section>

    <section class="kpi-grid" aria-label="Clinic summary">
      <div class="kpi stagger" style="--i:0">
        <span class="eyebrow">Patients on device</span>
        <span class="kpi-val">${fmtNum(st.patients.length)}</span>
        <span class="kpi-sub">${fmtNum(activeClinicians)} active ${plural(activeClinicians, 'clinician')}</span>
      </div>
      <div class="kpi stagger" style="--i:1">
        <span class="eyebrow">Issued today</span>
        <span class="kpi-val">${fmtNum(todayCount)}</span>
        <span class="kpi-sub">${fmtNum(issued.length)} total approved ${plural(issued.length, 'record')}</span>
      </div>
      <div class="kpi stagger" style="--i:2">
        <span class="eyebrow">Active QR codes</span>
        <span class="kpi-val">${fmtNum(validQrs)}</span>
        <span class="kpi-sub">24-hour patient scan window</span>
      </div>
      <div class="kpi stagger" style="--i:3">
        <span class="eyebrow">Clinic license</span>
        <span class="kpi-val">${fmtNum(licDays)}d</span>
        <span class="kpi-sub">${st.license && licenseActive(st.license) ? `Valid until ${new Date(st.license.body.exp * 1000).toLocaleDateString()}` : 'Renewal needed'}</span>
      </div>
    </section>

    <section class="dash-grid">
      <div class="card">
        <div class="card-header">
          <h3>${recentPatients.length ? 'Recent patients' : 'How a visit works'}</h3>
          ${recentPatients.length ? `<span class="xs muted">Click to open chart</span>` : ''}
        </div>
        ${recentPatients.length ? `
          <ul class="quick-list">${recentPatients.map((p) => {
            const last = latestByPatient.get(p.id);
            return `<li><button type="button" class="quick-item" data-action="select" data-id="${esc(p.id)}" title="${esc(p.name)}">
              ${avatarHtml(p.name, 'md')}
              <span class="qi-main">
                <b>${esc(p.name)}</b>
                <span>${last ? `${esc(last.copy.assessment || 'Consultation')} · ${esc(fmtDay(last.copy.encounter_date))}` : `${esc(p.age)} yrs${p.brgy ? ` · ${esc(p.brgy)}` : ''} · No records yet`}</span>
              </span>
              ${last ? `<span class="tag ${qrValid(last) ? 'tag-ok' : ''}">${qrValid(last) ? 'QR ready' : `v${last.rv}`}</span>` : '<span class="tag">New</span>'}
            </button></li>`;
          }).join('')}</ul>
        ` : `
          <ol class="flow-steps">
            <li><span class="step-num">1</span><div><b>Scan check-in QR</b><br><span class="small muted">The patient opens patnub › Scan on their phone, and the camera reads their public key.</span></div></li>
            <li><span class="step-num">2</span><div><b>Write note &amp; verify fields</b><br><span class="small muted">Type in English or Taglish, then check the medicines, instructions, and warning signs.</span></div></li>
            <li><span class="step-num">3</span><div><b>Show encrypted QR</b><br><span class="small muted">The patient scans the QR code back onto their phone. No internet needed.</span></div></li>
          </ol>
        `}
      </div>

      <div class="card">
        <div class="card-header">
          <h3>Recent activity</h3>
          <span class="xs muted">Local audit log</span>
        </div>
        ${st.auditLog.length ? `
          <ul class="audit-list">${st.auditLog.slice(0, 6).map((a) => `
            <li class="audit-row">
              <span class="audit-main"><i class="audit-dot ${auditTone(a.action)}" aria-hidden="true"></i><span><b>${esc(formatAuditAction(a.action))}</b>${a.details ? ` <span class="muted">· ${esc(a.details)}</span>` : ''}</span></span>
              <time>${esc(fmtRelTime(a.at))}</time>
            </li>`).join('')}</ul>
        ` : '<p class="muted small">Activity will appear here as clinicians sign in, check in patients, and issue records.</p>'}
      </div>
    </section>`;
}

function auditTone(action: string): string {
  if (action.includes('FAILED')) return 'bad';
  if (action.includes('ISSUED') || action.includes('ACTIVATED') || action.includes('CREATED') || action.includes('CHECKED_IN')) return 'ok';
  return '';
}

function formatAuditAction(action: string): string {
  return action.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
}

const matches = (p: Patient) => {
  const q = st.query.trim().toLowerCase();
  if (!q) return true;
  return p.name.toLowerCase().includes(q) || (p.brgy ?? '').toLowerCase().includes(q);
};

function viewPatient(p: Patient) {
  const steps = ['Write note', 'Review draft', 'Share QR'];
  const idx = { history: -1, write: 0, review: 1, qr: 2 }[st.stage];
  const issuedCount = st.records.filter((r) => r.status === 'ISSUED').length;
  return `<div class="card">
    <div class="pt-header">
      <div class="pt-title">
        ${avatarHtml(p.name, 'lg')}
        <div>
          <h2>${esc(p.name)}</h2>
          <div class="pt-pills">
            <span class="tag">${esc(p.age)} years</span>
            ${p.brgy ? `<span class="tag">${esc(p.brgy)}</span>` : ''}
            <span class="tag">${issuedCount} record${issuedCount === 1 ? '' : 's'}</span>
          </div>
        </div>
      </div>
      ${st.stage === 'history'
        ? `<button class="btn btn-primary" data-action="new-consult" ${licenseActive(st.license!) ? '' : 'disabled'}>${ico.plus} New consultation</button>`
        : `<button class="btn btn-sm" data-action="history">${ico.back} Back to chart</button>`}
    </div>
    ${idx >= 0 ? `<ol class="steps">${steps.map((x, i) => `<li class="${i < idx ? 'done' : i === idx ? 'cur' : ''}">${x}</li>`).join('')}</ol>` : ''}
    ${st.stage === 'history' ? viewHistory() : st.stage === 'write' ? viewWrite() : st.stage === 'review' ? viewReview() : viewQr(st.shown!)}
  </div>`;
}

function viewHistory() {
  const banner = !licenseActive(st.license!)
    ? '<p class="err">The clinic license has expired. Past records stay readable, but new QR codes cannot be issued until it is renewed.</p>'
    : '';
  return banner + historyList();
}

function historyList() {
  if (!st.records.length) {
    return `<div class="empty-chart">
      <h3>No visits yet</h3>
      <p class="muted small">Write a consultation note to create an encrypted QR summary for ${esc(st.selected!.name)}.</p>
      <button type="button" class="btn btn-primary" data-action="new-consult" ${licenseActive(st.license!) ? '' : 'disabled'}>${ico.plus} Start first consultation</button>
    </div>`;
  }
  const hasSuperseded = st.records.some((r) => r.status === 'SUPERSEDED');
  const currentCount = st.records.filter((r) => r.status === 'ISSUED').length;
  const shownRecords = hasSuperseded && st.historyFilter === 'current'
    ? st.records.filter((r) => r.status === 'ISSUED')
    : st.records;
  const filterBar = hasSuperseded ? `<div class="history-toolbar">
    <span class="eyebrow">Consultations</span>
    <div class="seg" role="tablist" aria-label="Filter consultation versions" data-seg-group="history-${esc(st.selected!.id)}">
      <span class="seg-pill" aria-hidden="true"></span>
      <button type="button" class="seg-btn" role="tab" aria-selected="${st.historyFilter === 'all'}" data-action="history-filter" data-filter="all">All (${fmtNum(st.records.length)})</button>
      <button type="button" class="seg-btn" role="tab" aria-selected="${st.historyFilter === 'current'}" data-action="history-filter" data-filter="current">Current (${fmtNum(currentCount)})</button>
    </div>
  </div>` : '';

  return `${filterBar}<ul class="rec-list">${shownRecords.map((r) => {
    const valid = r.status === 'ISSUED' && qrValid(r);
    const badgeClass = r.status === 'SUPERSEDED' ? '' : valid ? 'tag-ok' : 'tag-warn';
    const badgeText = r.status === 'SUPERSEDED' ? 'Replaced' : valid ? `QR valid · ${timeLeft(r.copy.exp)}` : 'QR expired';
    return `<li class="rec ${r.status === 'SUPERSEDED' ? 'old' : ''}">
      <div class="rec-head">
        <div>
          <b>${esc(r.copy.assessment || 'Consultation')}</b>
          <div class="rec-sub">${esc(fmtDay(r.copy.encounter_date))} · v${r.rv} · ${esc(by(st.staff.find((x) => x.staff_id === r.staff_id)))}</div>
        </div>
        <span class="tag ${badgeClass}">${badgeText}</span>
      </div>
      <div class="rec-body">
        ${r.copy.meds.length ? `<div class="chip-row">${r.copy.meds.map((m) => `<span class="med-chip">${esc(m.name)}${m.dose ? ` ${esc(m.dose)}` : ''}${m.freq ? ` · ${esc(m.freq)}` : ''}</span>`).join('')}</div>` : ''}
        ${r.copy.instructions.length ? `<ul class="rec-lines">${r.copy.instructions.map((ins) => `<li>${esc(ins)}</li>`).join('')}</ul>` : ''}
        ${r.copy.warnings.length ? `<div class="chip-row">${r.copy.warnings.map((w) => `<span class="warn-chip">Seek care: ${esc(w)}</span>`).join('')}</div>` : ''}
        ${r.copy.follow_up ? `<div class="small muted"><b>Follow-up:</b> ${esc(r.copy.follow_up)}</div>` : ''}
        ${r.raw_note ? `<details class="raw-note"><summary>Original clinical note (local only)</summary><p>${esc(r.raw_note)}</p></details>` : ''}
      </div>
      ${r.status === 'ISSUED' ? `<div class="rec-foot">
        <span class="xs muted">Encrypted to patient’s current phone</span>
        <div class="actions" style="margin-top:0">
          <button type="button" class="btn btn-sm btn-soft" data-action="show-qr" data-key="${esc(r.key)}" data-busy="Preparing…">${ico.qr} ${valid ? 'Show QR' : 'Make new QR'}</button>
          <button type="button" class="btn btn-sm" data-action="correct" data-key="${esc(r.key)}">Correct</button>
        </div>
      </div>` : ''}
    </li>`;
  }).join('')}</ul>`;
}

function draftFieldCount(d: Draft): number {
  return [Boolean(d.assessment), d.meds.length > 0, d.instructions.length > 0, d.warnings.length > 0, Boolean(d.follow_up)].filter(Boolean).length;
}

function renderDraftPreview(d: Draft): string {
  const count = draftFieldCount(d);
  if (count === 0) {
    return `<div class="preview-head"><span class="eyebrow">Live field preview</span></div>
      <p class="muted small" style="margin:0">Type your clinical note on the left, or pick a template, to preview how patnub organizes it.</p>`;
  }
  return `<div class="preview-head">
      <span class="eyebrow">Live field preview</span>
      <span class="tag tag-ok">${count} of 5 fields</span>
    </div>
    <div class="preview-field"><span class="xs muted">Assessment</span><strong>${esc(d.assessment || 'Not detected yet')}</strong></div>
    <div class="preview-field"><span class="xs muted">Medicines (${d.meds.length})</span>
      ${d.meds.length ? `<div class="chip-row">${d.meds.map((m) => `<span class="med-chip">${esc(m.name)}${m.dose ? ` ${esc(m.dose)}` : ''}</span>`).join('')}</div>` : '<span class="small muted">None detected yet</span>'}
    </div>
    <div class="preview-field"><span class="xs muted">Instructions (${d.instructions.length})</span>
      ${d.instructions.length ? `<ul class="rec-lines">${d.instructions.slice(0, 4).map((x) => `<li>${esc(x)}</li>`).join('')}</ul>` : '<span class="small muted">None detected yet</span>'}
    </div>
    <div class="preview-field"><span class="xs muted">Emergency signs (${d.warnings.length})</span>
      ${d.warnings.length ? `<div class="chip-row">${d.warnings.map((w) => `<span class="warn-chip">${esc(w)}</span>`).join('')}</div>` : '<span class="small muted">None detected yet</span>'}
    </div>
    <div class="preview-field"><span class="xs muted">Follow-up</span><strong>${esc(d.follow_up || 'Not specified')}</strong></div>`;
}

function correctionBanner(): string {
  if (!st.previous) return '';
  return `<div class="note">Correcting record <b>v${st.previous.rv}</b>. Approving will issue <b>v${st.previous.rv + 1}</b> and mark v${st.previous.rv} as Replaced.</div>`;
}

function viewWrite() {
  const initialDraft = draftFromNote(st.note);
  return `<form data-form="write" class="write-split">
    <div>
      ${correctionBanner()}
      <p class="muted small">Write in English or Taglish. Your original note stays on this device; the patient receives only the fields you approve in the next step.</p>
      <div class="tpl-row">
        <span class="xs muted" style="font-weight:650">Templates:</span>
        ${TEMPLATES.map((t, i) => `<button type="button" class="tpl-chip" data-action="template" data-tpl="${i}" aria-pressed="${st.note === t.note}">${esc(t.label)}</button>`).join('')}
        <button type="button" class="tpl-clear" id="clear-note-btn" data-action="clear-note" ${st.note.trim() ? '' : 'hidden'}>Clear</button>
      </div>
      <textarea name="note" rows="13" required data-autofocus data-input="note" placeholder="e.g. BP 150/95. Assessment: Stage 1 hypertension. Start Amlodipine 5 mg once daily in the morning…">${esc(st.note)}</textarea>
      <div class="actions"><button class="btn btn-primary">Review structured fields →</button></div>
    </div>
    <aside class="preview-card" id="live-draft-preview">${renderDraftPreview(initialDraft)}</aside>
  </form>`;
}

function medRow(m: { name: string; dose: string; freq: string }) {
  return `<div class="med"><input name="med_name" value="${esc(m.name)}" placeholder="Medicine" aria-label="Medicine"><input name="med_dose" value="${esc(m.dose)}" placeholder="Dose" aria-label="Dose"><input name="med_freq" value="${esc(m.freq)}" placeholder="How often" aria-label="How often"><button type="button" class="btn btn-sm" data-action="remove-med" aria-label="Remove medicine" title="Remove medicine">×</button></div>`;
}

function viewReview() {
  const d = st.draft!;
  return `<form data-form="approve" class="review">
    <aside class="source">
      <span class="eyebrow">Verbatim note (stays local)</span>
      <p>${esc(st.note)}</p>
      <button type="button" class="btn btn-sm" data-action="back-write">${ico.back} Edit note</button>
    </aside>
    <div class="fields">
      ${correctionBanner()}
      <div class="banner">${ico.shield}<span>Drafted by simple rules. Check each field before signing, because the patient’s assistant uses this as your instruction.</span></div>
      <div class="grid2">
        <label>Encounter date<input type="date" name="encounter_date" value="${esc(st.encounterDate)}" required></label>
        <label>Assessment<input name="assessment" value="${esc(d.assessment)}" data-autofocus></label>
      </div>
      <fieldset>
        <legend>Medicines</legend>
        <div id="meds">${d.meds.map(medRow).join('')}</div>
        <p class="muted small" id="meds-empty" ${d.meds.length ? 'hidden' : ''} style="margin:2px 0 10px">No medicines listed. Add one below if prescribed.</p>
        <button type="button" class="btn btn-sm btn-soft" data-action="add-med">${ico.plus} Add medicine</button>
      </fieldset>
      <div class="grid2">
        <label>Instructions · one per line<textarea name="instructions" rows="5">${esc(d.instructions.join('\n'))}</textarea></label>
        <label>Emergency warning signs · one per line<textarea name="warnings" rows="5">${esc(d.warnings.join('\n'))}</textarea></label>
      </div>
      <label>Follow-up<input name="follow_up" value="${esc(d.follow_up)}" placeholder="e.g. Return in 4 weeks"></label>
    </div>
    <div class="approve-bar">
      <label class="check"><input type="checkbox" name="ok" required> I reviewed this summary and it matches my assessment</label>
      <button class="btn btn-primary" data-busy="Signing &amp; sealing QR…">${ico.qr} Approve &amp; create QR</button>
    </div>
  </form>`;
}

function viewQr(r: ClinicRecord) {
  return `<div class="qr-wrap">
    <div class="qr-card" data-action="present" role="button" tabindex="0" aria-label="Show QR code full screen">
      ${qrSvg(r.qr)}
      <span class="qr-hint xs muted">${ico.expand} Tap or press Enter to enlarge</span>
    </div>
    <div>
      <span class="tag tag-ok">Signed &amp; encrypted</span>
      <h3 style="margin-top:8px;font-size:1.3125rem">Ready for ${esc(st.selected!.name)}</h3>
      <p class="muted">Ask the patient to open <b>patnub › Scan › Scan a record</b> and point their phone at this code. Works without internet.</p>
      <ul class="facts">
        <li>${ico.shield}<span>Locked to <b>${esc(st.selected!.name)}</b>’s phone key only</span></li>
        <li>${ico.clock}<span>Valid for <b data-countdown="${r.copy.exp}">${timeLeft(r.copy.exp) ?? 'expired'}</b> (until ${esc(fmtDate(r.copy.exp))})</span></li>
        <li>${ico.qr}<span>Record <b>v${r.rv}</b> · signed by ${esc(by(st.session!.staff))}</span></li>
      </ul>
      <div class="actions start">
        <button type="button" class="btn btn-primary" data-action="present">${ico.expand} Present full screen</button>
        <button type="button" class="btn" data-action="history">Done</button>
      </div>
    </div>
  </div>`;
}

function viewCheckInConfirm(c: CheckInResult) {
  const p = c.kind === 'new-phone' ? c.candidate : c.patient;
  const text = c.kind === 'known'
    ? `<b>${esc(p.name)}</b> is already registered on this device.`
    : c.kind === 'new'
    ? `Add <b>${esc(p.name)}</b>, ${esc(p.age)} yrs${p.brgy ? ` from ${esc(p.brgy)}` : ''}?`
    : `<b>${esc(p.name)}</b> matches ${esc(c.existing.name)} (${esc(c.existing.age)} yrs${c.existing.brgy ? `, ${esc(c.existing.brgy)}` : ''}) on a new phone. Confirm it is the same person: new QR codes will only open on this new phone.`;
  return `<div class="modal" role="dialog" aria-modal="true" aria-label="Check-in">
    <div class="card">
      <div class="sheet-handle" aria-hidden="true"></div>
      <h3>Patient check-in</h3>
      <p>${text}</p>
      <div class="actions">
        <button class="btn" data-action="checkin-cancel">Cancel</button>
        ${c.kind === 'new-phone' ? '<button class="btn" data-action="checkin-separate">Different person</button>' : ''}
        <button class="btn btn-primary" data-action="checkin-ok" data-autofocus>${c.kind === 'known' ? 'Open chart' : c.kind === 'new' ? 'Add patient' : 'Same person, new phone'}</button>
      </div>
    </div>
  </div>`;
}

function viewProfileModal(s: Session) {
  const staff = s.staff;
  const tab = st.profileTab;
  return `<div class="modal" role="dialog" aria-modal="true" aria-label="Clinician profile">
    <div class="card profile-modal">
      <div class="sheet-handle" aria-hidden="true"></div>
      <div class="profile-head">
        <div class="profile-id">
          ${avatarHtml(staff.body.name, 'lg')}
          <div>
            <h3>${esc(staff.body.name)}</h3>
            <div class="rec-sub">${esc(staff.body.profession)} · PRC ${esc(staff.body.license_no)}${staff.is_admin ? ' · Clinic admin' : ''}</div>
          </div>
        </div>
        <button type="button" class="btn btn-sm btn-ghost profile-close" data-action="close-profile" aria-label="Close profile">${ico.close}</button>
      </div>
      <div class="seg seg-full" role="tablist" aria-label="Profile sections" data-seg-group="profile-modal">
        <span class="seg-pill" aria-hidden="true"></span>
        <button type="button" class="seg-btn" role="tab" aria-selected="${tab === 'profile'}" data-action="profile-tab" data-tab="profile">Profile details</button>
        <button type="button" class="seg-btn" role="tab" aria-selected="${tab === 'passcode'}" data-action="profile-tab" data-tab="passcode">Change passcode</button>
      </div>
      ${tab === 'profile' ? (s.canAdmin ? `
        <form data-form="profile-save">
          <label>Full name<input name="name" value="${esc(staff.body.name)}" required autocomplete="off" autocapitalize="words" data-autofocus></label>
          <div class="grid2">
            <label>Profession<input name="profession" value="${esc(staff.body.profession)}" autocapitalize="characters" required></label>
            <label>PRC license no.<input name="license_no" value="${esc(staff.body.license_no)}" inputmode="numeric" autocapitalize="none" autocorrect="off" required></label>
          </div>
          <label>Current passcode${passwordInput('passcode', 'required autocomplete="current-password"')}</label>
          <p class="xs muted" style="margin:-4px 0 0">Needed to re-sign your clinician certificate for QR codes.</p>
          <div class="actions">
            <button type="button" class="btn" data-action="close-profile">Cancel</button>
            <button class="btn btn-primary" data-busy="Saving…">Save profile</button>
          </div>
        </form>
      ` : `
        <div class="profile-readonly">
          <div class="profile-kv"><span>Full name</span><b>${esc(staff.body.name)}</b></div>
          <div class="profile-kv"><span>Profession</span><b>${esc(staff.body.profession)}</b></div>
          <div class="profile-kv"><span>PRC license no.</span><b>${esc(staff.body.license_no)}</b></div>
        </div>
        <div class="banner" style="margin-top:12px">${ico.shield}<span>Your name, profession, and PRC license number are signed onto QR codes by a clinic admin. Ask an admin to update them under Clinic admin.</span></div>
        <div class="actions">
          <button type="button" class="btn" data-action="close-profile">Close</button>
          <button type="button" class="btn btn-primary" data-action="profile-tab" data-tab="passcode" data-autofocus>Change passcode</button>
        </div>
      `) : `
        <form data-form="change-passcode">
          <label>Current passcode${passwordInput('current_passcode', 'required autocomplete="current-password" data-autofocus')}</label>
          <div class="grid2">
            <label>New passcode${passwordInput('new_passcode', 'required minlength="6" autocomplete="new-password"')}</label>
            <label>Repeat new passcode${passwordInput('confirm', 'required autocomplete="new-password"')}</label>
          </div>
          <p class="xs muted" style="margin:-4px 0 0">At least 6 characters. Your signing key stays encrypted on this device.</p>
          <div class="actions">
            <button type="button" class="btn" data-action="close-profile">Cancel</button>
            <button class="btn btn-primary" data-busy="Updating…">Update passcode</button>
          </div>
        </form>
      `}
    </div>
  </div>`;
}

function viewAdminGate() {
  return `<div class="card admin-gate">
    <div class="admin-gate-icon">${ico.lock}</div>
    <h2>Verify admin passcode</h2>
    <p class="muted">Staff accounts, backups, audit logs, and device reset stay separate from patient charts and lock again after 2 minutes without activity.</p>
    <form data-form="admin-unlock">
      <label>Admin passcode for ${esc(st.session!.staff.body.name)}${passwordInput('passcode', 'required autocomplete="current-password" data-autofocus')}</label>
      <div class="actions">
        <button type="button" class="btn" data-action="exit-admin">Cancel</button>
        <button class="btn btn-primary" data-busy="Verifying…">${ico.lock} Unlock admin</button>
      </div>
    </form>
  </div>`;
}

function viewAdmin() {
  return `<div class="admin-grid">
    <div class="card">
      <div class="card-header">
        <h2>Clinicians</h2>
        <span class="count-badge">${st.staff.length}</span>
      </div>
      <ul class="rec-list" style="margin-bottom:18px">${st.staff.map((x) => {
        const editing = st.editingStaffId === x.staff_id;
        return `<li class="rec" style="padding:12px 14px">
          <div class="rec-head" style="align-items:center">
            <div style="display:flex;align-items:center;gap:10px;min-width:0">
              ${avatarHtml(x.body.name, 'md')}
              <div style="min-width:0">
                <b>${esc(by(x))}</b>
                <div class="rec-sub">PRC ${esc(x.body.license_no)}${x.is_admin ? ' · Admin' : ''}${x.disabled ? ' · Disabled' : ''}</div>
              </div>
            </div>
            <div class="staff-actions">
              <button type="button" class="btn btn-sm btn-ghost" data-action="edit-staff" data-id="${esc(x.staff_id)}">${editing ? 'Cancel' : 'Edit'}</button>
              ${x.staff_id === st.session!.staff.staff_id
                ? '<span class="tag tag-ok">You</span>'
                : `<button type="button" class="btn btn-sm" data-action="toggle-staff" data-id="${esc(x.staff_id)}">${x.disabled ? 'Enable' : 'Disable'}</button>`}
            </div>
          </div>
          ${editing ? `<form data-form="admin-edit-staff" data-id="${esc(x.staff_id)}" class="staff-edit-form">
            <label>Full name<input name="name" value="${esc(x.body.name)}" required autocomplete="off" autocapitalize="words" data-autofocus></label>
            <div class="grid2">
              <label>Profession<input name="profession" value="${esc(x.body.profession)}" autocapitalize="characters" required></label>
              <label>PRC license no.<input name="license_no" value="${esc(x.body.license_no)}" inputmode="numeric" autocapitalize="none" autocorrect="off" required></label>
            </div>
            <div class="actions" style="margin-top:8px">
              <button type="button" class="btn btn-sm" data-action="edit-staff" data-id="${esc(x.staff_id)}">Cancel</button>
              <button class="btn btn-sm btn-primary" data-busy="Saving…">Save changes</button>
            </div>
          </form>` : ''}
        </li>`;
      }).join('')}</ul>
      <form data-form="add-clinician">
        <h3>Add clinician</h3>
        ${clinicianFields()}
        <label class="check" style="margin:10px 0 14px"><input type="checkbox" name="is_admin"> Also clinic admin</label>
        <button class="btn btn-primary" data-busy="Adding…">${ico.plus} Add clinician</button>
      </form>
    </div>

    <div class="admin-stack">
      <div class="card">
        <h2>Encrypted backup</h2>
        <p class="muted small">All clinic data stays in this browser. Export an encrypted backup to a USB drive on a regular schedule.</p>
        <form data-form="export">
          <label>Your passcode${passwordInput('passcode', 'required')}</label>
          <button class="btn btn-primary" data-busy="Preparing backup…">${ico.upload} Download backup</button>
        </form>
        <details><summary>Restore from backup (replaces data on this device)</summary>${backupImportForm(true)}</details>
      </div>

      <div class="card">
        <div class="card-header">
          <h3>Recent audit log</h3>
          <span class="xs muted">${fmtNum(st.auditLog.length)} latest</span>
        </div>
        ${st.auditLog.length ? `<ul class="audit-list">${st.auditLog.slice(0, 8).map((a) => `
          <li class="audit-row">
            <span class="audit-main"><i class="audit-dot ${auditTone(a.action)}" aria-hidden="true"></i><span><b>${esc(formatAuditAction(a.action))}</b>${a.details ? ` <span class="muted">· ${esc(a.details)}</span>` : ''}</span></span>
            <time>${esc(fmtRelTime(a.at))}</time>
          </li>`).join('')}</ul>` : '<p class="muted small">No events yet.</p>'}
      </div>

      <div class="card">
        <h3>Reset device</h3>
        <p class="muted small">Removes the clinic license, staff accounts, patients, and records from this browser. Enter your admin passcode, then hold the button for 1.5 seconds.</p>
        <label>Confirm admin passcode${passwordInput('erase_passcode', 'id="erase-passcode" autocomplete="current-password"')}</label>
        <button type="button" class="btn btn-hold" data-hold-erase>
          <span>Hold 1.5s to erase everything</span>
          <span class="btn-hold-fill" aria-hidden="true">Erasing… keep holding</span>
        </button>
      </div>
    </div>
  </div>`;
}

function backupImportForm(requireAdminPass = false) {
  return `<form data-form="import" style="margin-top:10px">
    <label>Backup file<input name="file" type="file" accept=".json" required></label>
    <label>Passcode the backup was saved with${passwordInput('passcode', 'required')}</label>
    ${requireAdminPass ? `<label>Your current admin passcode${passwordInput('admin_passcode', 'required autocomplete="current-password"')}</label>` : ''}
    <button class="btn" data-busy="Restoring…">Restore backup</button>
  </form>`;
}

// ---------- actions ----------

async function refresh() {
  st.license = await getLicense();
  st.staff = await listStaff();
  st.patients = await listPatients();
  st.allRecords = await listAllRecords();
  st.auditLog = await listAudit(12);
  st.loaded = true;
  if (st.selected) {
    st.selected = st.patients.find((p) => p.id === st.selected!.id);
    st.records = st.selected ? await recordsFor(st.selected.id) : [];
  }
  render();
}

async function run(fn: () => Promise<void>, btn?: HTMLElement | null, done?: string) {
  const b = btn instanceof HTMLButtonElement ? btn : null;
  const label = b?.innerHTML;
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
      b.innerHTML = label ?? '';
    }
  }
}

// ---------- toasts ----------

const toasts = Object.assign(document.createElement('div'), { className: 'toasts' });
toasts.setAttribute('role', 'status');
toasts.setAttribute('aria-live', 'polite');
document.body.append(toasts);

function toast(text: string, kind: 'ok' | 'error' = 'ok') {
  const t = document.createElement('div');
  t.className = `toast ${kind}`;
  if (kind === 'error') t.setAttribute('role', 'alert');
  t.innerHTML = `<span class="toast-ico">${kind === 'error' ? ico.alert : ico.check}</span><span class="toast-msg">${esc(text)}</span><button type="button" class="toast-close" aria-label="Dismiss notification">${ico.close}</button>`;
  toasts.append(t);
  let remaining = kind === 'error' ? 6000 : 3000;
  let started = Date.now();
  let timer = setTimeout(dismiss, remaining);
  let dismissed = false;
  function dismiss() {
    if (dismissed) return;
    dismissed = true;
    clearTimeout(timer);
    t.classList.add('leaving');
    t.addEventListener('transitionend', () => t.remove(), { once: true });
    setTimeout(() => t.remove(), 300);
  }
  const pause = () => {
    clearTimeout(timer);
    remaining = Math.max(400, remaining - (Date.now() - started));
  };
  const resume = () => {
    if (dismissed) return;
    started = Date.now();
    timer = setTimeout(dismiss, remaining);
  };
  t.querySelector('.toast-close')!.addEventListener('click', dismiss);
  t.addEventListener('mouseenter', pause);
  t.addEventListener('mouseleave', resume);
  t.addEventListener('focusin', pause);
  t.addEventListener('focusout', resume);
}

// ---------- guards & form helpers ----------

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
      if (!st.adminKey) throw new Error('Unlock clinic administration first.');
      const c = clinician();
      const problem = checkClinician(c, f('confirm'));
      if (problem) throw new Error(problem);
      await createClinician(c, st.adminKey, st.license!, actor());
      form.reset();
    }, btn, `${f('name').trim()} can now sign in.`);
    case 'admin-unlock': return run(async () => {
      const wait = adminThrottle.cooldownSeconds();
      if (wait > 0) throw new Error(`Too many wrong passcodes. Try again in ${wait} s.`);
      const key = await unlockAdmin(actor(), f('passcode'));
      if (!key) {
        const sWait = adminThrottle.recordFailure();
        throw new Error(sWait > 0 ? `Wrong passcode. Try again in ${sWait} s.` : 'Wrong admin passcode.');
      }
      adminThrottle.reset();
      st.adminKey = key;
      touch();
    }, btn, 'Admin access unlocked for 2 minutes.');
    case 'sign-in': return run(async () => {
      const wait = signInThrottle.cooldownSeconds();
      if (wait > 0) throw new Error(`Too many wrong passcodes. Try again in ${wait} s.`);
      const s = await signIn(f('staff_id'), f('passcode'));
      if (!s) {
        const sWait = signInThrottle.recordFailure();
        throw new Error(sWait > 0 ? `Wrong passcode. Try again in ${sWait} s.` : 'Wrong passcode. Try again.');
      }
      signInThrottle.reset();
      if (st.lockedFrom !== s.staff.staff_id) { st.selected = undefined; clearConsult(); }
      st.lockedFrom = undefined;
      st.session = s;
      touch();
    }, btn);
    case 'paste-checkin': return run(async () => { st.pendingCheckIn = await readCheckIn(f('code')); }, btn);
    case 'write': {
      st.note = f('note');
      st.draft = draftFromNote(st.note);
      if (st.previous) {
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
    case 'profile-save': return run(async () => {
      if (!st.session) return;
      const wait = profileThrottle.cooldownSeconds();
      if (wait > 0) throw new Error(`Too many wrong passcodes. Try again in ${wait} s.`);
      let updated: Staff;
      try {
        updated = await updateOwnProfile(
          st.session.staff.staff_id,
          { name: f('name'), profession: f('profession'), license_no: f('license_no') },
          f('passcode'),
        );
      } catch (e) {
        if (e instanceof Error && e.message === 'Wrong passcode.') {
          const sWait = profileThrottle.recordFailure();
          throw new Error(sWait > 0 ? `Wrong passcode. Try again in ${sWait} s.` : 'Wrong passcode.');
        }
        throw e;
      }
      profileThrottle.reset();
      st.session = { ...st.session, staff: updated };
      st.profileOpen = false;
      touch();
    }, btn, 'Profile updated.');
    case 'change-passcode': return run(async () => {
      if (!st.session) return;
      const wait = profileThrottle.cooldownSeconds();
      if (wait > 0) throw new Error(`Too many wrong passcodes. Try again in ${wait} s.`);
      let updated: Staff;
      try {
        updated = await changeOwnPasscode(
          st.session.staff.staff_id,
          f('current_passcode'),
          f('new_passcode'),
          f('confirm'),
        );
      } catch (e) {
        if (e instanceof Error && e.message === 'Current passcode is incorrect.') {
          const sWait = profileThrottle.recordFailure();
          throw new Error(sWait > 0 ? `Current passcode is incorrect. Try again in ${sWait} s.` : 'Current passcode is incorrect.');
        }
        throw e;
      }
      profileThrottle.reset();
      st.session = { ...st.session, staff: updated };
      st.profileOpen = false;
      touch();
    }, btn, 'Passcode updated.');
    case 'admin-edit-staff': return run(async () => {
      if (!st.adminKey) throw new Error('Unlock clinic administration first.');
      const id = form.dataset.id!;
      const updated = await updateStaffProfileByAdmin(
        id,
        { name: f('name'), profession: f('profession'), license_no: f('license_no') },
        st.adminKey,
        actor(),
      );
      if (st.session?.staff.staff_id === id) {
        st.session = { ...st.session, staff: updated };
      }
      st.editingStaffId = undefined;
    }, btn, 'Clinician profile updated.');
    case 'import': return run(async () => {
      const file = (form.elements.namedItem('file') as HTMLInputElement).files?.[0];
      if (!file) throw new Error('Choose a backup file.');
      if (st.session) {
        if (!st.adminKey) throw new Error('Unlock clinic administration first.');
        const ok = await unlockAdmin(actor(), f('admin_passcode'));
        if (!ok) throw new Error('Wrong current admin passcode.');
        wipeKey(ok);
      }
      await importBackup(await file.text(), f('passcode'));
      lockWorkstation('manual');
      st.selected = undefined;
      clearConsult();
    }, btn, 'Backup restored. Sign in again.');
  }
});

function lockAdmin() {
  wipeKey(st.adminKey);
  st.adminKey = undefined;
  st.editingStaffId = undefined;
}

function lockWorkstation(reason: 'manual' | 'idle') {
  const id = st.session?.staff.staff_id;
  lockAdmin();
  wipeKey(st.session?.key);
  st.session = undefined;
  st.panel = 'patient';
  st.profileOpen = false;
  profileTrigger = null;
  if (reason === 'manual') {
    st.selected = undefined;
    clearConsult();
    st.lockedFrom = undefined;
  }
  if (id) void recordLockAudit(id, reason);
}

function filterPatients(q: string) {
  st.query = q;
  let visible = 0;
  st.patients.forEach((p) => {
    const show = matches(p);
    if (show) visible++;
    const li = app.querySelector<HTMLElement>(`#patients li[data-patient-id="${CSS.escape(p.id)}"]`);
    if (li) li.hidden = !show;
  });
  const badge = app.querySelector<HTMLElement>('#patient-count-badge');
  if (badge) badge.textContent = fmtNum(st.query.trim() ? visible : st.patients.length);
  const empty = app.querySelector<HTMLElement>('#patients-empty');
  const emptyQ = app.querySelector<HTMLElement>('#patients-empty-q');
  if (empty) empty.hidden = st.patients.length === 0 || visible > 0;
  if (emptyQ) emptyQ.textContent = st.query;
}

function syncWriteControls() {
  const preview = app.querySelector<HTMLElement>('#live-draft-preview');
  if (preview) preview.innerHTML = renderDraftPreview(draftFromNote(st.note));
  app.querySelectorAll<HTMLButtonElement>('.tpl-chip[data-tpl]').forEach((btn) => {
    const tpl = TEMPLATES[Number(btn.dataset.tpl)];
    btn.setAttribute('aria-pressed', String(Boolean(tpl && st.note === tpl.note)));
  });
  const clearBtn = app.querySelector<HTMLElement>('#clear-note-btn');
  if (clearBtn) clearBtn.hidden = st.note.trim() === '';
}

function syncMedEmptyState() {
  const meds = app.querySelector('#meds');
  const empty = app.querySelector<HTMLElement>('#meds-empty');
  if (meds && empty) empty.hidden = meds.children.length > 0;
}

app.addEventListener('input', (ev) => {
  const el = ev.target as HTMLInputElement;
  if (el.dataset.input === 'note') {
    st.note = el.value;
    syncWriteControls();
  }
  if (el.dataset.input === 'search') {
    filterPatients(el.value);
  }
});

app.addEventListener('change', (ev) => {
  const input = ev.target as HTMLInputElement;
  if (input.dataset.change !== 'license-file' || !input.files?.[0]) return;
  const file = input.files[0];
  void run(async () => { st.pendingAdmin = (await activateLicense(await file.text())).admin; }, null, 'License activated.');
});

// Drag-and-drop license file support
app.addEventListener('dragover', (ev) => {
  const dz = (ev.target as HTMLElement).closest<HTMLElement>('[data-drop]');
  if (!dz) return;
  ev.preventDefault();
  dz.classList.add('over');
});
app.addEventListener('dragleave', (ev) => {
  const dz = (ev.target as HTMLElement).closest<HTMLElement>('[data-drop]');
  if (dz) dz.classList.remove('over');
});
app.addEventListener('drop', (ev) => {
  const dz = (ev.target as HTMLElement).closest<HTMLElement>('[data-drop]');
  if (!dz) return;
  ev.preventDefault();
  dz.classList.remove('over');
  const file = ev.dataTransfer?.files?.[0];
  if (file && dz.dataset.drop === 'license') {
    void run(async () => { st.pendingAdmin = (await activateLicense(await file.text())).admin; }, null, 'License activated.');
  }
});

app.addEventListener('click', (ev) => {
  const target = ev.target as HTMLElement;
  if (target.classList.contains('modal')) {
    if (st.pendingCheckIn) {
      st.pendingCheckIn = undefined;
      render();
      return;
    }
    if (st.profileOpen) {
      closeProfileModal();
      return;
    }
  }

  const pwBtn = target.closest<HTMLElement>('[data-toggle-pw]');
  if (pwBtn) {
    const inp = pwBtn.parentElement?.querySelector('input');
    if (inp) {
      const showing = inp.type === 'password';
      inp.type = showing ? 'text' : 'password';
      pwBtn.setAttribute('aria-pressed', String(showing));
      pwBtn.setAttribute('aria-label', showing ? 'Hide passcode' : 'Show passcode');
      pwBtn.innerHTML = showing ? ico.eyeOff : ico.eye;
    }
    return;
  }

  const el = target.closest<HTMLElement>('[data-action]');
  if (!el) return;
  const a = el.dataset.action;
  const record = () => st.records.find((r) => r.key === el.dataset.key)!;
  switch (a) {
    case 'home':
      if (!leaveOk()) return;
      clearConsult();
      lockAdmin();
      st.selected = undefined;
      st.panel = 'patient';
      return render();
    case 'open-profile':
      if (!st.session) return;
      profileTrigger = el;
      st.profileOpen = true;
      st.profileTab = 'profile';
      return render();
    case 'close-profile':
      return closeProfileModal();
    case 'profile-tab':
      st.profileTab = el.dataset.tab === 'passcode' ? 'passcode' : 'profile';
      return render();
    case 'edit-staff': {
      const id = el.dataset.id!;
      st.editingStaffId = st.editingStaffId === id ? undefined : id;
      return render();
    }
    case 'sign-out':
      if (!leaveOk()) return;
      lockWorkstation('manual');
      return render();
    case 'open-admin':
      if (!st.session?.canAdmin || !leaveOk()) return;
      clearConsult();
      lockAdmin();
      st.panel = 'admin';
      return render();
    case 'exit-admin':
      lockAdmin();
      st.panel = 'patient';
      return render();
    case 'select':
      if (el.dataset.id !== st.selected?.id && !leaveOk()) return;
      if (el.dataset.id !== st.selected?.id) {
        clearConsult();
        st.historyFilter = 'all';
      }
      lockAdmin();
      st.selected = st.patients.find((p) => p.id === el.dataset.id); st.panel = 'patient'; return void refresh();
    case 'clear-search': {
      st.query = '';
      render();
      app.querySelector<HTMLInputElement>('input[data-input="search"]')?.focus();
      return;
    }
    case 'history-filter': {
      st.historyFilter = el.dataset.filter === 'current' ? 'current' : 'all';
      return render();
    }
    case 'template': {
      const idx = Number(el.dataset.tpl);
      const tpl = TEMPLATES[idx];
      const ta = app.querySelector<HTMLTextAreaElement>('textarea[name="note"]');
      if (tpl && ta) {
        ta.value = tpl.note;
        st.note = tpl.note;
        syncWriteControls();
        ta.focus();
      }
      return;
    }
    case 'clear-note': {
      const ta = app.querySelector<HTMLTextAreaElement>('textarea[name="note"]');
      st.note = '';
      if (ta) {
        ta.value = '';
        ta.focus();
      }
      syncWriteControls();
      return;
    }
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
    case 'add-med': {
      document.querySelector('#meds')!.insertAdjacentHTML('beforeend', medRow({ name: '', dose: '', freq: '' }));
      syncMedEmptyState();
      const inputs = document.querySelectorAll<HTMLInputElement>('#meds .med:last-child input[name="med_name"]');
      inputs[0]?.focus();
      return;
    }
    case 'remove-med': el.closest('.med')?.remove(); syncMedEmptyState(); return;
    case 'toggle-staff': return void run(async () => {
      if (!st.adminKey) throw new Error('Unlock clinic administration first.');
      const x = st.staff.find((s) => s.staff_id === el.dataset.id)!;
      await setDisabled(x, !x.disabled, actor());
    }, el, 'Saved.');
    case 'scan':
      if (!leaveOk()) return;
      return void startScan();
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

// Hold-to-erase (requires admin passcode + 1.5s linear hold via clip-path)
let holdTimer: ReturnType<typeof setTimeout> | null = null;
const cancelHold = () => {
  if (holdTimer) { clearTimeout(holdTimer); holdTimer = null; }
  app.querySelectorAll('.btn-hold.holding').forEach((b) => b.classList.remove('holding'));
};
async function tryEraseWithPasscode() {
  const inp = app.querySelector<HTMLInputElement>('#erase-passcode');
  const pass = inp?.value ?? '';
  if (!pass) {
    toast('Enter your admin passcode above before erasing.', 'error');
    inp?.focus();
    return;
  }
  const ok = await unlockAdmin(actor(), pass);
  if (!ok) {
    toast('Wrong admin passcode.', 'error');
    inp?.focus();
    return;
  }
  wipeKey(ok);
  lockWorkstation('manual');
  await eraseDevice();
  location.reload();
}
app.addEventListener('pointerdown', (ev) => {
  const btn = (ev.target as HTMLElement).closest<HTMLElement>('[data-hold-erase]');
  if (!btn || ev.button !== 0) return;
  cancelHold();
  btn.classList.add('holding');
  holdTimer = setTimeout(() => {
    holdTimer = null;
    cancelHold();
    void tryEraseWithPasscode();
  }, 1500);
});
['pointerup', 'pointercancel', 'pointerleave'].forEach((t) => app.addEventListener(t, cancelHold, true));

// Keyboard activation on the clickable QR card & hold-erase fallback
app.addEventListener('keydown', (ev) => {
  if ((ev.key === 'Enter' || ev.key === ' ') && (ev.target as HTMLElement).classList.contains('qr-card')) {
    ev.preventDefault();
    if (st.shown) present(st.shown);
  }
  if (ev.key === 'Enter' && (ev.target as HTMLElement).hasAttribute('data-hold-erase')) {
    ev.preventDefault();
    if (confirm('Erase the clinic license, clinicians, patients, and records from this browser?')) {
      void tryEraseWithPasscode();
    }
  }
});

// ---------- camera check-in (drag-to-dismiss sheet) ----------

async function startScan() {
  const prev = document.activeElement as HTMLElement | null;
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
  modal.innerHTML = `<div class="card">
    <div class="sheet-handle" aria-hidden="true"></div>
    <div class="card-header" style="margin-bottom:8px">
      <h3 style="margin:0">Scan the patient’s check-in code</h3>
      <span class="tag tag-ok">Camera active</span>
    </div>
    <div class="viewfinder"><video playsinline muted></video><span></span></div>
    <p class="muted small">On the patient’s phone: <b>patnub › Scan</b>. Hold it about 20 cm from the camera.</p>
    <div class="actions"><button type="button" class="btn" data-stop>Cancel</button></div>
  </div>`;
  document.body.append(modal);
  const video = modal.querySelector('video')!;
  video.srcObject = stream;
  await video.play();
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
  let stopped = false;
  let cleanupDrag = () => {};
  const stop = () => {
    if (stopped) return;
    stopped = true;
    cleanupDrag();
    stream.getTracks().forEach((t) => t.stop());
    modal.remove();
    document.removeEventListener('keydown', onKey);
    prev?.focus();
  };
  cleanupDrag = attachDragDismiss({ sheet: modal.querySelector<HTMLElement>('.card')!, backdrop: modal, onDismiss: stop });
  const stopBtn = modal.querySelector<HTMLButtonElement>('[data-stop]')!;
  stopBtn.addEventListener('click', stop);
  modal.addEventListener('click', (e) => { if (e.target === modal) stop(); });
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') stop(); };
  document.addEventListener('keydown', onKey);
  stopBtn.focus();

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

// ---------- full-screen QR for the patient (drag or tap to dismiss) ----------

function present(r: ClinicRecord) {
  const prev = document.activeElement as HTMLElement | null;
  const el = document.createElement('div');
  el.className = 'present';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'true');
  el.setAttribute('aria-label', 'QR code for the patient');
  el.tabIndex = -1;
  el.innerHTML = `<button type="button" class="btn btn-sm present-close" aria-label="Close full-screen QR">${ico.close} Close</button>
    <div class="present-inner">
      <div class="sheet-handle" aria-hidden="true"></div>
      <div class="qr">${qrSvg(r.qr)}</div>
      <p><b>${esc(st.selected?.name ?? '')}</b> · patnub › Scan › Scan a record</p>
      <p class="muted small">Tap anywhere, drag down, or press Esc to close</p>
    </div>`;
  let closed = false;
  let cleanupDrag = () => {};
  const close = () => {
    if (closed) return;
    closed = true;
    cleanupDrag();
    el.remove();
    document.removeEventListener('keydown', onKey);
    prev?.focus();
  };
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
  el.addEventListener('click', () => {
    if (el.querySelector('.present-inner')?.hasAttribute('data-just-dragged')) return;
    close();
  });
  document.addEventListener('keydown', onKey);
  document.body.append(el);
  cleanupDrag = attachDragDismiss({ sheet: el.querySelector<HTMLElement>('.present-inner')!, backdrop: el, onDismiss: close });
  el.focus();
}

// Esc dismisses check-in confirm or profile modal; `/` focuses patient search.
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && st.pendingCheckIn) {
    st.pendingCheckIn = undefined;
    render();
    return;
  }
  if (e.key === 'Escape' && st.profileOpen) {
    closeProfileModal();
    return;
  }
  if (e.key === '/' && !e.metaKey && !e.ctrlKey && !e.altKey) {
    const tag = (document.activeElement as HTMLElement | null)?.tagName;
    if (tag !== 'INPUT' && tag !== 'TEXTAREA' && tag !== 'SELECT') {
      const search = app.querySelector<HTMLInputElement>('input[data-input="search"]');
      if (search) {
        e.preventDefault();
        search.focus();
      }
    }
  }
});

// ---------- idle lock and live countdowns ----------

let lastActivity = Date.now();
const touch = () => { lastActivity = Date.now(); };
['pointerdown', 'keydown'].forEach((t) => document.addEventListener(t, touch, { passive: true }));

setInterval(() => {
  if (st.adminKey && Date.now() - lastActivity > ADMIN_IDLE_MS) {
    lockAdmin();
    st.panel = 'patient';
    toast('Admin access locked after 2 minutes of inactivity.');
    render();
  }
  if (st.session && Date.now() - lastActivity > IDLE_LOCK_MS) {
    st.lockedFrom = st.session.staff.staff_id;
    lockWorkstation('idle');
    document.querySelector('.present')?.remove();
    render();
  }
  app.querySelectorAll<HTMLElement>('[data-countdown]').forEach((n) => {
    n.textContent = timeLeft(Number(n.dataset.countdown)) ?? 'expired';
  });
}, 10_000);

window.addEventListener('online', render);
window.addEventListener('offline', render);
render();
void refresh();

if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js');
}
