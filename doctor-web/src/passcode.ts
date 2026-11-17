// Passcode-wrapped secrets. PBKDF2-SHA256 (600k iterations, OWASP 2023) -> AES-256-GCM.
import { b64u, unb64u } from '../../contract/contract.ts';
import type { Wrapped } from './store.ts';

const ITERATIONS = 600_000;
export const MIN_PASSCODE = 6;

async function keyFrom(passcode: string, salt: Uint8Array): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey('raw', new TextEncoder().encode(passcode), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', hash: 'SHA-256', salt: salt as BufferSource, iterations: ITERATIONS },
    base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'],
  );
}

export async function wrapSecret(secret: Uint8Array, passcode: string): Promise<Wrapped> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await keyFrom(passcode, salt), secret as BufferSource);
  return { salt: b64u(salt), iv: b64u(iv), ct: b64u(new Uint8Array(ct)) };
}

/** Returns null when the passcode is wrong. */
export async function unwrapSecret(w: Wrapped, passcode: string): Promise<Uint8Array | null> {
  try {
    const key = await keyFrom(passcode, unb64u(w.salt));
    return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64u(w.iv) as BufferSource }, key, unb64u(w.ct) as BufferSource));
  } catch {
    return null;
  }
}
