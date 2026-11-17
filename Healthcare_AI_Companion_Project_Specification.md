# Healthcare AI Companion — Project Specification

**Working title:** Kalusugan+ (provisional; name and branding are not yet approved)  
**Document status:** Product specification draft  
**Version:** 1.1  
**Date:** 18 November 2026  
**Product type:** Patient-facing healthcare companion with a clinician portal  
**Primary audience:** Patients in rural and underserved communities, and the healthcare professionals who care for them

> **v1.1 changes (18 Nov 2026):** the patient app is a native Android app and the clinician portal is a separate web app, delivered from three repositories (§7.5). Record transfer is a fully offline, signed and encrypted QR with no server round trip (§4.6.1). This resolves open decisions 3 and 11 and adds decisions 16–19 (§14). Where §4.6 and §7.3 describe an opaque token redeemed against a backend, §4.6.1 and §7.5 now take precedence.

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

- Native Android application (repository `patnub-ai`). It is **patient-only**: there is no clinician role, doctor screen or role switch in the app.
- Runs fully on the phone (local model, local database) and has no `INTERNET` permission. It needs the `CAMERA` permission for QR scanning.
- A simple home screen with prominent access to the AI assistant and common tasks.
- Large, readable controls and plain-language labels.
- Support text input first; voice input can be added with speech-to-text support.
- A health-record area for imported consultation information and other patient-approved records.

### 3.2 Doctor portal

- Responsive web dashboard for clinicians (repository `patnub-doctor-web`), backed by the API (repository `patnub-api`).
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

### 4.6.1 Offline QR transfer (v1.1 decision)

The QR carries the record itself, so the phone never contacts a server to import it. Because it contains health information, the payload is **encrypted to the intended patient's phone** and **signed by the clinician**. The "no raw PHI in the visible payload" rule above is met by encryption, not by omission.

**Pairing (once per visit).** The patient app shows a *check-in code*: a QR holding the patient's reference and the public half of a key pair generated and kept on the phone (X25519). The clinician portal scans or enters it when the patient is selected. This replaces the "identity matching" guess with a cryptographic one: only the intended phone can decrypt the record, so a wrong-patient scan fails by construction.

**Issuing.** On approval the API builds the *patient copy* of the record (structured, clinician-approved fields only), compresses it, signs it with the clinician's key, encrypts it to the patient's public key, and returns it as one QR, or as a numbered sequence of QR frames when it does not fit in one.

**Payload (version 1)**, in order: format version, issue and expiry time, record ID and record version, clinician certificate, ephemeral public key + nonce, ciphertext (compressed record) + authentication tag, Ed25519 signature over everything before it.

**Clinician trust.** The app bundles a root public key. Each clinician holds a short-lived certificate (ID, name, PRC number, facility, public key, validity) signed by that root and included in the QR, so the app can verify a clinician it has never seen, offline.

**Patient app on scan.** Check the signature and certificate chain, check expiry against the phone clock, check the record ID and version have not been imported already, decrypt, show the preview, require explicit consent, then store the record with its clinician source and approval metadata.

**Size.** A QR holds at most about 2.9 KB (binary, lowest error correction) and about 2.3 KB at medium error correction. Fixed overhead is roughly 350 bytes (certificate, signature, key, nonce, tag). That leaves roughly 2 KB of compressed record, which is a short consultation summary. Longer records use animated multi-frame QR. These are estimates to confirm against real records and real phone cameras.

**What this model cannot do.** State the limits in the UI and in clinician training:
- **No remote revocation of an issued QR.** A scan works offline, so it cannot be recalled. The defence is a short expiry plus a *superseding record version*: a higher version of the same record ID replaces the earlier one on the patient's phone when imported.
- **No server-enforced single use.** The app instead refuses to import the same record ID and version twice. Another phone cannot use the code because it cannot decrypt it.
- **Possession of a photo of the QR is not enough** to read it, but a photo plus the patient's unlocked phone is. Protect the app with the phone's screen lock or app lock.
- The patient never receives the clinician's raw note. It stays on the clinician side (§4.5 preservation rule). The patient copy contains only the approved structured fields.

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

### 7.3.1 Doctor QR flow, offline (v1.1)

```text
Patient phone                          Clinician portal (web)  ->  API
  show check-in code (public key) ---> scan code, select patient
                                       write note -> AI structuring -> clinician approves
                                       API: build patient copy, compress,
                                            sign (clinician key), encrypt (patient key)
  scan QR  <---------------------------- display QR (or numbered frames)
  verify signature + cert + expiry
  decrypt -> preview -> patient consent
  save to local DB -> available as Collection B for RAG
```

