package com.example.digi.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.digi.R

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

private val Ink = Color(0xFF070B12)
private val Panel = Color(0xFF121A28)
private val Accent = Color(0xFF2F6BFF)
private val Muted = Color(0xFF6B7688)
private val Danger = Color(0xFFFF6B6B)

/**
 * Pairing, designed for a device with no keyboard and no mouse.
 *
 * The on-screen character grid is not a fallback — it is the primary input. A signage box ships
 * with a four-way remote; some Android TV builds raise a soft keyboard for a text field and some do
 * not, and a commissioning engineer standing on a ladder at a railway platform cannot be left
 * dependent on which. Every control here is D-pad focusable and the grid is laid out so that the
 * digits (which pairing codes are mostly made of) come first.
 */
@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    onPaired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Ink)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val unicodeChar = keyEvent.nativeKeyEvent.unicodeChar
                    if (unicodeChar != 0 && unicodeChar.toChar().isLetterOrDigit()) {
                        viewModel.append(unicodeChar.toChar(), onPaired)
                        return@onKeyEvent true
                    }
                    when (keyEvent.key) {
                        Key.Backspace -> {
                            viewModel.backspace()
                            return@onKeyEvent true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            if (state.canSubmit) {
                                viewModel.submit(onPaired)
                                return@onKeyEvent true
                            }
                        }
                    }
                }
                false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(40.dp),
        ) {
            Text(
                text = stringResource(R.string.pair_title),
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.pair_subtitle),
                color = Muted,
                fontSize = 16.sp,
            )

            CodeBoxes(state.code)

            state.error?.let {
                Text(it, color = Danger, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
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
                    onChar = { char -> viewModel.append(char, onPaired) },
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
                    modifier = Modifier.width(220.dp).height(52.dp),
                ) {
                    Text(stringResource(R.string.pair_action), fontSize = 18.sp)
                }
            }

            // The device id and server address are shown because they are the two things an
            // engineer is asked for when pairing does not work, and reading them off the wall beats
            // an adb session against a box mounted three metres up.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 18.dp),
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

@Composable
private fun CodeBoxes(code: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(PairingViewModel.CODE_LENGTH) { index ->
            val char = code.getOrNull(index)
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 84.dp)
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
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private const val BACKSPACE = "⌫"
private const val CLEAR = "CLR"

/** Digits first: pairing codes are generated from an alphabet that is mostly numeric. */
private val KEYS: List<String> =
    ('0'..'9').map { it.toString() } + ('A'..'Z').map { it.toString() } + listOf(BACKSPACE, CLEAR)

@Composable
private fun CharacterGrid(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(12),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.width(640.dp).height(180.dp),
    ) {
        items(KEYS) { key ->
            KeyCap(
                label = key,
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

@Composable
private fun KeyCap(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(44.dp)
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
            fontSize = if (label.length > 1) 12.sp else 17.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
