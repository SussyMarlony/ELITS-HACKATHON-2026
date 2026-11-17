import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  base45Decode, base45Encode, ContractError, decodeCheckIn, encodeCheckIn, openRecord, RECORD_PREFIX, unb64u,
} from './contract.ts';
import { makeFixture } from './scripts/fixtures.ts';
import { keyFromJson } from './scripts/lib.ts';

const T = 1_795_000_000;
const code = (e: unknown) => (e as ContractError).code;

test('base45 matches RFC 9285 examples', () => {
  const te = new TextEncoder();
  assert.equal(base45Encode(te.encode('AB')), 'BB8');
  assert.equal(base45Encode(te.encode('Hello!!')), '%69 VD92EX0');
  assert.equal(new TextDecoder().decode(base45Decode('UJCLQE7W581')), 'base-45');
  assert.throws(() => base45Decode('GGW'), (e) => code(e) === 'FORMAT'); // 65535+ overflow
});

test('record round-trips for the intended phone', async () => {
  const f = await makeFixture({ iat: T });
  const out = await openRecord(f.qr, f.root.pub, f.patient, T + 60);
  assert.deepEqual(out.record, f.record);
  assert.equal(out.staff.name, 'Ana Reyes');
  assert.match(f.qr, /^PNB1:[0-9A-Z $%*+\-./:]+$/); // QR alphanumeric mode
  console.log(`record QR: ${f.qr.length} chars`);
});

test('another phone cannot open it', async () => {
  const f = await makeFixture({ iat: T });
  const g = await makeFixture({ iat: T });
  await assert.rejects(openRecord(f.qr, f.root.pub, g.patient, T + 60), (e) => code(e) === 'NOT_FOR_THIS_PHONE');
});

test('wrong root, tampering and expiry are rejected', async () => {
  const f = await makeFixture({ iat: T });
  const other = await makeFixture({ iat: T });
  await assert.rejects(openRecord(f.qr, other.root.pub, f.patient, T + 60), (e) => code(e) === 'SIGNATURE');

  const bytes = base45Decode(f.qr.slice(RECORD_PREFIX.length));
  bytes[bytes.length - 80] ^= 1; // inside the ciphertext
  await assert.rejects(openRecord(RECORD_PREFIX + base45Encode(bytes), f.root.pub, f.patient, T + 60), (e) => code(e) === 'SIGNATURE');

  await assert.rejects(openRecord(f.qr, f.root.pub, f.patient, T + 2 * 86_400), (e) => code(e) === 'EXPIRED');
  await assert.rejects(openRecord('hello', f.root.pub, f.patient, T), (e) => code(e) === 'FORMAT');
});

test('issued after the license expired is rejected; issued before stays valid', async () => {
  const lapsed = await makeFixture({ iat: T, licenseExp: T - 1 });
  await assert.rejects(openRecord(lapsed.qr, lapsed.root.pub, lapsed.patient, T + 60), (e) => code(e) === 'CHAIN');
  const ok = await makeFixture({ iat: T, licenseExp: T + 10 });
  await openRecord(ok.qr, ok.root.pub, ok.patient, T + 60); // scanned after license end: still fine
});

test('check-in code round-trips and rejects junk', () => {
  const c = { name: 'Maria Dela Cruz', age: 54, brgy: 'Brgy. Malinis', pub: 'A'.repeat(43) };
  assert.deepEqual(decodeCheckIn(encodeCheckIn(c)), c);
  assert.throws(() => decodeCheckIn('PNB1:XYZ'), (e) => code(e) === 'FORMAT');
});

test('vectors.json opens with this implementation', async () => {
  const v = JSON.parse(readFileSync(new URL('./vectors.json', import.meta.url), 'utf8'));
  const out = await openRecord(v.record_qr, unb64u(v.root_pub), keyFromJson(v.patient), v.now);
  assert.deepEqual(out.record, v.expected_record);
  await assert.rejects(openRecord(v.record_qr, unb64u(v.root_pub), keyFromJson(v.other_patient), v.now), (e) => code(e) === 'NOT_FOR_THIS_PHONE');
  assert.equal(encodeCheckIn(v.check_in), v.check_in_text);
});
