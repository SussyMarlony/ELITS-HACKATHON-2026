// Signs a demo clinic license with the demo root key.
// Usage: node scripts/issue-license.ts <clinic_id> "<clinic name>" [days=365] > license.json
// shortcut: the clinic admin key is generated here and shipped inside the license file;
// a real deployment generates it on the clinic device and only the public key is sent for signing.
import { b64u, generateSigningKey, signCert } from '../contract.ts';
import { DAY, demoRoot, keyToJson, now } from './lib.ts';

const [clinicId, clinicName, days = '365'] = process.argv.slice(2);
if (!clinicId || !clinicName) {
  console.error('Usage: node scripts/issue-license.ts <clinic_id> "<clinic name>" [days=365]');
  process.exit(1);
}
const admin = await generateSigningKey();
const start = now();
const cert = await signCert(
  { t: 'license', clinic_id: clinicId, clinic_name: clinicName, admin_pub: b64u(admin.pub), nbf: start, exp: start + Number(days) * DAY },
  demoRoot(),
);
console.log(JSON.stringify({ format: 'patnub-license-1', license_cert: b64u(cert), admin: keyToJson(admin) }, null, 2));
