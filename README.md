# ELITS-HACKATHON-2026 — Alaga Health (Kalusugan+)

Mobile healthcare AI companion unifying [`alaga-health`](./alaga-health/index.html) (5-tab Patient UI + 3-step Doctor Portal) with [`ravin`](./ravin/README.md) (`mobile-stream-runtime` / `libmsr_engine` C SDK streaming `medgemma-1.5-4b-it.Q4_K_M.gguf` inside a bounded `1 GiB` RAM arena) and a 100% local `SQLite` database.

## Documentation & Build Plan
- **[`ALAGA_HEALTH_ITERATIVE_BUILD_PHASES.md`](./ALAGA_HEALTH_ITERATIVE_BUILD_PHASES.md)** — Iterative, goal-directed 6-phase mobile build plan, architecture diagram, function-level upgrade matrix, and exit gates.
- **[`Healthcare_AI_Companion_Project_Specification.md`](./Healthcare_AI_Companion_Project_Specification.md)** — Complete product, RAG safety, doctor-to-patient QR workflow, data model, and QA specification.
- **[`alaga-health/index.html`](./alaga-health/index.html)** — Frontend mobile prototype (`Assistant`, `Records`, `Scan`, `Medicines`, `Visits`, and `Doctor` portal).
- **[`ravin/README.md`](./ravin/README.md)** — On-device streamed LLM runtime (`libmsr_engine`, `msr-cli`, `1 GiB` arena budget, 2-slot async layer prefetch).
