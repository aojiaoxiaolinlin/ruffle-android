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
                    KabuLoginScreen { uri, baseUrl, auth -> openSwf(uri, baseUrl, auth) }
                    CrashPrompt()
                }
            }
        }
    }

    private fun openSwf(uri: Uri, baseUrl: String?, auth: String) {
        val intent = Intent(
            this@MainActivity,
            PlayerActivity::class.java
        ).apply {
            data = uri
            putExtra("baseUrl", baseUrl)
            putExtra("authString", auth)
        }
        startActivity(intent)
    }
}
