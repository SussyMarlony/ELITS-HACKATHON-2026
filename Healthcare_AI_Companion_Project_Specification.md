# Healthcare AI Companion — Project Specification

**Working title:** Kalusugan+ (provisional; name and branding are not yet approved)  
**Document status:** Product specification draft  
**Version:** 1.2  
**Date:** 18 November 2026  
**Product type:** Patient-facing healthcare companion with a clinician portal  
**Primary audience:** Patients in rural and underserved communities, and the healthcare professionals who care for them

> **v1.2 changes (18 Nov 2026), replacing v1.1:** the whole system works with **no internet at the point of care**; internet is needed only to install and update. One repository holds the native Android **patient-only** app, an offline-first **clinician web app** (PWA, served as static files from Cloudflare Pages) and a shared `contract/`. There is **no backend API**. Records move from clinic to patient by a signed, encrypted offline QR (§4.6.1). Clinics buy an annual license; patients use the app free (§8.1). Where §2.4, §4.6, §7.1–§7.3 describe a backend, accounts or server-redeemed tokens, §4.6.1, §7.5 and §8.1 take precedence. Settled decisions are listed in §14.

> This document consolidates the product direction discussed so far. Product goals and features that were explicitly discussed are treated as product requirements. Technical choices and rollout priorities that were not finalized are identified as recommendations or open decisions rather than settled facts.

---

## 1. Product overview

The product is a mobile-first healthcare companion that helps patients understand health information, describe symptoms, prepare for medical appointments, and retain doctor-provided instructions. It combines a patient-facing AI assistant with a clinician-facing portal.

The patient assistant uses retrieval-augmented generation (RAG) to ground responses in reviewed medical information and, where the patient has authorized it, that patient's doctor-approved records. It must be designed to recognize uncertainty, avoid unsupported medical claims, and direct users to appropriate professional or emergency care when needed.

A defining feature is a secure doctor-to-patient QR workflow. A clinician writes and approves a consultation summary or care instructions. The system converts the approved content into a structured record and creates a QR code that the patient can scan to securely import that record into their health history. The assistant can then retrieve the record as patient-specific context in later conversations.

The system is an information, education, and care-continuity tool. It is **not a substitute for a clinician, a diagnostic device, or an emergency service**.

### 1.1 Product vision

Make reliable, understandable healthcare information and doctor-provided instructions easier to access, especially for people who face distance, connectivity, language, or health-literacy barriers.

### 1.2 Core value proposition

- **For patients:** Understand health information in plain language, know when to seek care, and keep important medical instructions in one place.
- **For doctors:** Create clear, reusable consultation summaries and transfer approved instructions to a patient's app with less friction.
- **For communities:** Improve access to trusted health information without implying that an AI chatbot can replace local healthcare services.

### 1.3 Product principles

1. **Safety before conversational convenience.** When a safe answer cannot be supported, the system should say so and provide a safer next step.
2. **Clinician-approved information stays traceable.** Doctor-issued records must retain their source, author, approval status, and date.
3. **AI does not silently become the medical record.** Generated summaries and extracted fields require review and approval before being treated as clinician-issued instructions.
4. **Patients stay in control.** Patients should understand what information is being saved and shared and be able to manage consent and access.
5. **Plain language and accessibility.** Use clear wording, accessible controls, and support for relevant local languages as the product develops.
6. **Designed for unreliable connectivity.** Provide useful offline access to approved content where feasible, without pretending that a full online AI service is available offline.

---

## 2. Users and roles

### 2.1 Patient

A person seeking general health information or managing information received from a healthcare professional.

Typical needs:
- Describe symptoms in text or voice.
- Understand possible explanations and warning signs without receiving an unsupported diagnosis.
- Read reliable information about a medicine.
- Prepare questions for a doctor.
- Understand and save a consultation recap.
- Import a doctor's instructions using a QR code.
- Review their own authorized health information.

### 2.2 Doctor / clinician

A verified healthcare professional who uses the clinician portal to create and approve patient-facing consultation summaries and instructions.

Typical needs:
- Create a consultation summary from a written paragraph or structured form.
- Review AI-assisted structuring for accuracy.
- Approve, issue, update, or revoke a patient record.
- Generate a secure QR code for the intended patient.
- See whether the patient has imported the record, where that status is supported by the workflow.

### 2.3 System administrator / clinical content administrator

A restricted operational role responsible for managing approved medical reference content, clinician and facility onboarding, and system configuration. This role should not automatically have unrestricted access to patient records.

### 2.4 Roles and permissions

| Capability | Patient | Verified clinician | Content / system administrator |
|---|---:|---:|---:|
| Use general AI medical information assistant | Yes | Optional | No routine need |
| View own saved health records | Yes | Only where authorized for care | No routine need |
| Create a clinician-issued consultation record | No | Yes | No |
| Approve clinician-issued instructions | No | Yes | No |
| Generate a QR transfer for a consultation record | No | Yes | No |
| Import a QR record | Yes, after authorization | No | No |
| Manage approved general medical sources | No | No | Yes, restricted |
| Review audit and operational events | Limited personal activity | Relevant own activity | Restricted operational access |

Access must be enforced by the backend, not merely hidden in the user interface.

---

## 3. Platforms and experience

### 3.1 Patient application

- Native Android application (`patnub-ai/`). It is **patient-only**: no clinician screens or role switch.
- One patient profile per phone, created at onboarding (name, age, barangay) together with a key pair kept on the phone. Shows a **check-in code** (QR) with that profile and the public key.
- Runs fully on the phone (local model, local database). `INTERNET` is used only for the optional one-time AI model download; models can also be imported from storage. It needs `CAMERA` for scanning.
- On-device models by phone RAM: MedGemma 1.5 4B in memory with photo understanding (8 GB+), MedGemma 1.5 4B streamed through msr_engine (6 GB), Gemma 4 E2B with photos (4 GB).
- A simple home screen with prominent access to the AI assistant and common tasks.
- Large, readable controls and plain-language labels.
- Support text input first; voice input can be added with speech-to-text support.
- A health-record area for imported consultation information and other patient-approved records.

