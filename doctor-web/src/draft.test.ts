import assert from 'node:assert/strict';
import { test } from 'node:test';
import { draftFromNote } from './draft.ts';

const maria = 'Patient seen for recurring headaches for 2 weeks with BP 150/95 on two readings. Assessment: Stage 1 hypertension. Start Amlodipine 5 mg once daily in the morning. Reduce salt intake, especially instant noodles and bagoong. Walk 30 minutes a day. Follow up in 4 weeks with the BP log. Seek emergency care if severe headache, chest pain, blurred vision, or weakness on one side.';

test('structures the demo hypertension note', () => {
  const d = draftFromNote(maria);
  assert.equal(d.assessment, 'Stage 1 hypertension');
  assert.deepEqual(d.meds, [{ name: 'Amlodipine', dose: '5 mg', freq: 'once daily, in the morning' }]);
  assert.equal(d.follow_up, 'Follow up in 4 weeks with the BP log');
  assert.deepEqual(d.warnings, ['severe headache', 'chest pain', 'blurred vision', 'weakness on one side']);
  assert.deepEqual(d.instructions, ['Reduce salt intake, especially instant noodles and bagoong', 'Walk 30 minutes a day']);
});

test('Taglish note keeps unknown sentences as instructions', () => {
  const d = draftFromNote('Assessment: Acute gastroenteritis. Uminom ng ORS pagkatapos ng bawat pagdumi. Balik po after 3 days kung hindi gumaling.');
  assert.equal(d.assessment, 'Acute gastroenteritis');
  assert.equal(d.meds[0].name, 'ORS');
  assert.match(d.follow_up, /Balik/);
});

test('empty note gives an empty draft', () => {
  assert.deepEqual(draftFromNote(''), { assessment: '', meds: [], instructions: [], warnings: [], follow_up: '' });
});
