// JNI bridge for ai.patnub.engine.NativeEngine.
//
// One generation loop, two ways to hold the weights (chosen per phone tier, see ModelCatalog.kt):
//  - STREAMED: msr_engine's streaming provider (msr_external_configure + MSR_NO_ALLOC, the same
//    load settings as msr_engine_load) keeps weights in a bounded arena. Text only; validated with
//    MedGemma 1.5 4B Q4_K_M (msr_engine/LIMITATION.md).
//  - RESIDENT: stock llama.cpp (mmap) plus mtmd for images. Used when the phone has the RAM.
// Both use the same prompt handling (raw chat turns, reasoning pre-fill, special tokens passed
// through for the Kotlin cleaner, repeat penalty). The provider is a process-wide singleton
// (LIMITATION.md B3), so one model per process.
#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdio>  // host test builds (scripts/test-native-host.sh)
#endif

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include <llama.h>
#include <mtmd.h>
#include <mtmd-helper.h>
#include <msr/msr_external_abi.h>

extern "C" void msr_streaming_cancel();

#define TAG "patnub_llm"
#ifdef __ANDROID__
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
#define LOGE(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

enum class Family { GEMMA3, GEMMA4, OTHER };

struct Bridge {
    bool streamed = false;

    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    mtmd_context* vision = nullptr;
    Family family = Family::OTHER;

    std::atomic<bool> cancel{false};
    std::mutex generate_mutex;
    std::string last_error;

    // Last generation (both modes); streamed mode adds msr_external_get_stats in nativeStats.
    // prompt_tokens = tokens actually processed; reused_tokens = served from the KV cache.
    uint64_t prompt_tokens = 0, reused_tokens = 0, generated_tokens = 0, ttft_ms = 0, prompt_ms = 0, generate_ms = 0;

    // Tokens currently in the KV cache (sequence 0), in order. A new prompt that starts with the
    // same tokens (instructions, earlier turns) only processes what is new: follow-up questions
    // answer in seconds instead of re-reading the whole conversation.
    std::vector<llama_token> kv_tokens;
};

std::once_flag g_backend_once;

using Clock = std::chrono::steady_clock;
uint64_t ms_since(Clock::time_point t0) {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t0).count());
}

void throw_java(JNIEnv* env, const std::string& msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(cls, msg.c_str());
}

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

Family family_of(const llama_model* model) {
    char arch[64] = {0};
    llama_model_meta_val_str(model, "general.architecture", arch, sizeof(arch));
    if (std::strncmp(arch, "gemma4", 6) == 0) return Family::GEMMA4;
    if (std::strncmp(arch, "gemma3", 6) == 0) return Family::GEMMA3;
    return Family::OTHER;
}

// One user turn, then open the model turn. Gemma 4's turn markers are not in llama.cpp's
// built-in template list, so both Gemma families are formatted here explicitly.
std::string format_turn(const Bridge* b, const std::string& user) {
    switch (b->family) {
        case Family::GEMMA4: return "<|turn>user\n" + user + "<turn|>\n<|turn>model\n";
        // MedGemma 1.5 (Gemma 3) writes a visible reasoning block first ("<unused94>thought ... <unused95>")
        // that can use up the whole token budget. An empty block makes it answer directly.
        case Family::GEMMA3: return "<start_of_turn>user\n" + user + "<end_of_turn>\n<start_of_turn>model\n<unused94>thought\n<unused95>";
        default: break;
    }
    const char* tmpl = llama_model_chat_template(b->model, nullptr);
    if (!tmpl) return user;
    llama_chat_message msg{"user", user.c_str()};
    int need = llama_chat_apply_template(tmpl, &msg, 1, true, nullptr, 0);
    if (need <= 0) return user;
    std::vector<char> buf(static_cast<size_t>(need) + 1);
    int n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), static_cast<int32_t>(buf.size()));
    return n > 0 ? std::string(buf.data(), static_cast<size_t>(n)) : user;
}

