package rs.ruffle

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Amber = Color(0xFFF8A72D.toInt())
private val TextPrimary = Color(0xFFF7E9D7.toInt())
private val TextSecondary = Color(0xB3D7C3AE.toInt())
private val CardBackground = Color(0xE61B1712.toInt())
private val DarkText = Color(0xFF2A1800.toInt())
private val Hairline = Color(0x26FFC46B)

private const val AUTHOR_NAME = "傲娇小霖霖"
private const val AUTHOR_QQ = "833462567"
private const val AUTHOR_BILIBILI_URL = "https://space.bilibili.com/33570253"
private const val AUTHOR_BILIBILI_DISPLAY = "space.bilibili.com/33570253"

@Composable
fun KabuLoginScreen(openGame: (Uri, String, String) -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val store = remember { RememberedLoginStore(context.applicationContext) }
    var username by rememberSaveable { mutableStateOf("") }
    // Passwords stay out of instance state; persistence is opt-in and encrypted.
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberLogin by remember { mutableStateOf(false) }
    var loadingSaved by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(store) {
        val saved = withContext(Dispatchers.IO) { store.load() }
        if (saved != null) {
            username = saved.username
            password = saved.password
            rememberLogin = true
        }
        loadingSaved = false
    }

    fun setRememberLogin(checked: Boolean) {
        rememberLogin = checked
        if (!checked) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { store.clear() }
                } finally {
                    busy = false
                }
            }
        }
    }

    val submit = {
        if (!busy && !loadingSaved && username.isNotBlank() && password.isNotEmpty()) {
            busy = true
            error = null
            val account = username.trim()
            val secret = password
            scope.launch {
                try {
                    val auth =
                        withContext(Dispatchers.IO) { KabuLoginClient().login(account, secret) }
                    val saved = withContext(Dispatchers.IO) {
                        runCatching {
                            if (rememberLogin) store.save(account, secret) else store.clear()
                        }.isSuccess
                    }
                    if (!saved) {
                        Toast.makeText(context, "登录成功，但保存账号密码失败", Toast.LENGTH_LONG).show()
                    }
                    if (!rememberLogin) password = ""
                    openGame(Uri.parse(KabuLoginClient.GAME_SWF), KabuLoginClient.BASE_URL, auth)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // Do not expose transport exceptions, URLs, cookies or credentials.
                    error =
                        if (failure is KabuLoginException) failure.message else "登录失败，请检查网络和账号密码后重试"
                } finally {
                    busy = false
                }
            }
        }
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
            painter = painterResource(R.drawable.kabu_icon),
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
                            username = username,
                            onUsernameChange = { username = it },
                            password = password,
                            onPasswordChange = { password = it },
                            passwordVisible = passwordVisible,
                            onTogglePasswordVisible = {
                                passwordVisible = !passwordVisible
                            },
                            rememberLogin = rememberLogin,
                            onRememberChange = { setRememberLogin(it) },
                            busy = busy,
                            loadingSaved = loadingSaved,
                            error = error,
                            onSubmit = { submit() },
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
                            username = username,
                            onUsernameChange = { username = it },
                            password = password,
                            onPasswordChange = { password = it },
                            passwordVisible = passwordVisible,
                            onTogglePasswordVisible = {
                                passwordVisible = !passwordVisible
                            },
                            rememberLogin = rememberLogin,
                            onRememberChange = { setRememberLogin(it) },
                            busy = busy,
                            loadingSaved = loadingSaved,
                            error = error,
                            onSubmit = { submit() },
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
                painter = painterResource(R.drawable.kabu_icon),
                contentDescription = null,
                modifier = Modifier
                    .size(logoSize)
                    .clip(RoundedCornerShape(corner))
                    .border(1.dp, Color(0x66FFC46B), RoundedCornerShape(corner)),
                contentScale = ContentScale.Crop
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "卡布西游",
                fontSize = if (compact) 26.sp else 32.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                color = TextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "游戏不卡 · 登录 4399 账号，开启西游冒险",
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
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit,
    rememberLogin: Boolean,
    onRememberChange: (Boolean) -> Unit,
    busy: Boolean,
    loadingSaved: Boolean,
    error: String?,
    onSubmit: () -> Unit,
    compact: Boolean
) {
    val focusManager = LocalFocusManager.current
    val formEnabled = !busy && !loadingSaved
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(if (compact) 22.dp else 26.dp),
        color = CardBackground,
        border = BorderStroke(1.dp, Hairline),
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
        ) {
            LoginField(
                value = username,
                onValueChange = onUsernameChange,
                label = "4399账号",
                leadingIcon = R.drawable.ic_person,
                enabled = formEnabled,
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )
            LoginField(
                value = password,
                onValueChange = onPasswordChange,
                label = "密码",
                leadingIcon = R.drawable.ic_lock,
                enabled = formEnabled,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Go,
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    Text(
                        text = if (passwordVisible) "隐藏" else "显示",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Amber,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onTogglePasswordVisible)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = formEnabled) {
                        onRememberChange(!rememberLogin)
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = rememberLogin,
                    enabled = formEnabled,
                    onCheckedChange = onRememberChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = Amber,
                        checkmarkColor = DarkText,
                        uncheckedColor = Color(0x66FFFFFF),
                        disabledCheckedColor = Amber.copy(alpha = 0.4f),
                        disabledUncheckedColor = Color(0x33FFFFFF)
                    )
                )
                Column {
                    Text(
                        "记住账号密码",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        "仅加密保存在本机",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            error?.let { message ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0x33FF5449)
                ) {
                    SelectionContainer {
                        Text(
                            message,
                            modifier = Modifier.padding(
                                horizontal = 12.dp,
                                vertical = 10.dp
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFB4AB.toInt())
                        )
                    }
                }
            }

            Button(
                onClick = onSubmit,
                enabled = formEnabled && username.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 48.dp else 52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = DarkText,
                    disabledContainerColor = Color(0x33F8A72D),
                    disabledContentColor = Color(0x802A1800.toInt())
                )
            ) {
                if (busy || loadingSaved) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = DarkText,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        busy -> "正在登录…"
                        loadingSaved -> "正在载入…"
                        else -> "登录并进入游戏"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!compact) {
                Text(
                    "勾选后会加密保存账号密码；游戏中可拖动侧边按钮调整位置。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: Int,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                painter = painterResource(leadingIcon),
                contentDescription = null,
                tint = Amber
            )
        },
        trailingIcon = trailingIcon,
        enabled = enabled,
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction
        ),
        keyboardActions = keyboardActions,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            disabledTextColor = TextSecondary,
            cursorColor = Amber,
            focusedBorderColor = Amber,
            unfocusedBorderColor = Color(0x33FFFFFF),
            disabledBorderColor = Color(0x1FFFFFFF),
            focusedLabelColor = Amber,
            unfocusedLabelColor = TextSecondary,
            disabledLabelColor = TextSecondary,
            focusedLeadingIconColor = Amber,
            unfocusedLeadingIconColor = TextSecondary,
            disabledLeadingIconColor = TextSecondary,
            focusedContainerColor = Color(0x14FFFFFF),
            unfocusedContainerColor = Color(0x0AFFFFFF),
            disabledContainerColor = Color(0x05FFFFFF)
        )
    )
}

@Composable
private fun AuthorCard(
    onCopyQq: () -> Unit,
    onOpenBilibili: () -> Unit,
    compact: Boolean
) {
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
