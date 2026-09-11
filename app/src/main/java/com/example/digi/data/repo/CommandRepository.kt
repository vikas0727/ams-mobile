package com.example.digi.data.repo

import com.example.digi.core.AppLog
import com.example.digi.core.ServerClock
import com.example.digi.data.local.dao.PendingCommandDao
import com.example.digi.data.local.entity.PendingCommandEntity
import com.example.digi.data.remote.ApiResult
import com.example.digi.data.remote.PlayerApi
import com.example.digi.data.remote.apiCall
import com.example.digi.data.remote.dto.AckRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Remote commands: fetch, persist, execute, acknowledge.
 *
 * `GET /player/commands` marks everything it returns as **delivered server-side before this device
 * has done anything with it**. That makes delivery at-most-once: a power cut between the fetch and
 * the ack leaves the command sitting in "delivered" forever, never retried, and an operator staring
 * at a screenshot request that will never be answered.
 *
 * Closing that window is why every fetched batch is written to disk first and only removed once the
 * ack has landed. A restart replays whatever is still there. It cannot fix the server-side gap on
 * its own — a command lost between the server marking it delivered and the bytes reaching this
 * device is gone — but it does fix the far more common case, which is this app being killed or the
 * box losing power mid-execution.
 */
class CommandRepository(
    private val api: PlayerApi,
    private val dao: PendingCommandDao,
) {

    data class Pending(
        val id: String,
        val command: String,
        val payload: JsonObject?,
        val attempts: Int,
    )

    /**
     * Pull anything queued and persist it before returning.
     *
     * @return newly fetched commands plus anything still unacknowledged from a previous run.
     */
    suspend fun fetchAndPersist(): List<Pending> {
        when (val result = apiCall { api.commands() }) {
            is ApiResult.Success -> {
                val fetched = result.data.commands
                if (fetched.isNotEmpty()) {
                    dao.insertAll(
                        fetched.map {
                            PendingCommandEntity(
                                id = it.id,
                                command = it.command,
                                payloadJson = it.payload?.let { p ->
                                    Json.encodeToString(JsonObject.serializer(), p)
                                },
                                issuedAt = it.issuedAt,
                                receivedAt = ServerClock.now(),
                            )
                        }
                    )
                    AppLog.i(TAG, "Fetched ${fetched.size} command(s): ${fetched.joinToString { it.command }}")
                }
            }

            is ApiResult.Unauthorized -> {
                AppLog.w(TAG, "Command fetch rejected: ${result.message}")
            }

            else -> AppLog.d(TAG, "Command fetch deferred: $result")
        }

        return outstanding()
    }

    /** Everything on disk awaiting execution or acknowledgement, oldest first. */
    suspend fun outstanding(): List<Pending> = dao.all().map { row ->
        Pending(
            id = row.id,
            command = row.command,
            payload = row.payloadJson?.let {
                runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
            },
            attempts = row.attempts,
        )
    }

    /**
     * Report the outcome and drop the local copy.
     *
     * The ack is truthful about degraded execution rather than optimistic: a SET_BRIGHTNESS this
     * box could not actually apply is acked with the reason attached, because a CMS showing 20%
     * against a panel sitting at 100% is worse than a visible failure.
     *
     * The local row is deleted even when the ack could not be delivered but the command *was*
     * executed — replaying a REBOOT_DEVICE on the next poll because its ack timed out would put the
     * screen in a reboot loop. Only an unexecuted command is worth keeping.
     */
    suspend fun acknowledge(
        id: String,
        success: Boolean,
        detail: String? = null,
        result: JsonElement? = null,
        executed: Boolean = true,
    ) {
        val response = apiCall {
            api.ackCommand(
                id,
                AckRequest(
                    success = success,
                    result = result,
                    errorMessage = if (success) null else detail,
                ),
            )
        }

        when (response) {
            is ApiResult.Success -> {
                dao.delete(id)
                AppLog.i(TAG, "Acked $id (${if (success) "success" else "failed"})${detail?.let { ": $it" } ?: ""}")
            }
            else -> {
                dao.markAttempted(id)
                if (executed) {
                    // Executed but unacknowledged. Keeping it would re-run it; the CMS will show it
                    // as delivered-but-never-acked, which is the accurate picture.
                    dao.delete(id)
                    AppLog.w(TAG, "Ack for $id could not be delivered; command already ran, not retrying")
                } else {
                    AppLog.d(TAG, "Ack for $id deferred, command still queued")
                }
            }
        }
    }

    /** CLEAR_DOWNLOAD_QUEUE and an unpair both need the local queue emptied. */
    suspend fun clear() = dao.clear()

    private companion object {
        const val TAG = "Commands"
    }
}
