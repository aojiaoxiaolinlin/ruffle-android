package rs.ruffle

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PromptCard = Color(0xF21B1712.toInt())
private val PromptText = Color(0xFFF7E9D7.toInt())
private val PromptSecondary = Color(0xFFD7C3AE)
private val PromptAmber = Color(0xFFF8A72D.toInt())

/**
 * Shown on the next launch after an uncaught exception or Rust panic.
 * The log file stays on disk and can also be shared from the player settings.
 */
@Composable
fun CrashPrompt() {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Diagnostics.PendingCrash?>(null) }

    LaunchedEffect(Unit) {
        pending = withContext(Dispatchers.IO) { Diagnostics.pendingCrash(context) }
    }

    val crash = pending ?: return
    AlertDialog(
        onDismissRequest = {
            Diagnostics.markCrashSeen(context)
            pending = null
        },
        containerColor = PromptCard,
        titleContentColor = PromptText,
        textContentColor = PromptSecondary,
        title = { Text(stringResource(R.string.crash_last_run)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.crash_prompt_body))
                Spacer(Modifier.height(10.dp))
                Text(
                    crash.summary.take(700),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = PromptSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.crash_privacy_hint),
                    fontSize = 11.sp,
                    color = PromptSecondary.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    Diagnostics.markCrashSeen(context)
                    pending = null
                    Diagnostics.shareReport(context, crash.summary)
                }
            ) {
                Text(stringResource(R.string.crash_share), color = PromptAmber)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    Diagnostics.markCrashSeen(context)
                    pending = null
                }
            ) {
                Text(stringResource(R.string.crash_dismiss), color = PromptSecondary)
            }
        }
    )
}
