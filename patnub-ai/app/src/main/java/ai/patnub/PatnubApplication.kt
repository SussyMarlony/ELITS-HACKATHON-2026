package ai.patnub

import ai.patnub.data.local.PatnubDatabase
import ai.patnub.data.local.seedDemoData
import ai.patnub.engine.Engine
import ai.patnub.engine.EngineConfig
import ai.patnub.engine.EngineException
import ai.patnub.engine.FakeEngine
import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PatnubApplication : Application() {
    /** App-lifetime scope for work that must outlive any single screen. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One database instance per process. Opened on first use. */
    val database: PatnubDatabase by lazy { PatnubDatabase.create(this) }

    /**
     * One engine per process, loaded once at startup so requests don't pay the load cost.
     * Replace FakeEngine with the JNI-backed engine when libmsr_engine is available.
     */
    val engine: Engine by lazy { FakeEngine() }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { database.seedDemoData() }
        appScope.launch {
            try {
                engine.load(EngineConfig(modelPath = MODEL_PATH))
            } catch (e: EngineException) {
                Log.e(TAG, "Engine failed to load", e)
            }
        }
    }

    private companion object {
        const val TAG = "PatnubApplication"

        // Location used on the reference device (build plan Phase 1).
        const val MODEL_PATH = "/storage/emulated/0/Download/gemma/medgemma-1.5-4b-it.Q4_K_M.gguf"
    }
}
