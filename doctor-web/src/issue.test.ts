import assert from 'node:assert/strict';
import { test } from 'node:test';
import { patientCopy } from './issue.ts';

const draft = { assessment: 'Stage 1 hypertension', meds: [], instructions: [], warnings: [], follow_up: '' };

test('unapproved draft cannot become a record (spec §12 check 4)', () => {
  assert.throws(() => patientCopy({ draft, encounter_date: '2026-11-18', approved: false }, 1000, () => 'r1'), /approve/);
});

test('approved draft: new id, version 1, 24 h window, only structured fields', () => {
  const c = patientCopy({ draft: { ...draft, raw_note: 'secret' } as never, encounter_date: '2026-11-18', approved: true }, 1000, () => 'r1');
  assert.deepEqual(c, { rid: 'r1', rv: 1, iat: 1000, exp: 1000 + 86400, encounter_date: '2026-11-18', ...draft });
});

test('correction keeps the record id and bumps the version', () => {
  const c = patientCopy({ draft, encounter_date: '2026-11-18', approved: true, previous: { rid: 'r1', rv: 2 } }, 1000, () => 'new');
  assert.equal(c.rid, 'r1');
  assert.equal(c.rv, 3);
});
