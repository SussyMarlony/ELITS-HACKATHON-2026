// Clinic actions: license, clinicians, patients, issuing. No DOM here.
import {
  b64u, ContractError, decodeCheckIn, generateSigningKey, sealRecord, signCert, unb64u, verify,
  type KeyPair, type LicenseBody, type PatientCopy, type StaffBody,
} from '../../contract/contract.ts';
import rootPubText from '../../contract/demo/root_pub.txt?raw';
import type { Draft } from './draft.ts';
import { patientCopy, RECORD_TTL_S } from './issue.ts';
import { MIN_PASSCODE, unwrapSecret, wrapSecret } from './passcode.ts';
import { all, audit, byPatient, dumpAll, get, put, restoreAll, type AuditEvent, type ClinicRecord, type License, type Patient, type Staff } from './store.ts';

const ROOT_PUB = unb64u(rootPubText.trim());
const now = () => Math.floor(Date.now() / 1000);

export interface Session {
  staff: Staff;
  key: KeyPair;
  admin?: KeyPair; // present when the clinician is an admin
}

// ---------- license ----------

export const getLicense = () => get<License>('kv', 'license');

export function licenseActive(l: License, t = now()): boolean {
  return t >= l.body.nbf && t <= l.body.exp;
}

/** Validates a license file and stores it. Returns the admin key it carries, for the first account. */
export async function activateLicense(fileText: string): Promise<{ license: License; admin: KeyPair }> {
  let file: { format?: string; license_cert?: string; admin?: { pub: string; priv: string } };
  try {
    file = JSON.parse(fileText);
  } catch {
    throw new Error('This is not a patnub license file.');
  }
  if (file.format !== 'patnub-license-1' || !file.license_cert || !file.admin) throw new Error('This is not a patnub license file.');
  const cert = unb64u(file.license_cert);
  const len = (cert[0] << 8) | cert[1];
  const bodyBytes = cert.subarray(2, 2 + len);
  if (!(await verify(ROOT_PUB, bodyBytes, cert.subarray(2 + len, 2 + len + 64)))) throw new Error('This license was not issued by patnub.');
  const body = JSON.parse(new TextDecoder().decode(bodyBytes)) as LicenseBody;
  if (body.t !== 'license' || body.admin_pub !== file.admin.pub) throw new Error('This license file is damaged.');
  const license: License = { id: 'license', cert: file.license_cert, body, admin_pub: body.admin_pub };
  await put('kv', license);
  await audit('system', 'LICENSE_ACTIVATED', body.clinic_id, `valid until ${new Date(body.exp * 1000).toISOString()}`);
  return { license, admin: { pub: unb64u(file.admin.pub), priv: unb64u(file.admin.priv) } };
}

// ---------- clinicians ----------

export interface NewClinician {
  name: string;
  profession: string;
  license_no: string;
  passcode: string;
  is_admin: boolean;
}

export function checkClinician(c: NewClinician, confirm: string): string | null {
  if (!c.name.trim()) return 'Enter the clinician’s name.';
  if (!c.profession.trim()) return 'Enter the profession, for example MD, RN or RM.';
  if (!c.license_no.trim()) return 'Enter the PRC license number.';
  if (c.passcode.length < MIN_PASSCODE) return `The passcode needs at least ${MIN_PASSCODE} characters.`;
  if (c.passcode !== confirm) return 'The passcodes do not match.';
  return null;
}

export async function createClinician(c: NewClinician, adminKey: KeyPair, license: License, actor = 'system'): Promise<Staff> {
  const key = await generateSigningKey();
  const body: StaffBody = {
    t: 'staff',
    clinic_id: license.body.clinic_id,
    staff_id: crypto.randomUUID(),
    name: c.name.trim(),
    profession: c.profession.trim().toUpperCase(),
    license_no: c.license_no.trim(),
    pub: b64u(key.pub),
    nbf: now() - 60,
    exp: license.body.exp,
  };
  const staff: Staff = {
    staff_id: body.staff_id,
    body,
    cert: b64u(await signCert(body, adminKey)),
    is_admin: c.is_admin,
    disabled: false,
    key: await wrapSecret(concatKey(key), c.passcode),
    admin_key: c.is_admin ? await wrapSecret(concatKey(adminKey), c.passcode) : undefined,
  };
  await put('staff', staff);
  await audit(actor, 'CLINICIAN_CREATED', staff.staff_id, `${body.name}, ${body.profession}${c.is_admin ? ', admin' : ''}`);
  return staff;
}

// Wrapped key blobs hold priv (32) | pub (32).
const concatKey = (k: KeyPair) => Uint8Array.from([...k.priv, ...k.pub]);
const splitKey = (b: Uint8Array): KeyPair => ({ priv: b.slice(0, 32), pub: b.slice(32) });

export const listStaff = () => all<Staff>('staff');

export async function setDisabled(staff: Staff, disabled: boolean, actor: string) {
  await put('staff', { ...staff, disabled });
  await audit(actor, disabled ? 'CLINICIAN_DISABLED' : 'CLINICIAN_ENABLED', staff.staff_id);
}

export async function signIn(staffId: string, passcode: string): Promise<Session | null> {
  const staff = await get<Staff>('staff', staffId);
  if (!staff || staff.disabled) return null;
  const key = await unwrapSecret(staff.key, passcode);
  if (!key) {
    await audit(staffId, 'SIGN_IN_FAILED', staffId);
    return null;
  }
  const admin = staff.admin_key ? await unwrapSecret(staff.admin_key, passcode) : null;
  await audit(staffId, 'SIGNED_IN', staffId);
  return { staff, key: splitKey(key), admin: admin ? splitKey(admin) : undefined };
}

