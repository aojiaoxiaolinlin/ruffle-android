package rs.ruffle

import android.content.Intent
import android.net.Uri
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
                    RuffleNavHost(openSwf = { uri, baseUrl -> openSwf(uri, baseUrl) })
                    CrashPrompt()
                }
            }
        }
    }

    private fun openSwf(uri: Uri, baseUrl: String?) {
        val intent = Intent(
            this@MainActivity,
            PlayerActivity::class.java
        ).apply {
            data = uri
            putExtra("baseUrl", baseUrl)
        }
        startActivity(intent)
    }
}
