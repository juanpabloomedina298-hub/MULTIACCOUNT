package com.example.multichatmanager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Error legible para mostrar en pantalla.
 * [targetFatal]: el destino (canal / directo) no sirve; no tiene caso intentar con más cuentas de esa plataforma.
 * [platformFatal]: la plataforma entera está bloqueada (por ejemplo, cuota diaria agotada).
 */
class ChatException(message: String, val targetFatal: Boolean = false, val platformFatal: Boolean = false) : Exception(message)

data class SendLog(val label: String, val ok: Boolean, val detail: String)
data class CycleResult(val sent: Int, val failed: Int, val total: Int)

/** Operaciones de red (bloqueantes). Separado en interfaz para poder probar el ciclo sin red. */
interface ChatTransport {
    fun resolveTwitch(session: SecureAccountStore.Session, input: String): ChatSender.TwitchTarget
    fun sendTwitch(session: SecureAccountStore.Session, target: ChatSender.TwitchTarget, text: String)
    fun resolveYouTube(session: SecureAccountStore.Session, input: String): String
    fun sendYouTube(session: SecureAccountStore.Session, liveChatId: String, text: String)
}

object ChatSender : ChatTransport {
    const val TWITCH_MAX = 500
    const val YOUTUBE_MAX = 200

    data class TwitchTarget(val broadcasterId: String, val login: String)
    private data class TwitchIdentity(val clientId: String, val userId: String, val scopes: List<String>)

    // ---------- Normalización de destinos ----------

    /** Acepta "canal", "@canal" o "twitch.tv/canal". Devuelve el login en minúsculas o null si no es válido. */
    fun normalizeTwitchChannel(input: String): String? {
        var s = input.trim().lowercase(Locale.ROOT)
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("m.")
        s = s.removePrefix("twitch.tv/").removePrefix("@")
        s = s.substringBefore('/').substringBefore('?')
        return s.takeIf { Regex("^[a-z0-9_]{3,25}$").matches(it) }
    }

    /** Acepta el ID (11 caracteres) o enlaces youtube.com/watch?v=, youtu.be/, /live/, /shorts/ y /embed/. */
    fun youTubeVideoId(input: String): String? {
        val s = input.trim()
        val idRegex = Regex("^[A-Za-z0-9_-]{11}$")
        if (idRegex.matches(s)) return s
        val withScheme = if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = (uri.host ?: return null).lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.")
        val path = uri.path.orEmpty()
        val id: String? = when (host) {
            "youtu.be" -> path.trim('/').substringBefore('/')
            "youtube.com", "music.youtube.com" ->
                uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
                    ?: Regex("^/(live|shorts|embed)/([A-Za-z0-9_-]{11})").find(path)?.groupValues?.get(2)
            else -> null
        }
        return id?.takeIf { idRegex.matches(it) }
    }

    // ---------- Twitch ----------

    private fun twitchIdentity(token: String): TwitchIdentity {
        val (code, body) = http("GET", "https://id.twitch.tv/oauth2/validate", mapOf("Authorization" to "OAuth $token"))
        if (code == 401) throw ChatException("Token de Twitch inválido o expirado. Vuelve a conectar la cuenta.")
        if (code !in 200..299) throw ChatException("Twitch no pudo validar el token (HTTP $code).")
        val json = JSONObject(body)
        val scopes = json.optJSONArray("scopes")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
        return TwitchIdentity(json.optString("client_id"), json.optString("user_id"), scopes)
    }

    override fun resolveTwitch(session: SecureAccountStore.Session, input: String): TwitchTarget {
        val login = normalizeTwitchChannel(input)
            ?: throw ChatException("Escribe un canal de Twitch válido (solo su nombre de usuario).", targetFatal = true)
        val identity = twitchIdentity(session.accessToken)
        val (code, body) = http(
            "GET", "https://api.twitch.tv/helix/users?login=" + enc(login),
            mapOf("Authorization" to "Bearer ${session.accessToken}", "Client-Id" to identity.clientId)
        )
        if (code == 401) throw ChatException("Twitch rechazó el token de esta cuenta.")
        if (code !in 200..299) throw ChatException("Twitch no pudo buscar el canal: " + errorMessage(body, code))
        val user = JSONObject(body).optJSONArray("data")?.optJSONObject(0)
            ?: throw ChatException("No existe el canal de Twitch \"$login\".", targetFatal = true)
        return TwitchTarget(user.getString("id"), user.optString("display_name").ifBlank { login })
    }