std::string piece_of(const llama_vocab* vocab, llama_token t) {
    char buf[256];
    // special = true: reasoning markers (<unused94>/<unused95>, <|channel>) reach Kotlin, which hides
    // the reasoning exactly instead of guessing where it ends.
    int32_t n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, true);
    if (n >= 0) return std::string(buf, static_cast<size_t>(n));
    std::vector<char> big(static_cast<size_t>(-n));
    n = llama_token_to_piece(vocab, t, big.data(), static_cast<int32_t>(big.size()), 0, true);
    return n > 0 ? std::string(big.data(), static_cast<size_t>(n)) : std::string();
}

long long proc_status_kb(const char* key) {
    FILE* fh = std::fopen("/proc/self/status", "r");
    if (!fh) return -1;
    char line[256];
    long long v = -1;
    while (std::fgets(line, sizeof(line), fh)) {
        if (std::strncmp(line, key, std::strlen(key)) == 0) { v = std::atoll(line + std::strlen(key)); break; }
    }
    std::fclose(fh);
    return v;
}

bool abort_cb(void* data) { return static_cast<Bridge*>(data)->cancel.load(); }

struct Callback {
    JNIEnv* env;
    jobject target;
    jmethodID method;
};

// Returns false when Kotlin asks to stop.
bool emit(Callback& cb, const char* data, size_t len) {
    jbyteArray arr = cb.env->NewByteArray(static_cast<jsize>(len));
    cb.env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte*>(data));
    jboolean keep = cb.env->CallBooleanMethod(cb.target, cb.method, arr);
    cb.env->DeleteLocalRef(arr);
    if (cb.env->ExceptionCheck()) return false;
    return keep == JNI_TRUE;
}

std::vector<llama_token> tokenize(const Bridge* b, const std::string& text) {
    std::vector<llama_token> toks(text.size() + 16);
    int n = llama_tokenize(b->vocab, text.data(), static_cast<int32_t>(text.size()), toks.data(), static_cast<int32_t>(toks.size()), true, true);
    if (n < 0) {
        toks.resize(static_cast<size_t>(-n));
        n = llama_tokenize(b->vocab, text.data(), static_cast<int32_t>(text.size()), toks.data(), static_cast<int32_t>(toks.size()), true, true);
    }
    if (n < 0) return {};
    toks.resize(static_cast<size_t>(n));
    return toks;
}

void forget_kv(Bridge* b) {
    llama_memory_clear(llama_get_memory(b->ctx), true);
    b->kv_tokens.clear();
}

/**
 * Makes the KV cache hold exactly [toks], reusing the longest prefix already there. At least the
 * last token is always (re)decoded so its logits exist. Returns 0 ok, 7 aborted, 8 error.
 */
int sync_kv(Bridge* b, const std::vector<llama_token>& toks) {
    size_t keep = 0;
    while (keep < b->kv_tokens.size() && keep < toks.size() && b->kv_tokens[keep] == toks[keep]) ++keep;
    if (keep >= toks.size()) keep = toks.empty() ? 0 : toks.size() - 1;
    if (keep == 0 || !llama_memory_seq_rm(llama_get_memory(b->ctx), 0, static_cast<llama_pos>(keep), -1)) {
        forget_kv(b);
        keep = 0;
    }
    b->kv_tokens.resize(keep);
    b->reused_tokens = keep;
    for (size_t off = keep; off < toks.size(); off += 512) {
        const int chunk = static_cast<int>(std::min<size_t>(512, toks.size() - off));
        if (llama_decode(b->ctx, llama_batch_get_one(const_cast<llama_token*>(toks.data()) + off, chunk)) != 0) {
            forget_kv(b);  // partial batch: the cache no longer matches kv_tokens
            return b->cancel.load() ? 7 : 8;
        }
        b->kv_tokens.insert(b->kv_tokens.end(), toks.begin() + static_cast<long>(off), toks.begin() + static_cast<long>(off) + chunk);
    }
    b->prompt_tokens = toks.size() - keep;
    return 0;
}

