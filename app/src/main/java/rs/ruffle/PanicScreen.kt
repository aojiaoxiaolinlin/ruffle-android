package rs.ruffle

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rs.ruffle.ui.theme.RuffleTheme

private val PanicAmber = Color(0xFFF8A72D.toInt())
private val PanicText = Color(0xFFF7E9D7.toInt())
private val PanicSecondary = Color(0xB3D7C3AE.toInt())
private val PanicCard = Color(0xF21B1712.toInt())
private val PanicDark = Color(0xFF2A1800.toInt())

@Composable
fun PanicScreen(
    message: String,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF12100C)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = PanicCard,
                border = BorderStroke(1.dp, Color(0x33FFC46B))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.crash_title),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = PanicText
                    )
                    Text(
                        stringResource(R.string.crash_error_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = PanicAmber
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0F0D0A)
                    ) {
                        SelectionContainer {
                            Text(
                                text = message,
                                modifier = Modifier
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = PanicSecondary
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.crash_privacy_hint),
                        fontSize = 11.sp,
                        color = PanicSecondary.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onShare,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PanicAmber,
                                contentColor = PanicDark
                            )
                        ) {
                            Text(
                                stringResource(R.string.crash_share),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedButton(
                            onClick = onCopy,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0x66FFC46B)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = PanicAmber
                            )
                        ) {
                            Text(stringResource(R.string.copy_error_details))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onClose) {
                            Text(
                                stringResource(R.string.crash_close),
                                color = PanicSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Panic - Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Panic - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PanicScreenPreview() {
    RuffleTheme {
        PanicScreen(
            message = "thread 'main' panicked at 'wgpu error: Out of Memory'\n" +
                "   0: ruffle_render_wgpu::backend::create_buffer\n" +
                "   1: ruffle_core::player::Player::render",
            onShare = {},
            onCopy = {},
            onClose = {}
        )
    }
}
