# TODO

Everything still open, most important first in each section. Check items off as they land.
Last updated: 18 Nov 2026.

## 1. Response speed (now: slow)

Where the time goes today (PC measurements through the app's own bridge; phones are slower):
MedGemma waits ~10 s before the first word on a ~500-token prompt, then writes ~8.6 tok/s.
Gemma 4 E2B: ~3 s, then ~13 tok/s. Streamed MedGemma (6 GB phones): ~3 tok/s. A photo adds ~30 s
(image encoding). Every new message re-reads the whole conversation from scratch.

Biggest wins first:

- [x] **Reuse the KV cache across turns.** `generate_resident` keeps `kv_tokens` and `sync_kv`
      only feeds tokens after the longest shared prefix (`app/src/main/cpp/patnub_llm.cpp`).
      Verified on PC: follow-ups reuse instructions + prior turns (`reused=166–250`).
- [x] **Pre-compute the instructions once.** `Assistant.prewarm()` runs `nativePrefill` on the
      fixed `RULES` prefix right after `engine.load`, and `NativeEngine.generate` cancels/waits for
      any in-progress prefill so the user never hits `BUSY`.
- [x] **Shorter prompt.** Trimmed `RULES`, `PromptBuilder.MAX_CONTEXT_CHARS` 1,400 → 450, send one
      passage unless the second is within 95% of top score, and put the patient's question before
      notes (fixed MedGemma echoing instructions; MedGemma TTFT dropped from ~10 s to ~3.0 s on PC).
- [x] **Warm up after load.** `assistant.prewarm()` evaluates the instruction prefix right after
      `engine.load` so weights page-fault in before the first question.
- [x] **Use the right CPU cores.** `CpuInfo.performanceCores()` reads `cpu_capacity` (falling back
      to `cpuinfo_max_freq` > slowest cluster) so little cores are excluded (e.g. 2 on Snapdragon 695,
      4 on Snapdragon 8 Gen 2).
- [ ] **Newer ARM instructions.** Build is `armv8.2-a+dotprod` only. Add an `i8mm` variant
      (`GGML_CPU_ALL_VARIANTS` + `GGML_BACKEND_DL`, or two `.so` builds chosen at runtime) for
      2021+ phones; measure on a real device.
- [x] **Allow weight repacking on phones with headroom.** `ModelCatalog.shouldRepack` enables
      `use_extra_bufts` for Gemma 4 Q4_0 on 6 GB+ phones (measured +359 MB anon, TTFT 1231 → 615 ms)
      and MedGemma Q4_K_M on 10 GB+ phones (kept off on 8 GB phones where Q4_K_M adds +1.5 GB anon).
- [ ] **Try a smaller batch for short prompts** (`FLASH_ATTN_EXT` is already enabled via `FLASH_ATTN_TYPE_AUTO`).
- [ ] **GPU.** Try llama.cpp's OpenCL backend (Adreno) and Vulkan; keep CPU as fallback.
- [ ] **Speculative decoding for Gemma 4.** `ggml-org/gemma-4-E2B-it-GGUF` ships an MTP draft
      (`mtp-gemma-4-E2B-it-Q4_0.gguf`, 59 MB); wire it in and measure.
- [x] **Faster photos (downscale + token cap).** `AssistantScreen` downscales picked photos to
      max 768 px on IO and `patnub_llm.cpp` sets `vp.image_max_tokens = 256` (caps Gemma 4 at
      589,824 px instead of 2.58M). Still open: try a lower-precision mmproj.
- [ ] **6 GB tier: in memory instead of streamed?** Measured on PC: in-memory MedGemma needs less
      non-reclaimable RAM (1.1 GB vs 1.3 GB), is ~3× faster and reads photos. Needs a real 6 GB phone test.
- [x] **Show progress honestly.** `ReadingProgress` shows pulsing dots + `"Reading your question… Ns"`
      until the first token arrives. Still open: lower `MAX_TOKENS` dynamically when the phone is hot.

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
- [x] Emergency card: nearest RHU / hospital, not only "Call 911".
- [ ] MedGemma often answers Tagalog questions in English; Gemma 4 is fine. Added `"Sagutin sa simpleng Tagalog o Taglish lamang."` in `PromptBuilder`; verify more Tagalog phrases or prefer Gemma 4 for Tagalog conversations.
- [ ] Streamed mode (msr_engine) still gets the one-message transcript prompt; give it real chat turns
      (needs a raw-prompt option in msr_engine).

## 4. Patient app features

- [x] Finish and commit the in-progress "edit profile" change (committed in `d21309a`).
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
- [ ] Phones without ARMv8.2 dot product are not supported (build needs dotprod): Cortex-A53-only
      phones and older big cores (A57/A72/A73), e.g. Helio P70 (OPPO CPH1969). Since the CPU check
      in `NativeEngine.load` they get a clear "can't run the model" message instead of a SIGILL crash.
      To run models there: a second `armv8-a` build of `libpatnub_llm.so` chosen at runtime.
- [ ] Verify 16 KB page-size compatibility on an Android 15+ device.

## 7. Release

- [ ] Signing config, R8/minify, check APK size (ML Kit OCR + barcode are ~20 MB per ABI).
- [ ] ML Kit may send anonymous usage logs now that the app has INTERNET; review or replace.
- [ ] Privacy policy and store listing; model license notices in the app (HAI-DEF terms, Apache 2.0).
- [ ] Replace the demo root key (`contract/demo/`) before any real clinic.

## 8. Team

- [ ] Owners for `doctor-web/`, `contract/` and `content/` (the role split in the build plan predates them).
