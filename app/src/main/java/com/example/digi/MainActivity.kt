package com.example.digi

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.digi.R
import com.example.digi.core.AppLog
import com.example.digi.core.DigiApp
import com.example.digi.core.PlayerHost
import com.example.digi.device.DeviceController
import com.example.digi.service.PlayerService
import com.example.digi.ui.diagnostics.DiagnosticsOverlay
import com.example.digi.ui.pairing.PairingScreen
import com.example.digi.ui.pairing.PairingViewModel
import com.example.digi.ui.player.PlayerScreen
import com.example.digi.ui.player.PlayerViewModel
import com.example.digi.ui.theme.DigiTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The only Activity: pairing screen, or the wall.
 *
 * It also acts as the app's [PlayerHost], because window brightness and screen capture both need a
 * window and an Activity, and the service that executes remote commands has neither. Registration is
 * tied to resume/pause so a command that arrives while the player is not in the foreground reports
 * that honestly instead of silently doing nothing.
 */
class MainActivity : ComponentActivity(), PlayerHost {

    private var playerViewModel: PlayerViewModel? = null
    private var captureThread: HandlerThread? = null
    private var frameBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A signage panel must never sleep, dim or show a lock screen, and it must come back on
        // after a power cut without anybody pressing anything.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        goImmersive()

        PlayerService.start(this)