### 3.2 Doctor portal

- Offline-first web app for clinicians (`doctor-web/`): a PWA installed once from Cloudflare Pages, then used with no connection. Data lives in the browser (IndexedDB).
- Demo deployment: **one shared device per clinic**. Each clinician unlocks it with their own passcode.
- One account type, **Clinician** (doctor, nurse or midwife), with an **admin flag**. The first account, created when the clinic license is activated, is admin + clinician. Admins add and disable clinicians.
- Note composer with a **rule-based draft** that pre-fills the structured fields from the note; the clinician edits and approves. An in-browser LLM is a later stretch, not part of the demo.
- Patient and consultation context should be visible only to authenticated, authorized users.
- A focused consultation-summary composer with review, approval, QR generation, and record-status actions.

### 3.3 Design direction

The initial visual direction is a clean, calm, Apple-inspired healthcare interface:
- Light backgrounds with restrained blue and green accents.
- High contrast for important warnings and actions.
- Rounded cards and clear spacing without excessive decoration.
- Friendly but professional iconography.
- Avoid visual treatments that make uncertain AI-generated information look like a confirmed diagnosis.

The initial concept mockup explored eight patient screens: home, AI chat, medicine information, doctor QR sharing, QR scanning, visit preparation, visit recap, and health records. The final screen design, branding, and product name remain to be confirmed.

---

## 4. Functional requirements

Priority definitions:
- **P0 — Core MVP:** Required to demonstrate the defining patient-assistant and doctor-to-patient QR workflow safely.
- **P1 — Next release:** Valuable supporting patient features after the core workflow is stable.
- **P2 — Later / validation required:** Additional capabilities that depend on deployment partners, connectivity, language validation, or operational readiness.

### 4.1 Patient home and navigation

**Priority:** P0

The home screen should provide quick access to:
- AI Medical Assistant.
- Health Records.
- Medicine Information (P1).
- Prepare for Visit (P1).
- Scan Doctor QR Code.
- Additional settings, consent, and help information.

Requirements:
- Display a clear route to urgent-care guidance and emergency instructions.
- Show connectivity state when it affects available functionality.
- Clearly distinguish saved doctor-approved records from AI-generated chat content.

### 4.2 AI Medical Assistant

**Priority:** P0

Patients can describe symptoms or ask general health questions through text. Voice input is a planned accessibility enhancement.

The assistant should:
1. Identify the general intent of the question (for example: symptom information, medicine information, follow-up on a doctor instruction, or general health education).
2. Ask relevant follow-up questions when needed, without making the interaction feel like a definitive diagnosis process.
3. Retrieve relevant approved medical references and, where authorized and relevant, the patient's doctor-approved records.
4. Explain information in plain language.
5. Separate general possibilities from confirmed patient facts.
6. Explain warning signs and appropriate care-seeking actions.
7. Provide citations or source references for factual medical answers where possible.
8. State when evidence is insufficient or the question is outside the system's safe scope.
9. Escalate to a qualified healthcare professional when the situation requires individualized clinical judgment.

The assistant must not:
- Claim to diagnose a patient.
- Invent test results, medical history, prescriptions, or doctor instructions.
- Prescribe medication or independently change a prescribed dose or treatment.
- Reassure a patient that a potentially serious symptom is harmless.
- Present a retrieved document as applicable to a patient without checking relevance and context.

**Example interaction:** A patient says they have had a headache for two days and feel dizzy when standing. The assistant should ask appropriate clarifying questions, provide general information only when supported, identify relevant warning signs, and recommend professional assessment when warranted. It must not assert a specific cause based on that short description.

### 4.3 RAG medical knowledge system

**Priority:** P0

RAG is the preferred approach for grounding answers in approved sources. It is not, by itself, a guarantee against hallucination.

#### Knowledge collections

Keep at least two logically separate retrieval collections:

1. **General medical knowledge:** Approved, versioned medical references and health guidance.
2. **Patient-specific records:** Doctor-approved consultation summaries, instructions, and other patient-authorized records.

The assistant should retrieve from each collection only when appropriate. Patient-specific data must not leak across users or become part of the general knowledge base.

#### Source management

Each general medical source should have metadata such as:
- Source title and issuing organization.
- Source type and topic.
- Publication or revision date, where available.
- Date added and last reviewed.
- Version and active / retired status.
- Geographic or population applicability, where known.
- Content owner or reviewer.

Only reviewed and approved content should be eligible for production retrieval. Retired or superseded content must be excluded or clearly handled.

#### Retrieval and answer generation

Recommended pipeline:
1. Receive the patient message.
2. Run safety and intent checks, including emergency-warning screening.
3. Determine which knowledge collections may be queried.
4. Retrieve relevant passages with metadata and source references.
5. Apply relevance and coverage checks.
6. Generate a plain-language response using the retrieved evidence and relevant authorized patient context.
7. Validate the response against safety rules and source support.
8. Return the answer with citations, uncertainty language, or a safe fallback.

#### Safe fallback behavior

When evidence is missing, contradictory, outdated, poorly matched, or below a validated retrieval threshold, the system should:
- Avoid guessing or filling gaps with unsupported medical claims.
- Explain that it cannot provide a reliable answer from the available information.
- Ask a clarifying question only when it could safely resolve the uncertainty.
- Recommend contacting a clinician or appropriate health service when needed.
- Trigger urgent-care guidance when emergency warning signs are present.

