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

## Release 0.3.0-beta.4 (2026-09-26)

Performance pass for low-RAM TV streamers. Tabs keep their state between visits. Home reads only the categories its rows need, and row cards are rebuilt only when their data changes. Card and billboard positions no longer recompose the page on every animation frame, and the scroll tint draws without recomposing. The trailer WebView pauses when its screen leaves and is released after 8 s. The search index uses slim queries and rebuilds only on real changes. The title page shows and focuses Play immediately, loads TMDB in parallel, matches similar titles off the main thread, and keeps its menu fully on screen whatever the text length. Frame extraction runs only while scrubbing, seeks land on keyframes, decoder fallback is on, and video uses its own OkHttp dispatcher. The title page pre-opens the stream connections. Queries no longer exceed SQLite's 999-variable limit on Android 11 and older, which had made channel deactivation and EPG filler/purge fail on most streamers. Down from the nav bar returns to the page. Reusing the provider's redirect target was tried and dropped: its playback URL is tied to the request.

## Release 0.3.0-beta.5 (2026-09-26)

Israel-time clock at the top right of every screen. A fresh decoder for every new stream: zapping across a resolution change (720p after 1080p) had drawn the picture unscaled in a corner over the old frame. ilvip12.net added to the scoped cleartext list; Discovery catch-up is still refused by the provider (HTML answer).

## Release 0.4.0 (2026-10-08)

Profiles under the provider login, synced across devices through the Cloudflare Worker in `server/` (D1; the account is a hash of provider URL, user and password, so the server never sees the login). "מי צופה?" on every launch, Netflix-style: profiles in a column over a backdrop of titles picked for the focused profile, rotating; full-screen profile editor and a gallery of 32 drawn avatars; profile dropdown in the nav bar. Each profile has its own continue-watching, list and recent searches. Personal rows from the profile's history on Home and in Movies/Series ("כי צפית ב…", "מותאם בשבילך", "חדש בשבילך", favourite genres), TMDB trending matched to the library, and curated themed rows rotating daily; the engine reads only light columns. Crash fix (Compose focusRestorer in lazy rows), Back returns to the section's tab and to the same card. Title page: Up from Play opens the whole plot when it is cut. Episodes panel redesign. Live player: with the ribbon open, Down reaches its buttons. New NT monogram logo (launcher icon, TV banner, login and profile screens). Published by replacing the v0.1.0-beta.1/NakashTV.apk asset (Downloader code 4033247) and tagged v0.4.0 as an archive.

## Release 0.4.1 (2026-10-08)

No profile is created automatically any more: a new install had made a default "ראשי" dated now, which won the sync over a deletion made on another TV and brought it back. A device without profiles now waits for the account's profiles from the server; an account with none is asked for its first profile. History kept from before profiles goes to the first profile chosen or created on the device.

## Release 0.4.2 (2026-10-09)

Playback: the profile sync is held while a video plays (its 30-second work showed as a small stutter on weak boxes) and catches up when playback stops or pauses. Smoother row navigation: TMDB extras no longer live in Compose state (results landing while a row slides had recomposed the whole page), in-card trailers after a 1.2 s dwell, fewer look-ups and prefetches ahead, card-size TMDB images, only the focused card reports its position; measured on the emulator (release build) dropped frames went from 19 % to 8 % and the worst 1 % of frames from 133 ms to 46 ms. Title page in Netflix TV style: text from the top, a scrolling menu that fades at its edges, "הסרה מהמשך צפייה" in the menu while there is something to continue. "פרקים ועוד": title and seasons (and "טריילרים ועוד") on the right, big episode pictures with name, plot and length on the left. Legacy pre-profile history is moved (not copied) into the first profile.

## Release 0.4.3 (2026-10-09)

Continuous video buffering: with a 15 s / 30 s buffer the player paused at 30 s and then pulled 15 s of video in one burst (measured on the emulator: 3-7 s of nothing, then 2-3 MB in a second, every ~23 s), which showed as a small freeze every ~20-30 s in movies and series on weak boxes (live channels were never affected). min = max = 30 s tops the buffer up continuously (measured: 200-900 KB every second, no gaps or bursts). Netflix-style rating on the title page ("לא בשבילי" / "אהבתי" / "ממש אהבתי!"), stored per profile (profile DB v2 adds a `ratings` table by migration; history kept, verified on the emulator), synced as items of kind `rating`, and fed into the recommendations: loved titles lead "כי צפית ב…" and weigh most in genre taste, liked ones add, disliked ones are never recommended and pull their genres down.

## Release 0.4.4 (2026-10-09)

Investigating a small freeze about every 30 s in series (not movies, not live) on one weak box only; another player app is smooth on that box. Ruled out on the emulator: the player path is identical for episodes and movies, and the three series checked share codec and audio with the movies (H.264, AAC stereo); frame rates were 25 ("ורדים וחטאים") and 23.976 ("מובלנד", "פרפר"). Two changes so the box itself can tell which cause it is: scrubbing thumbnails are no longer copied off the video surface during playback (only when a scrub starts or after 2 s paused; 0 captures in 30 s of playback on the emulator), and an optional Settings › נגן › "התאמת קצב רענון" (off by default) that switches the TV to a display mode showing the video's frame rate evenly during movies/episodes (same resolution; 0.04 % tolerance, so 60 Hz is not taken for 29.97) and back when the player closes. Debug builds only: `--es route <screen>` on launch and frame-rate / audio-format playback logs.
