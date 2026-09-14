package com.example.digi.data.repo

/*
 * REMOVED — delete this file (`git rm`).
 *
 * This used to push a downscaled JPEG of the panel to /player/live-frame every few seconds so the
 * CMS could show a "live" preview. It was the wrong mechanism for the job. A still every five
 * seconds is a slideshow, not video, so it never answered the question operators actually asked;
 * and producing one cost a full-surface readback plus two large bitmap allocations, which on a
 * 2340x1080 box was enough to make the video on the wall visibly stutter the moment somebody opened
 * the panel. The feature degraded the thing it existed to observe.
 *
 * The CMS now plays the screen's own playlist in the operator's browser, seeded from the same
 * manifest the player syncs and offset to the position the panel is at. It is smooth because it is
 * ordinary CDN playback, and it costs the device nothing at all.
 *
 * What it does NOT do is prove anything about the panel — that it is powered on, on the right
 * input, or that the app has not crashed. The SCREENSHOT command still does that, on demand, and is
 * cheap precisely because it is occasional. See PlayerHost.captureScreenshot.
 *
 * This file is left as an empty valid source file rather than deleted from here because the tooling
 * writing it cannot remove files from the repository. It declares nothing and compiles to nothing,
 * so a build works whether or not it has been deleted yet.
 */
