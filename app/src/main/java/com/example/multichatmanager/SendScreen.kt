package com.example.multichatmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SPanel = Color(0xFF1B1D26)
private val SMuted = Color(0xFF9DA0AE)
private val SWarn = Color(0xFFFFC56E)
private val SOk = Color(0xFF8BD5A5)
private val SBad = Color(0xFFFF8A80)

private fun formatSeconds(total: Long): String =
    if (total < 60L) "$total s" else "${total / 60L} min ${total % 60L} s"

/** Pestaña "Enviar": el mismo mensaje sale de cada cuenta, una tras otra, con una pausa configurable. */
@Composable
fun ColumnScope.SendScreen(
    state: SenderState,
    accounts: List<Account>,
    authStates: Map<Int, String>,
    onStart: (List<Account>) -> Unit
) {
    val connected = accounts.filter { authStates.containsKey(it.id) }
    val participants = state.participants(connected)
    val problem = state.problem(participants)
    val delayMs = state.delayMs()
    val estimateSeconds = if (delayMs != null && participants.size > 1) (participants.size - 1) * delayMs / 1000L else 0L

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
        item {
            Text(
                "Envía el mismo mensaje desde varias cuentas, una tras otra, con una pausa entre cada una.",
                color = SMuted, fontSize = 12.sp
            )
        }
        item {
            OutlinedTextField(
                value = state.twitchChannel,
                onValueChange = { state.twitchChannel = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !state.running,
                label = { Text("Canal de Twitch (usuario)") },
                placeholder = { Text("ej. nombredelcanal") }
            )
        }
        item {
            OutlinedTextField(
                value = state.youtubeVideo,
                onValueChange = { state.youtubeVideo = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !state.running,
                label = { Text("Directo de YouTube (enlace o ID)") },
                placeholder = { Text("https://youtube.com/watch?v=…") }
            )
        }
        item {
            OutlinedTextField(
                value = state.message,
                onValueChange = { state.message = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                enabled = !state.running,
                label = { Text("Mensaje") },
                supportingText = { Text("${state.message.length} caracteres · máx. ${ChatSender.YOUTUBE_MAX} en YouTube, ${ChatSender.TWITCH_MAX} en Twitch") }
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = state.delayText,
                    onValueChange = { state.delayText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !state.running,
                    isError = state.delayMs() == null,
                    label = { Text("Pausa entre cuentas (segundos)") },
                    supportingText = { Text("La cuenta 1 envía, espera esta pausa, envía la 2… Mínimo 1 s.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1", "2", "5", "10").forEach { v ->
                        FilterChip(
                            selected = state.delayText.trim() == v,
                            onClick = { if (!state.running) state.delayText = v },
                            label = { Text("$v s") }
                        )
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Cuentas que envían: ${participants.size} de ${connected.size} conectadas",
                    color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(enabled = !state.running, onClick = { state.excluded.clear() }) { Text("Todas") }
                TextButton(enabled = !state.running, onClick = { connected.forEach { state.excluded[it.id] = true } }) { Text("Ninguna") }
            }
        }
        if (connected.isEmpty()) {
            item {
                Text(
                    "Aún no hay cuentas conectadas. Conéctalas en la pestaña Cuentas con Auth o Pegar token.",
                    color = SMuted, fontSize = 12.sp
                )
            }
        } else {
            items(connected, key = { it.id }) { acc ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.excluded[acc.id] != true,
                        enabled = !state.running,
                        onCheckedChange = { on ->
                            if (on) {
                                state.excluded.remove(acc.id)
                            } else {
                                state.excluded[acc.id] = true
                            }
                        }
                    )
                    Column(Modifier.weight(1f)) {
                        Text(acc.name, color = Color.White, fontSize = 14.sp)
                        val noTarget = state.targetFor(acc.platform).isBlank()
                        Text(
                            if (noTarget) "Sin destino: no enviará" else authStates[acc.id].orEmpty(),
                            color = if (noTarget) SWarn else SMuted, fontSize = 11.sp
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.running) {
                    Button(
                        onClick = { state.stop() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Detener") }
                } else {
                    Button(
                        onClick = { state.confirming = true },
                        enabled = problem == null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Enviar ciclo (${participants.size} cuentas)") }
                    if (problem != null) {
                        Text(problem, color = SWarn, fontSize = 12.sp)
                    } else {
                        Text(
                            "Pausa ${formatDelay(delayMs ?: 0L)} · duración aprox. ${formatSeconds(estimateSeconds)}",
                            color = SMuted, fontSize = 12.sp
                        )
                    }
                }
            }
        }
        if (state.status.isNotBlank()) {
            item { Text(state.status, color = Color(0xFFBFA7FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
        items(state.log.asReversed()) { entry ->
            Text(
                "${if (entry.ok) "✓" else "✗"} ${entry.label}: ${entry.detail}",
                color = if (entry.ok) SOk else SBad, fontSize = 12.sp
            )
        }
    }

    if (state.confirming) {
        val youtubeCount = participants.count { it.platform == "YouTube" }
        val twitchCount = participants.count { it.platform == "Twitch" }
        AlertDialog(
            onDismissRequest = { state.confirming = false },
            containerColor = SPanel,
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text("¿Enviar ahora?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("“${state.message.trim().take(120)}”")
                    Text(
                        "${participants.size} cuentas (YouTube $youtubeCount · Twitch $twitchCount), " +
                            "${formatDelay(delayMs ?: 0L)} entre cada una. Duración aprox.: ${formatSeconds(estimateSeconds)}.",
                        color = SMuted, fontSize = 13.sp
                    )
                    Text(
                        "Úsalo solo en chats donde esté permitido y con cuentas tuyas: repetir el mismo mensaje en cadena puede causar bloqueos.",
                        color = SMuted, fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(onClick = { state.confirming = false; onStart(participants) }) { Text("Enviar") }
            },
            dismissButton = { TextButton(onClick = { state.confirming = false }) { Text("Cancelar") } }
        )
    }
}
