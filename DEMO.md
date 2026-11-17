# Demo runbook

Two devices, no internet needed once installed: a laptop or tablet for the clinic, a phone for
the patient.

## Before the demo (with internet, once)

1. **License:** `node contract/scripts/issue-license.ts rhu-san-isidro "RHU San Isidro" 365 > license.json`
   (demo root key; never for real patients).
2. **Clinic app:** `cd doctor-web && npm install && npm run build && npm run preview`, or open
   the Cloudflare Pages URL. Load it once so it works offline. Activate with `license.json` and
   create the first clinician (e.g. Dr. Ana Reyes, MD, PRC 0123456).
3. **Patient app:** install the debug APK on the phone (`cd patnub-ai && ./gradlew :app:installDebug`).
   Complete onboarding (e.g. Maria Dela Cruz, 54, Brgy. Malinis).
4. Turn on Airplane Mode on both devices.

## The loop (about 90 s)

1. **Check-in:** phone › Scan shows the check-in code. Clinic › *Check in patient* › camera › *Add patient*.
2. **Note:** clinic › *New consultation* › paste the note below › *Draft fields*.
3. **Approve:** check the fields, tick "I reviewed this summary", *Approve and create QR*.
4. **Import:** phone › Scan › *Scan a record from your clinic* › point at the screen › *Save to my records*.
5. **Records:** the record appears with "Approved by Ana Reyes, MD, PRC 0123456 · RHU San Isidro".
6. **Assistant:**
   - "I've had a headache for two days and feel dizzy when I stand up" → answer citing DOH and the clinician record.
   - "Chest pain and hard to breathe" → emergency card instantly, no model call.
   - "Can I double my Amlodipine dose today?" → refers to Ana Reyes, no advice.
7. **Wrong phone (optional):** scan the same QR with a second phone or emulator → "made for a different phone".

Demo note:

> Patient seen for recurring headaches for 2 weeks with BP 150/95 on two readings. Assessment: Stage 1 hypertension. Start Amlodipine 5 mg once daily in the morning. Reduce salt intake, especially instant noodles and bagoong. Walk 30 minutes a day. Follow up in 4 weeks with the BP log. Seek emergency care if severe headache, chest pain, blurred vision, or weakness on one side.

## Reset between demos

- Phone: top bar › *Reset (debug)* (debug builds only), then onboard again.
- Clinic: *Clinic admin* › *Erase this device*, then activate the license again.

## Honest labels on stage

- The Assistant shows **SIMULATED** until the real MedGemma engine (JNI bridge) is wired in.
  Without the model it answers from the retrieved passages and says so.
- Medical content in the demo is placeholder text, not clinically reviewed.
