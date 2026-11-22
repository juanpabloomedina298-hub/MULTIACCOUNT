package com.example.multichatmanager

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Estado de la pestaña "Enviar". Vive a nivel de la app para que no se pierda al cambiar de pestaña. */
class SenderState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("multiaccount", Context.MODE_PRIVATE)

    var twitchChannel by mutableStateOf(prefs.getString("send_twitch", "").orEmpty())
    var youtubeVideo by mutableStateOf(prefs.getString("send_youtube", "").orEmpty())
    var delayText by mutableStateOf(prefs.getString("send_delay", "2").orEmpty().ifBlank { "2" })
    var message by mutableStateOf("")

    /** Cuentas que el usuario desmarcó. Todas las demás cuentas conectadas participan. */
    val excluded = mutableStateMapOf<Int, Boolean>()
    val log = mutableStateListOf<SendLog>()
    var running by mutableStateOf(false)
    var confirming by mutableStateOf(false)
    var status by mutableStateOf("")

    private var job: Job? = null

    /** Pausa entre cuentas en milisegundos, o null si el valor no es válido (mínimo 1 s). */
    fun delayMs(): Long? = delayText.trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it >= 1.0 && it <= 3600.0 }
        ?.let { (it * 1000).toLong() }

    fun targetFor(platform: String): String = if (platform == "Twitch") twitchChannel else youtubeVideo

    /** Cuentas conectadas, marcadas y con destino escrito, en orden de espacio (1..30). */
    fun participants(connected: List<Account>): List<Account> =
        connected.filter { excluded[it.id] != true && targetFor(it.platform).isNotBlank() }

    /** Motivo por el que todavía no se puede enviar, o null si todo está listo. */
    fun problem(participants: List<Account>): String? {
        val text = message.trim()
        if (text.isEmpty()) return "Escribe el mensaje."
        if (delayMs() == null) return "La pausa debe ser de 1 segundo o más."
        if (participants.isEmpty()) return "No hay cuentas conectadas con destino. Escribe el canal o directo y conecta cuentas."
        if (participants.any { it.platform == "Twitch" }) {
            if (ChatSender.normalizeTwitchChannel(twitchChannel) == null) return "El canal de Twitch no es válido."
            if (text.length > ChatSender.TWITCH_MAX) return "Twitch admite hasta ${ChatSender.TWITCH_MAX} caracteres."
        }
        if (participants.any { it.platform == "YouTube" }) {
            if (ChatSender.youTubeVideoId(youtubeVideo) == null) return "El enlace o ID del directo de YouTube no es válido."
            if (text.length > ChatSender.YOUTUBE_MAX) return "YouTube admite hasta ${ChatSender.YOUTUBE_MAX} caracteres."
        }
        return null
    }

    fun start(scope: CoroutineScope, store: SecureAccountStore, participants: List<Account>) {
        if (running) return
        val delayMs = delayMs() ?: return
        val text = message.trim()
        val twitchInput = twitchChannel.trim()
        val youtubeInput = youtubeVideo.trim()
        prefs.edit()
            .putString("send_twitch", twitchInput)
            .putString("send_youtube", youtubeInput)
            .putString("send_delay", delayText.trim())
            .apply()
        log.clear()
        running = true
        status = "Preparando…"
        var sent = 0
        job = scope.launch {
            try {
                val result = runCycle(
                    participants = participants,
                    sessionOf = { id -> store.get(id) },
                    text = text,
                    twitchInput = twitchInput,
                    youtubeInput = youtubeInput,
                    delayMs = delayMs,
                    onStatus = { status = it },
                    onLog = { entry -> if (entry.ok) sent++; log.add(entry) }
                )
                status = "Terminado: ${result.sent} enviados, ${result.failed} con error."
            } catch (e: CancellationException) {
                status = "Detenido. Enviados: $sent de ${participants.size}."
                throw e
            } catch (e: Exception) {
                status = "Error inesperado: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                running = false
                job = null
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