        setContent {
            DigiTheme {
                val store = remember { DigiApp.graph(this).store }
                val paired by store.paired.collectAsStateWithLifecycle()

                // Immersive is decided by which screen is up (see goImmersive), so it has to be
                // re-applied the moment that changes. Without this a box would sit on the pairing
                // screen's visible status bar until something else happened to move window focus.
                LaunchedEffect(paired) { goImmersive() }

                val showDiagnostics by diagnosticsVisible.collectAsStateWithLifecycle()

                val rotation by appRotation.collectAsStateWithLifecycle()

                RotatedCanvas(rotation, Modifier.fillMaxSize().background(Color.Black)) {
                    if (paired) {
                        val vm: PlayerViewModel = viewModel()
                        playerViewModel = vm
                        PlayerScreen(viewModel = vm)
                    } else {
                        val vm: PairingViewModel = viewModel()
                        PairingScreen(
                            viewModel = vm,
                            onPaired = {
                                // Restart the service loop so it picks up the new credential
                                // immediately rather than on its next heartbeat.
                                PlayerService.start(this@MainActivity)
                            },
                        )
                    }

                    val askingForPin by pinPrompt.collectAsStateWithLifecycle()
                    if (askingForPin) {
                        PinGate(
                            expected = { DigiApp.graph(this@MainActivity).settings.devicePassword() },
                            onCancel = { pinPrompt.value = false },
                            onAccepted = {
                                pinAccepted.value = true
                                pinPrompt.value = false
                                diagnosticsVisible.value = true
                            },
                        )
                    }

                    if (showDiagnostics) {
                        DiagnosticsOverlay(
                            onClose = { diagnosticsVisible.value = false },
                            onForceSync = { playerViewModel?.forceSync() },
                            onUnpair = {
                                lifecycleScope.launch {
                                    DigiApp.graph(this@MainActivity).commands.clear()
                                    DigiApp.graph(this@MainActivity).pairing.unpair()
                                    diagnosticsVisible.value = false
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Overlay visibility lives on the Activity rather than in the composition, because the key
     * handler that toggles it is an Activity callback — routing that through a lambda captured
     * during composition would reassign it on every recomposition for no benefit.
     */
    private val diagnosticsVisible = MutableStateFlow(false)

    /**
     * Set once the on-device PIN has been entered, for as long as this Activity lives.
     *
     * Per-session rather than remembered: `deviceProtection` exists so that site staff standing at
     * a panel cannot reach diagnostics or unpair the screen, and a PIN that stayed satisfied across
     * a reboot would protect nothing the morning after an engineer used it.
     */
    private val pinAccepted = MutableStateFlow(false)
    private val pinPrompt = MutableStateFlow(false)

    /**
     * The app canvas rotation, in degrees.
     *
     * Seeded from the last applied value so a cold start comes up the right way round rather than
     * showing landscape for a beat and then swinging in front of whoever is watching.
     */
    private val appRotation by lazy { MutableStateFlow(DigiApp.graph(this).store.appRotationDegrees) }

    override fun applyAppRotation(degrees: Int) {
        appRotation.value = if (degrees in setOf(0, 90, 180, 270)) degrees else 0
    }

    override fun onResume() {
        super.onResume()
        PlayerHost.register(this)
        PlayerService.setForeground(true)
        goImmersive()
        /*
         * Re-asserted rather than trusted from onCreate.
         *
         * FLAG_KEEP_SCREEN_ON lives on the window, and the window does not necessarily survive
         * everything this activity does: a configuration change the manifest does not claim, a
         * recreate after the process is restored from a low-memory kill, or a vendor overlay that
         * takes focus and hands it back can all leave a window whose flags are not the ones set at
         * first launch. Re-adding a flag that is already set costs nothing, and the failure it
         * prevents — a panel that sleeps hours later, for no reason anyone can reconstruct — is one
         * of the more expensive ones to diagnose from a wall.
         */
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        PlayerHost.unregister(this)
        PlayerService.setForeground(false)
        super.onPause()
    }

    override fun onDestroy() {
        captureThread?.quitSafely()
        captureThread = null
        frameBitmap?.recycle()
        frameBitmap = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // System UI comes back after a dialog or a volume overlay; put it away again rather than
        // leaving a status bar on the wall for the rest of the day.
        if (hasFocus) goImmersive()
    }

    /**
     * Back is swallowed while paired.
     *
     * There is nowhere to go back to, and a stray press on a remote that left the player would put
     * the launcher on a public screen. This is a single swallowed key, not app pinning — the device
     * is never locked and anyone can leave with Home. The diagnostics overlay is on INFO or MENU,
     * which no content playback path uses.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_YELLOW -> {
                if (diagnosticsVisible.value) {
                    diagnosticsVisible.value = false
                } else {
                    // `deviceProtection` with a password set: the PIN prompt stands in front of
                    // diagnostics, which is where Unpair lives.
                    val password = DigiApp.graph(this).settings.devicePassword()
                    if (password != null && !pinAccepted.value) pinPrompt.value = true
                    else diagnosticsVisible.value = true
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                val paired = DigiApp.graph(this).store.paired.value
                if (paired) true else super.onKeyDown(keyCode, event)
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * Full-screen, but not while somebody is pairing.
     *
     * IMMERSIVE_STICKY together with HIDE_NAVIGATION is what a wall panel needs and what a text
     * field cannot survive on a good number of OEM boxes: raising the IME changes window focus,
     * [onWindowFocusChanged] fires as it settles, this runs again, and re-hiding the system UI takes
     * the keyboard down with it. The user sees a tap that does nothing, or a keyboard that flashes
     * and vanishes — and whether it happens comes down to the vendor's window manager, which is why
     * it struck some devices and not others.
     *
     * The player still gets immersive. The pairing screen is a commissioning tool used once, by
     * somebody standing in front of the device, and a visible status bar there costs nothing.
     */
    private fun goImmersive() {
        if (!DigiApp.graph(this).store.paired.value) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            return
        }

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    /* ── PlayerHost ─────────────────────────────────────────────────────────── */

    /**
     * PixelCopy rather than `View.getDrawingCache()`.
     *
     * The drawing cache never contains video: an ExoPlayer surface is composited by the display
     * hardware, not drawn into the view hierarchy, so a cache-based capture of a signage screen
     * returns everything except the thing the operator wanted to see. PixelCopy reads the window's
     * actual surface and gets the frame that is on the wall.
     */
    override suspend fun captureScreenshot(maxWidthPx: Int?, jpegQuality: Int?): File? = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            AppLog.w(TAG, "Screen capture needs API 26+; this box is ${Build.VERSION.SDK_INT}")
            return@withContext null
        }

        val view = window.decorView
        val sourceWidth = view.width
        val sourceHeight = view.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return@withContext null

        /*
         * Ask PixelCopy for the size actually wanted, rather than copying the whole panel and
         * shrinking it afterwards.
         *
         * PixelCopy scales the source surface into whatever bitmap it is handed, so a 640px live
         * frame costs one GPU blit into ~0.7MB instead of a full-resolution ARGB_8888 copy — 10MB
         * on a 2340x1080 panel — followed by a CPU rescale of the same. At one frame every five
         * seconds the old path meant a 10MB allocation, a large-object GC and a software resize on
         * a repeating cycle, which on a 2GB box is visible as a hitch in the video every time.
         *
         * The operator-requested screenshot passes no width and still gets the full panel.
         */
        val targetWidth = if (maxWidthPx != null && maxWidthPx in 1 until sourceWidth) maxWidthPx else sourceWidth
        val targetHeight = (sourceHeight.toLong() * targetWidth / sourceWidth).toInt().coerceAtLeast(1)

        val jpeg = jpegQuality != null
        // Live frames are a fixed size on a repeating cycle, so the one bitmap is reused rather than
        // reallocated every five seconds. Captures are serialised by the single service loop, so
        // there is no second caller to race with. A full-size screenshot is rare and one-off, and is
        // not worth keeping a 10MB buffer alive for.
        val reusable = jpeg && targetWidth < sourceWidth
        val bitmap = if (reusable) obtainFrameBitmap(targetWidth, targetHeight)
        else Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

        // A dedicated thread for the copy callback: PixelCopy must not deliver onto the main
        // looper it is about to block reading the surface from.
        val thread = captureThread ?: HandlerThread("digi-capture").also {
            it.start()
            captureThread = it
        }

        val copied = CompletableDeferred<Boolean>()
        try {
            // srcRect null = the whole window, scaled into `bitmap`.
            PixelCopy.request(window, null, bitmap, { status ->
                copied.complete(status == PixelCopy.SUCCESS)
            }, Handler(thread.looper))

            if (!copied.await()) {
                AppLog.w(TAG, "PixelCopy failed")
                if (!reusable) bitmap.recycle()
                return@withContext null
            }

            /*
             * The window copy is the whole capture, again.
             *
             * A previous version read each video SurfaceView separately with PixelCopy and drew the
             * result over this bitmap, on the theory that a SurfaceView is never in a window copy.
             * That theory is right in general and was wrong here: on this fleet's boxes the window
             * copy already contained the video — the zone is on a TextureView whenever Live Data
             * View is on, which is exactly when screenshots are being taken — so the overlay could
             * only ever repaint a correct picture with whatever the second copy returned, and on
             * hardware where that second read comes back empty the result was a black rectangle
             * over a screenshot that had been fine.
             *
             * Left out rather than made conditional. If a genuinely black-video screenshot shows up
             * on a box with Live Data View OFF, the fix is to read that one surface and composite
             * only when the window copy is actually empty there — provable on the device, which the
             * removed version was not.
             */

            // Compression is the slow part and needs no window, so it goes off the main thread — an
            // encode on a cheap SoC is comfortably long enough to drop frames, and at one live frame
            // every five seconds that would be visible on the wall.
            withContext(Dispatchers.IO) {
                val file = File(
                    cacheDir,
                    "${if (jpeg) "frame" else "screenshot"}-${System.currentTimeMillis()}" +
                        if (jpeg) ".jpg" else ".png",
                )
                FileOutputStream(file).use { out ->
                    if (jpeg) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(1, 100), out)
                    } else {
                        // PNG for an operator-requested screenshot: it goes in the history and may
                        // be read closely, and a re-encoded JPEG of a text-heavy signage layout is
                        // harder to read than the extra megabyte is to send.
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
                if (!reusable) bitmap.recycle()
                file
            }
        } catch (e: Throwable) {
            AppLog.e(TAG, "Screen capture failed", e)
            if (!reusable) runCatching { bitmap.recycle() }
            null
        }
    }

    /**
     * The reusable live-frame buffer, reallocated only when the requested size changes — which
     * happens once, on the first frame after the panel's resolution is known.
     */
    private fun obtainFrameBitmap(width: Int, height: Int): Bitmap {
        val existing = frameBitmap
        if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { frameBitmap = it }
    }

    /**
     * Window attributes are main-thread-only and the caller here is the command executor running on
     * the service's IO loop, so this hops threads rather than touching the window from wherever it
     * happens to be called.
     */
    override fun applyWindowBrightness(level: Int) {
        runOnUiThread { DeviceController.applyWindowBrightness(this, level) }
    }

    override fun setBlanked(blanked: Boolean) {
        runOnUiThread { playerViewModel?.setBlanked(blanked) }
    }

    override fun currentlyPlaying(): String? = playerViewModel?.currentlyPlaying()

    override fun playbackState(): PlayerHost.PlaybackState? = playerViewModel?.playbackState()

    /**
     * Hops to the main thread because the caller is the service's IO loop and this ends in Compose
     * state that must not be written from elsewhere. Reports whether there was actually a view model
     * to restart — before pairing, or with the Activity gone, there is nothing to recycle and the
     * caller needs to know rather than assume.
     */
    override fun restartPlayback(): Boolean {
        val vm = playerViewModel ?: return false
        runOnUiThread { vm.restartPlayback() }
        return true
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}

/**
 * The player's canvas, turned by [degrees].
 *
 * The trick is that a 90 or 270 degree turn has to swap the box's width and height BEFORE rotating
 * it: rotation happens about the centre and does not resize anything, so rotating a
 * 1920x1080 box by ninety degrees inside a 1920x1080 window leaves a 1080-wide picture with bars
 * either side and the top and bottom cropped off. Laying it out 1080x1920 first and then turning it
 * lands it exactly over the panel.
 *
 * Done in the drawing layer rather than through `requestedOrientation`, which is advisory: plenty of
 * TV boxes lock themselves to landscape and ignore it, and the ones that honour it recreate the
 * Activity — restarting playback every time an operator changes a setting.
 */
@Composable
private fun RotatedCanvas(
    degrees: Int,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (degrees == 0) {
        Box(modifier, content = content)
        return
    }

    BoxWithConstraints(modifier) {
        val swap = degrees == 90 || degrees == 270
        Box(
            modifier = Modifier
                .size(
                    width = if (swap) maxHeight else maxWidth,
                    height = if (swap) maxWidth else maxHeight,
                )
                .align(Alignment.Center)
                .graphicsLayer { rotationZ = degrees.toFloat() },
            content = content,
        )
    }
}

/**
 * The on-device PIN prompt for `deviceProtection`.
 *
 * Numeric and driven from a TV remote — there is no keyboard on a signage box, and the field this
 * checks against is described in the CMS as a PIN. The comparison is a plain string match because
 * that is what the CMS stores; this gate keeps a passer-by out of diagnostics, and it is not, and
 * should not be mistaken for, a secret worth protecting cryptographically.
 *
 * Cancels back to content rather than trapping anyone: a wrong guess must not leave a public screen
 * sitting behind a modal.
 */
@Composable
private fun PinGate(
    expected: () -> String?,
    onCancel: () -> Unit,
    onAccepted: () -> Unit,
) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6070B12)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.pin_title),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = if (entered.isEmpty()) "—" else "•".repeat(entered.length),
                color = if (wrong) Color(0xFFFF6B6B) else Color.White,
                fontSize = 28.sp,
                letterSpacing = 6.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(if (wrong) R.string.pin_wrong else R.string.pin_hint),
                color = Color(0xFF9AA4B2),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(18.dp))

            // A remote's number keys reach the Activity, not a composable, so the digits are laid
            // out as focusable buttons that a D-pad can walk.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..9).forEach { digit ->
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1B2434))
                            .clickable {
                                wrong = false
                                if (entered.length < 12) entered += digit.toString()
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text("$digit", color = Color.White, fontSize = 14.sp) }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GateButton(stringResource(R.string.pin_clear)) { entered = ""; wrong = false }
                GateButton(stringResource(R.string.pin_cancel)) { onCancel() }
                GateButton(stringResource(R.string.pin_ok)) {
                    if (entered.isNotEmpty() && entered == expected()) onAccepted()
                    else { wrong = true; entered = "" }
                }
            }
        }
    }
}

@Composable
private fun GateButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF243047))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(label, color = Color.White, fontSize = 13.sp) }
}
