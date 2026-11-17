# Project Context — patnub.ai (Alaga Health / Kalusugan+)

> Single-file orientation for the whole repo (ELITS-HACKATHON-2026). Read this first.
> Last updated: 2026-11-18 (v1.2 re-plan). **v1.2:** no internet at the point of care; Android app is patient-only; clinicians use an offline PWA in `doctor-web/` (Cloudflare Pages, static); shared formats in `contract/`; no backend; records move by signed, encrypted offline QR; clinics hold an annual license. See spec v1.2 and build plan §0, which win over older text in this file. Where this file and a source doc disagree, the source doc wins; check it.

## 1. What this is

**patnub.ai** is an offline-first Android healthcare companion for patients in rural and underserved communities, with a clinician portal. Product names in the docs: **Alaga Health** and **Kalusugan+** (the spec calls the name provisional). The app package is `ai.patnub`.

Core idea:

1. A **patient** asks a local AI assistant about symptoms, medicines and visits. Answers are grounded (RAG) in approved medical references plus that patient's doctor-approved records.
2. A **doctor** writes a consultation note. AI structures it into fields, the doctor edits and approves it, and the app issues a **signed QR code**.
3. The patient **scans the QR** to import the record into their timeline. The assistant then uses that record as patient-specific context.

It is an information and care-continuity tool. It is **not** a diagnostic device, a clinician substitute or an emergency service.

Everything runs **on the phone**: a quantized MedGemma model, a local SQLite database, no network.

## 2. The repo: one real project plus supplements

| Folder / file | Role | Status |
|---|---|---|
| **`patnub-ai/`** | **The real project.** Native Android app (Kotlin + Jetpack Compose). Everything ships from here. | Patient app complete for the demo; see patnub-ai/README.md |
| `doctor-web/` | **Planned.** Clinician offline PWA (Vite + TypeScript), static on Cloudflare Pages. | Not started |
| `contract/` | **Planned.** Check-in code, certificate and QR payload formats, test vectors, demo license-signing scripts. | Not started |
| `msr_engine/` | Git submodule: the `libmsr_engine` runtime (replaces `ravin/`). Run `git submodule update --init`. | Empty until initialised |
| `ravin/` | Supplement. `mobile-stream-runtime` / `libmsr_engine`: C/C++ inference engine that streams GGUF weights from storage into a bounded RAM arena. Will be bridged into patnub-ai (JNI). | Phases 1–4 done, benchmarked on a real phone |
| `alaga-health/` | Supplement. Single-file HTML prototype (`index.html`) of the 5-tab patient UI and 3-step doctor portal. UX/behaviour reference, not shipped. | Mock logic (regex "AI", in-memory state) |
| `Healthcare_AI_Companion_Project_Specification.md` | Supplement. Product spec: requirements, RAG safety, QR workflow, data model, QA, risks, open decisions. | v1.0 draft, 2026-11-17 |
| `ALAGA_HEALTH_ITERATIVE_BUILD_PHASES.md` | Supplement. 6-phase build plan with exit gates, architecture diagram, role split. | Plan |
| `README.md` | Repo overview. Predates `patnub-ai/`; still describes the Capacitor/WebView idea. | Slightly stale |
| `images/` | Empty. | – |

Relationship: spec says **what**, build-phases doc says **in what order**, `alaga-health` shows **how it should look and behave**, `ravin` provides **the on-device brain**, `patnub-ai` is **where it all lands**.

> Note: the build-phases doc and root README describe a Capacitor/WebView shell around the HTML prototype. `patnub-ai/` is instead a **native Compose app** (its README says it is "separate from the web mockup"). Treat native Compose as the direction; the HTML is a reference only.

## 3. patnub-ai (the real project)

**Path:** `patnub-ai/` · **Language:** Kotlin · **UI:** Jetpack Compose + Material 3

| Item | Value |
|---|---|
| Application ID / package | `ai.patnub` |
| minSdk / targetSdk / compileSdk | 26 / 36 / 36 |
| Gradle wrapper | 9.8.0 (checksum-pinned) |
| Android Gradle Plugin | 9.5.1 (built-in Kotlin) |
| Kotlin + Compose compiler plugin | 2.4.21 |
| Compose BOM | 2026.09.00 |
| JDK | 17+ |
| Version | 0.1.0 (versionCode 1) |

**Build:**
```sh
cd patnub-ai
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/
./gradlew :app:installDebug      # to a connected phone
```

### 3.1 Code layout (`app/src/main/java/ai/patnub/`)

