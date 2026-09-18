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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.digi.R
import com.example.digi.core.AmsConstants
import com.example.digi.core.DigiApp
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
 *  - **nothing assigned** → "No Content Assigned", with what to do next.
 *  - **downloading** → the full progress panel, black, centred, on its own.
 *  - **downloads failed** → the reason, on the glass.
 *
 * A download takes the screen. This reverses an earlier design in which an already-playing loop
 * kept running behind a small corner chip while the next playlist came down — the reasoning being
 * that a content change should never black out a live panel. Operationally the opposite is wanted:
 * when a new playlist is assigned the old one is what the site has just been told to STOP showing,
 * and leaving it up for the length of a download means a screen carrying withdrawn advertising for
 * minutes after it was pulled. Going black and saying why is the honest state, and it is a state an
 * operator standing at the panel can read from across the room.
 *
 * Note that this costs nothing on a routine re-sync: `ContentRepository.downloadAssets` only enters
 * [DownloadState.Downloading] when at least one asset is genuinely absent from the cache. A manifest
 * refresh that re-signs URLs for files the box already holds never reaches this branch, so a healthy
 * screen is not interrupted by housekeeping.
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

    val context = LocalContext.current
    /*
     * Read as a FLOW, not once.
     *
     * This was `remember { ... }` with no key — evaluated at first composition and never again — so
     * toggling dot indicators in the portal changed nothing until somebody restarted the app on the
     * wall. From an operator's chair that is indistinguishable from the setting being broken, which
     * is exactly how it was reported.
     *
     * A rendering preference has nothing to push at hardware, so there is no "apply" step to hang it
     * on; the screen simply has to redraw when the value changes, which is what collecting it does.
     */
    val settings by DigiApp.graph(context).store.settings.collectAsStateWithLifecycle()
    // Threaded down to every zone so a playback restart disposes the ExoPlayers and their surfaces
    // and builds fresh ones. See PlayerViewModel.restartPlayback.
    val playbackGeneration by viewModel.playbackGeneration.collectAsStateWithLifecycle()
    val dotIndicators = settings?.dotIndicators == AmsConstants.ACTIVE

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
        /*
         * A download outranks the frame.
         *
         * PlayerViewModel drops the frame to null while a download is running, which is what
         * actually stops playback: the zones leave the composition, their ExoPlayers are released
         * by ZoneContent's DisposableEffect, and no proof-of-play accrues for slides nobody can
         * see. This check is the same decision made one tick earlier, so the swap to black happens
         * on the composition that observes the download rather than up to TICK_MS later — the
         * difference between a clean cut and a visible stutter of the outgoing loop.
         */
        if (current != null && download !is DownloadState.Downloading) {
            LayoutCanvas(current, playbackGeneration)
            // The CMS's `dotIndicators` setting, honoured. Read at draw time rather than pushed,
            // because it is a rendering preference with nothing to "apply" to hardware.
            if (dotIndicators) SlideDots(current, Modifier.align(Alignment.BottomCenter))
        } else {
            StatusScreen(download = download, hasPlan = plan != null)
        }
    }
}

@Composable
private fun LayoutCanvas(frame: PlaybackEngine.Frame, playbackGeneration: Int) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = maxWidth
        val panelHeight = maxHeight

        frame.zones
            .sortedBy { it.zone.zIndex }
            .forEach { zoneFrame ->
                val zone = zoneFrame.zone
                // Keyed by zone, so Compose identifies these by WHICH zone rather than by position
                // in the list. Without it a layout change that reorders or drops a zone slides the
                // remembered state — including the zone's ExoPlayer and its live surface — onto a
                // different zone, which is a black flash at best and the wrong clip at worst.
                // The generation joins the key so "Restart Player App" tears every zone down and
                // rebuilds it — releasing each decoder — without restarting the process.
                key(zone.key, playbackGeneration) {
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
                    // The actual reason, on the wall. Whoever is standing in front of a screen that
                    // will not play is the person who can fix the network it is on, and making them
                    // go and find adb first wastes the trip.
                    Text(
                        text = download.reason,
                        color = Dim,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 760.dp).padding(top = 4.dp),
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
 * Slide position dots, as the CMS's `dotIndicators` setting asks for.
 *
 * Drawn for the busiest zone — the one with the most slides — because a layout can have several
 * running at different lengths and a row of dots that tracked a two-item ticker while a ten-item
 * main zone cycled underneath would be telling the viewer something untrue about the loop.
 *
 * Deliberately small and low-contrast. This sits on a public screen: it is an operator's aid, not
 * part of the advert somebody paid for.
 */
@Composable
private fun SlideDots(frame: PlaybackEngine.Frame, modifier: Modifier = Modifier) {
    val zone = frame.zones.maxByOrNull { it.zone.slides.size } ?: return
    val count = zone.zone.slides.size
    if (count < 2) return

    Row(
        modifier = modifier.padding(bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(if (index == zone.slideIndex) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == zone.slideIndex) Color.White.copy(alpha = 0.85f)
                        else Color.White.copy(alpha = 0.35f)
                    )
            )
        }
    }
}
