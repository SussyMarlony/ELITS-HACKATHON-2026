package ai.patnub

import ai.patnub.data.local.PatnubDatabase
import ai.patnub.data.local.seedDemoData
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PatnubApplication : Application() {
    /** App-lifetime scope for work that must outlive any single screen. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One database instance per process. Opened on first use. */
    val database: PatnubDatabase by lazy { PatnubDatabase.create(this) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { database.seedDemoData() }
    }
}