| File | Purpose |
|---|---|
| `MainActivity.kt` | Entry point, edge-to-edge, sets theme |
| `ui/PatnubApp.kt` | Root `Scaffold`. Top bar has a Patient/Doctor segmented switch (`Role` enum). Bottom nav shown only for Patient. State via `rememberSaveable`. |
| `ui/patient/` | Assistant, Records, Scan, Medicines, Visits screens |
| `content/` (repo root) | Public-domain medical content pipeline (MedlinePlus, FDA labels) |
| `ui/theme/Color.kt`, `Theme.kt` | Material 3 colours/theme (teal accent, matching the prototype) |
| `res/` | strings, vector tab icons, adaptive launcher icon, `data_extraction_rules.xml` |

### 3.2 Privacy defaults already in place
- **Offline.** `INTERNET` only for the optional one-time model download; nothing the patient enters leaves the phone.
- `allowBackup="false"` plus `data_extraction_rules.xml`: patient data stays out of cloud backup and device transfer.

### 3.3 Next steps (Phase 1 remainder)
1. Local SQLite schema + seed data (`alaga_local.db`).
2. `libmsr_engine` JNI bridge.
3. Live telemetry pill in the top bar (RSS, streamed layers, TTFT, thermal).

## 4. Product summary (from the spec)

**Users:** Patient · Verified clinician · Content/system admin (no routine access to patient records).

**Patient tabs:** Assistant · Records · Scan · Medicines · Visits.
**Doctor flow:** Write note → Review AI draft → Issue QR.

**Principles:** safety before convenience; clinician-approved info stays traceable (author, status, date); AI never silently becomes the medical record (human approval required); patient controls consent; plain language and accessibility; works with unreliable connectivity.

**Key features (spec §4):**
- AI assistant with RAG over two collections: **A** general knowledge (approved DOH clinical guidelines, Philippine National Formulary medicines) and **B** patient-scoped doctor records.
- **Emergency red-flag gate** that runs *before* the LLM (target < 20 ms) and short-circuits to emergency guidance.
- **Doctor note composer** with AI structuring into editable fields, verbatim raw note preserved, mandatory approval checkbox.
- **QR grant**: opaque signed token (HMAC-SHA256 or Ed25519), **zero plaintext PHI in the QR**, single-use, 24 h expiry, revocable; wrong-patient blocker.
- **Records timeline** that distinguishes `CLINICIAN_APPROVED` from `PATIENT_REPORTED`, with revision/provenance.
- **Medicines** lookup + packaging scan, with a mandatory name/strength/formulation confirmation sheet.
- **Visits**: prep questions, plain-language recap, Patient-Reported badge, doctor-record conflict detector.
- Offline-first; local-language and accessibility support (Taglish notes appear in the demo).

**Non-goals:** no diagnosis, no replacing clinicians or emergency services.

**Open decisions (spec §14):** brand name, first deployment region, doctor verification, patient identity matching, content sources and clinical governance, QR policy, retention/deletion rules, language priority, offline scope, integrations, compliance pathway, model/vector stack, operating model. Platform is partly settled now: native Android.

## 5. Architecture (100% on device)

```
patnub-ai (Compose UI: Patient 5 tabs / Doctor 3 steps / telemetry pill)
   │
   ▼  Kotlin ⇄ JNI bridge (planned)
 1. Pre-LLM emergency red-flag gate
 2. RBAC + patient isolation (WHERE patient_id = :active)
 3. QR grant signer/verifier
 4. RAG prompt builder + response validator
   │                         │
   ▼                         ▼
 ravin / libmsr_engine    SQLite alaga_local.db
  msr_engine_load           general_knowledge_chunks (Collection A)
  msr_engine_generate       clinical_records         (Collection B)
  msr_engine_get_stats      qr_grants, audit_events
  MedGemma 1.5 4B Q4_K_M    visit_items, medication_references
```

Fallback rule: if the model is unavailable or slow, the UI falls back to deterministic logic so the stage demo never crashes.

## 6. Build phases (from `ALAGA_HEALTH_ITERATIVE_BUILD_PHASES.md`)

| # | Phase | Exit gate (short) |
|---|---|---|
| 1 | Repo unification, `libmsr_engine` bridge, local SQLite | Boots in Airplane Mode, live engine stats in header, Patient/Doctor switch, data persists across restarts |
| 2 | Safety gate + dual-collection RAG (Assistant tab) | 3 test prompts behave correctly (emergency, general, patient-scoped) |
| 3 | Doctor AI structuring + approval gate | Taglish note → AI draft → edit → approve → row in `clinical_records` |
| 4 | Signed QR grants, wrong-patient guard, RAG memory loop | The spec's defining loop works end to end |
| 5 | Medicines lookup/scan, Visits prep/recap | Spec §4.8–4.10 satisfied |
| 6 | Runtime tuning, QA suite, demo polish | 10/10 spec §12 tests pass; peak VmRSS ≤ 1,310 MiB; full doctor→patient→RAG loop offline in < 90 s |

