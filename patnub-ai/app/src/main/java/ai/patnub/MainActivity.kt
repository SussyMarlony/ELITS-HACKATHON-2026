package ai.patnub

import ai.patnub.ui.ModelHost
import ai.patnub.ui.PatnubApp
import ai.patnub.ui.patient.PatientServices
import ai.patnub.ui.theme.PatnubTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PatnubTheme {
                val app = application as PatnubApplication
                PatnubApp(app.engine, app.identity, ModelHost(app.models, { app.loadedPackageId }, app::startModel)) { selfId, code ->
                    PatientServices(app.database, app.importer, app.assistant, selfId, code)
                }
            }
        }
    }
}
