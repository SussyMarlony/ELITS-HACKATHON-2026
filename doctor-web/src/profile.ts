// Pure clinician profile & passcode updates. Keeps cryptographic cert signing and key wrapping testable in Node.
import { b64u, signCert, type KeyPair, type StaffBody } from '../../contract/contract.ts';
import { MIN_PASSCODE, unwrapSecret, wrapSecret } from './passcode.ts';
import type { Staff } from './store.ts';

export interface ProfilePatch {
  name: string;
  profession: string;
  license_no: string;
}

export function checkProfileFields(p: ProfilePatch): string | null {
  if (!p.name.trim()) return 'Enter the clinician’s name.';
  if (!p.profession.trim()) return 'Enter the profession, for example MD, RN or RM.';
  if (!p.license_no.trim()) return 'Enter the PRC license number.';
  return null;
}

/** Updates certified clinician identity fields and re-signs the staff certificate with the clinic admin key. */
export async function resignStaffProfile(staff: Staff, patch: ProfilePatch, adminKey: KeyPair): Promise<Staff> {
  const err = checkProfileFields(patch);
  if (err) throw new Error(err);
  const body: StaffBody = {
    ...staff.body,
    name: patch.name.trim(),
    profession: patch.profession.trim().toUpperCase(),
    license_no: patch.license_no.trim(),
  };
  return {
    ...staff,
    body,
    cert: b64u(await signCert(body, adminKey)),
  };
}

/** Re-wraps the clinician's signing key (and admin key if held) under a new passcode without altering their cert. */
export async function rekeyStaffPasscode(
  staff: Staff,
  currentPasscode: string,
  newPasscode: string,
  confirm: string,
): Promise<Staff> {
  if (!currentPasscode) throw new Error('Enter your current passcode.');
  if (newPasscode.length < MIN_PASSCODE) throw new Error(`The new passcode needs at least ${MIN_PASSCODE} characters.`);
  if (newPasscode !== confirm) throw new Error('The new passcodes do not match.');
  if (newPasscode === currentPasscode) throw new Error('Choose a passcode different from your current one.');

  let rawKey: Uint8Array | null = null;
  let rawAdmin: Uint8Array | null = null;
  try {
    rawKey = await unwrapSecret(staff.key, currentPasscode);
    if (!rawKey) throw new Error('Current passcode is incorrect.');
    if (staff.is_admin && staff.admin_key) {
      rawAdmin = await unwrapSecret(staff.admin_key, currentPasscode);
      if (!rawAdmin) throw new Error('Current passcode is incorrect.');
    }
    return {
      ...staff,
      key: await wrapSecret(rawKey, newPasscode),
      admin_key: rawAdmin ? await wrapSecret(rawAdmin, newPasscode) : staff.admin_key,
    };
  } finally {
    rawKey?.fill(0);
    rawAdmin?.fill(0);
  }
}
