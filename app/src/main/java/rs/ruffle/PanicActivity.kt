package rs.ruffle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import rs.ruffle.ui.theme.RuffleTheme

class PanicActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Diagnostics.installCrashHandler(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val message = intent.getStringExtra("message") ?: "Unknown"
        val activity = this
        setContent {
            RuffleTheme {
                PanicScreen(
                    message = message,
                    onShare = {
                        Diagnostics.markCrashSeen(activity)
                        Diagnostics.shareReport(activity, message)
                    },
                    onCopy = {
                        Diagnostics.markCrashSeen(activity)
                        Diagnostics.copyReport(activity, message)
                    },
                    onClose = {
                        Diagnostics.markCrashSeen(activity)
                        activity.finish()
                    }
                )
            }
        }
    }
}