// Resident generation with optional image. Returns 0 ok, 7 aborted, 8 error (msr_result codes).
int generate_resident(Bridge* b, const std::string& prompt, const std::string& image, bool raw,
                      int max_tokens, float temperature, int seed, Callback& cb) {
    std::string user = prompt;
    mtmd::bitmap_ptr bmp;
    if (!image.empty()) {
        if (!b->vision) { b->last_error = "this model was loaded without image support"; return 1; }
        mtmd_helper_bitmap_wrapper w = mtmd_helper_bitmap_init_from_file(b->vision, image.c_str(), false, mtmd_helper_init_opt_default());
        if (!w.bitmap) { b->last_error = "could not read the image"; return 1; }
        bmp.reset(w.bitmap);
        // Raw prompts are already in the chat format; Kotlin put the media marker in the last user turn.
        if (!raw) user = std::string(mtmd_default_marker()) + "\n" + prompt;
    }
    const std::string text = raw ? prompt : format_turn(b, user);

    const auto t0 = Clock::now();
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(b->ctx));

    if (bmp) {
        // Photo turns are not cached (image tokens are not plain token ids): start clean.
        forget_kv(b);
        mtmd::input_chunks_ptr chunks(mtmd_input_chunks_init());
        mtmd_input_text in{text.c_str(), text.size(), /*add_special=*/true, /*parse_special=*/true};
        const mtmd_bitmap* bitmaps[1] = {bmp.get()};
        if (mtmd_tokenize(b->vision, chunks.get(), &in, bitmaps, 1) != 0) {
            b->last_error = "tokenize failed"; return 8;
        }
        const size_t n_tok = mtmd_helper_get_n_tokens(chunks.get());
        if (static_cast<int32_t>(n_tok) + max_tokens > n_ctx) { b->last_error = "prompt + max_tokens exceeds context"; return 8; }
        llama_pos n_past = 0;
        if (mtmd_helper_eval_chunks(b->vision, b->ctx, chunks.get(), 0, 0, 512, true, &n_past) != 0) {
            forget_kv(b);
            if (b->cancel.load()) return 7;
            b->last_error = "prompt evaluation failed"; return 8;
        }
        b->prompt_tokens = n_tok;
        b->reused_tokens = 0;
    } else {
        const std::vector<llama_token> toks = tokenize(b, text);
        if (toks.empty()) { b->last_error = "tokenize failed"; return 8; }
        if (static_cast<int32_t>(toks.size()) + max_tokens > n_ctx) {
            // Long conversations: drop the cache and let Kotlin's history cap keep prompts short.
            b->last_error = "prompt + max_tokens exceeds context"; return 8;
        }
        const int rc = sync_kv(b, toks);
        if (rc == 7) return 7;
        if (rc != 0) { b->last_error = "prompt decode failed"; return 8; }
    }
    b->prompt_ms = ms_since(t0);

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Small models loop ("I know you have ... I know you have ..."); a mild repeat penalty stops it.
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(b->vocab), 64, 1.15f, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }
    const auto g0 = Clock::now();
    int rc = 0;
    for (int i = 0; i < max_tokens; ++i) {
        if (b->cancel.load()) { rc = 7; break; }
        llama_token tok = llama_sampler_sample(smpl, b->ctx, -1);
        llama_sampler_accept(smpl, tok);
        if (llama_vocab_is_eog(b->vocab, tok)) break;
        if (b->generated_tokens == 0) b->ttft_ms = ms_since(t0);
        ++b->generated_tokens;
        const std::string piece = piece_of(b->vocab, tok);
        if (!piece.empty() && !emit(cb, piece.data(), piece.size())) { rc = 7; break; }
        if (llama_decode(b->ctx, llama_batch_get_one(&tok, 1)) != 0) {
            forget_kv(b);
            if (b->cancel.load()) { rc = 7; break; }
            b->last_error = "decode failed"; rc = 8; break;
        }
        if (!bmp) b->kv_tokens.push_back(tok);
    }
    b->generate_ms = ms_since(g0);
    llama_sampler_free(smpl);
    return rc;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_ai_patnub_engine_NativeEngine_nativeLoad(JNIEnv* env, jclass, jstring jmodel, jstring jmmproj, jboolean streamed,
                                              jlong budget, jint ctx_tokens, jint threads, jint read_threads, jboolean prefetch, jboolean repack) {
    const std::string model_path = jstr(env, jmodel);
    const std::string mmproj = jstr(env, jmmproj);
    auto* b = new Bridge();
    b->streamed = streamed == JNI_TRUE;

    if (b->streamed) {
        // Same settings as msr_engine_load: the provider reads them at the first model load.
        msr_external_configure(model_path.c_str(), static_cast<unsigned long long>(budget), prefetch ? 1 : 0, read_threads);
        setenv("MSR_NO_ALLOC", "1", 1);
    }

    std::call_once(g_backend_once, [] { llama_backend_init(); });
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU only: consistent across phones
    // Repacking speeds up Q4 matrix maths by copying weights into an optimized CPU layout, at the
    // cost of extra private RAM; only enabled on phones with headroom.
    mp.use_extra_bufts = repack == JNI_TRUE && !b->streamed;
    if (b->streamed) {
        mp.load_mode = LLAMA_LOAD_MODE_NONE;  // no whole-file mmap; the provider binds the weights
        mp.lazy_mode = LLAMA_LAZY_MODE_OFF;
    }
    b->model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!b->model) { delete b; throw_java(env, "Could not load the model file"); return 0; }
    b->vocab = llama_model_get_vocab(b->model);
    b->family = family_of(b->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = ctx_tokens > 0 ? static_cast<uint32_t>(ctx_tokens) : 2048;
    cp.n_batch = 512;
    cp.n_threads = threads > 0 ? threads : 4;
    cp.n_threads_batch = cp.n_threads;
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = b;
    b->ctx = llama_init_from_model(b->model, cp);
    if (!b->ctx) { llama_model_free(b->model); delete b; throw_java(env, "Could not create the model context"); return 0; }

    if (!mmproj.empty() && !b->streamed) {
        mtmd_context_params vp = mtmd_context_params_default();
        vp.use_gpu = false;
        vp.n_threads = cp.n_threads;
        vp.print_timings = false;
        vp.warmup = false;
        // Cap dynamic-resolution models (Gemma 4) so phone camera photos stay well under ~10 s.
        vp.image_max_tokens = 256;
        b->vision = mtmd_init_from_file(mmproj.c_str(), b->model, vp);
        if (!b->vision) LOGE("vision projector failed to load; continuing text-only");
    }
    return reinterpret_cast<jlong>(b);
}

