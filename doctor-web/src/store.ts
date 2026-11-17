// IndexedDB storage for the clinic device (spec v1.2: one shared device, no sync).
import type { LicenseBody, PatientCopy, StaffBody } from '../../contract/contract.ts';

/** A secret encrypted with a passcode-derived key (PBKDF2 -> AES-GCM). All fields base64url. */
export interface Wrapped {
  salt: string;
  iv: string;
  ct: string;
}

export interface License {
  id: 'license';
  cert: string; // b64url license certificate
  body: LicenseBody;
  admin_pub: string;
}

export interface Staff {
  staff_id: string;
  body: StaffBody;
  cert: string; // b64url staff certificate
  is_admin: boolean;
  disabled: boolean;
  key: Wrapped; // Ed25519 private key, wrapped with this clinician's passcode
  admin_key?: Wrapped; // clinic admin private key, wrapped with the same passcode
}

export interface Patient {
  id: string;
  name: string;
  age: number;
  brgy?: string;
  pub: string; // current phone's X25519 key
  created_at: number;
}

export interface ClinicRecord {
  key: string; // `${rid}:${rv}`
  rid: string;
  rv: number;
  patient_id: string;
  staff_id: string;
  raw_note: string; // verbatim; never leaves this device (spec §4.5)
  copy: PatientCopy;
  status: 'ISSUED' | 'SUPERSEDED';
  qr: string;
  created_at: number;
}

export interface AuditEvent {
  id?: number;
  at: number;
  actor: string;
  action: string;
  resource: string;
  details?: string;
}

export const STORES = ['kv', 'staff', 'patients', 'records', 'audit'] as const;
export type StoreName = (typeof STORES)[number];

let dbp: Promise<IDBDatabase> | null = null;

function db(): Promise<IDBDatabase> {
  dbp ??= new Promise((resolve, reject) => {
    const req = indexedDB.open('patnub-clinic', 1);
    req.onupgradeneeded = () => {
      const d = req.result;
      d.createObjectStore('kv', { keyPath: 'id' });
      d.createObjectStore('staff', { keyPath: 'staff_id' });
      d.createObjectStore('patients', { keyPath: 'id' });
      d.createObjectStore('records', { keyPath: 'key' }).createIndex('patient_id', 'patient_id');
      d.createObjectStore('audit', { keyPath: 'id', autoIncrement: true });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbp;
}

function wrap<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function os(name: StoreName, mode: IDBTransactionMode = 'readonly') {
  return (await db()).transaction(name, mode).objectStore(name);
}

export const get = async <T>(name: StoreName, key: IDBValidKey) => wrap((await os(name)).get(key)) as Promise<T | undefined>;
export const all = async <T>(name: StoreName) => wrap((await os(name)).getAll()) as Promise<T[]>;
export const put = async (name: StoreName, value: unknown) => { await wrap((await os(name, 'readwrite')).put(value)); };
export const byPatient = async (patientId: string) =>
  wrap((await os('records')).index('patient_id').getAll(patientId)) as Promise<ClinicRecord[]>;

export async function audit(actor: string, action: string, resource: string, details?: string) {
  await put('audit', { at: Date.now(), actor, action, resource, details } satisfies AuditEvent);
}

/** Whole database as plain objects, for the encrypted backup. */
export async function dumpAll(): Promise<Record<StoreName, unknown[]>> {
  const out = {} as Record<StoreName, unknown[]>;
  for (const s of STORES) out[s] = await all(s);
  return out;
}

/** Replaces everything with a backup. One transaction: all or nothing. */
export async function restoreAll(data: Record<StoreName, unknown[]>): Promise<void> {
  const tx = (await db()).transaction([...STORES], 'readwrite');
  for (const s of STORES) {
    const store = tx.objectStore(s);
    store.clear();
    for (const v of data[s] ?? []) store.put(v);
  }
  await new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}
