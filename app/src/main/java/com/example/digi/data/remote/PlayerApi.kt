package com.example.digi.data.remote

import com.example.digi.data.remote.dto.AckRequest
import com.example.digi.data.remote.dto.AckResponse
import com.example.digi.data.remote.dto.CommandsResponse
import com.example.digi.data.remote.dto.DeviceInfoRequest
import com.example.digi.data.remote.dto.DeviceInfoResponse
import com.example.digi.data.remote.dto.DownloadedFilesRequest
import com.example.digi.data.remote.dto.DownloadedFilesResponse
import com.example.digi.data.remote.dto.Envelope
import com.example.digi.data.remote.dto.HeartbeatBackfillRequest
import com.example.digi.data.remote.dto.HeartbeatBackfillResponse
import com.example.digi.data.remote.dto.HeartbeatRequest
import com.example.digi.data.remote.dto.HeartbeatResponse
import com.example.digi.data.remote.dto.LiveFrameResponse
import com.example.digi.data.remote.dto.MeResponse
import com.example.digi.data.remote.dto.PairRequest
import com.example.digi.data.remote.dto.PairResponse
import com.example.digi.data.remote.dto.PlayerEventsRequest
import com.example.digi.data.remote.dto.PlayerEventsResponse
import com.example.digi.data.remote.dto.ProofOfPlayRequest
import com.example.digi.data.remote.dto.ProofOfPlayResponse
import com.example.digi.data.remote.dto.ScreenshotResponse
import com.example.digi.data.remote.dto.SyncResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * Every route in AMS's Player module, and nothing else.
 *
 * `Response<Envelope<T>>` rather than a bare `T` because the HTTP status is the only trustworthy
 * success signal here: the backend's `sendSuccess` helper writes the human message into `errorMsg`
 * whenever its `isSuccess` flag is left off, which several of these endpoints do on their happy
 * path. See [com.example.digi.data.remote.dto.Envelope].
 *
 * Auth is a Bearer player token on every route but `pair`, attached by [AuthInterceptor]; the token
 * is re-checked against the database on every single request, so unpairing a screen in the CMS
 * revokes this device within one heartbeat rather than in a year when the JWT expires.
 */
interface PlayerApi {

    @POST("player/pair")
    suspend fun pair(@Body body: PairRequest): Response<Envelope<PairResponse>>

    @GET("player/me")
    suspend fun me(): Response<Envelope<MeResponse>>

    @GET("player/sync")
    suspend fun sync(): Response<Envelope<SyncResponse>>

    @POST("player/heartbeat")
    suspend fun heartbeat(@Body body: HeartbeatRequest): Response<Envelope<HeartbeatResponse>>

    /**
     * Replay the beats that happened while this device was cut off.
     *
     * Records history only: the server deliberately does not move `lastHeartbeatAt` or `isOnline`
     * from this route, because a replay of three-day-old beats must not mark a screen online that
     * has since been unplugged again. Only a live [heartbeat] says a screen is up now.
     */
    @POST("player/heartbeat-backfill")
    suspend fun heartbeatBackfill(
        @Body body: HeartbeatBackfillRequest,
    ): Response<Envelope<HeartbeatBackfillResponse>>

    @GET("player/commands")
    suspend fun commands(): Response<Envelope<CommandsResponse>>

    @POST("player/commands/{id}/ack")
    suspend fun ackCommand(
        @Path("id") id: String,
        @Body body: AckRequest,
    ): Response<Envelope<AckResponse>>

    @POST("player/proof-of-play")
    suspend fun proofOfPlay(@Body body: ProofOfPlayRequest): Response<Envelope<ProofOfPlayResponse>>

    @POST("player/device-info")
    suspend fun deviceInfo(@Body body: DeviceInfoRequest): Response<Envelope<DeviceInfoResponse>>

    @POST("player/downloaded-files")
    suspend fun downloadedFiles(
        @Body body: DownloadedFilesRequest,
    ): Response<Envelope<DownloadedFilesResponse>>

    @POST("player/events")
    suspend fun events(@Body body: PlayerEventsRequest): Response<Envelope<PlayerEventsResponse>>

    /**
     * A Live Data View frame — a downscaled JPEG of what is on the panel right now.
     *
     * Separate from [screenshot]: these overwrite one object per screen instead of accumulating a
     * history, and the response says whether anyone is still watching. Not encrypted, for the same
     * reason as the screenshot route.
     */
    @Multipart
    @POST("player/live-frame")
    suspend fun liveFrame(@Part file: MultipartBody.Part): Response<Envelope<LiveFrameResponse>>

    /**
     * multipart/form-data with a "file" part. `commandId` closes out the SCREENSHOT command that
     * asked for the capture.
     *
     * Not encrypted — multer parses the body before body-parser could see a `data` field, and the
     * crypto interceptor skips non-JSON bodies for exactly this route.
     */
    @Multipart
    @POST("player/screenshot")
    suspend fun screenshot(
        @Part file: MultipartBody.Part,
        @Part("commandId") commandId: RequestBody?,
    ): Response<Envelope<ScreenshotResponse>>
}
