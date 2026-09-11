package com.example.digi.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `GET /player/commands` — up to 20 pending commands, oldest first.
 *
 * Fetching them **marks them delivered server-side**, before this player has done anything with
 * them. That makes delivery at-most-once: a crash between the fetch and the ack leaves the command
 * stuck in "delivered" forever, never retried. The player therefore persists the fetched batch to
 * disk before acting on it, so a restart still acknowledges work it had already accepted.
 */
@Serializable
data class CommandsResponse(
    val commands: List<CommandDto> = emptyList(),
)

@Serializable
data class CommandDto(
    val id: String,
    val command: String,
    /** Free-form per command type: `{level: 0-100}` for SET_VOLUME / SET_BRIGHTNESS, a settings
     *  patch for APPLY_CONFIG, usually absent for the rest. */
    val payload: JsonObject? = null,
    val issuedAt: String? = null,
)

/**
 * `POST /player/commands/:id/ack`.
 *
 * `success:false` marks the command failed and stores `errorMessage` for the operator to read. A
 * successful SET_VOLUME / SET_BRIGHTNESS also writes the confirmed level back onto the screen
 * record — so acking a volume change the device could not actually apply would leave the CMS
 * showing a value the panel is not at.
 */
@Serializable
data class AckRequest(
    val success: Boolean = true,
    val result: JsonElement? = null,
    val errorMessage: String? = null,
)

@Serializable
data class AckResponse(
    val id: String? = null,
    val status: String? = null,
)
