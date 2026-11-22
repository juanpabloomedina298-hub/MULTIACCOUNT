package com.example.multichatmanager

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Conecta una cuenta pegando directamente un token de acceso.
 * El token se valida contra el proveedor antes de guardarse; si es inválido o expiró, no se guarda nada.
 */
class TokenConnector(private val store: SecureAccountStore) {

    /** Valida [rawToken] en segundo plano y lo guarda en el espacio [accountId]. [onResult] se llama desde un hilo de fondo. */
    fun connect(accountId: Int, platform: String, rawToken: String, onResult: (Result<String>) -> Unit) {
        Thread {
            val result = runCatching {
                val token = cleanToken(rawToken)
                require(token.isNotEmpty()) { "Pega un token." }
                require(token.none { it.isWhitespace() }) { "El token no debe contener espacios ni saltos de línea." }
                val session = if (platform == "Twitch") validateTwitch(token) else validateGoogle(token, accountId)
                store.save(accountId, session)
                session.displayName
            }.recoverCatching { e ->
                throw if (e is IOException) IllegalStateException("No se pudo contactar al proveedor. Revisa tu conexión e intenta de nuevo.") else e
            }
            onResult(result)
        }.start()
    }

    /** Quita comillas, espacios y prefijos comunes ("Bearer ", "oauth:"). */
    private fun cleanToken(raw: String): String =
        raw.trim().trim('"', '\'', '`').trim().replace(Regex("^(?i)(bearer\\s+|oauth[:\\s]+)"), "").trim()

    private fun validateTwitch(token: String): SecureAccountStore.Session {
        val (code, body) = request(
            url = "https://id.twitch.tv/oauth2/validate",
            headers = mapOf("Authorization" to "OAuth $token")
        )
        if (code == 401) throw IllegalStateException("Twitch rechazó el token: es inválido o ya expiró.")
        if (code !in 200..299) throw IllegalStateException("Twitch no pudo validar el token (HTTP $code).")
        val json = JSONObject(body)
        val login = json.optString("login")
        if (login.isBlank()) throw IllegalStateException("Este token no pertenece a un usuario de Twitch (parece un token de aplicación).")
        val expiresIn = json.optLong("expires_in", 0L)
        return SecureAccountStore.Session(
            provider = "Twitch",
            displayName = login,
            accessToken = token,
            expiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000L else 0L,
            manual = true
        )
    }

    private fun validateGoogle(token: String, accountId: Int): SecureAccountStore.Session {
        val (code, body) = request(
            url = "https://oauth2.googleapis.com/tokeninfo?access_token=" + URLEncoder.encode(token, "UTF-8")
        )
        if (code == 400 || code == 401) throw IllegalStateException("Google rechazó el token: es inválido o ya expiró.")
        if (code !in 200..299) throw IllegalStateException("Google no pudo validar el token (HTTP $code).")
        val info = JSONObject(body)
        val email = info.optString("email").takeIf { it.isNotBlank() }
        val expiresIn = info.optLong("expires_in", 0L)
        if (expiresIn <= 0L) throw IllegalStateException("El token de Google ya expiró.")

        // Nombre del canal (opcional): solo funciona si el token tiene permiso de YouTube.
        val channelTitle = runCatching {
            val (c, b) = request(
                url = "https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true",
                headers = mapOf("Authorization" to "Bearer $token")
            )
            if (c in 200..299) JSONObject(b).optJSONArray("items")?.optJSONObject(0)?.optJSONObject("snippet")?.optString("title") else null
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val label = when {
            channelTitle != null && email != null -> "$channelTitle · $email"
            channelTitle != null -> channelTitle
            email != null -> email
            else -> "YouTube · cuenta $accountId"
        }
        return SecureAccountStore.Session(
            provider = "Google/YouTube",
            displayName = label,
            accessToken = token,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000L,
            manual = true
        )
    }

    private fun request(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 15000
            c.readTimeout = 15000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            c.disconnect()
        }
    }
}
