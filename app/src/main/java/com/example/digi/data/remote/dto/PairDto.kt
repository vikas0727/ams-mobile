package com.example.digi.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `POST /player/pair` — the only unauthenticated player route, and the only place a device ever
 * gets a credential.
 *
 * `deviceInfo` rides along on purpose: the schema marks it optional and the controller merges it
 * straight onto the screen, which saves a round trip on first boot and means the CMS's Additional
 * Info tab is populated the instant an operator finishes pairing, not a heartbeat later.
 */
@Serializable
data class PairRequest(
    val pairingCode: String,
    val deviceUniqueId: String,
    val platform: String,
    val appVersion: String? = null,
    val osVersion: String? = null,
    val resolution: ResolutionDto? = null,
    val deviceInfo: JsonObject? = null,
)

@Serializable
data class PairResponse(
    /** Long-lived (one year) RS256 token carrying type:"player". Store it; it is the only way back. */
    val playerToken: String,
    val screen: PairedScreenDto,
    val heartbeatIntervalSeconds: Int? = null,
)

@Serializable
data class PairedScreenDto(
    val id: String,
    val name: String? = null,
    val orientation: String? = null,
    val resolution: ResolutionDto? = null,
    val settings: SettingsDto? = null,
    val timezone: String? = null,
)

/** `GET /player/me` — confirms the token still works without pulling the whole manifest. */
@Serializable
data class MeResponse(
    val id: String,
    val name: String? = null,
    val orientation: String? = null,
    val resolution: ResolutionDto? = null,
    val settings: SettingsDto? = null,
    val contentVersion: Int? = null,
    val serverTime: String? = null,
    val heartbeatIntervalSeconds: Int? = null,
    /** So a player that just restarted picks capture up at boot rather than waiting a beat. */
    val realtimeCaptureEnabled: Boolean? = null,
)
