# QA: Spec §12 checks

Run everything with `scripts/verify.sh` (contract, clinic app, Android unit tests).

| # | Check (build plan Phase 6) | Where it is tested | Status |
|---|---|---|---|
| 1 | Supported general question returns cited sources | `patnub-ai` `KeywordRankerTest.headacheAndDizzy_matchesHypertension`, `AssistantRulesTest.promptKeepsGeneralAndPersonalApartAndShort` | Automated |
| 2 | Unsupported question gets the safe fallback, no guessing | `KeywordRankerTest.unrelatedQuestion_returnsNothing` (empty retrieval → `Reply.NoMatch`, no model call) | Automated |
| 3 | Emergency symptoms escalate in < 20 ms, before the model | `RedFlagGateTest`, `RedFlagGateTimingTest` | Automated |
| 4 | Unapproved draft cannot produce a QR | `doctor-web/src/issue.test.ts` | Automated |
| 5 | QR carries no readable health information | `contract/contract.test.ts` "QR text carries no readable health information" | Automated |
| 6 | Wrong-patient scan is blocked and logged | `contract.test.ts` "another phone cannot open it", `RecordCodecVectorsTest.otherPhoneCannotOpen`; logging in `RecordImporter` (`QR_REJECTED`) | Block automated; log not unit-tested |
| 7 | Replay or revoked QR is rejected | `ImportPolicyTest` (replay/older version), expiry in `contract.test.ts` and `RecordCodecVectorsTest.expired` | Automated. Remote revocation is impossible offline by design (spec §4.6.1) |
| 8 | Cross-patient isolation, including prompt injection | Structural: one patient per phone; records for anyone else cannot be decrypted (check 6); `Assistant.ask` takes the owner id from `PatientIdentity`, never from text; Room query binds `:patientId` | Not unit-tested (needs an on-device Room test) |
| 9 | Medicine scan asks for name, strength and form first | `MedicineMatcherTest`; the Confirm screen enables "Show information" only when all three are chosen | Logic automated, screen manual |
| 10 | Recap conflicts with a clinician record are flagged | `VisitPlannerTest.recapSortsSentencesAndFlagsDoseConflict` | Automated |

Also covered: tampering and wrong root (`contract.test.ts`, `RecordCodecVectorsTest`), license
validity judged at issue time, cross-implementation interop (Kotlin opens the TypeScript-sealed
vector), passcode wrapping, rule-based draft parser, dose-change guard.

## Not covered yet

- On-device runs: camera scanning of the dense record QR, Keystore key creation, end-to-end
  timing (< 90 s), and peak memory (≤ 1,310 MiB) need a real phone and the JNI engine.
- The red-flag phrases, seeded knowledge passages and medicine data are placeholders that need
  clinician review (spec §14, decision 7).
- No measured clinical evaluation set yet (spec §12.2).