Do not rely only on the language model's self-reported confidence. Retrieval thresholds and response checks should be evaluated with representative test cases and reviewed by qualified clinical professionals.

### 4.4 Emergency and clinical safety handling

**Priority:** P0

Safety checks should run before ordinary answer generation and again before the response is shown.

Requirements:
- Define a clinically reviewed set of emergency and urgent warning signs for the intended deployment population.
- When a message suggests an emergency, prioritize clear instructions to seek immediate local emergency care rather than continuing a normal chatbot conversation.
- Do not allow a low retrieval score or a reassuring model-generated answer to override a configured emergency rule.
- Do not invent local emergency numbers or facility availability. These must be configured and verified for each deployment area.
- Present the assistant's limitations in understandable language.
- Provide a route to professional help for uncertain, complex, worsening, or persistent symptoms.

A qualified clinical team must define and validate the actual triage rules and wording before pilot deployment.

### 4.5 Doctor portal and consultation summary composer

**Priority:** P0

A verified clinician can create a consultation record using:
- A free-text paragraph, and/or
- Structured fields for the relevant clinical and follow-up information.

The system may use AI to organize the paragraph into structured fields, such as:
- Encounter date.
- Patient reference.
- Visit summary.
- Clinician's assessment as explicitly documented by the clinician.
- Care instructions.
- Medication information as explicitly supplied by the clinician.
- Follow-up instructions and timing.
- Warning signs or return precautions.
- Clinician identity and approval metadata.

Important rules:
- AI extraction is a drafting aid only.
- The clinician must review and approve the final record before it is issued.
- The system must preserve the original clinician-entered text or an auditable version of it.
- The app must not transform an uncertain or missing field into a definitive clinical instruction.
- Corrections and updates should create an auditable revision rather than silently overwriting an issued record.

### 4.6 Doctor-generated QR code and secure record transfer

**Priority:** P0 — defining product capability

The QR code is a secure transfer mechanism for a doctor-approved record. It should not contain raw patient medical information directly.

#### Doctor workflow

1. Clinician signs in to the doctor portal.
2. Clinician selects or confirms the intended patient using the deployment's approved identity-matching process.
3. Clinician enters a consultation paragraph or structured instructions.
4. The system prepares a structured draft.
5. Clinician reviews and edits the draft.
6. Clinician approves and issues the record.
7. The system creates a QR code containing a secure, opaque reference or short-lived access token.
8. Clinician shares or displays the QR code for the intended patient.

#### Patient workflow

1. Patient opens the QR scanner in the app.
2. Patient scans the clinician's QR code.
3. The app validates the code and displays a safe preview of the record source and details.
4. Patient authenticates or confirms identity as required by the transfer policy.
5. Patient explicitly consents to import the record.
6. The record is added to the patient's health timeline with its clinician source and approval metadata.
7. The RAG assistant may retrieve the imported record in later conversations, subject to relevance and consent rules.

#### QR security requirements

- QR codes must not expose raw protected health information in their visible payload.
- Use an opaque identifier or cryptographically protected, limited-access token.
- Tokens should be short-lived, revocable, and limited to the intended record and action.
- Require patient authorization and appropriate identity checks before import.
- Prevent replay or unauthorized repeated use where the transfer model requires one-time redemption.
- Use encrypted transport and authenticated backend access.
- Log generation, access, redemption, revocation, and relevant failures.
- Provide a way to revoke an unredeemed code and correct or supersede an issued record.
- Do not treat possession of a QR image alone as sufficient proof that a person is authorized to view or import the record.

### 4.6.1 Offline QR transfer (v1.2)

The QR carries the record itself, so the phone never contacts a server. Because it contains health information, the payload is **encrypted to the intended patient's phone** and **signed by the issuing clinician**. The "no raw PHI in the visible payload" rule above is met by encryption.

**Check-in.** The clinician app scans the patient's check-in code with the device camera (webcam or phone camera). It stores the patient in a local list keyed by the public key, so a returning patient is recognised without typing. Showing the code is the patient's consent to share those profile details.

**Issuing.** On approval the clinician app builds the *patient copy* (approved structured fields only), compresses it, signs it with the clinician's key, encrypts it to the patient's public key (X25519), and shows it as one QR, or as numbered QR frames if it does not fit.

**Payload (format version 1):** format version, issue and expiry time, record ID and version, clinic license certificate, staff certificate, ephemeral public key + nonce, ciphertext + tag, Ed25519 signature over all of it.

**Trust chain, checked offline by the patient app:** root public key (bundled in the APK) → **clinic license certificate** (clinic/RHU name and ID, admin public key, valid until the end of the subscription) → **staff certificate** signed by the clinic admin (name, profession such as MD/RN/RM, license number, public key, validity) → record signature. The patient app shows "Approved by <name>, <profession>, PRC <number>, <clinic>".

**Patient app on scan.** Verify the chain and signature, check expiry, refuse a record ID and version already imported, decrypt, preview, require consent, store with source and approval metadata.

**Size.** A QR holds at most about 2.9 KB (binary, low error correction), about 2.3 KB at medium. Overhead with both certificates is roughly 750 bytes, leaving about 1.5 KB of compressed record: a short consultation summary. Longer records use animated multi-frame QR. Estimates; confirm with real records and phone cameras.

