package rs.ruffle

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun KabuLoginScreen(openGame: (Uri, String, String) -> Unit) {
    val context = LocalContext.current
    val store = remember { RememberedLoginStore(context.applicationContext) }
    var username by rememberSaveable { mutableStateOf("") }
    // Passwords stay out of instance state; persistence is opt-in and encrypted.
    var password by remember { mutableStateOf("") }
    var rememberLogin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) {
        val saved = withContext(Dispatchers.IO) { store.load() }
        if (saved != null) {
            username = saved.username
            password = saved.password
            rememberLogin = true
        }
        busy = false
    }
    val submit = {
        if (!busy && username.isNotBlank() && password.isNotEmpty()) {
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
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge
                )
                Text("登录4399账号，进入卡布西游", style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("4399账号") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(onGo = { submit() })
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = rememberLogin,
                        enabled = !busy,
                        onCheckedChange = { checked ->
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
                    )
                    Text("记住账号密码（仅保存在本机）")
                }
                error?.let {
                    SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Button(
                    onClick = { submit() },
                    enabled =
                    !busy && username.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "正在登录…" else "登录并进入游戏")
                }
                if (busy) CircularProgressIndicator()
                Text(
                    "勾选后，登录成功会加密保存账号密码；取消勾选即清除。游戏中可拖动侧边工具栏调整位置。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.client_copyright),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