// ---------- patients ----------

export type CheckInResult =
  | { kind: 'known'; patient: Patient }
  | { kind: 'new'; patient: Patient }
  | { kind: 'new-phone'; existing: Patient; candidate: Patient };

/** Reads a check-in code. Does not save; the caller confirms first. */
export async function readCheckIn(text: string): Promise<CheckInResult> {
  let c;
  try {
    c = decodeCheckIn(text.trim());
  } catch (e) {
    throw new Error(e instanceof ContractError ? 'That is not a patnub check-in code.' : String(e));
  }
  const patients = await all<Patient>('patients');
  const known = patients.find((p) => p.pub === c.pub);
  if (known) return { kind: 'known', patient: known };
  const candidate: Patient = { id: crypto.randomUUID(), name: c.name, age: c.age, brgy: c.brgy, pub: c.pub, created_at: Date.now() };
  const sameName = patients.find((p) => p.name.toLowerCase() === c.name.toLowerCase() && Math.abs(p.age - c.age) <= 1);
  return sameName ? { kind: 'new-phone', existing: sameName, candidate } : { kind: 'new', patient: candidate };
}

export async function savePatient(p: Patient, actor: string, action = 'PATIENT_CHECKED_IN') {
  await put('patients', p);
  await audit(actor, action, p.id);
}

export const listPatients = async () => (await all<Patient>('patients')).sort((a, b) => b.created_at - a.created_at);
export const recordsFor = async (patientId: string) => (await byPatient(patientId)).sort((a, b) => b.created_at - a.created_at);
export const listAllRecords = async () => (await all<ClinicRecord>('records')).sort((a, b) => b.created_at - a.created_at);
export const listAudit = async (limit = 12) => (await all<AuditEvent>('audit')).sort((a, b) => b.at - a.at).slice(0, limit);

// ---------- issuing ----------

export interface IssueInput {
  patient: Patient;
  note: string;
  draft: Draft;
  encounter_date: string;
  approved: boolean;
  /** Set when correcting an issued record: same rid, next version. */
  previous?: ClinicRecord;
}

export async function issue(s: Session, license: License, input: IssueInput): Promise<ClinicRecord> {
  if (!licenseActive(license)) throw new Error('The clinic license has expired. Renew it to issue records.');
  const copy = patientCopy(input, now(), () => crypto.randomUUID());
  const record: ClinicRecord = {
    key: `${copy.rid}:${copy.rv}`,
    rid: copy.rid,
    rv: copy.rv,
    patient_id: input.patient.id,
    staff_id: s.staff.staff_id,
    raw_note: input.note,
    copy,
    status: 'ISSUED',
    qr: await seal(s, license, copy, input.patient.pub),
    created_at: Date.now(),
  };
  await put('records', record);
  if (input.previous) await put('records', { ...input.previous, status: 'SUPERSEDED' });
  await audit(s.staff.staff_id, input.previous ? 'RECORD_CORRECTED' : 'RECORD_ISSUED', record.key, `patient ${input.patient.id}`);
  return record;
}

/** New QR for an existing record version: after the 24 h window, or to the patient's new phone. */
export async function reissue(s: Session, license: License, record: ClinicRecord, patient: Patient): Promise<ClinicRecord> {
  if (!licenseActive(license)) throw new Error('The clinic license has expired. Renew it to issue records.');
  const t = now();
  const copy = { ...record.copy, iat: t, exp: t + RECORD_TTL_S };
  const updated = { ...record, copy, qr: await seal(s, license, copy, patient.pub) };
  await put('records', updated);
  await audit(s.staff.staff_id, 'RECORD_REISSUED', record.key, `patient ${patient.id}`);
  return updated;
}

function seal(s: Session, license: License, copy: PatientCopy, patientPub: string) {
  return sealRecord({
    licenseCert: unb64u(license.cert),
    staffCert: unb64u(s.staff.cert),
    staffKey: s.key,
    patientPub: unb64u(patientPub),
    record: copy,
  });
}

/** Demo reset: wipes this device's clinic data. The page reloads into license activation. */
export async function eraseDevice(): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const req = indexedDB.deleteDatabase('patnub-clinic');
    req.onsuccess = () => resolve();
    req.onerror = () => reject(req.error);
    req.onblocked = () => resolve(); // other tabs hold it open; deletion finishes when they close
  });
}

export const qrValid = (r: ClinicRecord) => r.status === 'ISSUED' && now() <= r.copy.exp;

// ---------- backup (spec §7.5: encrypted export to USB) ----------

export async function exportBackup(passcode: string): Promise<Blob> {
  const data = new TextEncoder().encode(JSON.stringify(await dumpAll()));
  const wrapped = await wrapSecret(data, passcode);
  await audit('admin', 'BACKUP_EXPORTED', 'clinic');
  return new Blob([JSON.stringify({ format: 'patnub-clinic-backup-1', created_at: new Date().toISOString(), wrapped })], { type: 'application/json' });
}

export async function importBackup(fileText: string, passcode: string): Promise<void> {
  let file;
  try {
    file = JSON.parse(fileText);
  } catch {
    throw new Error('This is not a patnub backup file.');
  }
  if (file?.format !== 'patnub-clinic-backup-1') throw new Error('This is not a patnub backup file.');
  const data = await unwrapSecret(file.wrapped, passcode);
  if (!data) throw new Error('Wrong passcode for this backup.');
  await restoreAll(JSON.parse(new TextDecoder().decode(data)));
  await audit('admin', 'BACKUP_RESTORED', 'clinic');
}
