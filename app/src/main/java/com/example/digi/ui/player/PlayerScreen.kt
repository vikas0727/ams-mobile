package com.example.digi.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.digi.R
import com.example.digi.data.repo.ContentRepository.DownloadState
import com.example.digi.player.PlaybackEngine

private val Muted = Color(0xFF9AA4B2)
private val Dim = Color(0xFF5C6675)
private val Accent = Color(0xFF2F6BFF)
private val Track = Color(0xFF1C2230)
private val Bad = Color(0xFFFF6B6B)

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
 *
 * Three non-playing states sit in front of it, and which one shows is chosen carefully — on a
 * screen in a public place the difference between "nothing is assigned", "content is on its way"
 * and "something went wrong" is the whole message:
 *
 *  - **nothing playing, nothing downloading** → "No Content Assigned", with what to do next.
 *  - **nothing playing, downloading** → full progress panel.
 *  - **already playing, downloading** → a small corner chip. A content change must not black out a
 *    live screen, so the old loop keeps running and the new one swaps in when it is complete.
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val download by viewModel.downloadState.collectAsStateWithLifecycle()
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
            // Playing, but a newer playlist is still coming down. Small and out of the way.
            (download as? DownloadState.Downloading)?.let { DownloadChip(it) }
        } else {
            StatusScreen(download = download, hasPlan = plan != null)
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
 * Shown when there is nothing on the wall yet.
 *
 * Deliberately restrained. `hasContent:false` is a documented, normal state — an idle screen with
 * nothing scheduled — so the no-content case must not look like an error, while the download and
 * failure cases must be distinguishable from it at a glance from across a room.
 */
@Composable
private fun StatusScreen(download: DownloadState, hasPlan: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(48.dp),
        ) {
            when (download) {
                is DownloadState.Downloading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CircularProgressIndicator(
                            color = Accent,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = stringResource(R.string.player_downloading),
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Text(
                        text = stringResource(
                            R.string.player_downloading_count,
                            download.index,
                            download.total,
                        ),
                        color = Muted,
                        fontSize = 18.sp,
                    )

                    // Per-file percentage under the file count: on a station uplink a single 40MB
                    // video can take minutes, and a bar that only moves once per file looks stuck.
                    LinearProgressIndicator(
                        progress = { download.percent / 100f },
                        modifier = Modifier.width(520.dp),
                        color = Accent,
                        trackColor = Track,
                    )

                    Text(
                        text = "${download.fileName} · ${download.percent}%",
                        color = Dim,
                        fontSize = 15.sp,
                    )

                    Text(
                        text = stringResource(R.string.player_downloading_hint),
                        color = Dim,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                is DownloadState.Failed -> {
                    Text(
                        text = stringResource(R.string.player_download_failed),
                        color = Bad,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(
                            R.string.player_download_failed_detail,
                            download.failed,
                            download.total,
                        ),
                        color = Muted,
                        fontSize = 16.sp,
                    )
                }

                DownloadState.Idle -> {
                    Text(
                        text = stringResource(R.string.player_no_content),
                        color = Muted,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (hasPlan) {
                        Text(
                            text = stringResource(R.string.player_no_content_hint),
                            color = Dim,
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The playing-while-downloading indicator.
 *
 * Bottom-left, small, semi-transparent: it has to be visible to someone looking for it and ignorable
 * to everyone else, because whatever is behind it is live content in a public space.
 */
@Composable
private fun DownloadChip(state: DownloadState.Downloading) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xCC0B1220))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.player_downloading_count,
                    state.index,
                    state.total,
                ),
                color = Muted,
                fontSize = 13.sp,
            )
            LinearProgressIndicator(
                progress = { state.percent / 100f },
                modifier = Modifier.width(200.dp),
                color = Accent,
                trackColor = Track,
            )
        }
    }
}
