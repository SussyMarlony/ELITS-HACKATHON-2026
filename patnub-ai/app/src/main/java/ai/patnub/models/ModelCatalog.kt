package ai.patnub.models

/** One downloadable file. [sha256] is the Hugging Face LFS object id, checked after download or import. */
data class ModelFile(val fileName: String, val url: String, val bytes: Long, val sha256: String)

enum class PhoneTier { LOW, MID, HIGH }

/**
 * A model plus how to run it. [streamed] uses msr_engine's bounded arena (text only);
 * otherwise the model is memory-mapped and [vision] enables photo questions.
 */
data class ModelPackage(
    val id: String,
    val title: String,
    val summary: String,
    val tier: PhoneTier,
    val main: ModelFile,
    val vision: ModelFile?,
    val streamed: Boolean,
    val budgetBytes: Long,
    val license: String,
    val licenseUrl: String,
    /** Notice text the license requires to accompany the model (HAI-DEF §3.1.5), shown verbatim. */
    val notice: String? = null,
    /** Use restrictions users must be told about (HAI-DEF §3.1.1 / §3.2). */
    val restrictionsUrl: String? = null,
) {
    val files: List<ModelFile> get() = listOfNotNull(main, vision)
    val totalBytes: Long get() = files.sumOf { it.bytes }
    val medical: Boolean get() = id.startsWith("medgemma")
}

/**
 * Two models, three tiers (decision 2026-11-18): MedGemma 1.5 4B (Jan 2026, text + medical
 * images) wherever it can run, Gemma 4 E2B (Apr 2026, built for phones) on low-RAM phones.
 */
object ModelCatalog {
    private const val GiB = 1L shl 30
    private const val HF = "https://huggingface.co"

    // The exact Q4_K_M file msr_engine was validated with (MODEL_COMPATIBILITY.md, same SHA-256).
    private val medgemmaQ4 = ModelFile(
        "medgemma-1.5-4b-it.Q4_K_M.gguf",
        "$HF/mradermacher/medgemma-1.5-4b-it-GGUF/resolve/main/medgemma-1.5-4b-it.Q4_K_M.gguf",
        2_489_894_624, "ee5121f1b6ffda000f65bcf14b86a653f1beae2438663381f61980e3cf639454",
    )
    private val medgemmaVision = ModelFile(
        "medgemma-1.5-4b-it.mmproj-Q8_0.gguf",
        "$HF/mradermacher/medgemma-1.5-4b-it-GGUF/resolve/main/medgemma-1.5-4b-it.mmproj-Q8_0.gguf",
        591_377_888, "51b3afea721c89e03e83abe7e80a536271016554aaf853ed5c587230c4dc0cf0",
    )
    private val gemma4E2b = ModelFile(
        "gemma-4-E2B-it-Q4_0.gguf",
        "$HF/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_0.gguf",
        2_841_481_184, "8e30dff3ac4c8434c49a7036fa15564bdbb6044e42bf04550bf1a096ad7e6a52",
    )
    private val gemma4Vision = ModelFile(
        "mmproj-gemma-4-E2B-it-Q8_0.gguf",
        "$HF/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/mmproj-gemma-4-E2B-it-Q8_0.gguf",
        557_368_096, "042cc2561806cd3b5cb5efb25cab76911e3100c43cc09d854904f921924b1429",
    )

    private const val MEDGEMMA_TERMS = "Health AI Developer Foundations terms"
    private const val MEDGEMMA_TERMS_URL = "https://developers.google.com/health-ai-developer-foundations/terms"

    /** Required word for word with every distribution of HAI-DEF models (HAI-DEF terms §3.1.5). */
    const val HAI_DEF_NOTICE =
        "HAI-DEF is provided under and subject to the Health AI Developer Foundations Terms of Use found at " +
            "https://developers.google.com/health-ai-developer-foundations/terms"
    private const val HAI_DEF_PROHIBITED_USE_URL =
        "https://developers.google.com/health-ai-developer-foundations/prohibited-use-policy"

    val packages = listOf(
        ModelPackage(
            id = "medgemma-4b-resident", title = "MedGemma 1.5 4B · full",
            summary = "Google's medical model, held in memory. Fastest answers and reads photos (medicine packs, wounds, documents).",
            tier = PhoneTier.HIGH, main = medgemmaQ4, vision = medgemmaVision,
            streamed = false, budgetBytes = 0, license = MEDGEMMA_TERMS, licenseUrl = MEDGEMMA_TERMS_URL,
            notice = HAI_DEF_NOTICE, restrictionsUrl = HAI_DEF_PROHIBITED_USE_URL,
        ),
        ModelPackage(
            id = "medgemma-4b-streamed", title = "MedGemma 1.5 4B · streamed",
            summary = "The same medical model, streamed from storage to fit in about 1.3 GB of memory. Text only, slower.",
            tier = PhoneTier.MID, main = medgemmaQ4, vision = null,
            streamed = true, budgetBytes = GiB, license = MEDGEMMA_TERMS, licenseUrl = MEDGEMMA_TERMS_URL,
            notice = HAI_DEF_NOTICE, restrictionsUrl = HAI_DEF_PROHIBITED_USE_URL,
        ),
        ModelPackage(
            id = "gemma4-e2b", title = "Gemma 4 E2B",
            summary = "Google's small phone model. Not medically trained, so answers rely on your saved sources. Reads photos.",
            tier = PhoneTier.LOW, main = gemma4E2b, vision = gemma4Vision,
            streamed = false, budgetBytes = 0, license = "Apache 2.0", licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
        ),
    )

    /**
     * Tier from total RAM as Android reports it (always a little under the marketing size):
     * 4 GB phones report ~3.6 GiB, 6 GB ~5.5 GiB, 8 GB ~7.3 GiB.
     */
    fun tierFor(totalRamBytes: Long): PhoneTier = when {
        totalRamBytes < 9L * GiB / 2 -> PhoneTier.LOW  // < 4.5 GiB
        totalRamBytes < 7L * GiB -> PhoneTier.MID
        else -> PhoneTier.HIGH
    }

    fun recommendedFor(totalRamBytes: Long): ModelPackage = packages.first { it.tier == tierFor(totalRamBytes) }

    /**
     * Weight repacking speeds up Q4 matrix math at the cost of extra un-evictable RAM.
     * Safe for Gemma 4 (Q4_0, +~360 MB) on 6 GB+ phones and MedGemma (Q4_K_M, +~1.5 GB) on 10 GB+.
     */
    fun shouldRepack(pkg: ModelPackage, totalRamBytes: Long): Boolean {
        if (pkg.streamed) return false
        return if (pkg.medical) totalRamBytes >= 9L * GiB else totalRamBytes >= 9L * GiB / 2
    }

    fun byId(id: String): ModelPackage? = packages.firstOrNull { it.id == id }
}
