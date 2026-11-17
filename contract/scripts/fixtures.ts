// Builds a full clinic chain and a sealed record, for tests and vectors.
import { b64u, generateEncryptionKey, generateSigningKey, sealRecord, signCert, type KeyPair, type PatientCopy } from '../contract.ts';

export async function makeFixture(opts: { iat: number; licenseExp?: number; root?: KeyPair }) {
  const root = opts.root ?? (await generateSigningKey());
  const admin = await generateSigningKey();
  const staffKey = await generateSigningKey();
  const patient = await generateEncryptionKey();
  const t = opts.iat;
  const licenseCert = await signCert(
    { t: 'license', clinic_id: 'rhu-san-isidro', clinic_name: 'RHU San Isidro', admin_pub: b64u(admin.pub), nbf: t - 100, exp: opts.licenseExp ?? t + 365 * 86_400 },
    root,
  );
  const staffCert = await signCert(
    { t: 'staff', clinic_id: 'rhu-san-isidro', staff_id: 's1', name: 'Ana Reyes', profession: 'MD', license_no: '0123456', pub: b64u(staffKey.pub), nbf: t - 100, exp: t + 365 * 86_400 },
    admin,
  );
  const record: PatientCopy = {
    rid: 'r-0001', rv: 1, iat: t, exp: t + 86_400,
    encounter_date: '2026-11-18',
    assessment: 'Stage 1 hypertension',
    meds: [{ name: 'Amlodipine', dose: '5 mg', freq: 'once daily, in the morning' }],
    instructions: ['Reduce salt intake (instant noodles, bagoong)', 'Walk 30 minutes daily'],
    warnings: ['Severe headache', 'Chest pain', 'Blurred vision', 'Weakness on one side'],
    follow_up: 'Follow up in 4 weeks with BP log',
  };
  const qr = await sealRecord({ licenseCert, staffCert, staffKey, patientPub: patient.pub, record });
  return { root, admin, staffKey, patient, licenseCert, staffCert, record, qr };
}
