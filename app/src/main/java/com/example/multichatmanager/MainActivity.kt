package com.example.multichatmanager

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

private val Bg = Color(0xFF101116)
private val Panel = Color(0xFF1B1D26)
private val Purple = Color(0xFF9B72F2)
private val Muted = Color(0xFF9DA0AE)

class MainActivity : ComponentActivity() {
    private lateinit var secureStore: SecureAccountStore
    private lateinit var twitchOAuth: TwitchOAuth
    private lateinit var tokenConnector: TokenConnector
    private var pendingGoogleAccountId: Int? = null
    private var pendingGoogleCallback: ((Result<String>) -> Unit)? = null

    companion object {
        private const val REQUEST_GOOGLE_AUTH = 7401
        @Volatile var authUiListener: ((Int, Result<String>) -> Unit)? = null
        @Volatile var twitchStartListener: ((Int, String) -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureStore = SecureAccountStore(this)
        twitchOAuth = TwitchOAuth(this, secureStore)
        tokenConnector = TokenConnector(secureStore)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Bg, surface = Panel)) {
                MultiAccountApp(
                    store = secureStore,
                    onGoogleAuth = { accountId, callback -> startGoogleAuth(accountId, callback) },
                    onTwitchAuth = { accountId ->
                        runCatching {
                            twitchOAuth.begin(
                                accountId,
                                onStarted = { code -> runOnUiThread { twitchStartListener?.invoke(accountId, code) } },
                                onResult = { result -> runOnUiThread { setAuthResult(accountId, result) } }
                            )
                        }.onFailure { setAuthResult(accountId, Result.failure(it)) }
                    },
                    onTokenConnect = { accountId, platform, token, callback ->
                        tokenConnector.connect(accountId, platform, token) { result -> runOnUiThread { callback(result) } }
                    },
                    onDisconnect = { accountId ->
                        secureStore.get(accountId)?.let { session ->
                            // Los tokens pegados a mano solo se borran localmente: revocarlos podría romper otros usos del mismo token.
                            if (!session.manual) when (session.provider) {
                                "Twitch" -> twitchOAuth.revoke(session.accessToken)
                                "Google/YouTube" -> revokeGoogleToken(session.accessToken)
                            }
                        }
                        secureStore.remove(accountId)
                    }
                )
            }
        }
    }

    private fun setAuthResult(accountId: Int, result: Result<String>) {
        // The Compose screen observes the encrypted store on resume/recomposition; callback
        // notifications are delivered through the account-status event below.
        authUiListener?.invoke(accountId, result)
    }

    /** Starts Google's native OAuth authorization UI for YouTube API access. */
    private fun startGoogleAuth(accountId: Int, callback: (Result<String>) -> Unit) {
        if (pendingGoogleAccountId != null) {
            callback(Result.failure(IllegalStateException("Ya hay otra autorización de Google en curso.")))
            return
        }
        pendingGoogleAccountId = accountId
        pendingGoogleCallback = callback

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    Scope("openid"),
                    Scope("email"),
                    Scope("profile"),
                    Scope("https://www.googleapis.com/auth/youtube.readonly")
                )
            )
            .build()

        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        finishGoogleAuth(Result.failure(IllegalStateException("Google no devolvió la pantalla de consentimiento.")))
                    } else {
                        try {
                            startIntentSenderForResult(
                                pendingIntent.intentSender,
                                REQUEST_GOOGLE_AUTH,
                                null,
                                0,
                                0,
                                0
                            )
                        } catch (e: IntentSender.SendIntentException) {
                            finishGoogleAuth(Result.failure(e))
                        }
                    }
                } else {
                    saveGoogleAuthorization(result)
                }
            }
            .addOnFailureListener { error -> finishGoogleAuth(Result.failure(error)) }
    }

    @Deprecated("Google Identity authorization result is delivered through Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_GOOGLE_AUTH) return
        if (resultCode != RESULT_OK || data == null) {
            finishGoogleAuth(Result.failure(IllegalStateException("Se canceló la autorización de Google.")))
            return
        }
        try {
            val result = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
            saveGoogleAuthorization(result)
        } catch (e: ApiException) {
            finishGoogleAuth(Result.failure(e))
        } catch (e: Exception) {
            finishGoogleAuth(Result.failure(e))
        }
    }

    private fun saveGoogleAuthorization(result: AuthorizationResult) {
        val accountId = pendingGoogleAccountId ?: return
        val token = result.accessToken
        if (token.isNullOrBlank()) {
            finishGoogleAuth(Result.failure(IllegalStateException("Google no entregó un token OAuth de acceso.")))
            return
        }
        @Suppress("DEPRECATION")
        val email = runCatching { result.toGoogleSignInAccount()?.email }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        val expiresIn = 3600L
        val label = email ?: "YouTube · cuenta $accountId"
        try {
            secureStore.save(
                accountId,
                SecureAccountStore.Session(
                    provider = "Google/YouTube",
                    displayName = label,
                    accessToken = token,
                    expiresAt = System.currentTimeMillis() + expiresIn * 1000L
                )
            )
            finishGoogleAuth(Result.success(label))
        } catch (e: Exception) {
            finishGoogleAuth(Result.failure(e))
        }
    }

    private fun revokeGoogleToken(token: String) {
        Thread {
            runCatching {
                val connection = java.net.URL("https://oauth2.googleapis.com/revoke").openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                val body = "token=" + java.net.URLEncoder.encode(token, "UTF-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                connection.responseCode
                connection.disconnect()
            }
        }.start()
    }

    private fun finishGoogleAuth(result: Result<String>) {
        val callback = pendingGoogleCallback
        pendingGoogleAccountId = null
        pendingGoogleCallback = null
        callback?.invoke(result)
    }

}

