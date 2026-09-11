package com.example.digi.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.digi.R
import com.example.digi.player.PlaybackEngine

/**
 * The wall.
 *
 * Zones are positioned as fractions of the panel rather than in the authoring canvas's pixels,
 * because a 1920x1080 playlist regularly ends up on a panel that is not 1920x1080 — a portrait
 * install, a 4K box downscaling, a tablet standing in during commissioning. The backend emits
 * percentages alongside the authored pixels for exactly this reason.
 *
 * Zones are drawn in `zIndex` order and overlap freely: a ticker sitting on top of video is the
 * single most common signage layout there is, which is why the backend treats overlap as a warning
 * rather than a rejection.
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val download by viewModel.downloading.collectAsStateWithLifecycle()
    val blanked by viewModel.blanked.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(parseColor(frame?.layout?.backgroundColor) ?: Color.Black)
    ) {
        // A scheduled turn-off renders black and silent but keeps the loop running underneath, so
        // POWER_ON resumes where the wall should be rather than restarting the loop.
        if (blanked) {
            Box(Modifier.fillMaxSize().background(Color.Black))
            return@Box
        }

        val current = frame
        if (current != null) {
            LayoutCanvas(current)
        } else {
            IdleScreen(
                downloadingLabel = download?.let {
                    "${it.fileName} (${it.index}/${it.total}) ${it.percent}%"
                },
                downloadFraction = download?.let { it.percent / 100f },
                hasPlan = plan != null,
            )
        }
    }
}

@Composable
private fun LayoutCanvas(frame: PlaybackEngine.Frame) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = maxWidth
        val panelHeight = maxHeight

        frame.zones
            .sortedBy { it.zone.zIndex }
            .forEach { zoneFrame ->
                val zone = zoneFrame.zone
                ZoneContent(
                    zoneFrame = zoneFrame,
                    muted = zone.muted,
                    modifier = Modifier
                        .offset(
                            x = panelWidth * zone.x,
                            y = panelHeight * zone.y,
                        )
                        .size(
                            width = panelWidth * zone.width,
                            height = panelHeight * zone.height,
                        ),
                )
            }
    }
}

/**
 * Shown when there is nothing to play.
 *
 * Deliberately restrained. `hasContent:false` is a documented, normal state — an idle screen with
 * nothing scheduled — so this must not look like an error. What it does do is make a downloading
 * screen visibly different from an idle one, because "the wall is blank" and "the wall is blank and
 * pulling 400MB over a station uplink" call for completely different responses from whoever is
 * standing in front of it.
 */
@Composable
private fun IdleScreen(
    downloadingLabel: String?,
    downloadFraction: Float?,
    hasPlan: Boolean,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(48.dp),
        ) {
            Text(
                text = if (downloadingLabel != null) {
                    androidx.compose.ui.res.stringResource(R.string.player_downloading)
                } else {
                    androidx.compose.ui.res.stringResource(R.string.player_no_content)
                },
                color = Color(0xFF9AA4B2),
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
            )

            if (downloadingLabel != null) {
                Text(downloadingLabel, color = Color(0xFF5C6675), fontSize = 16.sp)
                LinearProgressIndicator(
                    progress = { downloadFraction ?: 0f },
                    modifier = Modifier.fillMaxWidth(0.5f),
                    color = Color(0xFF2F6BFF),
                    trackColor = Color(0xFF1C2230),
                )
            } else if (hasPlan) {
                Text(
                    androidx.compose.ui.res.stringResource(R.string.player_no_content_hint),
                    color = Color(0xFF5C6675),
                    fontSize = 16.sp,
                )
            }
        }
    }
}
