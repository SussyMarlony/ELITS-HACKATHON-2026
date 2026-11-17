// patnub.ai offline transfer contract, format version 1. See README.md for the byte layout.
// Shared by doctor-web (browser) and the scripts in this folder (Node 24+). WebCrypto only.
// The Android app implements the patient half (check-in encode, record open) in Kotlin and
// must pass vectors.json.

const te = new TextEncoder();
const td = new TextDecoder('utf-8', { fatal: true });
const subtle = globalThis.crypto.subtle;

export const RECORD_PREFIX = 'PNB1:';
export const CHECKIN_PREFIX = 'PNBC1:';
const FORMAT_VERSION = 1;
const HKDF_INFO = te.encode('patnub-record-v1');

export type ErrorCode =
  | 'FORMAT' // not a patnub code, or malformed
  | 'SIGNATURE' // a signature in the chain or on the record is wrong
  | 'CHAIN' // certificates do not belong together or were not valid at issue time
  | 'EXPIRED' // the record QR is past its expiry
  | 'NOT_FOR_THIS_PHONE'; // encrypted to a different patient key

export class ContractError extends Error {
  readonly code: ErrorCode;
  constructor(code: ErrorCode, message: string = code) {
    super(message);
    this.code = code;
  }
}

// ---------- bytes ----------

export function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

export function b64u(bytes: Uint8Array): string {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function unb64u(text: string): Uint8Array {
  const s = atob(text.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(s, (c) => c.charCodeAt(0));
}

// RFC 9285 Base45: fits the QR alphanumeric mode, about as dense as byte mode.
const B45 = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:';

export function base45Encode(bytes: Uint8Array): string {
  let out = '';
  for (let i = 0; i < bytes.length; i += 2) {
    if (i + 1 < bytes.length) {
      let n = bytes[i] * 256 + bytes[i + 1];
      out += B45[n % 45] + B45[Math.floor(n / 45) % 45] + B45[Math.floor(n / 2025)];
    } else {
      const n = bytes[i];
      out += B45[n % 45] + B45[Math.floor(n / 45)];
    }
  }
  return out;
}

export function base45Decode(text: string): Uint8Array {
  const v = Array.from(text, (c) => {
    const i = B45.indexOf(c);
    if (i < 0) throw new ContractError('FORMAT', 'bad base45 character');
    return i;
  });
  if (v.length % 3 === 1) throw new ContractError('FORMAT', 'bad base45 length');
  const out: number[] = [];
  for (let i = 0; i < v.length; i += 3) {
    if (i + 2 < v.length) {
      const n = v[i] + v[i + 1] * 45 + v[i + 2] * 2025;
      if (n > 0xffff) throw new ContractError('FORMAT', 'bad base45 value');
      out.push(n >> 8, n & 0xff);
    } else {
      const n = v[i] + v[i + 1] * 45;
      if (n > 0xff) throw new ContractError('FORMAT', 'bad base45 value');
      out.push(n);
    }
  }
  return Uint8Array.from(out);
}

async function deflate(data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await new Response(new Blob([data as BufferSource]).stream().pipeThrough(new CompressionStream('deflate-raw'))).arrayBuffer());
}

async function inflate(data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await new Response(new Blob([data as BufferSource]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).arrayBuffer());
}

// ---------- keys (raw 32-byte keys everywhere) ----------

export interface KeyPair {
  pub: Uint8Array;
  priv: Uint8Array;
}

async function generate(alg: 'Ed25519' | 'X25519'): Promise<KeyPair> {
  const usages: KeyUsage[] = alg === 'Ed25519' ? ['sign', 'verify'] : ['deriveBits'];
  const kp = (await subtle.generateKey({ name: alg }, true, usages)) as CryptoKeyPair;
  const jwk = await subtle.exportKey('jwk', kp.privateKey);
  return { pub: unb64u(jwk.x!), priv: unb64u(jwk.d!) };
}

export const generateSigningKey = () => generate('Ed25519');
export const generateEncryptionKey = () => generate('X25519');

function importPrivate(alg: 'Ed25519' | 'X25519', kp: KeyPair): Promise<CryptoKey> {
  const usages: KeyUsage[] = alg === 'Ed25519' ? ['sign'] : ['deriveBits'];
  return subtle.importKey('jwk', { kty: 'OKP', crv: alg, x: b64u(kp.pub), d: b64u(kp.priv) }, { name: alg }, false, usages);
}

export async function sign(signer: KeyPair, msg: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await subtle.sign('Ed25519', await importPrivate('Ed25519', signer), msg as BufferSource));
}

