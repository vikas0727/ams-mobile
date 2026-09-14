package com.example.digi.core

/**
 * The half of AMS's `Utils/Constants.js` that reaches a device.
 *
 * Mirrored here as literals rather than parsed from the API because they are a wire contract: the
 * backend sends the string "SET_VOLUME" and expects `completed: 1`, and a typo in either direction
 * is a runtime-only failure that looks like "the command did nothing". Keeping them in one file
 * makes the diff against Constants.js a two-minute job when the backend adds a command.
 */
object AmsConstants {

    /** The backend models booleans as 1/0 integers throughout — `varConst.ACTIVE` / `INACTIVE`. */
    const val ACTIVE = 1
    const val INACTIVE = 0

    /** Heartbeat cadence fallback: SCREEN_OFFLINE_AFTER_SECONDS (180) / 3. The server sends the
     *  real value on pair, /me and every heartbeat; this is only used before the first response. */
    const val DEFAULT_HEARTBEAT_SECONDS = 60

    /** Screen lifecycle, from SCREEN_STATUS_ARRAY. */
    object ScreenStatus {
        const val UNPAIRED = "unpaired"
        const val ACTIVE = "active"
        const val SUSPENDED = "suspended"
    }

    /** PLAYER_PLATFORM_ARRAY — this app only ever reports the first. */
    const val PLATFORM_ANDROID = "android"

    /** SCREEN_COMMAND_ARRAY, in the order Constants.js declares them. */
    object Command {
        const val SCREENSHOT = "SCREENSHOT"
        const val SET_VOLUME = "SET_VOLUME"
        const val SET_BRIGHTNESS = "SET_BRIGHTNESS"
        const val RESTART_APP = "RESTART_APP"
        const val REBOOT_DEVICE = "REBOOT_DEVICE"
        const val CLEAR_CACHE = "CLEAR_CACHE"
        const val SYNC_NOW = "SYNC_NOW"
        const val KIOSK_ON = "KIOSK_ON"
        const val KIOSK_OFF = "KIOSK_OFF"
        const val TIME_SYNC = "TIME_SYNC"
        const val POWER_ON = "POWER_ON"
        const val POWER_OFF = "POWER_OFF"
        const val UPDATE_APP = "UPDATE_APP"
        const val DELETE_UNUSED_MEDIA = "DELETE_UNUSED_MEDIA"
        const val GET_DOWNLOAD_STATUS = "GET_DOWNLOAD_STATUS"
        const val CLEAR_DOWNLOAD_QUEUE = "CLEAR_DOWNLOAD_QUEUE"
        const val SYNC_LOCAL_REPORTS = "SYNC_LOCAL_REPORTS"
        const val REFETCH_PLAYLIST = "REFETCH_PLAYLIST"
        const val APPLY_CONFIG = "APPLY_CONFIG"
        const val START_REALTIME_CAPTURE = "START_REALTIME_EVENT_CAPTURE"
        const val STOP_REALTIME_CAPTURE = "STOP_REALTIME_EVENT_CAPTURE"
    }

    /** LOG_TYPE_ARRAY — the two streams the CMS Logs tab renders. */
    object LogType {
        const val PLAYLIST_EVENT = "playlist_event"
        const val IN_APP = "in_app"
    }

    /**
     * The render-pipeline trace the CMS expects inside a `playlist_event`. These names come from
     * the live Wilyer portal's Logs tab, which is the spec: an operator diagnosing "why is this
     * screen blank" reads them top to bottom and expects the same vocabulary.
     */
    object LogStatus {
        const val DISPLAY_CONDITION_PASSED_TARGET = "DISPLAY_CONDITION_PASSED_TARGET"
        const val ASSIGNED_TO_RENDER = "ASSIGNED_TO_RENDER"
        const val PLAYING = "PLAYING"
        const val COMPLETED = "COMPLETED"
        const val SKIPPED = "SKIPPED"
        const val DOWNLOAD_STARTED = "DOWNLOAD_STARTED"
        const val DOWNLOAD_COMPLETED = "DOWNLOAD_COMPLETED"
        const val DOWNLOAD_FAILED = "DOWNLOAD_FAILED"
        const val RENDER_FAILED = "RENDER_FAILED"
    }