data class Account(val id: Int, val name: String, val platform: String)
data class QueuedMessage(val id: Long, val platform: String, val text: String)

@Composable
fun MultiAccountApp(
    store: SecureAccountStore,
    onGoogleAuth: (Int, (Result<String>) -> Unit) -> Unit,
    onTwitchAuth: (Int) -> Unit,
    onTokenConnect: (Int, String, String, (Result<String>) -> Unit) -> Unit,
    onDisconnect: (Int) -> Unit
) {
    val context = LocalContext.current
    val accounts = remember { (1..15).map { Account(it, "YouTube $it", "YouTube") } + (16..30).map { Account(it, "Twitch ${it - 15}", "Twitch") } }
    var page by remember { mutableStateOf("accounts") }
    var selected by remember { mutableStateOf(accounts.first()) }
    var platformFilter by remember { mutableStateOf("Todos") }
    var message by remember { mutableStateOf("") }
    var queue by remember { mutableStateOf(loadQueue(context)) }
    var queuePaused by remember { mutableStateOf(false) }
    var browserUrl by remember { mutableStateOf("https://www.youtube.com") }
    var browserStatus by remember { mutableStateOf("") }
    var authNotice by remember { mutableStateOf("") }
    var tokenTarget by remember { mutableStateOf<Account?>(null) }
    var tokenBusy by remember { mutableStateOf(false) }
    var tokenError by remember { mutableStateOf<String?>(null) }
    val authStates = remember { mutableStateMapOf<Int, String>().apply {
        accounts.forEach { account ->
            store.get(account.id)?.let { session ->
                if (session.expiresAt == 0L || session.expiresAt > System.currentTimeMillis()) {
                    put(account.id, session.displayName)
                }
            }
        }
    } }
    val webViews = remember { mutableStateMapOf<Int, WebView>() }
    val urlByAccount = remember { mutableStateMapOf<Int, String>() }
    val currentWebView = webViews[selected.id]
    val profileSupported = remember { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }

    DisposableEffect(Unit) {
        MainActivity.twitchStartListener = { id, code -> authNotice = "Twitch cuenta $id: código $code. Completa la autorización en la pestaña que se abrió; esperando confirmación…" }
        MainActivity.authUiListener = { id, result ->
            if (id in 1..30) {
                result.onSuccess { authStates[id] = it; authNotice = "Cuenta $id conectada: $it" }
                    .onFailure { authNotice = "Error en cuenta $id: ${it.message ?: "autenticación fallida"}" }
            }
        }
        onDispose { MainActivity.authUiListener = null; MainActivity.twitchStartListener = null }
    }

    fun authenticate(account: Account) {
        authNotice = "Abriendo autenticación de ${account.name}…"
        if (account.platform == "YouTube") {
            onGoogleAuth(account.id) { result ->
                result.onSuccess { authStates[account.id] = it; authNotice = "Google conectado en ${account.name}: $it" }
                    .onFailure { authNotice = "Error en ${account.name}: ${it.message ?: "autenticación fallida"}" }
            }
        } else onTwitchAuth(account.id)
    }
    fun persistQueue(newQueue: List<QueuedMessage>) {
        queue = newQueue
        saveQueue(context, newQueue)
    }

    Scaffold(containerColor = Bg, bottomBar = {
        NavigationBar(containerColor = Panel) {
            NavigationBarItem(selected = page == "accounts" || page == "browser", onClick = { page = "accounts" }, icon = { Icon(Icons.Default.People, null) }, label = { Text("Cuentas") })
            NavigationBarItem(selected = page == "queue", onClick = { page = "queue" }, icon = { Icon(Icons.Default.Send, null) }, label = { Text("Cola (${queue.size})") })
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            Text("MULTIACCOUNT", color = Purple, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(when (page) { "browser" -> selected.name; "queue" -> "Mensajes en cola"; else -> "Panel de cuentas" }, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            when (page) {
                "accounts" -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Todos", "YouTube", "Twitch").forEach { f -> FilterChip(selected = platformFilter == f, onClick = { platformFilter = f }, label = { Text(f) }) }
                    }
                    Text("30 espacios · credenciales guardadas por separado", color = Muted, fontSize = 12.sp)
                    if (authNotice.isNotBlank()) Text(authNotice, color = Color(0xFFBFA7FF), fontSize = 12.sp)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        items(accounts.filter { platformFilter == "Todos" || it.platform == platformFilter }, key = { it.id }) { acc ->
                            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(acc.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                            val saved = authStates[acc.id]
                                            Text(if (saved != null) "Conectada: $saved" else "No conectada", color = if (saved != null) Color(0xFF8BD5A5) else Muted, fontSize = 11.sp)
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { authenticate(acc) }, modifier = Modifier.weight(1f)) { Text("Auth") }
                                        FilledTonalButton(onClick = { tokenError = null; tokenTarget = acc }, modifier = Modifier.weight(1f)) {
                                            Icon(Icons.Default.VpnKey, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pegar token", maxLines = 1)
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { openAccount(acc, { selected = it; page = "browser"; browserUrl = urlByAccount[it.id] ?: if (it.platform == "YouTube") "https://www.youtube.com" else "https://www.twitch.tv" }) }, modifier = Modifier.weight(1f)) { Text("Abrir") }
                                        if (authStates.containsKey(acc.id)) OutlinedButton(onClick = {
                                            onDisconnect(acc.id); authStates.remove(acc.id); authNotice = "Cuenta ${acc.id} desconectada localmente"
                                        }) { Text("Desconectar") }
                                    }
                                }
                            }
                        }
                    }
                }
                "browser" -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { currentWebView?.let { if (it.canGoBack()) it.goBack() } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Icon(Icons.Default.ArrowBack, null) }
                        OutlinedButton(onClick = { currentWebView?.let { if (it.canGoForward()) it.goForward() } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Icon(Icons.Default.ArrowForward, null) }
                        OutlinedButton(onClick = { currentWebView?.reload() }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Icon(Icons.Default.Refresh, null) }
                        OutlinedButton(onClick = { browserUrl = if (selected.platform == "YouTube") "https://www.youtube.com" else "https://www.twitch.tv"; currentWebView?.loadUrl(browserUrl) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Icon(Icons.Default.Home, null) }
                    }
                    OutlinedTextField(value = browserUrl, onValueChange = { browserUrl = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Dirección web") }, trailingIcon = { IconButton(onClick = { val u = normalizeUrl(browserUrl); browserUrl = u; urlByAccount[selected.id] = u; webViews[selected.id]?.loadUrl(u) }) { Icon(Icons.Default.ArrowForward, "Ir") } })
                    if (!profileSupported) Text("Aviso: Android System WebView no admite perfiles múltiples. Las sesiones web podrían compartirse; el almacenamiento OAuth de la app sí se mantiene separado por cuenta.", color = Color(0xFFFFC56E), fontSize = 11.sp)
                    else Text("Perfil web: ${selected.name}", color = Color(0xFF8BD5A5), fontSize = 11.sp)
                    key(selected.id) {
                        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
                            val existing = webViews[selected.id]
                            val w = existing ?: WebView(ctx).apply {
                                if (profileSupported) WebViewCompat.setProfile(this, "multiaccount_${selected.id}")
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadsImagesAutomatically = true
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        url?.let { urlByAccount[selected.id] = it; browserUrl = it }
                                    }
                                }
                            }.also { webViews[selected.id] = it }
                            (w.parent as? android.view.ViewGroup)?.removeView(w)
                            if (w.url.isNullOrBlank()) w.loadUrl(browserUrl)
                            w
                        }, update = { w -> if (w.url.isNullOrBlank()) w.loadUrl(browserUrl) })
                    }
                    OutlinedButton(onClick = { page = "accounts" }, modifier = Modifier.fillMaxWidth()) { Text("Volver a todas las cuentas") }
                }
                else -> {
                    Text("Prepara mensajes y guárdalos en una cola local. La app no los publica automáticamente: tú confirmas cada envío en YouTube o Twitch.", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(value = message, onValueChange = { message = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("Escribe el mensaje") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (message.isNotBlank()) { persistQueue(queue + QueuedMessage(System.currentTimeMillis(), "Ambas", message.trim())); message = "" } }, modifier = Modifier.weight(1f)) { Text("Agregar a cola") }
                        OutlinedButton(onClick = { queuePaused = !queuePaused }) { Text(if (queuePaused) "Reanudar" else "Pausar") }
                    }
                    Text(if (queuePaused) "Cola pausada" else "${queue.size} mensajes guardados", color = Muted, fontSize = 12.sp)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        items(queue, key = { it.id }) { item ->
                            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(item.text, color = Color.White)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager; cm.setPrimaryClip(android.content.ClipData.newPlainText("Mensaje", item.text)); browserStatus = "Mensaje copiado" }, modifier = Modifier.weight(1f)) { Text("Copiar") }
                                        OutlinedButton(onClick = { persistQueue(queue.filterNot { it.id == item.id }) }) { Text("Eliminar") }
                                    }
                                }
                            }
                        }
                    }
                    if (browserStatus.isNotBlank()) Text(browserStatus, color = Muted)
                }
            }
        }
    }

    tokenTarget?.let { acc ->
        TokenDialog(
            account = acc,
            busy = tokenBusy,
            error = tokenError,
            onSubmit = { raw ->
                tokenBusy = true
                tokenError = null
                onTokenConnect(acc.id, acc.platform, raw) { result ->
                    tokenBusy = false
                    result.onSuccess { label ->
                        authStates[acc.id] = label
                        authNotice = "${acc.name} conectada con token: $label"
                        tokenTarget = null
                    }.onFailure { tokenError = it.message ?: "No se pudo validar el token." }
                }
            },
            onDismiss = { tokenTarget = null; tokenError = null }
        )
    }
}