**Limits of the offline model** (state them in the UI and in clinician training):
- **No remote revocation** of an issued QR. Defences: short expiry, and a *superseding record version* (same record ID, higher version) replaces the earlier one when imported.
- **No server-enforced single use.** The app refuses duplicates; other phones cannot decrypt.
- A photo of the QR is useless without the patient's phone; protect the app with the phone's screen lock.
- The clinician's **raw note never leaves the clinic device**; the patient copy holds only approved structured fields (§4.5 preservation rule is met on the clinic side).
- **Lost or replaced patient phone:** the clinic reissues past records to the new phone's check-in code. There is no cloud backup.

#### Memory terminology and design

The QR scan should update the patient's **authorized health record / patient context**, not train or modify the underlying AI model. RAG retrieves the approved record as context at answer time. This keeps patient-specific information separate, traceable, editable under the applicable record policy, and less likely to contaminate general medical knowledge.

### 4.7 Health records and patient timeline

**Priority:** P0 basic storage; P2 richer timeline

The patient should be able to view imported doctor-issued records and distinguish them from personal notes or AI-generated summaries.

Each record should show, where applicable:
- Record type.
- Date of encounter or issue.
- Clinician and facility.
- Approval / issued status.
- Instructions and follow-up details.
- Source and revision history.

The richer timeline may include consultation summaries, medication records, and general checkups. A separate medications view can be added as the records feature matures.

### 4.8 Medicine information lookup and packaging scan

**Priority:** P1

Patients can search for a medicine by name or use the camera to scan its packaging.

The result may display:
- What the medicine is commonly used for.
- Common side effects.
- Important warnings and contraindications from the source information.
- Storage information, where available.
- How the official label or the patient's verified prescription says it should be taken.

Requirements and limitations:
- Use an approved and maintained medicine information source.
- If packaging recognition is uncertain, ask the patient to confirm the medicine name, strength, and formulation before showing details.
- Show the source and distinguish manufacturer-label information from clinician-specific instructions.
- Do not infer or generate a personalized dose.
- Do not recommend starting, stopping, splitting, combining, or changing medication without appropriate professional direction.
- For conflicting label and patient-record information, do not resolve the conflict by guessing; advise the patient to contact the prescriber or pharmacist.

### 4.9 Visit preparation

**Priority:** P1

Before an appointment, the patient can enter a health concern or select a topic. The app generates a short list of questions they may wish to ask their clinician.

Example questions:
- What could be contributing to these symptoms?
- Are there tests or examinations I should ask about?
- What changes or precautions should I follow?
- Which warning signs mean I should seek care sooner?

Requirements:
- Questions should help the patient communicate, not steer the clinician toward a predetermined diagnosis.
- The patient can edit, remove, or save questions.
- The system should avoid presenting generated questions as medical recommendations.

### 4.10 Visit recap

**Priority:** P1

After an appointment, the patient may type or dictate what they remember the doctor saying. The app rewrites it in plain language and organizes it into a simple summary and to-do list.

Possible output:
- Plain-language explanation.
- Instructions the patient reports receiving.
- Follow-up actions.
- Follow-up date, if the patient or clinician supplied one.
- Questions to clarify with the healthcare professional.

Requirements:
- Clearly identify this as a patient-reported recap unless it was issued or verified by the clinician.
- Do not turn unclear recollections into confirmed prescriptions or clinical facts.
- Ask the patient to review and correct the recap before saving it.
- If the recap conflicts with a clinician-issued record, show the conflict and recommend confirming it with the clinician; do not silently choose one.

### 4.11 Offline-first support

**Priority:** P2; important for rural deployment

The app should be designed to remain useful when connectivity is intermittent.

Potential capabilities:
- Locally cached, reviewed, general health information that has been approved for offline use.
- Ability to view previously imported records, subject to device security and user authentication.
- Save non-sensitive drafts locally where appropriate and synchronize securely when connectivity returns.
- Show clear timestamps and sync status.

Offline limitations:
- Do not imply that live RAG retrieval, clinician escalation, current medicine data, or emergency-service availability works without a connection unless it has been explicitly implemented and tested.
- Do not generate unsupported answers from stale cached data.
- Explain when the user is viewing cached content and its last update date.

### 4.12 Local language and accessibility support

**Priority:** P2; validate before rollout

The product should be designed to support Filipino and relevant regional languages based on the deployment community. Voice input and spoken output may be added where they provide a meaningful accessibility benefit.

Requirements:
- Medical translations must be clinically reviewed.
- Preserve medication names, quantities, units, and clinician instructions accurately.
- Make it clear when a translation is automated and may need confirmation.
- Use plain language and accessible typography, contrast, and touch targets.
- Test with users who have different levels of health and digital literacy.

### 4.13 Clinician escalation and local care information

**Priority:** P0 for safe fallback; P2 for richer directory integration

When the assistant cannot safely answer or the patient's description suggests that professional assessment is needed, it should direct the patient to appropriate care.

A future directory may include verified local facilities, clinics, and service contacts. Such data must be maintained and verified for each deployment area. The app must not invent opening hours, contact details, or service availability.

---

## 5. Key user journeys

### 5.1 Patient asks about symptoms

1. Patient opens the assistant.
2. Patient describes symptoms in text or voice.
3. Safety screening checks for emergency indicators.
4. If emergency indicators are present, the app provides urgent-care guidance.
5. Otherwise, the assistant asks relevant follow-up questions as needed.
6. RAG retrieves suitable approved sources and relevant authorized patient records.
7. The assistant produces a plain-language, source-grounded response or a safe fallback.
8. Patient can review the sources and seek professional help when advised.

### 5.2 Doctor issues a consultation record

1. Doctor signs in.
2. Doctor opens or creates the patient's encounter.
3. Doctor writes a summary or completes structured fields.
4. System produces a structured draft, if AI assistance is enabled.
5. Doctor reviews and approves the final content.
6. System issues the record and generates a secure QR code.
7. Doctor shares the QR code with the patient.

