package com.example.digi.ui.pairing

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.digi.R
import kotlin.math.floor

private val Ink = Color(0xFF070B12)
private val Panel = Color(0xFF121A28)
private val Accent = Color(0xFF2F6BFF)
private val Muted = Color(0xFF6B7688)
private val Danger = Color(0xFFFF6B6B)

/**
 * Pairing, for both kinds of device this app is commissioned on.
 *
 * ## Two input methods, not one
 *
 * The on-screen character grid is here because a signage box ships with a four-way remote and no
 * keyboard, and some Android TV builds never raise an IME at all. It was, for a while, the ONLY
 * input: the code boxes were decorative `Box`es, so on a tablet or phone — which is what most
 * people actually commission with — tapping them did nothing at all and there was no way to type.
 * `PairingViewModel.setCode` was written for an IME that nothing ever called.
 *
 * So the code row is now a real, focusable text field wearing the same six boxes. Tap it and the
 * keyboard comes up; press a key on the grid with a remote and it fills the same state. Neither
 * path knows about the other.
 *
 * ## Sized from the space it is given
 *
 * Every dimension here used to be a constant — 64dp boxes, a 640dp grid — chosen for a 1080p panel.
 * Below about 700dp of width that overflowed with no way to scroll to what had been pushed off, and
 * the submit button was the first casualty. Now the box size is derived from the available width and
 * the whole column scrolls, so a 7" tablet held in portrait gets a smaller version of the same
 * screen rather than a clipped one.
 */
