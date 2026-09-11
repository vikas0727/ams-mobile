package com.example.digi.data.remote

import com.example.digi.data.remote.dto.Envelope
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

/**
 * What every call in this app returns.
 *
 * The distinction that matters to a signage player is not success-versus-failure but
 * **[Offline] versus [Failed]**: a screen on a station link loses connectivity constantly and that
 * is not an error condition, it is the normal weather. [Offline] means "try again later, keep
 * playing from cache, keep queueing reports". [Failed] means the server answered and said no —
 * retrying the same request unchanged will keep saying no.
 *
 * [Unauthorized] is separated out because it is the one failure with a real remedy: the screen has
 * been unpaired, suspended or deleted in the CMS, and the app must drop its token and go back to
 * the pairing screen rather than retry a credential that will never work again.
 */
sealed interface ApiResult<out T> {

    data class Success<T>(val data: T, val message: String? = null) : ApiResult<T>

    /** No usable network path: DNS, timeout, connection reset, TLS. Retry later. */
    data class Offline(val cause: Throwable) : ApiResult<Nothing>

    /** The server replied with a non-2xx status, or a 2xx whose envelope had no payload. */
    data class Failed(val code: Int, val message: String?) : ApiResult<Nothing>

    /** 401. The token is dead — unpair locally. */
    data class Unauthorized(val message: String?) : ApiResult<Nothing>
}

/**
 * Run a call and normalise everything that can come back from it.
 *
 * Deliberately catches [Throwable] rather than a hand-picked list: a malformed manifest throwing a
 * serialization error inside a background heartbeat loop must not take the playback service down
 * with it. [CancellationException] is re-thrown so coroutine cancellation still works.
 */
suspend fun <T : Any> apiCall(block: suspend () -> Response<Envelope<T>>): ApiResult<T> = try {
    val response = block()
    val envelope = response.body()
    when {
        response.code() == 401 -> ApiResult.Unauthorized(envelope?.text ?: "Player token rejected")
        !response.isSuccessful -> ApiResult.Failed(response.code(), envelope?.text ?: response.message())
        envelope?.response != null -> ApiResult.Success(envelope.response, envelope.text)
        // A 200 with no payload happens on routes whose only job is to acknowledge; callers that
        // need data treat it as a failure, and callers that do not use ackOnly() below.
        else -> ApiResult.Failed(response.code(), envelope?.text ?: "Empty response")
    }
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    ApiResult.Offline(e)
} catch (e: Throwable) {
    ApiResult.Failed(-1, e.message ?: e::class.java.simpleName)
}

/** True when the caller should keep its local queue and try again rather than discard work. */
val ApiResult<*>.isRetryable: Boolean
    get() = this is ApiResult.Offline || (this is ApiResult.Failed && (code == -1 || code >= 500 || code == 429))