    override fun sendTwitch(session: SecureAccountStore.Session, target: TwitchTarget, text: String) {
        if (text.length > TWITCH_MAX) throw ChatException("El mensaje supera $TWITCH_MAX caracteres (límite de Twitch).")
        val identity = twitchIdentity(session.accessToken)
        if ("user:write:chat" !in identity.scopes) {
            throw ChatException("A este token le falta el permiso user:write:chat. Vuelve a conectar la cuenta con Auth o pega un token que lo incluya.")
        }
        val payload = JSONObject()
            .put("broadcaster_id", target.broadcasterId)
            .put("sender_id", identity.userId)
            .put("message", text)
            .toString()
        val (code, body) = http(
            "POST", "https://api.twitch.tv/helix/chat/messages",
            mapOf(
                "Authorization" to "Bearer ${session.accessToken}",
                "Client-Id" to identity.clientId,
                "Content-Type" to "application/json"
            ),
            payload
        )
        if (code == 429) throw ChatException("Twitch limitó la velocidad (429). Aumenta la pausa entre cuentas.")
        if (code == 401) throw ChatException("Twitch rechazó el token. Vuelve a conectar la cuenta.")
        if (code !in 200..299) throw ChatException("Twitch rechazó el mensaje: " + errorMessage(body, code))
        val item = runCatching { JSONObject(body).getJSONArray("data").getJSONObject(0) }.getOrNull()
        if (item != null && !item.optBoolean("is_sent", true)) {
            val reason = item.optJSONObject("drop_reason")?.optString("message").orEmpty()
            throw ChatException(if (reason.isNotBlank()) "Twitch descartó el mensaje: $reason" else "Twitch descartó el mensaje.")
        }
    }

    // ---------- YouTube ----------

    override fun resolveYouTube(session: SecureAccountStore.Session, input: String): String {
        val videoId = youTubeVideoId(input)
            ?: throw ChatException("Pega el enlace (o el ID) de un directo de YouTube.", targetFatal = true)
        val (code, body) = http(
            "GET", "https://www.googleapis.com/youtube/v3/videos?part=liveStreamingDetails&id=" + enc(videoId),
            mapOf("Authorization" to "Bearer ${session.accessToken}")
        )
        if (code == 401) throw ChatException("Google rechazó el token de esta cuenta (inválido o expirado).")
        if (code !in 200..299) {
            val quota = isQuotaReason(googleReason(body))
            throw ChatException("YouTube no pudo consultar el directo: " + errorMessage(body, code), platformFatal = quota)
        }
        val item = JSONObject(body).optJSONArray("items")?.optJSONObject(0)
            ?: throw ChatException("No encontré ese video de YouTube.", targetFatal = true)
        return item.optJSONObject("liveStreamingDetails")?.optString("activeLiveChatId")?.takeIf { it.isNotBlank() }
            ?: throw ChatException("Ese video no está en vivo ahora o tiene el chat desactivado.", targetFatal = true)
    }

    override fun sendYouTube(session: SecureAccountStore.Session, liveChatId: String, text: String) {
        if (text.length > YOUTUBE_MAX) throw ChatException("El mensaje supera $YOUTUBE_MAX caracteres (límite del chat de YouTube).")
        val payload = JSONObject().put(
            "snippet", JSONObject()
                .put("liveChatId", liveChatId)
                .put("type", "textMessageEvent")
                .put("textMessageDetails", JSONObject().put("messageText", text))
        ).toString()
        val (code, body) = http(
            "POST", "https://www.googleapis.com/youtube/v3/liveChat/messages?part=snippet",
            mapOf("Authorization" to "Bearer ${session.accessToken}", "Content-Type" to "application/json"),
            payload
        )
        if (code in 200..299) return
        if (code == 401) throw ChatException("Google rechazó el token (inválido o expirado). Vuelve a conectar la cuenta.")
        val reason = googleReason(body)
        val message = when (reason) {
            "insufficientPermissions" ->
                "Falta el permiso para escribir en el chat. Vuelve a conectar la cuenta con Auth, o pega un token con el permiso youtube.force-ssl."
            "quotaExceeded", "dailyLimitExceeded" -> "Se agotó la cuota diaria de la YouTube Data API."
            "rateLimitExceeded", "userRateLimitExceeded" -> "YouTube limitó la velocidad. Aumenta la pausa entre cuentas."
            "liveChatDisabled" -> "El chat de este directo está desactivado."
            "liveChatEnded" -> "El chat en vivo ya terminó."
            "liveChatNotFound" -> "No se encontró el chat en vivo."
            else -> errorMessage(body, code)
        }
        throw ChatException(message, platformFatal = isQuotaReason(reason))
    }

