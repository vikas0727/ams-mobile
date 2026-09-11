package com.example.digi.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.digi.core.AppLog
import com.example.digi.core.DigiApp
import com.example.digi.core.ServerClock
import com.example.digi.service.PlayerService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Scrim = Color(0xE6070B12)
private val Panel = Color(0xFF121A28)
private val Accent = Color(0xFF2F6BFF)
private val Good = Color(0xFF3DD68C)
private val Bad = Color(0xFFFF6B6B)
private val Label = Color(0xFF6B7688)

/**
 * The on-device diagnostics panel.
 *
 * Opened by pressing the yellow/info key or holding Back on a remote. This exists because the boxes
 * this fleet runs on are mounted high on walls in public stations: when a screen is showing the
 * wrong thing, the realistic diagnosis is someone reading this panel from the floor, not an adb
 * session over a network that may be the thing that is broken.
 *
 * So everything here is answerable without the network: what the device thinks it is, what it last
 * heard from the server, what is queued, what is on disk, and the last few hundred log lines.
 */
@Composable
fun DiagnosticsOverlay(
    onClose: () -> Unit,
    onForceSync: () -> Unit,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val graph = remember { DigiApp.graph(context) }
    val service by PlayerService.serviceState.collectAsStateWithLifecycle()
    val plan by graph.content.plan.collectAsStateWithLifecycle()
    val logs by AppLog.entries.collectAsStateWithLifecycle()

    // Queue depths and cache size need a database round trip, so they refresh on their own slow
    // timer rather than on every recomposition.
    val stats by produceState(initialValue = Stats(), graph) {
        while (true) {
            value = Stats(
                proofOfPlayQueued = runCatching { graph.proofOfPlay.queuedCount() }.getOrDefault(-1),
                eventsQueued = runCatching { graph.events.queuedCount() }.getOrDefault(-1),
                cachedBytes = runCatching { graph.mediaCache.occupiedBytes() }.getOrDefault(0),
                cachedFiles = runCatching { graph.mediaCache.inventory().size }.getOrDefault(0),
                freeBytes = runCatching { graph.mediaCache.freeSpaceBytes() }.getOrDefault(0),
            )
            delay(3_000)
        }
    }

    var confirmUnpair by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().background(Scrim)) {
        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {

            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Diagnostics", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)

                Section("Screen")
                Field("Name", graph.store.screenName ?: "—")
                Field("Screen ID", graph.store.screenId ?: "—")
                Field("Device ID", graph.store.deviceUniqueId ?: "—")
                Field("Orientation", graph.store.orientation ?: "—")

                Section("Server")
                Field(
                    "Heartbeat",
                    if (service.online) "online" else "OFFLINE",
                    if (service.online) Good else Bad,
                )
                Field("Last beat", formatAge(graph.heartbeat.secondsSinceLastSuccess()))
                Field(
                    "Server view",
                    // The CMS marks a screen offline after 180s without a beat. Saying so plainly
                    // stops a support call that begins "the portal says it's offline but it's
                    // clearly playing".
                    if (graph.heartbeat.consideredOfflineByServer()) "would show OFFLINE" else "shows online",
                    if (graph.heartbeat.consideredOfflineByServer()) Bad else Good,
                )
                Field("Clock offset", "${ServerClock.currentOffsetMs} ms")
                Field("Last sync", formatTime(graph.store.lastSyncedAt))

                Section("Content")
                Field("Source", plan?.source ?: "—")
                Field("Version", graph.store.contentVersion.toString())
                Field("Playlist", plan?.playlistName ?: "—")
                Field("Layouts", (plan?.layouts?.size ?: 0).toString())
                plan?.cluster?.let {
                    Field(
                        "Wall",
                        "${it.clusterName ?: "cluster"} · position ${it.position} · " +
                            "sync ${if (it.syncEnabled) "on" else "off"}"
                    )
                }
                Field(
                    "Missing assets",
                    (plan?.missingAssets ?: 0).toString(),
                    if ((plan?.missingAssets ?: 0) > 0) Bad else Good,
                )

                Section("Storage & queues")
                Field("Cached", "${stats.cachedFiles} file(s), ${stats.cachedBytes / MB} MB")
                Field("Free space", "${stats.freeBytes / MB} MB")
                Field("Plays queued", stats.proofOfPlayQueued.toString())
                Field(
                    "Log events queued",
                    "${stats.eventsQueued}" +
                        if (graph.store.realtimeCaptureEnabled) " (capture ON)" else " (capture off)"
                )

                Row(
                    Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onForceSync,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) { Text("Sync now") }

                    Button(
                        onClick = { if (confirmUnpair) onUnpair() else confirmUnpair = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (confirmUnpair) Bad else Panel,
                        ),
                    ) {
                        // Two-step, because unpairing from the device means a trip back to the CMS
                        // for a new code and, on a wall-mounted box, a second visit.
                        Text(if (confirmUnpair) "Confirm unpair" else "Unpair")
                    }

                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = Panel),
                    ) { Text("Close") }
                }
            }

            LogPane(logs, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun LogPane(entries: List<AppLog.Entry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        // Follow the tail: a panel that has to be scrolled to see what just happened is useless to
        // someone holding a remote at arm's length.
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Column(modifier.background(Panel).padding(12.dp)) {
        Text("Recent activity", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(entries) { entry ->
                Text(
                    text = entry.format(),
                    color = when (entry.level) {
                        "E" -> Bad
                        "W" -> Color(0xFFFFC14D)
                        else -> Color(0xFF97A3B6)
                    },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title.uppercase(),
        color = Accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun Field(label: String, value: String, valueColor: Color = Color.White) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Label, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        Text(value, color = valueColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

private data class Stats(
    val proofOfPlayQueued: Int = 0,
    val eventsQueued: Int = 0,
    val cachedBytes: Long = 0,
    val cachedFiles: Int = 0,
    val freeBytes: Long = 0,
)

private const val MB = 1024L * 1024L
private val CLOCK = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US)

private fun formatTime(millis: Long): String =
    if (millis <= 0) "never" else CLOCK.format(Date(millis))

private fun formatAge(seconds: Long): String = when {
    seconds < 0 -> "never"
    seconds < 60 -> "${seconds}s ago"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s ago"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m ago"
}