@Composable
private fun TokenDialog(
    account: Account,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var token by remember(account.id) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Panel,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text("Conectar ${account.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (account.platform == "YouTube") "Pega un token de acceso OAuth de Google (suele empezar con ya29.) con permiso de YouTube."
                    else "Pega un token de usuario OAuth de Twitch. Se acepta con o sin el prefijo oauth:.",
                    color = Muted, fontSize = 12.sp
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    label = { Text("Token de acceso") },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Mostrar u ocultar token")
                        }
                    }
                )
                TextButton(enabled = !busy, onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }
                    token = clip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().trim()
                }) {
                    Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pegar del portapapeles")
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                if (busy) Text("Validando token…", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(token) }, enabled = !busy && token.isNotBlank()) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Conectar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") } }
    )
}

private fun normalizeUrl(raw: String): String {
    val value = raw.trim()
    if (value.isEmpty()) return "https://www.google.com"
    return if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
}

private fun openAccount(account: Account, select: (Account) -> Unit) = select(account)

private fun saveQueue(context: Context, queue: List<QueuedMessage>) {
    val arr = JSONArray()
    queue.forEach { arr.put(JSONObject().put("id", it.id).put("platform", it.platform).put("text", it.text)) }
    context.getSharedPreferences("multiaccount", Context.MODE_PRIVATE).edit().putString("queue", arr.toString()).apply()
}
private fun loadQueue(context: Context): List<QueuedMessage> = try {
    val arr = JSONArray(context.getSharedPreferences("multiaccount", Context.MODE_PRIVATE).getString("queue", "[]"))
    (0 until arr.length()).map { val o = arr.getJSONObject(it); QueuedMessage(o.getLong("id"), o.getString("platform"), o.getString("text")) }
} catch (_: Exception) { emptyList() }
