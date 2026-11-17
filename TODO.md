# TODO

Everything still open, most important first in each section. Check items off as they land.
Last updated: 18 Nov 2026.

## 1. Response speed (now: slow)

Where the time goes today (PC measurements through the app's own bridge; phones are slower):
MedGemma waits ~10 s before the first word on a ~500-token prompt, then writes ~8.6 tok/s.
Gemma 4 E2B: ~3 s, then ~13 tok/s. Streamed MedGemma (6 GB phones): ~3 tok/s. A photo adds ~30 s
(image encoding). Every new message re-reads the whole conversation from scratch.

Biggest wins first:

- [ ] **Reuse the KV cache across turns.** `generate_resident` calls `llama_memory_clear` every time,
      so each message re-processes the instructions, background and whole history. Keep the cache and
      only feed the tokens after the longest shared prefix. Expect first-word time on follow-ups to
      drop from ~10 s to ~1–2 s. (`app/src/main/cpp/patnub_llm.cpp`)
- [ ] **Pre-compute the instructions once.** The rules block is identical for every message: evaluate it
      at model load and save the state (`llama_state_seq_get_data` / `_set_data`), restore per question.
- [ ] **Shorter prompt.** Trim the instruction text, cap each reference passage lower (`PromptBuilder.MAX_CONTEXT_CHARS`
      1,400 → ~800), send one passage instead of two when the top score is clearly ahead.
- [ ] **Warm up after load.** Run a 1-token generation right after `engine.load` so the first real
      question does not pay for page-faulting the weights in.
- [ ] **Use the right CPU cores.** Threads are `availableProcessors().coerceIn(2, 4)`; pick the count of
      performance cores (read `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`) and pin batch threads.
- [ ] **Newer ARM instructions.** Build is `armv8.2-a+dotprod` only. Add an `i8mm` variant
      (`GGML_CPU_ALL_VARIANTS` + `GGML_BACKEND_DL`, or two `.so` builds chosen at runtime) for
      2021+ phones; measure on a real device.
- [ ] **Allow weight repacking on 8 GB+ phones.** It is off everywhere to save memory; on phones with
      room it speeds up Q4 matrix maths. Make it a per-tier setting and measure memory.
- [ ] **Try CPU flash attention** (`cparams.flash_attn_type`) and a smaller batch for short prompts.
- [ ] **GPU.** Try llama.cpp's OpenCL backend (Adreno) and Vulkan; keep CPU as fallback.
- [ ] **Speculative decoding for Gemma 4.** `ggml-org/gemma-4-E2B-it-GGUF` ships an MTP draft
      (`mtp-gemma-4-E2B-it-Q4_0.gguf`, 59 MB); wire it in and measure.
- [ ] **Faster photos.** Downscale before encoding, set `image_max_tokens`, try a lower-precision mmproj.
- [ ] **6 GB tier: in memory instead of streamed?** Measured on PC: in-memory MedGemma needs less
      non-reclaimable RAM (1.1 GB vs 1.3 GB), is ~3× faster and reads photos. Needs a real 6 GB phone test.
- [ ] **Show progress honestly.** While the prompt is processed, show "Reading your question…" with
      the elapsed time, then stream; consider shorter `MAX_TOKENS` (220 / 140) when the phone is hot.

## 2. Test on real hardware

- [ ] Run `DEMO.md` end to end with two devices in Airplane Mode (clinic laptop + phone).
- [ ] Patient app on a phone: onboarding/Keystore, check-in QR shown to a webcam, scanning the dense
      record QR, model download and import, Stop button, Back behaviour, keyboard, brightness.
- [ ] Measure per tier on real phones: first-word time, tok/s, peak memory, temperature over 10 minutes.
- [ ] 4 GB phone with Gemma 4 E2B under memory pressure (PC: 858 MiB must stay in RAM, 2.7 GB mapped).
- [ ] Clinic app camera check-in with a real webcam (only tested with the `?debug` paste box).
- [ ] Repeat `scripts/test-conversation.sh` several times per scenario; answers vary at temp 0.3.

## 3. Clinical content and safety

- [ ] Clinician review of `content/REVIEW.md`; record names in `reviewed_by`.
- [ ] Tagalog-speaking clinician to check the 33 unverified emergency wordings.
- [ ] Philippine sources: ask DOH for permission to use CPGs and the Philippine National Formulary,
      then add them to `content/` (FDA labels are US products and units).
- [ ] ORS and other common RHU items with no FDA label.
- [ ] Clinical evaluation set and the measures in spec §12.2 (evidence support, safe fallback rate,
      emergency sensitivity, unsafe advice rate).
- [ ] Emergency card: nearest RHU / hospital, not only "Call 911".
- [ ] MedGemma often answers Tagalog questions in English; Gemma 4 is fine. Try a Tagalog example
      exchange in the prompt, or prefer Gemma 4 for Tagalog conversations.
- [ ] Streamed mode (msr_engine) still gets the one-message transcript prompt; give it real chat turns
      (needs a raw-prompt option in msr_engine).

## 4. Patient app features

- [ ] Finish and commit the in-progress "edit profile" change (uncommitted: `UserDao.kt`,
      `PatientIdentity.kt`, `PatnubApp.kt`, `PatientScreens.kt`, `strings.xml`).
- [ ] Tagalog / Filipino UI translation (all strings are English).
- [ ] Voice input for questions and visit recaps (offline speech-to-text).
- [ ] Medicine pack scan through the vision model (MedGemma read "PARACETAMOL 500 mg Tablet" correctly on PC).
- [ ] Keep chat history across app restarts (Room table, patient can delete).
- [ ] Better retrieval: on-device embeddings in `embedding_blob` instead of keyword matching.
- [ ] Multi-frame QR for long records.
- [ ] Accessibility pass: TalkBack labels, large text, contrast.

## 5. Clinic web app (`doctor-web/`)

- [ ] Deploy to Cloudflare Pages (settings in `doctor-web/README.md`).
- [ ] AI help drafting the note (today: rule-based parser). In-browser model or optional.
- [ ] More than one device per clinic (sync, or per-device records).
- [ ] Root key custody, clinician certificate expiry/revocation for real use.
- [ ] Cloudflare Worker to sell and renew clinic licenses (spec §8.1).

## 6. Engine (`msr_engine`) and native build

- [ ] msr_engine B1–B4 (`msr_engine/LIMITATION.md`): typed cancel status, per-engine `MSR_NO_ALLOC`,
      provider singleton (switching models restarts the app), node-name refill trigger.
- [ ] Gemma 4 cannot be streamed (msr_engine patches the Gemma 3 graph only).
- [ ] Phones with only Cortex-A53 cores are not supported (build needs dotprod).
- [ ] Verify 16 KB page-size compatibility on an Android 15+ device.

## 7. Release

- [ ] Signing config, R8/minify, check APK size (ML Kit OCR + barcode are ~20 MB per ABI).
- [ ] ML Kit may send anonymous usage logs now that the app has INTERNET; review or replace.
- [ ] Privacy policy and store listing; model license notices in the app (HAI-DEF terms, Apache 2.0).
- [ ] Replace the demo root key (`contract/demo/`) before any real clinic.

## 8. Team

- [ ] Owners for `doctor-web/`, `contract/` and `content/` (the role split in the build plan predates them).