JNIEXPORT jint JNICALL
Java_ai_patnub_engine_NativeEngine_nativeGenerate(JNIEnv* env, jclass, jlong handle, jstring jprompt, jstring jimage,
                                                  jboolean raw, jint max_tokens, jfloat temperature, jint seed, jobject callback) {
    auto* b = reinterpret_cast<Bridge*>(handle);
    if (!b) return 1;
    std::lock_guard<std::mutex> lock(b->generate_mutex);
    b->cancel.store(false);
    b->last_error.clear();
    b->prompt_tokens = b->generated_tokens = b->ttft_ms = b->prompt_ms = b->generate_ms = 0;

    jclass cls = env->GetObjectClass(callback);
    Callback cb{env, callback, env->GetMethodID(cls, "onToken", "([B)Z")};
    const std::string prompt = jstr(env, jprompt);
    const std::string image = jstr(env, jimage);

    return generate_resident(b, prompt, image, raw == JNI_TRUE, max_tokens, temperature, seed, cb);
}

/** Processes [text] into the KV cache without generating (instructions at load, warm-up). */
JNIEXPORT jint JNICALL
Java_ai_patnub_engine_NativeEngine_nativePrefill(JNIEnv* env, jclass, jlong handle, jstring jtext) {
    auto* b = reinterpret_cast<Bridge*>(handle);
    if (!b) return 1;
    std::lock_guard<std::mutex> lock(b->generate_mutex);
    b->cancel.store(false);
    const std::vector<llama_token> toks = tokenize(b, jstr(env, jtext));
    if (toks.empty()) return 1;
    return sync_kv(b, toks);
}

