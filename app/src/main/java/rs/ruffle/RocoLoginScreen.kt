package rs.ruffle

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Amber = Color(0xFFF8A72D.toInt())
private val TextPrimary = Color(0xFFF7E9D7.toInt())
private val TextSecondary = Color(0xB3D7C3AE.toInt())
private val CardBackground = Color(0xE61B1712.toInt())
private val DarkText = Color(0xFF2A1800.toInt())
private val Hairline = Color(0x26FFC46B)

private const val AUTHOR_NAME = "傲娇小霖霖"
private const val AUTHOR_QQ = "1075919913"
private const val AUTHOR_BILIBILI_URL = "https://space.bilibili.com/33570253"
private const val AUTHOR_BILIBILI_DISPLAY = "space.bilibili.com/33570253"

@Composable
fun RocoLoginScreen() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val submit = {
        context.startActivity(android.content.Intent(context, RocoLoginActivity::class.java))
    }
    val reauthorize = {
        context.startActivity(
            android.content.Intent(context, RocoLoginActivity::class.java)
                .putExtra("reauthorize", true)
        )
    }

    val copyQq: () -> Unit = {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("QQ 群", AUTHOR_QQ))
        Toast.makeText(context, "QQ 群号已复制", Toast.LENGTH_SHORT).show()
    }
    val openBilibili: () -> Unit = {
        runCatching { uriHandler.openUri(AUTHOR_BILIBILI_URL) }
            .onFailure {
                Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
            }
        Unit
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF09111F), Color(0xFF13100B), Color(0xFF1D1208))
                )
            )
    ) {
        Image(
            painter = painterResource(R.drawable.roco_art),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 110.dp, y = (-80).dp)
                .size(430.dp)
                .alpha(0.055f),
            contentScale = ContentScale.Fit
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
        ) {
            val landscape = maxWidth > maxHeight
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = if (landscape) 32.dp else 24.dp,
                        vertical = if (landscape) 16.dp else 28.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (landscape) {
                    Arrangement.Center
                } else {
                    Arrangement.Top
                }
            ) {
                if (landscape) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = 920.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(0.9f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            BrandingBlock(compact = true)
                            AuthorCard(
                                onCopyQq = copyQq,
                                onOpenBilibili = openBilibili,
                                compact = true
                            )
                            Text(
                                text = stringResource(R.string.client_copyright),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary.copy(alpha = 0.55f),
                                textAlign = TextAlign.Center
                            )
                        }
                        LoginFormCard(
                            modifier = Modifier.weight(1.1f),
                            onSubmit = { submit() },
                            onReauthorize = { reauthorize() },
                            compact = true
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 440.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        BrandingBlock(compact = false)
                        LoginFormCard(
                            modifier = Modifier.fillMaxWidth(),
                            onSubmit = { submit() },
                            onReauthorize = { reauthorize() },
                            compact = false
                        )
                        AuthorCard(
                            onCopyQq = copyQq,
                            onOpenBilibili = openBilibili,
                            compact = false
                        )
                        Text(
                            text = stringResource(R.string.client_copyright),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandingBlock(compact: Boolean) {
    val glowSize = if (compact) 96.dp else 116.dp
    val logoSize = if (compact) 68.dp else 82.dp
    val corner = if (compact) 18.dp else 22.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(glowSize)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x66F8A72D), Color.Transparent)
                    ),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.roco_icon),
                contentDescription = null,
                modifier = Modifier
                    .size(logoSize)
                    .clip(RoundedCornerShape(corner))
                    .border(1.dp, Color(0x66FFC46B), RoundedCornerShape(corner)),
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "洛克王国",
                fontSize = if (compact) 26.sp else 32.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                color = TextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "游戏不卡 · 登录 QQ 账号，开启魔法冒险",
                style = if (compact) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LoginFormCard(
    modifier: Modifier,
    onSubmit: () -> Unit,
    onReauthorize: () -> Unit,
    compact: Boolean
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(if (compact) 22.dp else 26.dp),
        color = CardBackground,
        border = BorderStroke(1.dp, Hairline),
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("QQ 官方登录", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "自动保存并复用登录信息，有效时直接进入游戏。首次登录或失效后使用 QQ 官方授权。",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = DarkText
                )
            ) {
                Text("进入游戏", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onReauthorize, modifier = Modifier.fillMaxWidth()) {
                Text("重新授权 / 切换账号", color = Amber)
            }
        }
    }
}

@Composable
private fun AuthorCard(onCopyQq: () -> Unit, onOpenBilibili: () -> Unit, compact: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 16.dp else 20.dp),
        color = Color(0x0DFFFFFF),
        border = BorderStroke(1.dp, Color(0x1FFFFFFF))
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 10.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (compact) "关于作者 · $AUTHOR_NAME" else "关于作者",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Amber,
                    letterSpacing = 0.5.sp
                )
                Spacer(Modifier.width(8.dp))
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = Hairline,
                    thickness = 1.dp
                )
            }
            if (!compact) {
                Text(
                    AUTHOR_NAME,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            Text(
                "本软件目前免费供玩家使用。若你是花钱买来的，娃儿，你上当了！请向购买平台举报卖家，别再付款。",
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x1FF8A72D), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 8.dp),
                style = if (compact) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = Amber,
                textAlign = TextAlign.Center
            )
            AuthorLinkRow(
                icon = R.drawable.ic_content_copy,
                title = "QQ 群",
                subtitle = AUTHOR_QQ,
                action = "复制",
                compact = compact,
                onClick = onCopyQq
            )
            AuthorLinkRow(
                icon = R.drawable.ic_open_in_new,
                title = "Bilibili 主页",
                subtitle = AUTHOR_BILIBILI_DISPLAY,
                action = "打开",
                compact = compact,
                onClick = onOpenBilibili
            )
            if (!compact) {
                Text(
                    "点击群号复制，点击主页在浏览器中打开",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.65f)
                )
            }
        }
    }
}

@Composable
private fun AuthorLinkRow(
    icon: Int,
    title: String,
    subtitle: String,
    action: String,
    compact: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(
                horizontal = 4.dp,
                vertical = if (compact) 2.dp else 6.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Amber,
            modifier = Modifier.size(if (compact) 16.dp else 18.dp)
        )
        Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary
            )
        }
        Text(
            action,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Amber
        )
    }
}