@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    onPaired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Ink)
            // The status/nav bars are visible on this screen (see MainActivity.goImmersive) so that
            // the IME behaves; that means their space has to be respected or the title sits under
            // the clock. imePadding lifts the content clear of the keyboard itself.
            .safeDrawingPadding()
            .imePadding(),
    ) {
        /*
         * One breakpoint, and it is about the phone-in-portrait case rather than any particular
         * device. Below this there is not room for the full-size boxes plus a thirteen-wide grid,
         * and above it the original proportions are what the screen was designed at.
         */
        val compact = maxWidth < 600.dp || maxHeight < 480.dp

        val outerPadding = if (compact) 16.dp else 40.dp
        val available = maxWidth - outerPadding * 2

        val metrics = remember(available, compact) { Metrics.forWidth(available, compact) }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                // Scrollable so that nothing is ever unreachable: a keyboard over a short landscape
                // screen can leave less than 200dp of usable height, and the submit button has to
                // stay gettable at whatever is left.
                .verticalScroll(rememberScrollState())
                .padding(outerPadding),
        ) {
            Text(
                text = stringResource(R.string.pair_title),
                color = Color.White,
                fontSize = if (compact) 24.sp else 34.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.pair_subtitle),
                color = Muted,
                fontSize = if (compact) 14.sp else 16.sp,
            )

            CodeField(
                code = state.code,
                enabled = !state.busy,
                metrics = metrics,
                onCodeChange = viewModel::setCode,
                onSubmit = { if (state.canSubmit) viewModel.submit(onPaired) },
            )

            state.error?.let {
                Text(
                    text = it,
                    color = Danger,
                    fontSize = if (compact) 13.sp else 15.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (state.busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(22.dp))
                    Text(stringResource(R.string.pair_working), color = Muted, fontSize = 16.sp)
                }
            } else {
                CharacterGrid(
                    metrics = metrics,
                    onChar = viewModel::append,
                    onBackspace = viewModel::backspace,
                    onClear = viewModel::clear,
                )

                Button(
                    onClick = { viewModel.submit(onPaired) },
                    enabled = state.canSubmit,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        disabledContainerColor = Panel,
                    ),
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .fillMaxWidth()
                        .height(if (compact) 46.dp else 52.dp),
                ) {
                    Text(stringResource(R.string.pair_action), fontSize = if (compact) 16.sp else 18.sp)
                }
            }

            // The device id and server address are shown because they are the two things an
            // engineer is asked for when pairing does not work, and reading them off the wall beats
            // an adb session against a box mounted three metres up.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = if (compact) 8.dp else 18.dp),
            ) {
                Text(
                    "${stringResource(R.string.pair_device_id)}: ${state.deviceId}",
                    color = Muted,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(state.serverUrl, color = Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/**
 * Everything whose size depends on how much width there is.
 *
 * Computed once per width rather than scattered through the tree, so the code boxes and the key
 * grid cannot disagree about how much room they have and overflow each other.
 */
private data class Metrics(
    val boxWidth: Dp,
    val boxHeight: Dp,
    val boxGap: Dp,
    val codeFontSize: Int,
    val keySize: Dp,
    val keyGap: Dp,
    val keyColumns: Int,
) {
    companion object {
        fun forWidth(available: Dp, compact: Boolean): Metrics {
            val boxGap = if (compact) 6.dp else 12.dp
            // Never wider than the design size, but as narrow as it must be to fit six of them.
            val boxWidth = ((available - boxGap * (PairingViewModel.CODE_LENGTH - 1)) /
                PairingViewModel.CODE_LENGTH).coerceIn(28.dp, 64.dp)

            val keyGap = if (compact) 6.dp else 8.dp
            val keySize = if (compact) 34.dp else 44.dp
            // As many keys per row as actually fit. Ten is the number that matters — it puts the
            // digits on one row, and pairing codes are mostly digits.
            val columns = floor(((available + keyGap) / (keySize + keyGap)))
                .toInt()
                .coerceIn(6, 13)

            return Metrics(
                boxWidth = boxWidth,
                boxHeight = boxWidth * 84f / 64f,
                boxGap = boxGap,
                // Scaled off the box rather than fixed, or a 28dp box gets a 40sp glyph.
                codeFontSize = (boxWidth.value * 0.6f).toInt().coerceIn(14, 40),
                keySize = keySize,
                keyGap = keyGap,
                keyColumns = columns,
            )
        }
    }
}

/**
 * The six code boxes, which are also the text field.
 *
 * A real [BasicTextField] sits underneath at zero alpha holding the actual code; the boxes are what
 * it looks like. That is what makes a tap raise the keyboard — an IME needs a focused editor to
 * attach to, and drawing boxes that look like one is not enough, which is precisely the bug this
 * replaces.
 *
 * The keyboard is asked for explicitly as well as implicitly. Focus alone is enough on a phone;
 * on several TV-derived builds the window reports a hardware keyboard it does not have and the IME
 * is suppressed, and asking the window's insets controller for it directly gets it up anyway.
 */
@Composable
private fun CodeField(
    code: String,
    enabled: Boolean,
    metrics: Metrics,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current

    val showKeyboard = {
        if (enabled) {
            runCatching { focusRequester.requestFocus() }
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.ime())
            }
        }
    }

    Box(contentAlignment = Alignment.Center) {
        BasicTextField(
            value = code,
            onValueChange = onCodeChange,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                // Codes are uppercase alphanumerics; autocorrect on a six-character nonsense string
                // is actively harmful, and the VM upper-cases whatever arrives anyway.
                capitalization = KeyboardCapitalization.Characters,
                autoCorrect = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier
                .focusRequester(focusRequester)
                // Present and focusable, but invisible: the boxes below are the visible field. Kept
                // at a real size rather than 0.dp because a zero-sized editor is skipped for focus
                // on some builds.
                .size(1.dp)
                .alpha(0f),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(metrics.boxGap),
            modifier = Modifier.clickable(enabled = enabled) { showKeyboard() },
        ) {
            repeat(PairingViewModel.CODE_LENGTH) { index ->
                val char = code.getOrNull(index)
                Box(
                    modifier = Modifier
                        .size(width = metrics.boxWidth, height = metrics.boxHeight)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Panel)
                        .border(
                            width = 2.dp,
                            // Highlight the slot about to be filled, so a remote-driven entry has a
                            // visible cursor without needing a blinking caret.
                            color = if (index == code.length) Accent else Color(0xFF22304A),
                            shape = RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = char?.toString() ?: "",
                        color = Color.White,
                        fontSize = metrics.codeFontSize.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private const val BACKSPACE = "⌫"
private const val CLEAR = "CLR"

/** Digits first: pairing codes are generated from an alphabet that is mostly numeric. */
private val KEYS: List<String> =
    ('0'..'9').map { it.toString() } + ('A'..'Z').map { it.toString() } + listOf(BACKSPACE, CLEAR)

/**
 * The remote-driven keypad.
 *
 * Plain rows rather than a LazyVerticalGrid. Thirty-eight fixed keys need no virtualisation, and a
 * lazy grid nested in the scrolling column above is the classic way to get an infinite-height
 * measure crash — which would be a crash on the one screen nobody can get past.
 */
@Composable
private fun CharacterGrid(
    metrics: Metrics,
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(metrics.keyGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KEYS.chunked(metrics.keyColumns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(metrics.keyGap)) {
                row.forEach { key ->
                    KeyCap(
                        label = key,
                        size = metrics.keySize,
                        onClick = {
                            when (key) {
                                BACKSPACE -> onBackspace()
                                CLEAR -> onClear()
                                else -> onChar(key.first())
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyCap(label: String, size: Dp, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Accent else Panel)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (focused) Color.White else Color(0xFFB7C2D4),
            fontSize = if (label.length > 1) (size.value * 0.27f).sp else (size.value * 0.39f).sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