    // ---------- Utilidades ----------

    private fun isQuotaReason(reason: String?) = reason == "quotaExceeded" || reason == "dailyLimitExceeded"

    private fun googleReason(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun errorMessage(body: String, code: Int): String = runCatching {
        val json = JSONObject(body)
        val error = json.opt("error")
        when {
            error is JSONObject -> error.optString("message")
            json.has("message") -> json.optString("message")
            else -> ""
        }
    }.getOrDefault("").ifBlank { "HTTP $code" }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun http(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: IOException) {
            throw ChatException("Sin conexión con el servidor. Revisa tu internet.")
        } finally {
            c.disconnect()
        }
    }
}

fun formatDelay(ms: Long): String =
    if (ms % 1000L == 0L) "${ms / 1000L} s" else String.format(Locale.US, "%.1f s", ms / 1000.0)

/**
 * Recorre [participants] en orden: la primera cuenta envía, espera [delayMs], envía la siguiente, y así hasta terminar.
 * Cancelable: al cancelar la corrutina se detiene antes del siguiente envío.
 */
suspend fun runCycle(
    participants: List<Account>,
    sessionOf: (Int) -> SecureAccountStore.Session?,
    text: String,
    twitchInput: String,
    youtubeInput: String,
    delayMs: Long,
    onStatus: (String) -> Unit,
    onLog: (SendLog) -> Unit,
    transport: ChatTransport = ChatSender,
    now: () -> Long = { System.currentTimeMillis() }
): CycleResult {
    var sent = 0
    var failed = 0
    var twitchTarget: ChatSender.TwitchTarget? = null
    var chatId: String? = null
    val blocked = mutableMapOf<String, String>()
    var attempted = false

    for ((index, acc) in participants.withIndex()) {
        currentCoroutineContext().ensureActive()

        val blockReason = blocked[acc.platform]
        if (blockReason != null) {
            failed++
            onLog(SendLog(acc.name, false, "Omitida: $blockReason"))
            continue
        }

        if (attempted) {
            onStatus("Esperando ${formatDelay(delayMs)} antes de ${acc.name}…")
            delay(delayMs)
        }
        attempted = true
        onStatus("Enviando ${index + 1}/${participants.size} · ${acc.name}")

        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val session = sessionOf(acc.id) ?: throw ChatException("La cuenta ya no está conectada.")
                if (session.expiresAt != 0L && session.expiresAt <= now()) {
                    throw ChatException("El token expiró. Vuelve a conectar la cuenta.")
                }
                if (acc.platform == "Twitch") {
                    val target = twitchTarget ?: transport.resolveTwitch(session, twitchInput).also { twitchTarget = it }
                    transport.sendTwitch(session, target, text)
                } else {
                    val id = chatId ?: transport.resolveYouTube(session, youtubeInput).also { chatId = it }
                    transport.sendYouTube(session, id, text)
                }
            }
        }

        val error = outcome.exceptionOrNull()
        if (error == null) {
            sent++
            onLog(SendLog(acc.name, true, "Enviado"))
        } else {
            failed++
            val chatError = error as? ChatException
            if (chatError != null && (chatError.targetFatal || chatError.platformFatal)) {
                blocked[acc.platform] = chatError.message.orEmpty()
            }
            onLog(SendLog(acc.name, false, error.message ?: "Error desconocido"))
        }
    }
    return CycleResult(sent, failed, participants.size)
}
