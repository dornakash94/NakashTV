# Development handoff — 2026-09-19

The repository now contains the Android application source, based on the user's Claude 0.2 ZIP. The previous APK release remains v0.1.0-beta.1.

Changes in the incoming source are listed in CHANGES-0.2-claude.md. During integration, restored the missing kidsPattern and docsPattern declarations in HomeViewModel and set versionCode 3 / versionName 0.2.0-beta.1.

## Setup

Clone the repository, open it in Android Studio, install SDK 35 and use the Android Studio JDK. Android Studio creates local.properties. Run ./gradlew assembleDebug testDebugUnitTest.

## Git workflow

Commit or stash local changes before git pull --ff-only. Review changes, build, and test before pushing. This repository has no automatic APK publishing workflow.

## Signing and installation

Release signing uses NAKASHTV_KEYSTORE and NAKASHTV_SIGNING_PASSWORD with alias nakashtv. The owner's existing release key is kept privately outside this repository. Reuse it for future release updates. Debug and release certificates differ; do not uninstall the user's app to bypass a signature mismatch. Keep account data and viewing history.

## Current beta limitations

Provider ratings are not IMDb ratings. Physical-device testing and extended catchup checks remain incomplete. No next-episode countdown enhancement is required for this beta. Refer to the source for current behavior; older specification sections may describe future work.

Downloader code 4033247 currently points specifically to the v0.1.0-beta.1 APK and does not automatically follow new tags.

## Integration validation

assembleDebug and all 39 unit tests passed on 2026-09-19 after the missing declarations were restored. Installed as an in-place debug update on the original Android TV emulator. Release/R8 validation for 0.2 has not yet been run.

## Release 0.2.0-beta.2 (2026-09-20)

Published the Netflix-grade UI overhaul as v0.2.0-beta.2 (versionCode 4), release-signed with the NakashTV distribution key (same certificate as prior releases, so it updates in place). Distribution URL is the fixed asset the Downloader code resolves to: the NakashTV.apk asset on the v0.1.0-beta.1 release. That asset was replaced (gh release upload --clobber) with the 0.2.0-beta.2 build, so Downloader code 4033247 keeps working and updates in place. Tag v0.2.0-beta.2 also holds the same APK as a versioned archive. To ship a future build without changing the code, replace that same v0.1.0-beta.1/NakashTV.apk asset again.

## TMDB extras (2026-09-25)

Detail pages use TMDB for the official YouTube trailer (muted in a framed player, "▶ טריילר" for full screen with sound), cast with photos, "similar" titles that exist in the provider library, and a backdrop/Hebrew overview when the provider lacks them. Movies use the provider's tmdb_id; series are found by title and year. Responses are cached on disk for 7 days (cacheDir/tmdb). Trailers play only in YouTube's official embedded player (IFrame API in a WebView); nothing is drawn over it.

Key: put `tmdb.apiKey=<API Key>` in local.properties on the build machine (git-ignored), or set NAKASHTV_TMDB_KEY. It is compiled into BuildConfig.TMDB_KEY, so every installed TV works with no setup. Settings → כללי → "טריילרים ומידע מ־TMDB" can override it. The provider auth interceptor is removed from the TMDB client, so provider credentials never reach TMDB.

## Release 0.3.0-beta.1 (2026-09-25)

Netflix-style browse (billboard with trailer on Movies/Series; rows with widening cards and in-card trailers on Home, Movies, Series; channel cards with in-card live preview on Home and Live), Netflix-style detail page with full-screen trailer and a "כותרים דומים" panel, continuous catch-up, program-ribbon player, TMDB extras, pre-built search index. Published by replacing the v0.1.0-beta.1/NakashTV.apk asset (Downloader code 4033247) and tagged v0.3.0-beta.1 as an archive.

## Release 0.3.0-beta.3 (2026-09-25)

Stability on low-memory TVs. The trailer WebView's renderer (~100-150 MB, plus a video decoder) could be reclaimed by the system under memory pressure, and with no `onRenderProcessGone` handler Android killed the whole app (reported as the app getting slow and then crashing). Both WebViews now handle a lost renderer. The shared trailer WebView is created only when a trailer is wanted, and it is released when real playback starts, when the app is stopped, and on `onTrimMemory(RUNNING_LOW)`. The Coil memory cache is capped at 15 % and cleared on the same events. The player's "טוענים שידור…" text is replaced by a spinner. The remote's channel rocker (CH+/CH−) zaps inside a channel, also with the menu open or during catch-up. Zapping to a channel with another aspect ratio no longer leaves the new picture in a corner over the old frame: the hosted video view is laid out again whenever the video size changes.