No network call is made by the phone at any step.

### 7.4 Design constraints

- Enforce patient-level isolation in every patient-record query.
- Enforce role-based authorization for clinician and administrative actions.
- Keep general medical retrieval logically separate from patient-specific retrieval.
- Use secure transport and encryption at rest for sensitive data.
- Use managed secrets and token hashing; do not store reusable QR secrets in plaintext.
- Version prompts, models, sources, safety rules, and evaluation sets so behavior can be audited.
- Preserve provenance for every doctor-issued record and every cited knowledge source.

### 7.5 Repositories and responsibilities (v1.1)

| Repo (proposed name) | Owns | Does not own |
|---|---|---|
| `patnub-ai` | Android patient app: Assistant, Records, Scan, Medicines, Visits; on-device model (`msr_engine`); local database; QR verify, decrypt and import; check-in code | Clinician features, signing keys, anything that needs a network |
| `patnub-doctor-web` | Clinician UI: sign-in, patient selection (scan check-in code), note composer, AI-draft review, approval, QR display, record history and supersede | Business rules and keys. It calls the API |
| `patnub-api` | Clinician authentication and verification, clinician certificates and the root key, patient-copy building, signing, encryption, the clinician-side record store and revisions, audit events, and the **contract** | The patient's on-phone data |

**Contract.** The QR payload format, the patient-copy JSON schema, the check-in code format and the API's OpenAPI description are defined in `patnub-api/contract/`, with **test vectors** (a known key pair, record and resulting QR bytes). The Android app and the web app copy that folder, and both must pass the vectors. This replaces a fourth repo. Version the contract and bump the payload format version on any breaking change.

**Data placement.** The raw clinician note, drafts, revisions and audit trail live on the clinician side (API database). The patient's phone holds only issued patient copies, chat history, visit notes and the knowledge base. Nothing patient-side is uploaded.

**Impact on the build plan.** Phase 3 (clinician AI structuring and approval) and the doctor half of Phase 4 move to `patnub-doctor-web` and `patnub-api`; the Android Phase 4 work becomes the offline QR verify and import described in §4.6.1. The current doctor screens and role switch in the app are to be removed.

---

## 8. Security, privacy, and governance

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
3. ~~**Target devices.**~~ **Resolved in v1.1:** native Android patient app; clinicians use a web app. iOS is not planned.
4. **Doctor onboarding:** Who verifies clinicians, credentials, and facility affiliations?
5. **Patient identity matching:** What process links a doctor's encounter to the correct patient account?
6. **Medical content sources:** Which organizations and reference databases will be approved, and who reviews updates?
7. **Clinical governance:** Which qualified clinicians will define and approve symptom safety rules, triage language, and medication content?
8. **QR policy:** Should a code be single-use, time-limited, reusable by the same patient, or governed by different policies for different record types?
9. **Consent and retention:** What are the retention, correction, deletion, and revocation rules for patient records and chat history?
10. **Language priorities:** Which local languages should be supported first, and who will clinically review translations?
11. ~~**Connectivity assumptions.**~~ **Resolved in v1.1:** the patient app is fully offline, including record import (§4.6.1). The clinician portal and API require a connection.
12. **Integration needs:** Will the first release connect to clinic systems, pharmacies, national health systems, or external medical records, or remain standalone?
13. **Deployment and compliance:** What hosting region, legal basis, privacy controls, and regulatory pathway apply to the selected market?
14. **AI and infrastructure stack:** Which LLM, embedding model, vector database, application framework, and hosting provider will be used?
15. **Business and operating model:** Who funds the service, maintains medical content, supports patients, and handles clinical escalations?
16. **Clinician AI structuring (new in v1.1):** Notes now go to the API. Which model structures them: one hosted by the project, a self-hosted model, or a third-party service? Raw notes are health information, so a third-party service needs a privacy review and patient or facility consent.
17. **Offline QR details:** Confirm the payload size limits with real records. How is the patient's check-in code delivered to the clinician's computer (webcam scan, typed short code, or both)? Do multi-frame QR codes need to be supported in the first release?
18. **Key and certificate management:** Who holds the root key, how long do clinician certificates last, and how is a lost or revoked clinician removed given that phones are offline (short certificate life, app updates)?
19. **Patient copy scope:** Which fields appear in the patient copy, and is the raw clinician note ever shared with the patient?

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
