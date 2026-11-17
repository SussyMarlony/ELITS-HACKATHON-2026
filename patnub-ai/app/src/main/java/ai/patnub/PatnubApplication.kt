package ai.patnub

import ai.patnub.assistant.Assistant
import ai.patnub.data.local.PatnubDatabase
import ai.patnub.data.local.seedDemoData
import ai.patnub.engine.Engine
import ai.patnub.engine.EngineConfig
import ai.patnub.engine.EngineException
import ai.patnub.engine.EngineState
import ai.patnub.engine.NativeEngine
import ai.patnub.identity.PatientIdentity
import ai.patnub.models.ModelStore
import ai.patnub.transfer.RecordImporter
import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PatnubApplication : Application() {
    /** App-lifetime scope for work that must outlive any single screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One database instance per process. Opened on first use. */
    val database: PatnubDatabase by lazy { PatnubDatabase.create(this) }

    /** The patient who owns this phone. */
    val identity: PatientIdentity by lazy { PatientIdentity(this, database) }

    val importer: RecordImporter by lazy { RecordImporter(database, identity) }
    val assistant: Assistant by lazy { Assistant(database, engine) }
    val models: ModelStore by lazy { ModelStore(this) }

    /** One engine per process (msr_engine's provider is a singleton). Loads once a model is installed. */
    val engine: Engine by lazy { NativeEngine() }

    /** Id of the package loaded into [engine] in this process, if any. */
    @Volatile var loadedPackageId: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        models.refresh()
        appScope.launch {
            database.seedDemoData()
            identity.load()
        }
        startModel()
    }

    /** Loads the selected model if it is installed and nothing is loaded yet. Safe to call again. */
    fun startModel() {
        val p = models.selected
        if (!models.isReady(p) || engine.state.value != EngineState.Unloaded) return
        appScope.launch {
            try {
                engine.load(
                    EngineConfig(
                        modelPath = models.fileFor(p.main).path,
                        visionPath = p.vision?.let { models.fileFor(it).path },
                        streamed = p.streamed,
                        budgetBytes = p.budgetBytes,
                        threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                    ),
                )
                loadedPackageId = p.id
            } catch (e: EngineException) {
                Log.e(TAG, "Model failed to load", e)
            }
        }
    }

    private companion object {
        const val TAG = "PatnubApplication"
    }
}
