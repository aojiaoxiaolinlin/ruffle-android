package rs.ruffle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import rs.ruffle.ui.theme.RuffleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            RuffleTheme {
                RuffleNavHost(openSwf = { uri, baseUrl -> openSwf(uri, baseUrl) })
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
