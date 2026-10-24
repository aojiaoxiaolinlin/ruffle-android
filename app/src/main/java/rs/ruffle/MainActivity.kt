package rs.ruffle

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import rs.ruffle.ui.theme.RuffleTheme

class MainActivity : ComponentActivity() {
    private var sessionVersion by mutableIntStateOf(0)
    private var sessionExpired by mutableStateOf(false)

    private val qqLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val auth = result.data
                ?.getStringExtra(KabuQqLoginActivity.RESULT_AUTH)
                .orEmpty()
            val validated = runCatching { KabuSession.parse(auth) }.getOrNull()
            if (validated == null) {
                Toast.makeText(this, "QQ 登录凭据无效，请重试", Toast.LENGTH_LONG).show()
            } else {
                sessionVersion += 1
                openSwf(
                    Uri.parse(KabuLoginClient.GAME_SWF),
                    KabuLoginClient.BASE_URL,
                    validated
                )
            }
        } else {
            val error = result.data
                ?.getStringExtra(KabuQqLoginActivity.RESULT_ERROR)
                .orEmpty()
            if (error.isNotBlank()) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        Diagnostics.installCrashHandler(this)
        super.onCreate(savedInstanceState)
        sessionExpired = intent.getBooleanExtra(KabuSession.EXPIRED, false)
        intent.removeExtra(KabuSession.EXPIRED)
        intent.removeExtra(KabuSession.REAUTHORIZE)

        setContent {
            RuffleTheme {
                Box(Modifier.fillMaxSize()) {
                    KabuLoginScreen(
                        sessionVersion = sessionVersion,
                        sessionExpired = sessionExpired,
                        openGame = { uri, baseUrl, auth -> openSwf(uri, baseUrl, auth) },
                        onQqLogin = {
                            qqLoginLauncher.launch(
                                KabuQqLoginActivity.newIntent(this@MainActivity)
                            )
                        }
                    )
                    CrashPrompt()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(KabuSession.REAUTHORIZE, false)) {
            sessionExpired = intent.getBooleanExtra(KabuSession.EXPIRED, false)
            sessionVersion += 1
            intent.removeExtra(KabuSession.REAUTHORIZE)
            intent.removeExtra(KabuSession.EXPIRED)
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