    /** In-app lifecycle actions, the `in_app` stream. */
    object LogAction {
        const val APP_STARTED = "APP_STARTED"
        const val APP_STOPPED = "APP_STOPPED"
        const val PAIRED = "PAIRED"
        const val UNPAIRED = "UNPAIRED"
        const val SYNC_STARTED = "SYNC_STARTED"
        const val SYNC_COMPLETED = "SYNC_COMPLETED"
        const val SYNC_FAILED = "SYNC_FAILED"
        const val HEARTBEAT_FAILED = "HEARTBEAT_FAILED"
        const val COMMAND_RECEIVED = "COMMAND_RECEIVED"
        const val COMMAND_COMPLETED = "COMMAND_COMPLETED"
        const val COMMAND_FAILED = "COMMAND_FAILED"
        const val NETWORK_LOST = "NETWORK_LOST"
        const val NETWORK_RESTORED = "NETWORK_RESTORED"
        const val SETTINGS_APPLIED = "SETTINGS_APPLIED"
        const val POWER_OFF_SCHEDULE = "POWER_OFF_SCHEDULE"
        const val POWER_ON_SCHEDULE = "POWER_ON_SCHEDULE"
        const val AUTO_RESTART = "AUTO_RESTART"

        /** Files removed because the screen is no longer assigned them. Worth a log line: an
         *  operator asking "where did that campaign go" deserves to see when it was let go of, and
         *  from which screen. The backend takes `action` as a free string by design, so a new event
         *  name needs no migration. */
        const val CACHE_CLEARED = "CACHE_CLEARED"
    }

    /** ITEM_TYPE_ARRAY. A manifest always arrives flattened to `media` — sequences are expanded
     *  server-side — but proof-of-play still carries the type. */
    object ItemType {
        const val MEDIA = "media"
        const val SEQUENCE = "sequence"
    }

    /** Which rung of the playback priority chain the manifest came from, echoed in `contentSource`. */
    object ContentSource {
        const val CLUSTER = "cluster"
        const val SCHEDULED = "scheduled"
        const val DIRECT_PLAYLIST = "direct_playlist"
        const val MEDIA_FILES = "media_files"
        const val DEFAULT_PLAYLIST = "default_playlist"
        const val NONE = "none"
    }

    /** ZONE_TYPE_ARRAY. */
    object ZoneType {
        const val MAIN = "main"
        const val SIDEBAR = "sidebar"
        const val TICKER = "ticker"
        const val HEADER = "header"
        const val FOOTER = "footer"
    }

    /** TRANSITION_ARRAY. */
    object Transition {
        const val NONE = "none"
        const val FADE = "fade"
        const val SLIDE = "slide"
        const val ZOOM = "zoom"
    }

    /** Media kinds the Library accepts — jpg/jpeg/png/mp4, enforced before the S3 stream. */
    object MediaType {
        const val IMAGE = "image"
        const val VIDEO = "video"
        const val WIDGET = "widget"
    }

    /** Status values `/player/downloaded-files` accepts per file. */
    object DownloadStatus {
        const val DOWNLOADED = "downloaded"
        const val DOWNLOADING = "downloading"
        const val QUEUED = "queued"
        const val FAILED = "failed"
    }

    /** ROTATION_ARRAY (physical panel) and APP_ROTATION_ARRAY (the app's own canvas). */
    object Rotation {
        const val NORMAL = "Normal"
        const val DEG_90 = "90"
        const val DEG_180 = "180"
        const val DEG_270 = "270"
    }

    /** DAY_ARRAY, in the backend's order — index 0 is Monday, matching java.time's DayOfWeek. */
    val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** CANVAS_PRESETS. */
    val CANVAS_HORIZONTAL = 1920 to 1080
    val CANVAS_VERTICAL = 1080 to 1920

    /** ZONE_DEFAULT_SLIDE_SECONDS — what a slide runs for when nothing else says. */
    const val DEFAULT_SLIDE_SECONDS = 7

    /** Batch ceilings the backend enforces (`array.max` in PlayerJoiSchema). Exceeding either is a
     *  400 that would strand a whole offline queue, so the uploaders chunk to these. */
    const val MAX_EVENTS_PER_BATCH = 1000
    const val MAX_FILES_PER_REPORT = 5000
}
