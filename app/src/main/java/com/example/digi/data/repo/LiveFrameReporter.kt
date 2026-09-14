package com.example.digi.data.repo

/*
 * REMOVED — delete this file (`git rm app/src/main/java/com/example/digi/data/repo/LiveFrameReporter.kt`).
 *
 * This pushed a downscaled JPEG of the panel to /player/live-frame every few seconds so the CMS
 * could show a "live" preview. A still every five seconds is a slideshow, not video, so it never
 * answered the question operators actually asked — and producing one cost a full-surface readback,
 * so opening the preview degraded the playback it existed to observe.
 *
 * Replaced by position reporting: the player emits which slide it is on and how far into it, over
 * the socket it already holds (PlayerService.streamPlaybackState), and the CMS plays the same files
 * from the CDN seeked to that point. Real video at full frame rate, a few hundred bytes per report
 * instead of 40KB of JPEG, and still honest — when the reports stop, the preview says the screen is
 * not reporting rather than playing content a dead panel is not showing.
 *
 * The on-demand SCREENSHOT command remains the way to ask what is genuinely on the glass, and is
 * cheap precisely because it is occasional. See PlayerHost.captureScreenshot.
 *
 * Left as a comment-only file rather than deleted because the tooling writing it cannot remove files
 * from the repository. It declares nothing, so the build is identical whether or not it is gone —
 * but nothing references it any more, which is the part that matters.
 */
