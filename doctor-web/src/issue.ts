// Builds the patient copy. Pure, so the approval rule is testable without a browser.
import type { PatientCopy } from '../../contract/contract.ts';
import type { Draft } from './draft.ts';

export const RECORD_TTL_S = 24 * 3600; // QR scan window (spec §4.6.1)

export interface CopyInput {
  draft: Draft;
  encounter_date: string;
  /** The clinician ticked "I reviewed this summary". Spec §4.5: nothing is issued without it. */
  approved: boolean;
  previous?: { rid: string; rv: number };
}

export function patientCopy(input: CopyInput, now: number, newId: () => string): PatientCopy {
  if (!input.approved) throw new Error('Review and approve the summary before creating a QR code.');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(input.encounter_date)) throw new Error('Enter the encounter date.');
  const { assessment, meds, instructions, warnings, follow_up } = input.draft; // never the raw note
  return {
    rid: input.previous?.rid ?? newId(),
    rv: (input.previous?.rv ?? 0) + 1,
    iat: now,
    exp: now + RECORD_TTL_S,
    encounter_date: input.encounter_date,
    assessment, meds, instructions, warnings, follow_up,
  };
}