export async function verify(pub: Uint8Array, msg: Uint8Array, sig: Uint8Array): Promise<boolean> {
  const key = await subtle.importKey('raw', pub as BufferSource, 'Ed25519', false, ['verify']);
  return subtle.verify('Ed25519', key, sig as BufferSource, msg as BufferSource);
}

async function recordKey(own: KeyPair, peerPub: Uint8Array, salt: Uint8Array): Promise<CryptoKey> {
  const peer = await subtle.importKey('raw', peerPub as BufferSource, 'X25519', false, []);
  const shared = await subtle.deriveBits({ name: 'X25519', public: peer }, await importPrivate('X25519', own), 256);
  const ikm = await subtle.importKey('raw', shared, 'HKDF', false, ['deriveKey']);
  return subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: salt as BufferSource, info: HKDF_INFO as BufferSource },
    ikm,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

// ---------- certificates ----------

/** Signed by the root key. Valid for the clinic's subscription period. */
export interface LicenseBody {
  t: 'license';
  clinic_id: string;
  clinic_name: string;
  admin_pub: string; // b64url Ed25519
  nbf: number; // unix seconds
  exp: number;
}

/** Signed by the clinic admin key from the license. */
export interface StaffBody {
  t: 'staff';
  clinic_id: string;
  staff_id: string;
  name: string;
  profession: string; // MD, RN, RM, ...
  license_no: string;
  pub: string; // b64url Ed25519
  nbf: number;
  exp: number;
}

function u16(n: number): Uint8Array {
  if (n > 0xffff) throw new ContractError('FORMAT', 'field too long');
  return Uint8Array.of(n >> 8, n & 0xff);
}

/** cert = u16 body length | body (UTF-8 JSON) | Ed25519 signature over body (64). */
export async function signCert(body: LicenseBody | StaffBody, signer: KeyPair): Promise<Uint8Array> {
  const bytes = te.encode(JSON.stringify(body));
  return concat(u16(bytes.length), bytes, await sign(signer, bytes));
}

interface ParsedCert<T> {
  body: T;
  bodyBytes: Uint8Array;
  sig: Uint8Array;
  end: number;
}

function parseCert<T>(buf: Uint8Array, at: number): ParsedCert<T> {
  if (at + 2 > buf.length) throw new ContractError('FORMAT', 'truncated certificate');
  const len = (buf[at] << 8) | buf[at + 1];
  const end = at + 2 + len + 64;
  if (end > buf.length) throw new ContractError('FORMAT', 'truncated certificate');
  const bodyBytes = buf.subarray(at + 2, at + 2 + len);
  let body: T;
  try {
    body = JSON.parse(td.decode(bodyBytes)) as T;
  } catch {
    throw new ContractError('FORMAT', 'certificate body is not JSON');
  }
  return { body, bodyBytes, sig: buf.subarray(at + 2 + len, end), end };
}

// ---------- check-in code (patient phone -> clinic) ----------

export interface CheckIn {
  name: string;
  age: number;
  brgy?: string;
  pub: string; // b64url X25519
}

export function encodeCheckIn(c: CheckIn): string {
  return CHECKIN_PREFIX + base45Encode(te.encode(JSON.stringify(c)));
}

export function decodeCheckIn(text: string): CheckIn {
  if (!text.startsWith(CHECKIN_PREFIX)) throw new ContractError('FORMAT', 'not a patnub check-in code');
  let c: CheckIn;
  try {
    c = JSON.parse(td.decode(base45Decode(text.slice(CHECKIN_PREFIX.length))));
  } catch (e) {
    throw e instanceof ContractError ? e : new ContractError('FORMAT', 'check-in code is not JSON');
  }
  if (typeof c.name !== 'string' || typeof c.age !== 'number' || unb64uSafe(c.pub)?.length !== 32) {
    throw new ContractError('FORMAT', 'check-in code is missing fields');
  }
  return c;
}

function unb64uSafe(s: unknown): Uint8Array | null {
  try {
    return typeof s === 'string' ? unb64u(s) : null;
  } catch {
    return null;
  }
}

// ---------- record QR (clinic -> patient phone) ----------

export interface PrescribedMed {
  name: string;
  dose: string;
  freq: string;
}

/** The patient copy: approved structured fields only, never the raw clinician note. */
export interface PatientCopy {
  rid: string;
  rv: number;
  iat: number;
  exp: number;
  encounter_date: string; // YYYY-MM-DD
  assessment: string;
  meds: PrescribedMed[];
  instructions: string[];
  warnings: string[];
  follow_up: string;
}

