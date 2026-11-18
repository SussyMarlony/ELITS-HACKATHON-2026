import assert from 'node:assert/strict';
import { test } from 'node:test';
import { b64u, generateSigningKey, signCert, unb64u, verify, type StaffBody } from '../../contract/contract.ts';
import { unwrapSecret, wrapSecret } from './passcode.ts';
import { rekeyStaffPasscode, resignStaffProfile } from './profile.ts';
import type { Staff } from './store.ts';

async function makeFixture(): Promise<{ adminKey: Awaited<ReturnType<typeof generateSigningKey>>; staff: Staff }> {
  const adminKey = await generateSigningKey();
  const staffKey = await generateSigningKey();
  const body: StaffBody = {
    t: 'staff',
    clinic_id: 'clinic-1',
    staff_id: 'staff-1',
    name: 'Dr. Maria Santos',
    profession: 'MD',
    license_no: '0123456',
    pub: b64u(staffKey.pub),
    nbf: 1000,
    exp: 9999999999,
  };
  const staff: Staff = {
    staff_id: body.staff_id,
    body,
    cert: b64u(await signCert(body, adminKey)),
    is_admin: true,
    disabled: false,
    key: await wrapSecret(Uint8Array.from([...staffKey.priv, ...staffKey.pub]), 'oldpass1'),
    admin_key: await wrapSecret(Uint8Array.from([...adminKey.priv, ...adminKey.pub]), 'oldpass1'),
  };
  return { adminKey, staff };
}

test('resignStaffProfile updates identity fields and produces a valid admin-signed cert', async () => {
  const { adminKey, staff } = await makeFixture();
  const updated = await resignStaffProfile(
    staff,
    { name: '  Dr. Maria Reyes ', profession: 'md', license_no: ' 0999888 ' },
    adminKey,
  );
  assert.equal(updated.body.name, 'Dr. Maria Reyes');
  assert.equal(updated.body.profession, 'MD');
  assert.equal(updated.body.license_no, '0999888');
  assert.equal(updated.body.pub, staff.body.pub);

  const certBytes = unb64u(updated.cert);
  const len = (certBytes[0] << 8) | certBytes[1];
  const bodyBytes = certBytes.subarray(2, 2 + len);
  const sig = certBytes.subarray(2 + len, 2 + len + 64);
  assert.equal(await verify(adminKey.pub, bodyBytes, sig), true);
  assert.equal(JSON.parse(new TextDecoder().decode(bodyBytes)).name, 'Dr. Maria Reyes');
});

test('resignStaffProfile rejects blank fields', async () => {
  const { adminKey, staff } = await makeFixture();
  await assert.rejects(
    () => resignStaffProfile(staff, { name: ' ', profession: 'MD', license_no: '123' }, adminKey),
    /Enter the clinician/,
  );
});

test('rekeyStaffPasscode verifies current passcode and re-wraps both keys under the new passcode', async () => {
  const { staff } = await makeFixture();
  await assert.rejects(
    () => rekeyStaffPasscode(staff, 'wrongpass', 'newpass1', 'newpass1'),
    /Current passcode is incorrect/,
  );
  await assert.rejects(
    () => rekeyStaffPasscode(staff, 'oldpass1', 'short', 'short'),
    /at least 6/,
  );
  await assert.rejects(
    () => rekeyStaffPasscode(staff, 'oldpass1', 'newpass1', 'mismatch'),
    /do not match/,
  );

  const updated = await rekeyStaffPasscode(staff, 'oldpass1', 'newpass1', 'newpass1');
  assert.equal(updated.cert, staff.cert);
  assert.equal(await unwrapSecret(updated.key, 'oldpass1'), null);
  assert.ok(await unwrapSecret(updated.key, 'newpass1'));
  assert.ok(await unwrapSecret(updated.admin_key!, 'newpass1'));
});
