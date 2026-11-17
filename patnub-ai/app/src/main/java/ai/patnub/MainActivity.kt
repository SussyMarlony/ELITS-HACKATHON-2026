package ai.patnub

import ai.patnub.ui.PatnubApp
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
                PatnubApp()
            }
        }
    }
}