export interface SealInput {
  licenseCert: Uint8Array;
  staffCert: Uint8Array;
  staffKey: KeyPair;
  patientPub: Uint8Array;
  record: PatientCopy;
  /** Test vectors only. Real calls must leave these out so they are random. */
  ephemeral?: KeyPair;
  nonce?: Uint8Array;
}

/**
 * payload = version (1) | license cert | staff cert | ephemeral X25519 pub (32) | nonce (12)
 *           | AES-256-GCM(deflate(JSON record)) with the header as AAD | Ed25519 sig (64) over all before it.
 * QR text = "PNB1:" + base45(payload).
 */
export async function sealRecord(input: SealInput): Promise<string> {
  const eph = input.ephemeral ?? (await generateEncryptionKey());
  const nonce = input.nonce ?? crypto.getRandomValues(new Uint8Array(12));
  const header = concat(Uint8Array.of(FORMAT_VERSION), input.licenseCert, input.staffCert, eph.pub, nonce);
  const key = await recordKey(eph, input.patientPub, concat(eph.pub, input.patientPub));
  const plain = await deflate(te.encode(JSON.stringify(input.record)));
  const ct = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv: nonce as BufferSource, additionalData: header as BufferSource }, key, plain as BufferSource));
  const signed = concat(header, ct);
  return RECORD_PREFIX + base45Encode(concat(signed, await sign(input.staffKey, signed)));
}

export interface OpenedRecord {
  record: PatientCopy;
  license: LicenseBody;
  staff: StaffBody;
}

/** Verify root -> license -> staff -> record, then decrypt. Verifies before decrypting. */
export async function openRecord(text: string, rootPub: Uint8Array, patient: KeyPair, now: number): Promise<OpenedRecord> {
  if (!text.startsWith(RECORD_PREFIX)) throw new ContractError('FORMAT', 'not a patnub record code');
  const buf = base45Decode(text.slice(RECORD_PREFIX.length));
  if (buf[0] !== FORMAT_VERSION) throw new ContractError('FORMAT', `unsupported format version ${buf[0]}`);

  const lic = parseCert<LicenseBody>(buf, 1);
  const staff = parseCert<StaffBody>(buf, lic.end);
  const headerEnd = staff.end + 32 + 12;
  if (buf.length < headerEnd + 16 + 64) throw new ContractError('FORMAT', 'truncated record');
  const sigAt = buf.length - 64;

  if (lic.body.t !== 'license' || staff.body.t !== 'staff') throw new ContractError('FORMAT', 'wrong certificate type');
  if (!(await verify(rootPub, lic.bodyBytes, lic.sig))) throw new ContractError('SIGNATURE', 'license not signed by root');
  if (!(await verify(unb64u(lic.body.admin_pub), staff.bodyBytes, staff.sig))) throw new ContractError('SIGNATURE', 'staff not signed by clinic admin');
  if (staff.body.clinic_id !== lic.body.clinic_id) throw new ContractError('CHAIN', 'staff belongs to another clinic');
  if (!(await verify(unb64u(staff.body.pub), buf.subarray(0, sigAt), buf.subarray(sigAt)))) {
    throw new ContractError('SIGNATURE', 'record not signed by staff');
  }

  const ephPub = buf.subarray(staff.end, staff.end + 32);
  const nonce = buf.subarray(staff.end + 32, headerEnd);
  let record: PatientCopy;
  try {
    const key = await recordKey(patient, ephPub, concat(ephPub, patient.pub));
    const plain = await subtle.decrypt({ name: 'AES-GCM', iv: nonce as BufferSource, additionalData: buf.subarray(0, headerEnd) as BufferSource }, key, buf.subarray(headerEnd, sigAt) as BufferSource);
    record = JSON.parse(td.decode(await inflate(new Uint8Array(plain))));
  } catch {
    // The signature already passed, so a failure here means another patient's key.
    throw new ContractError('NOT_FOR_THIS_PHONE');
  }

  // Validity is judged at issue time so patients keep records after a clinic's license lapses (spec §8.1).
  const within = (c: { nbf: number; exp: number }) => record.iat >= c.nbf && record.iat <= c.exp;
  if (!within(lic.body) || !within(staff.body)) throw new ContractError('CHAIN', 'certificate not valid at issue time');
  if (now > record.exp) throw new ContractError('EXPIRED');
  return { record, license: lic.body, staff: staff.body };
}