### 5.3 Patient imports a doctor record

1. Patient scans the QR code.
2. System validates the code and checks access conditions.
3. App previews the record source and content.
4. Patient confirms consent and completes any required identity check.
5. System imports the record into the patient's account.
6. The record appears in the health timeline.
7. The assistant can retrieve the record when relevant in future chats.

### 5.4 Patient checks a medicine

1. Patient searches by medicine name or scans the package.
2. App identifies the likely medicine and requests confirmation if needed.
3. App retrieves approved medicine information.
4. App displays uses, common side effects, warnings, and label information with sources.
5. App distinguishes general reference information from the patient's own prescription.
6. For uncertainty or conflicting directions, app directs the patient to a pharmacist or prescriber.

### 5.5 Patient prepares for or recaps a visit

1. Patient enters the topic or their recollection of the visit.
2. App generates either appointment questions or a plain-language recap.
3. Patient reviews and edits the output.
4. Patient saves it as a patient-created item, clearly distinguished from clinician-issued records.

---

## 6. Data and information model

The following is a conceptual model, not a finalized database schema.

| Entity | Purpose | Important fields (illustrative) |
|---|---|---|
| UserAccount | Authentication and account identity | user ID, role, status, contact/authentication references |
| PatientProfile | Patient-facing profile | patient ID, user ID, preferred language, consent settings |
| ClinicianProfile | Clinician identity and verification | clinician ID, user ID, credentials/verification status, facility association |
| Facility | Care location | facility ID, name, verified location/contact details |
| Encounter | A consultation or care interaction | encounter ID, patient ID, clinician ID, date, status |
| ClinicalRecord | Doctor-issued summary or instructions | record ID, encounter ID, content, source text, author, approval state, version |
| QRGrant | Secure QR transfer authorization | grant ID, record ID, token hash/reference, expiry, status, redemption details |
| PatientConsent | Patient permissions | patient ID, purpose, scope, consent status, timestamp, revocation details |
| ChatSession | Conversation grouping | session ID, patient ID, timestamps |
| ChatMessage | Patient and assistant messages | message ID, session ID, role, content reference, safety flags, timestamps |
| KnowledgeSource | Approved medical reference | source ID, title, publisher, version, review date, status |
| KnowledgeChunk | Searchable source segment | chunk ID, source ID, text reference, embedding reference, metadata |
| MedicationReference | Approved medicine information | medicine ID, name, strength/form, approved source references |
| VisitPreparation | Patient-generated appointment questions | item ID, patient ID, topic, questions, timestamp |
| VisitRecap | Patient-reported consultation recap | item ID, patient ID, text, review status, timestamp |
| AuditEvent | Security and clinical workflow audit | event ID, actor, action, resource, time, result |

### 6.1 Data classification

At minimum, distinguish:
- Public, approved general medical reference content.
- Personal account and profile information.
- Sensitive patient health information.
- Clinician identity and professional information.
- Security credentials, tokens, and audit metadata.

Apply access controls, retention rules, and encryption appropriate to each class. Patient-specific records and chat content must never be included in a shared general-purpose retrieval index.

---

## 7. Technical architecture

The implementation stack has not yet been selected. The following is a technology-agnostic reference architecture.

### 7.1 Main components

1. **Patient client:** Mobile application or mobile-first web app for chat, records, QR scanning, and patient tools.
2. **Clinician portal:** Web application for clinician authentication, patient/encounter workflows, record authoring, approval, and QR creation.
3. **Backend API:** Authentication, authorization, patient records, clinician records, consent, audit events, and application business logic.
4. **AI orchestration service:** Intent handling, safety checks, retrieval, prompt construction, response generation, and response validation.
5. **Medical knowledge service:** Ingestion, review workflow, chunking, indexing, versioning, and retirement of approved references.
6. **Patient-context retrieval service:** Secure, patient-scoped retrieval of authorized doctor-issued records.
7. **QR service:** Secure token creation, validation, expiry, redemption, and revocation.
8. **Data stores:** Relational database for structured records and permissions; vector index for retrieval; protected object storage for source files and relevant uploads.
9. **Optional media services:** Speech-to-text, text-to-speech, and packaging text recognition, introduced with quality and privacy controls.
10. **Monitoring and audit:** Operational monitoring, security logging, quality evaluation, and clinical safety review metrics.

### 7.2 Logical request flow

```text
Patient App
    |
    v
Authenticated Backend API
    |
    v
Safety / Intent Checks --------------------> Urgent-care fallback
    |
    v
AI Orchestrator
    |                         |
    v                         v
General Medical RAG       Patient-scoped Record Retrieval
    |                         |
    +------------+------------+
                 |
                 v
        Grounded Response Draft
                 |
                 v
      Safety / Evidence Validation
                 |
        +--------+--------+
        |                 |
        v                 v
Supported answer      Safe fallback / escalation
        |
        v
Patient App with sources and next steps
```

### 7.3 Doctor QR flow

```text
Clinician Portal
      |
      v
Draft Consultation Summary
      |
      v
AI-assisted structuring (optional)
      |
      v
Clinician review and approval
      |
      v
Issued Clinical Record
      |
      v
QR Grant / Opaque Token
      |
      v
Patient scans QR
      |
      v
Validate grant + authenticate/authorize
      |
      v
Preview + patient consent
      |
      v
Import record into patient account
      |
      v
Patient-scoped RAG context
```

### 7.3.1 Doctor QR flow, offline (v1.2)