**Current position (v1.2 phases, build plan §0):** Phase 1 in progress (Android shell, Room DB, `Engine`/`NativeEngine`, status pill done; Doctor view removal, onboarding, JNI bridge, `doctor-web/` scaffold pending). Phase 2 logic (red-flag gate, keyword retrieval) done and unit-tested; UI pending.

## 7. ravin (on-device inference engine)

**What:** Systems R&D project to run an LLM while **bounding peak RAM** by streaming quantized weights from storage into reusable buffers. Separate upstream repo: `RavinEduard/Mobility_Engine` (private). Not a medical product.

**Model:** `medgemma-1.5-4b-it.Q4_K_M.gguf` (2.313 GiB, 34 dense layers at 54.2 MiB each; `token_embd` is 525 MiB and doubles as output head). Vision projector: `mmproj-medgemma-1.5-4b-it-BF16.gguf` (for medicine-package scan). **Weights are never committed.**

**Key measured results** (reference device: Redmi Turbo 3, aarch64, 11.5 GB RAM, Termux):

| Result | Number |
|---|---|
| Stock llama.cpp, with repack | 4,984 MiB peak |
| Stock llama.cpp, `--no-repack` | ~2,624 MiB peak, ~12 tok/s at 2K |
| Naive `mmap` of the GGUF | 2,319 MiB RSS (97.7% of file) |
| Storage throughput | ~1.4 GiB/s |
| Pure layer streaming | ≈ 0.6 tok/s ceiling, so **hybrid residency** is used |
| **Streamed, 1 GiB arena, 28/34 layers, 2-slot prefetch** | **1,255.8 MiB peak, 3.00 tok/s** (+45.6% vs no prefetch), byte-identical output |
| KV cache | 136 KiB/token (1.06 GiB at 8K, f16) |

**Status:** Phases 1–4 complete (storage reader/GGUF inspection, bounded arena + layer plan, async prefetch, installable C SDK `libmsr_engine` + `msr-cli`). Thermal is only partly mitigated (device reaches 74 °C). Reference arm64 device tested; Linux/macOS untested; Windows needs a port.

**Layout:** `native/` (C++ core), `sdk/` (installable SDK: `CMakeLists.txt`, `cmake/msrConfig.cmake`, `examples/minimal.cpp`, `tools/msr_cli.cpp`), `scripts/` (GGUF inspect, layer budget, IO bench, mmap probe, A/B prefetch, measured runner), `tests/byte_exact_check.sh`, `updates.md` (session log). In this checkout only `sdk/`, `scripts/`, `tests/`, `README.md`, `updates.md` are present; `native/`, `docs/`, `BENCHMARKS.md` etc. live in the upstream repo.

**What patnub-ai needs from it:** build `libmsr_engine.so` for Android, expose load/generate/stats via JNI, ship or side-load the GGUF.

## 8. alaga-health (HTML prototype)

`alaga-health/index.html`, about 86 KB, no build step. iPhone-style frame, light/dark theme, teal accent (`#0e8f7e`).
- State is volatile (`state.records = []`).
- `answer(q)`: 7 hardcoded regex branches.
- `parseNote(text)`: regex splitter over 7 hardcoded medicines.
- `qrSVG()`: visual-only QR, no cryptography.
- Mock medicine scan (900 ms timeout, "Paracetamol").

Use it for screen flow, copy and visual design when building the Compose screens. Do not port its logic; the phases doc maps each function to its real replacement.

## 9. Working notes for contributors / agents

- Work in `patnub-ai/`. Treat other folders as read-only references unless the task says otherwise.
- Keep the app offline: network use is limited to the optional model download; add no network SDKs.
- No plaintext PHI in QR codes, logs or backups.
- AI output that becomes a clinical record needs explicit human approval.
- Keep a deterministic fallback for every AI-powered feature.
- Never commit model weights.
- Git history: `main`; latest commit scaffolds the Android shell. Commit messages use `feat(patnub-ai): …` style.

## 10. Known doc inconsistencies
- Root `README.md` and the phases doc assume a Capacitor/WebView shell and the repo name `SussyMarlony/ELITS-HACKATHON-2026`; `patnub-ai/` is native Compose.
- Root README title says Alaga Health / Kalusugan+; app is branded patnub.ai.
- `ravin/README.md` points to model paths on the phone (`/storage/emulated/0/Download/gemma/`) and to files not in this checkout.
