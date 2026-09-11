package com.example.digi.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every AMS response, from `Utils/ResponseHandler.js`.
 *
 * Note the asymmetry the backend actually ships: `sendSuccess(res, data, msg, true)` emits
 * `{ message, response }`, while `sendSuccess(res, data, msg)` with the flag left off emits
 * `{ errorMsg, response }` — and half the player endpoints use the second form on their *success*
 * path. So a non-null `errorMsg` is NOT by itself an error. The HTTP status is the only reliable
 * success signal, which is why [com.example.digi.data.remote.ApiCall] keys off the status code and
 * treats these two fields purely as display text.
 */
@Serializable
data class Envelope<T>(
    val message: String? = null,
    val errorMsg: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    val response: T? = null,
) {
    /** Whatever human-readable text the server attached, in the order it is worth showing. */
    val text: String? get() = errorMsg?.takeIf { it.isNotBlank() } ?: message?.takeIf { it.isNotBlank() }
}