```text
Patient phone (Android, offline)       Clinic device (PWA, offline)
  show check-in code (profile+key) --> camera scan, patient saved locally
                                       write note -> rule-based draft -> clinician edits, approves
                                       build patient copy, compress,
                                       sign (staff key), encrypt (patient key)
  scan QR  <-------------------------- display QR (or numbered frames)
  verify root -> license -> staff -> signature, expiry
  decrypt -> preview -> consent -> save locally -> Collection B for RAG
```

Neither device makes a network call.

### 7.4 Design constraints

- Enforce patient-level isolation in every patient-record query.
- Enforce role-based authorization for clinician and administrative actions.
- Keep general medical retrieval logically separate from patient-specific retrieval.
- Use secure transport and encryption at rest for sensitive data.
- Use managed secrets and token hashing; do not store reusable QR secrets in plaintext.
- Version prompts, models, sources, safety rules, and evaluation sets so behavior can be audited.
- Preserve provenance for every doctor-issued record and every cited knowledge source.

### 7.5 Repository and responsibilities (v1.2)

One repository, three folders:

| Folder | Owns |
|---|---|
| `patnub-ai/` | Android patient app: onboarding and key pair, check-in code, Assistant (on-device MedGemma via `msr_engine`), Records, Scan (verify, decrypt, import), Medicines, Visits; local database |
| `doctor-web/` | Clinician PWA (Vite + TypeScript, no framework, styled after the `alaga-health` prototype's doctor portal): license activation, clinician accounts and passcodes, patient list, note composer and rule-based draft, approval, QR issuing, record history and supersede, audit log, encrypted export/import backup. Deployed as static files to Cloudflare Pages |
| `contract/` | Formats shared by both apps: check-in code, certificates, QR payload, patient-copy JSON schema; **test vectors** both apps must pass; demo scripts that hold the demo root key and sign clinic license files |

There is no backend. Bump the payload format version on any breaking change.

**Data placement.** Clinic side (one browser): clinicians, patients seen, raw notes, drafts, issued records and revisions, audit log, keys (encrypted with each clinician's passcode). Patient side (one phone): profile, key pair, imported patient copies, chat history, visit notes, knowledge base. Nothing is synced.

**Clinic device data loss.** The admin exports an encrypted backup (protected by the admin passcode) to a USB stick; the app reminds them. Re-importing the license file restores the ability to issue.

**Knowledge base and app updates** ship with app updates (APK / PWA refresh), which need internet once.

---

## 8. Security, privacy, and governance

### 8.1 Licensing (v1.2)

- Patients use the app free. Each clinic/RHU buys an **annual license**, delivered as a signed license file (§4.6.1, clinic license certificate) that the admin imports into the clinician app.
- **On expiry** the clinician app stops issuing new QRs and shows a renewal prompt; existing clinic records stay readable. The patient app keeps every imported record and still accepts a QR issued while the license was valid. Patients never lose care information because a clinic did not renew.
- Known demo gap: issue time is set by the clinic device, so an expired clinic could backdate. Acceptable for the demo; a real deployment needs a mitigation (for example short-lived staff certificates renewed with the license).
- **Demo:** a script in `contract/` signs license files with the demo root key. **Later:** a Cloudflare Worker sells and renews licenses and returns the signed file. It is used about once a year and never at the point of care.
- Demo shortcuts (demo root key in the repo, single shared device, backdating gap) must be labelled in code and replaced before real use.


Healthcare information is highly sensitive. Privacy and security requirements must be designed in from the beginning.

### 8.1 Minimum security requirements

- Secure authentication for patients and clinicians.
- Strong clinician account verification before issuing records.
- Role-based access control and least-privilege permissions.
- Patient-scoped authorization checks on every read and write.
- Encryption in transit and at rest.
- Secure session management and token expiry.
- Rate limiting and abuse prevention for authentication, chat, and QR endpoints.
- Audit logs for record creation, approval, access, sharing, import, modification, and revocation.
- Secure backups and tested recovery procedures.
- Protection against unauthorized data export and insecure device caching.

### 8.2 Consent and transparency

- Explain what information is saved and why.
- Obtain consent before importing a clinician record or using patient records for AI personalization, as required by the applicable policy.
- Make data-sharing scope understandable.
- Provide a way to review and revoke future access where supported by the record and legal-retention model.
- Clearly label AI-generated content, patient-reported content, and clinician-approved content.

### 8.3 Privacy and regulatory readiness

The deployment jurisdiction, legal basis for processing, retention rules, hosting requirements, and regulatory classification have not yet been finalized. Before any real-patient pilot, obtain qualified legal, privacy, security, and clinical review for the target jurisdiction. Confirm the applicable health-data, privacy, telehealth, professional-practice, and medical-software requirements. Do not treat this specification as legal or clinical approval.

### 8.4 AI governance

- Maintain a documented list of approved models and model versions.
- Review model and prompt changes before production release.
- Keep test sets for unsupported questions, conflicting evidence, urgent symptoms, adversarial prompts, and patient-record isolation.
- Monitor error reports and unsafe-output incidents.
- Provide a process to disable or roll back a model or source collection.
- Do not use identifiable patient conversations to train general models by default. Any proposed secondary use requires separate review, lawful basis, and appropriate consent.

---

## 9. Non-functional requirements

These are proposed targets and should be confirmed through technical discovery and user testing.

### 9.1 Reliability and availability

- Gracefully handle intermittent network connections.
- Do not lose an approved clinical record or a completed QR import because of a transient network failure.
- Clearly report failed, pending, and completed actions.
- Provide backup, recovery, and incident-response procedures.

### 9.2 Performance

- Keep navigation and local record viewing responsive on supported devices.
- Provide a clear loading state for AI retrieval and generation.
- Avoid showing a partial medical answer as final before safety validation is complete.
- Establish measurable latency targets after selecting the deployment environment and model provider.

### 9.3 Accessibility and usability

- Use readable typography and strong contrast.
- Support scalable text and screen-reader labels for key controls.
- Keep critical actions easy to reach and large enough to tap.
- Use plain language and explain medical terminology.
- Test with users with low digital literacy and users in the intended rural deployment setting.

### 9.4 Maintainability

- Version the API, source documents, prompts, models, and structured clinical records.
- Use automated tests for permissions, QR expiry/revocation, record import, and patient data isolation.
- Maintain documented operational procedures for content review and incident response.

---

## 10. MVP scope and release plan

### 10.1 Core MVP — prove the defining workflow

The recommended first release should prioritize:

1. Patient account and secure access.
2. Patient AI chatbot for general symptom and health-information questions.
3. RAG over a small, curated, clinically reviewed medical knowledge base.
4. Safety checks, emergency handling, citations, and safe fallback responses.
5. Verified clinician login and a basic doctor dashboard.
6. Doctor consultation-summary composer with clinician review and approval.
7. Secure QR generation, scanning, patient authorization, and record import.
8. Basic patient health-record view for imported clinician-issued records.
9. Audit events for clinician approval and QR transfer.

**MVP success demonstration:** A patient asks a supported general health question and receives a source-grounded, safely bounded answer; a clinician creates and approves a consultation summary; the patient scans a secure QR code, consents to import it, and the assistant can later retrieve that approved record as relevant context.

### 10.2 Next release

- Medicine lookup by name.
- Packaging scan and medicine confirmation.
- Visit preparation question generator.
- Patient-reported visit recap with review and correction.
- Expanded health timeline and medication view.

### 10.3 Later releases / deployment-dependent work

- Offline library of approved health information.
- More robust local-language and voice support.
- Verified local care directory and facility escalation pathways.
- Additional clinician workflows and facility integration.
- Additional record types and interoperability, subject to partner and regulatory requirements.

---

## 11. Acceptance criteria for core MVP

### 11.1 Patient AI assistant and RAG

- A patient can submit a text question and receive a response or an explicit safe fallback.
- Supported factual medical responses are grounded in approved retrieved sources.
- Source references are visible to the patient where the answer relies on external medical knowledge.
- If retrieval is insufficient or contradictory, the assistant does not invent an answer.
- Emergency warning signs trigger the configured urgent-care pathway before ordinary conversational guidance.
- The assistant does not claim to diagnose or independently prescribe or change treatment.
- A patient's record cannot be retrieved in another patient's session, including through prompt manipulation or guessed identifiers.

### 11.2 Clinician summary authoring

- Only an authenticated, verified clinician can issue a clinician record.
- A draft is not treated as an issued record until the clinician approves it.
- AI-extracted fields are editable and clearly marked for review.
- The issued record records its author, issue time, source text or source reference, and version.
- An issued record cannot be silently altered without an auditable revision.

### 11.3 QR transfer

- A QR code references a specific approved record without exposing its medical content in plaintext.
- An invalid, expired, revoked, or already-redeemed code is handled according to the configured grant policy and does not disclose the record.
- The patient sees a preview and must confirm the import.
- Successful import creates a patient-owned reference to the clinician-issued record, retaining its provenance.
- QR generation, validation, redemption, and revocation events are auditable.

### 11.4 Patient record use in RAG

- Only the authenticated patient's authorized records are eligible for retrieval.
- Retrieved patient context is identified as clinician-issued or patient-reported.
- The assistant does not turn patient recollections into clinician-confirmed instructions.
- Revoked or superseded records are handled according to the defined record policy.

---

## 12. Quality assurance and evaluation

A healthcare AI product requires evaluation beyond ordinary software testing.

### 12.1 Test categories

- General medical questions with strong source coverage.
- Questions with no supporting source.
- Conflicting, stale, or irrelevant source passages.
- Emergency and urgent warning-sign examples reviewed by clinicians.
- Ambiguous symptom descriptions and incomplete information.
- Medication name, strength, and formulation disambiguation.
- Conflicts between a patient-reported recap and a doctor-issued record.
- QR expiry, replay, revocation, unauthorized access, and wrong-patient scenarios.
- Cross-patient data isolation and access-control attacks.
- Low-connectivity, interrupted-sync, and offline-cache behavior.
- Language and speech-recognition errors, especially around medical terms and numbers.

### 12.2 Suggested measures

Set numeric targets after creating a representative clinical evaluation set. Measures should include:
- Evidence support rate for factual medical claims.
- Citation correctness and source relevance.
- Safe fallback rate on unsupported questions.
- Emergency-pathway sensitivity, measured on a clinician-reviewed test set.
- Unsafe advice / prohibited behavior rate.
- Patient-record retrieval isolation test results.
- QR authorization and expiry correctness.
- Clinician correction rate for AI-structured summaries.
- Patient comprehension and task-completion results.

Do not optimize only for answer rate or conversational satisfaction. Safety and evidence quality take priority.

---

## 13. Risks and mitigations

| Risk | Potential impact | Mitigation |
|---|---|---|
| Hallucinated or unsupported medical advice | Patient harm or delayed care | Curated sources, evidence checks, safe fallbacks, clinical review, continuous evaluation |
| Missed emergency indicators | Delayed emergency care | Clinician-defined safety rules, conservative escalation, testing, visible limitations |
| Incorrect AI structuring of a doctor's paragraph | Wrong patient instructions | Clinician approval required; preserve original text; audit revisions |
| QR code exposed or forwarded | Unauthorized health-data access | Opaque short-lived grants, patient authorization, identity checks, revocation, audit |
| Wrong patient record association | Serious privacy and care-continuity failure | Verified patient matching, preview, confirmation, strong patient isolation |
| Outdated or unsuitable medical content | Misleading advice | Source ownership, versioning, review dates, retirement workflow |
| Medicine scan misidentification | Incorrect medicine information | Confirm name, strength, and formulation; show uncertainty; use trusted reference data |
| Low or unstable connectivity | Interrupted access or incomplete transfer | Offline-approved content, clear sync state, retry-safe operations |
| Misunderstood language or speech | Incorrect symptom or instruction interpretation | Confirm critical names, numbers, and instructions; clinician-reviewed translations |
| Users treat AI as a doctor | Over-reliance | Clear positioning, careful wording, referral pathways, user education |

---

## 14. Open decisions

The following decisions still need to be made before implementation is fully specified:

1. **Product name and brand:** Is “Kalusugan+” the chosen name or only a visual placeholder?
2. **Initial deployment location:** Which country, region, and rural communities will be served first?
3. ~~**Target devices.**~~ **Resolved (v1.2):** native Android patient app; clinicians use an offline PWA on any device with a browser and camera. iOS is not planned.
4. ~~**Doctor onboarding.**~~ **Resolved for the demo (v1.2):** each licensed clinic's admin creates clinician accounts; the staff certificate records profession and license number. Who verifies licenses in a real deployment is still open.
5. ~~**Patient identity matching.**~~ **Resolved (v1.2):** the patient's check-in code binds the record to that phone's key (§4.6.1).
6. **Medical content sources:** Which organizations and reference databases will be approved, and who reviews updates?
7. **Clinical governance:** Which qualified clinicians will define and approve symptom safety rules, triage language, and medication content?
8. ~~**QR policy.**~~ **Resolved (v1.2):** time-limited, one import per record version, superseded by a higher version; no remote revocation (§4.6.1).
9. **Consent and retention:** What are the retention, correction, deletion, and revocation rules for patient records and chat history?
10. **Language priorities:** Which local languages should be supported first, and who will clinically review translations?
11. ~~**Connectivity assumptions.**~~ **Resolved (v1.2):** no internet at the point of care on either side; internet only to install, update and (later) renew licenses.
12. **Integration needs:** Will the first release connect to clinic systems, pharmacies, national health systems, or external medical records, or remain standalone?
13. **Deployment and compliance:** What hosting region, legal basis, privacy controls, and regulatory pathway apply to the selected market?
14. **AI and infrastructure stack:** Which LLM, embedding model, vector database, application framework, and hosting provider will be used?
15. **Business and operating model:** Who funds the service, maintains medical content, supports patients, and handles clinical escalations?
16. **Clinician AI structuring:** demo uses a rule-based draft. Is an in-browser LLM worth adding later, given model size and WebGPU support on clinic PCs?
17. **Offline QR details:** confirm size limits with real records; decide whether multi-frame QR is needed in the first release.
18. **Root key custody and real license verification:** who holds the production root key, and how is a clinic or clinician removed early when phones are offline (short certificate life, app updates)?
19. **Multi-device clinics:** sync or per-device records once a clinic has more than one device.

---

## 15. Suggested implementation milestones

These are sequencing recommendations, not calendar commitments.

### Milestone 1 — Discovery and clinical foundations

- Confirm target region, patient groups, clinician partners, and language needs.
- Agree on intended use, limitations, and clinical governance.
- Select and review the first medical knowledge sources.
- Define patient identity, consent, record retention, and QR security policies.

### Milestone 2 — Core application foundations

- Build authentication and role-based access.
- Build the patient app shell and clinician portal shell.
- Implement patient and clinician data separation.
- Implement audit logging and secure storage.

### Milestone 3 — RAG assistant and safety layer

- Ingest the first approved medical sources.
- Implement retrieval and source citations.
- Implement safety checks, safe fallbacks, and the clinician-reviewed emergency pathway.
- Build and evaluate a test suite before exposing the assistant to real patients.

### Milestone 4 — Doctor record and QR workflow

- Implement clinician summary authoring and approval.
- Implement secure QR grants, preview, patient consent, import, expiry, and revocation.
- Connect imported records to patient-scoped retrieval.
- Test wrong-patient and unauthorized-access scenarios.

### Milestone 5 — Pilot readiness

- Conduct clinical, privacy, security, and usability reviews.
- Test with representative users and clinicians in a controlled setting.
- Establish incident reporting, monitoring, support, and rollback processes.
- Only proceed to a real-patient pilot after required approvals and safeguards are in place.

### Milestone 6 — Supporting features

- Add medicine lookup, visit preparation, visit recap, enhanced timeline, offline library, and local-language support in a validated order.

---

## 16. Product boundaries and non-goals

The product is not intended to:
- Replace doctors, nurses, pharmacists, clinics, or emergency services.
- Provide definitive diagnosis from a symptom conversation.
- Independently prescribe medication or personalize dosing.
- Change, discontinue, or recommend combining prescribed medicines without professional guidance.
- Allow an AI-generated consultation summary to become an issued medical instruction without clinician approval.
- Store raw patient medical information directly inside a QR code.
- Train or modify the base AI model using a patient's QR-imported record.
- Treat patient-reported visit recaps as verified clinician instructions.
- Guarantee that all capabilities work offline or in every language from the first release.

---

## 17. Summary

The product's defining loop is:

**Patient asks → AI retrieves approved evidence → assistant responds safely or escalates → doctor creates and approves instructions → patient imports them securely by QR → assistant uses the authorized record as patient-specific context in future conversations.**

The strongest first release is the safe RAG-powered patient assistant paired with the verified clinician portal and secure QR transfer. Medicine information, visit preparation, visit recap, richer health records, offline support, and local-language features extend that foundation without weakening the product's central safety and trust requirements.

---

*End of specification.*
