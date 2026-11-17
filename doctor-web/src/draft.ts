// Rule-based note -> structured draft (spec v1.2: no LLM on the clinic side for the demo).
// Ported from parseNote() in alaga-health/index.html. The clinician always reviews the result.
import type { PrescribedMed } from '../../contract/contract.ts';

export interface Draft {
  assessment: string;
  meds: PrescribedMed[];
  instructions: string[];
  warnings: string[];
  follow_up: string;
}

// shortcut: fixed list of common RHU medicines; upgrade to the medication_references table later.
export const KNOWN_MEDS = [
  'Amlodipine', 'Losartan', 'Metformin', 'Amoxicillin', 'Paracetamol', 'Cetirizine',
  'Oral Rehydration Salts', 'ORS', 'Salbutamol', 'Ibuprofen', 'Mefenamic acid', 'Cotrimoxazole',
];

const sentences = (text: string) => text.replace(/\s+/g, ' ').trim().split(/(?<=[.!?])\s+/).filter(Boolean);
const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export function draftFromNote(text: string): Draft {
  const s = sentences(text);
  const used = new Set<number>();
  s.forEach((x, i) => { if (/^patient\b/i.test(x)) used.add(i); }); // context line, not an instruction
  const take = (re: RegExp) => {
    const i = s.findIndex((x, j) => !used.has(j) && re.test(x));
    if (i < 0) return '';
    used.add(i);
    return s[i];
  };

  const assessment = take(/assessment|diagnos|impression/i).replace(/^(assessment|diagnosis|impression)\s*:?\s*/i, '').replace(/\.$/, '');

  const meds: PrescribedMed[] = [];
  s.forEach((x, j) => KNOWN_MEDS.forEach((name) => {
    if (!new RegExp(`\\b${escapeRe(name)}\\b`, 'i').test(x)) return;
    used.add(j);
    const pick = (re: RegExp) => (x.match(re) ?? [''])[0];
    const dose = pick(/\d+(?:\.\d+)?\s?(?:mg|mcg|ml|mL|g)\b(?:\/\d+\s?mL)?/i);
    const freq = [
      pick(/(once|twice|three times) (a day|daily)|once daily|twice daily|every \d+ hours(?: as needed)?|as needed/i),
      pick(/in the morning|at night|with meals|before bed/i),
      pick(/for \d+ (days|weeks)/i),
    ].filter(Boolean).join(', ');
    meds.push({ name, dose, freq });
  }));

  const follow_up = take(/follow[- ]?up|return|come back|balik/i).replace(/\.$/, '');
  const warnS = take(/emergency|seek|\bER\b/i);
  const warnings = warnS.replace(/^.*?\bif\s+/i, '').replace(/\.$/, '')
    .split(/,\s*(?:or\s+)?|\s+or\s+/).map((w) => w.trim()).filter(Boolean);
  const instructions = s.filter((_, i) => !used.has(i)).map((x) => x.replace(/\.$/, ''));
  return { assessment, meds, instructions, warnings, follow_up };
}