JNIEXPORT void JNICALL
Java_ai_patnub_engine_NativeEngine_nativeCancel(JNIEnv*, jclass, jlong handle) {
    auto* b = reinterpret_cast<Bridge*>(handle);
    if (!b) return;
    b->cancel.store(true);
    if (b->streamed) msr_streaming_cancel();  // drops queued prefetches
}

JNIEXPORT jstring JNICALL
Java_ai_patnub_engine_NativeEngine_nativeLastError(JNIEnv* env, jclass, jlong handle) {
    auto* b = reinterpret_cast<Bridge*>(handle);
    return env->NewStringUTF(b ? b->last_error.c_str() : "no engine");
}

/** 3 = Gemma 3 family (MedGemma), 4 = Gemma 4, 0 = other. */
JNIEXPORT jint JNICALL
Java_ai_patnub_engine_NativeEngine_nativeFamily(JNIEnv*, jclass, jlong handle) {
    auto* b = reinterpret_cast<Bridge*>(handle);
    if (!b) return 0;
    return b->family == Family::GEMMA3 ? 3 : b->family == Family::GEMMA4 ? 4 : 0;
}

JNIEXPORT jboolean JNICALL
Java_ai_patnub_engine_NativeEngine_nativeSupportsImages(JNIEnv*, jclass, jlong handle) {
    auto* b = reinterpret_cast<Bridge*>(handle);
    return b && b->vision && mtmd_support_vision(b->vision) ? JNI_TRUE : JNI_FALSE;
}

/** [prompt, generated, ttft, prompt_ms, generate_ms, budget, resident, peak_weights, read_bytes,
 *   streamed_layers, vm_hwm_kb, vm_rss_kb, reused_prompt_tokens] */
JNIEXPORT jlongArray JNICALL
Java_ai_patnub_engine_NativeEngine_nativeStats(JNIEnv* env, jclass, jlong handle) {
    auto* b = reinterpret_cast<Bridge*>(handle);
    jlong v[13] = {0};
    if (b) {
        v[0] = (jlong) b->prompt_tokens; v[1] = (jlong) b->generated_tokens; v[2] = (jlong) b->ttft_ms;
        v[3] = (jlong) b->prompt_ms; v[4] = (jlong) b->generate_ms;
        v[12] = (jlong) b->reused_tokens;
        if (b->streamed) {
            msr_external_stats w{};
            msr_external_get_stats(&w);
            v[5] = (jlong) w.budget_bytes; v[6] = (jlong) w.resident_weight_bytes; v[7] = (jlong) w.peak_weight_bytes;
            v[8] = (jlong) w.read_bytes; v[9] = (jlong) w.streamed_layers;
        }
        v[10] = proc_status_kb("VmHWM:"); v[11] = proc_status_kb("VmRSS:");
    }
    jlongArray out = env->NewLongArray(13);
    env->SetLongArrayRegion(out, 0, 13, v);
    return out;
}

JNIEXPORT void JNICALL
Java_ai_patnub_engine_NativeEngine_nativeFree(JNIEnv*, jclass, jlong handle) {
    auto* b = reinterpret_cast<Bridge*>(handle);
    if (!b) return;
    b->cancel.store(true);
    std::lock_guard<std::mutex> lock(b->generate_mutex); // never free while generating
    if (b->vision) mtmd_free(b->vision);
    if (b->ctx) llama_free(b->ctx);
    if (b->model) llama_model_free(b->model);
    delete b;
}

}  // extern "C"
