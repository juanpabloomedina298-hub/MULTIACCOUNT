package com.example.multichatmanager

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Twitch's documented Device Code grant for public clients; no embedded client secret. */
class TwitchOAuth(private val context: Context, private val store: SecureAccountStore) {
    fun begin(accountId: Int, onStarted: (String) -> Unit, onResult: (Result<String>) -> Unit) {
        val clientId = BuildConfig.TWITCH_CLIENT_ID
        require(clientId.isNotBlank()) { "Configura TWITCH_CLIENT_ID en gradle.properties o como variable de entorno." }
        Thread {
            try {
                val body = "client_id=${enc(clientId)}&scopes=${enc("user:read:email")}" 
                val response = post("https://id.twitch.tv/oauth2/device", body)
                val deviceCode = response.getString("device_code")
                val userCode = response.getString("user_code")
                val expiresIn = response.optLong("expires_in", 1800L)
                val interval = response.optLong("interval", 5L).coerceAtLeast(1L)
                val completeUri = response.optString("verification_uri_complete")
                    .ifBlank { response.optString("verification_uri").ifBlank { "https://www.twitch.tv/activate" } }
                onStarted(userCode)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(completeUri)) }
                }
                val deadline = System.currentTimeMillis() + expiresIn * 1000L
                var waitSeconds = interval
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(waitSeconds * 1000L)
                    val tokenResult = runCatching {
                        post(
                            "https://id.twitch.tv/oauth2/token",
                            "client_id=${enc(clientId)}&scopes=${enc("user:read:email")}&device_code=${enc(deviceCode)}&grant_type=${enc("urn:ietf:params:oauth:grant-type:device_code")}"
                        )
                    }
                    if (tokenResult.isSuccess) {
                        val tokenJson = tokenResult.getOrThrow()
                        val accessToken = tokenJson.getString("access_token")
                        val profile = validateToken(accessToken)
                        val expiresAt = System.currentTimeMillis() + tokenJson.optLong("expires_in", 0L) * 1000L
                        store.save(accountId, SecureAccountStore.Session("Twitch", profile, accessToken, expiresAt, tokenJson.optString("refresh_token").takeIf { it.isNotBlank() }))
                        onResult(Result.success(profile))
                        return@Thread
                    }
                    val error = tokenResult.exceptionOrNull()?.message.orEmpty()
                    when {
                        error.contains("authorization_pending", ignoreCase = true) -> Unit
                        error.contains("slow_down", ignoreCase = true) -> waitSeconds += 5L
                        error.contains("expired_token", ignoreCase = true) -> throw IllegalStateException("El código de Twitch expiró. Intenta de nuevo.")
                        error.contains("access_denied", ignoreCase = true) -> throw IllegalStateException("Se rechazó el acceso a Twitch.")
                        else -> if (System.currentTimeMillis() + waitSeconds * 1000L >= deadline) throw IllegalStateException("No se completó la autorización de Twitch: $error")
                    }
                }
                throw IllegalStateException("El código de Twitch expiró. Intenta de nuevo.")
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }.start()
    }

    fun revoke(token: String) {
        Thread {
            runCatching {
                val connection = URL("https://id.twitch.tv/oauth2/revoke").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                val body = "client_id=${enc(BuildConfig.TWITCH_CLIENT_ID)}&token=${enc(token)}"
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                connection.disconnect()
                if (code !in 200..299) throw IllegalStateException("Twitch no pudo revocar el token (HTTP $code).")
            }
        }.start()
    }

    private fun validateToken(token: String): String {
        val connection = URL("https://id.twitch.tv/oauth2/validate").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "OAuth $token")
        connection.connectTimeout = 15000; connection.readTimeout = 15000
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("Twitch rechazó el token ($code): $body")
        val json = JSONObject(body)
        return json.optString("login").ifBlank { "Twitch · ${json.optString("user_id", "conectada")}" }
    }

    private fun post(endpoint: String, body: String): JSONObject {
        val boundary = "MultiAccountBoundary${System.nanoTime()}"
        val c = URL(endpoint).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.connectTimeout = 15000
        c.readTimeout = 15000
        val multipart = buildString {
            body.split('&').forEach { pair ->
                val parts = pair.split('=', limit = 2)
                val name = java.net.URLDecoder.decode(parts[0], "UTF-8")
                val value = java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                append(value).append("\r\n")
            }
            append("--$boundary--\r\n")
        }
        c.outputStream.use { it.write(multipart.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        val json = runCatching { JSONObject(response) }.getOrElse { throw IllegalStateException("Respuesta no válida de Twitch ($code): ${response.take(300)}") }
        if (code !in 200..299) throw IllegalStateException(json.optString("message", json.optString("error", "HTTP $code")))
        return json
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
