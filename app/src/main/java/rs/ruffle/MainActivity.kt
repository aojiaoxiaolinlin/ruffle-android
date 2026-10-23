package rs.ruffle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import rs.ruffle.ui.theme.RuffleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        Diagnostics.installCrashHandler(this)
        super.onCreate(savedInstanceState)

        setContent {
            RuffleTheme {
                Box(Modifier.fillMaxSize()) {
                    RocoLoginScreen()
                    CrashPrompt()
                }
            }
        }
    }
}
