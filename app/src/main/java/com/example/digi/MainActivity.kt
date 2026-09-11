package com.example.digi

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The only Activity: pairing screen, or the wall.
 *
 * It also acts as the app's [PlayerHost], because kiosk mode, window brightness and screen capture
 * all need a window and an Activity, and the service that executes remote commands has neither.
 * Registration is tied to resume/pause so a command that arrives while the player is not in the
 * foreground reports that honestly instead of silently doing nothing.
 */
class MainActivity : ComponentActivity(), PlayerHost {

    private var playerViewModel: PlayerViewModel? = null
    private var captureThread: HandlerThread? = null

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
                val showDiagnostics by diagnosticsVisible.collectAsStateWithLifecycle()

                Box(Modifier.fillMaxSize().background(Color.Black)) {
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

    override fun onResume() {
        super.onResume()
        PlayerHost.register(this)
        PlayerService.setForeground(true)
        goImmersive()
    }

    override fun onPause() {
        PlayerHost.unregister(this)
        PlayerService.setForeground(false)
        super.onPause()
    }

    override fun onDestroy() {
        captureThread?.quitSafely()
        captureThread = null
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
     * On a kiosk there is nowhere to go back to, and a stray press on a remote that left the player
     * would put the launcher on a public screen. The diagnostics overlay is reached by the INFO or
     * MENU key instead, which no content playback path uses.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_YELLOW -> {
                diagnosticsVisible.value = !diagnosticsVisible.value
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                val paired = DigiApp.graph(this).store.paired.value
                if (paired) true else super.onKeyDown(keyCode, event)
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun goImmersive() {
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
    override suspend fun captureScreenshot(): File? = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            AppLog.w(TAG, "Screen capture needs API 26+; this box is ${Build.VERSION.SDK_INT}")
            return@withContext null
        }

        val view = window.decorView
        if (view.width <= 0 || view.height <= 0) return@withContext null

        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        // A dedicated thread for the copy callback: PixelCopy must not deliver onto the main
        // looper it is about to block reading the surface from.
        val thread = captureThread ?: HandlerThread("digi-capture").also {
            it.start()
            captureThread = it
        }

        val copied = CompletableDeferred<Boolean>()
        try {
            PixelCopy.request(window, bitmap, { status ->
                copied.complete(status == PixelCopy.SUCCESS)
            }, Handler(thread.looper))

            if (!copied.await()) {
                AppLog.w(TAG, "PixelCopy failed")
                bitmap.recycle()
                return@withContext null
            }

            // Compression is the slow part and needs no window, so it goes back off the main
            // thread — a 1080p PNG encode on a cheap SoC is comfortably long enough to drop frames.
            withContext(Dispatchers.IO) {
                val file = File(cacheDir, "screenshot-${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    // PNG because the CMS stores and displays these as-is, and a re-encoded JPEG of
                    // a text-heavy signage layout is harder to read than the extra megabyte is to
                    // send.
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bitmap.recycle()
                file
            }
        } catch (e: Throwable) {
            AppLog.e(TAG, "Screen capture failed", e)
            runCatching { bitmap.recycle() }
            null
        }
    }

    /**
     * Lock task and window attributes are main-thread-only, and every caller here is the command
     * executor running on the service's IO loop. Hopping threads and waiting is the honest option:
     * the executor needs the real outcome to put in its acknowledgement, and posting-and-guessing
     * would report a success the device may not have achieved.
     */
    override fun setKiosk(enabled: Boolean): DeviceController.Outcome = onMainThread(
        fallback = DeviceController.Outcome.failed("Timed out waiting for the UI thread"),
    ) {
        if (enabled) DeviceController.startKiosk(this) else DeviceController.stopKiosk(this)
    }

    override fun applyWindowBrightness(level: Int) {
        runOnUiThread { DeviceController.applyWindowBrightness(this, level) }
    }

    override fun setBlanked(blanked: Boolean) {
        runOnUiThread { playerViewModel?.setBlanked(blanked) }
    }

    override fun currentlyPlaying(): String? = playerViewModel?.currentlyPlaying()

    /** Run [block] on the main thread and wait for its result, or give up after [timeoutMs]. */
    private fun <T> onMainThread(fallback: T, timeoutMs: Long = 5_000, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()

        val latch = CountDownLatch(1)
        val holder = AtomicReference(fallback)
        runOnUiThread {
            try {
                holder.set(block())
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) holder.get() else fallback
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
