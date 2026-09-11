package com.example.digi.ui.pairing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.digi.BuildConfig
import com.example.digi.core.DigiApp
import com.example.digi.data.repo.PairingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The pairing screen's state.
 *
 * Codes are six characters, uppercase, and the backend upper-cases and trims whatever it is sent —
 * but doing it here too means the boxes on screen show what will actually be submitted rather than
 * what was typed, which is the difference between a confusing rejection and an obvious one.
 */
class PairingViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = DigiApp.graph(app)

    data class UiState(
        val code: String = "",
        val busy: Boolean = false,
        val error: String? = null,
        val pairedAs: String? = null,
        val deviceId: String = "",
        val serverUrl: String = BuildConfig.API_BASE_URL,
    ) {
        val canSubmit: Boolean get() = code.length == CODE_LENGTH && !busy
    }

    private val _state = MutableStateFlow(UiState(deviceId = graph.pairing.deviceId()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun append(char: Char, onPaired: (() -> Unit)? = null) {
        val state = _state.value
        if (state.busy || state.code.length >= CODE_LENGTH) return
        val newCode = state.code + char.uppercaseChar()
        _state.value = state.copy(code = newCode, error = null)
        if (newCode.length == CODE_LENGTH && onPaired != null) {
            submit(onPaired)
        }
    }

    fun backspace() {
        val state = _state.value
        if (state.busy || state.code.isEmpty()) return
        _state.value = state.copy(code = state.code.dropLast(1), error = null)
    }

    fun clear() {
        _state.value = _state.value.copy(code = "", error = null)
    }

    /** Accepts a pasted or IME-typed value, filtered to the characters a pairing code can contain. */
    fun setCode(raw: String, onPaired: (() -> Unit)? = null) {
        val cleaned = raw.uppercase().filter { it.isLetterOrDigit() }.take(CODE_LENGTH)
        _state.value = _state.value.copy(code = cleaned, error = null)
        if (cleaned.length == CODE_LENGTH && onPaired != null) {
            submit(onPaired)
        }
    }

    fun submit(onPaired: () -> Unit) {
        val current = _state.value
        if (!current.canSubmit) return

        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            when (val outcome = graph.pairing.pair(current.code)) {
                is PairingRepository.PairOutcome.Paired -> {
                    _state.value = _state.value.copy(busy = false, pairedAs = outcome.screenName)
                    onPaired()
                }
                is PairingRepository.PairOutcome.Rejected -> {
                    // Clear the code on rejection: a wrong or expired code is never worth
                    // resubmitting, and leaving it on screen invites exactly that.
                    _state.value = _state.value.copy(busy = false, error = outcome.message, code = "")
                }
                is PairingRepository.PairOutcome.Offline -> {
                    // Keep the code: the network is the problem, not what was typed, and retyping
                    // six characters on a TV remote is genuinely tedious.
                    _state.value = _state.value.copy(busy = false, error = outcome.message)
                }
            }
        }
    }

    companion object {
        const val CODE_LENGTH = 6
    }
}
