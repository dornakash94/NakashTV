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

## Release 0.4.5 (2026-10-09)

The father confirmed 0.4.4 fixed the series freezes with refresh-rate matching OFF: the cause was copying the video surface for scrubbing thumbnails every 10 s during playback. Sync fix: since 0.4.2 the profile sync was held during playback, so a TV switched off mid-episode (the app is frozen at once) never sent its last progress and other TVs did not show it in "המשך צפייה". Now the sync runs once a minute while playing (every 30 s otherwise), and when the app leaves the screen it syncs at once and also enqueues a one-time WorkManager sync (network required) that runs even if the app is frozen first. Verified on the emulator (debug log): syncs at 60 s intervals during live playback and within 2 s of pressing Home.

## Release 0.4.6 (2026-10-11)

Motion in movies/series did not look fully smooth on the owner's device, while other apps on the same device were; refresh-rate matching switched modes but did not help. On the emulator the player is clean (125 frames per 5 s at 25 fps, 0 dropped or skipped, 0 UI frames drawn during playback). Added Settings › נגן › "ניגון חלק (מנהרה)" (off by default): tunneled playback for movies/episodes through the DefaultTrackSelector, so the device's video hardware paces frames as the big streaming apps do; live stays untunneled; devices without a tunneling decoder fall back to normal playback (verified on the emulator). No frame is copied off the surface while tunneled; scrub frames come from the file. Tunneling itself is untested (no tunneling decoder on the emulator).

## Release 0.4.7 (2026-10-12)

Settings › כללי › "הפעלה עם הדלקת המכשיר" (on by default): a BOOT_COMPLETED receiver opens the app when the device starts. Android 10+ allows this only with "display over other apps"; without it the row says so, opens that permission screen, and offers turning the feature off. Verified on the emulator (Android 14): opens after a reboot with the permission, not without it. A TV coming back from standby does not send BOOT_COMPLETED, so there the app opens only after a full restart. The emulator's system image was moved out of the iCloud-synced folder to ~/Android/tv-image (iCloud had evicted it).

## Release 0.4.8 (2026-10-12)

In-app updates: the sync Worker serves `GET /v1/app?channel=stable|debug` from the `app_release` D1 table; the app checks on start and every 6 hours, offers the release (עדכון עכשיו / אחר כך with a 24 h snooze, or mandatory), asks once for "install unknown apps", downloads the APK and hands it to PackageInstaller; the prompt waits while the player is on screen; debug builds read the debug channel. **On every release, after uploading the APK, set the stable row** (versionCode, versionName, url, notes, mandatory) with `wrangler d1 execute nakashtv-sync --remote --command "INSERT OR REPLACE INTO app_release ..."`. Devices on 0.4.7 or older update to 0.4.8 through Downloader once. Profile photos stored inside the profile as a data URI (the parents' profile) are decoded and shown; 0.4.7 shows a plain square for them.

## Release 0.5.2 (2026-10-31)

"הישרדות" (series 176) showed "לא הצלחנו לטעון את התוכן" on every TV: one episode in season 6 arrives from the provider with `"info": ""`, and that single field failed the whole get_series_info parse. Nested objects that arrive as "" or [] (episode info, series info, an empty episodes list) now read as empty. A failed title-page load logs its cause under `NakashDetail`. A video decoder that fails mid-playback is skipped and the next decoder on the device is tried (the emulator's H.264 decoder once rejected this file's headers; the retry path itself was not exercised). Verified on the emulator: the series opens with 3 seasons and S6E1 plays at 720p with no dropped frames. Known: the TMDB match for this title by Hebrew name and year can pick a Korean show with the same Hebrew title.

## Release 0.5.3 (2026-11-06)

Error reports from the family's TVs: a playback error (with what was playing, its video/audio format and the decoder) or a crash is written to a file at once and sent to the sync server as an item of kind `diag` (profile `device`, key per install). URLs and the provider login are removed; the account shows only as a 12-character hash prefix. At most one report per failure and title per 10 minutes and 40 a day. Read them with `wrangler d1 execute nakashtv-sync --remote --command "SELECT data FROM items WHERE kind='diag' ORDER BY updated_at DESC LIMIT 20"`. The error screen now shows the error code, and Settings shows the last crash on the TV (CrashLog).

The father's "לא הצלחנו להתחיל את הצפייה" on המחתרת – פרקים לפי שידור טורקיה S02E02: that episode is HEVC 1080p (hvc1.1.6.L120), which plays on the emulator. Decoder errors other than a mid-file decoding failure (init, query, format unsupported / exceeds capabilities) fell to the generic message without trying another decoder; every decoder error now tries the device's next decoder, and a stall with seconds of video buffered but no frames counts as a stuck decoder (next decoder) instead of "the provider is not responding". When nothing can play it, the message names the format. The real cause on his box is unconfirmed until his report arrives.

Stability on weak boxes: no start-up crash on a broken Keystore; the first sync no longer reads whole catalog responses into memory and two full syncs no longer run together. Rows move like Netflix: an instant step inside a row and one 300 ms slide between rows (Home, Live, Movies, Series).

Home recommendations change every visit: a pool of 23 row kinds with about 10 drawn per visit (seeded, so a visit is stable while it lasts), reasons beyond "כי צפית ב…" (more from the director, the time of year, short series for the weekend, and others), and less repetition of the same titles across rows and visits.

## Release 0.5.4 (2026-11-06)

From the owner's look at 0.5.3 on his box. A card widening on Home jumped (a stretched sharp copy of the poster on boxes without blur, then the wide picture popping in): the wide pictures of the cards next to the focused one are now decoded in memory at the card's exact size and memory key, so a step shows its picture in the same frame; one that has to load fades in over the poster, and when a sharper picture replaces it the old one stays until the new one is up. Boxes before Android 12 (no blur) show the plain tile instead of the stretched poster. Trailers start sooner: YouTube's player page is loaded paused while the viewer is still on the card (that page, not the video, was most of the wait), trailer lookups for the nearest cards start together, the dwell before a trailer is 1 s, and the page is kept 30 s after leaving a screen instead of 8 s. Not measured on a box.

The Movies and Series tabs draw their personal rows from a pool of kinds like Home, each tab with its own kind of title only (new episodes first on Series; "סדרות שעזבת באמצע", "סרטים שהתחלת ולא סיימת", short series, collections, because-you-watched and saved), with the tab's own memory of what it showed.

## Release 0.5.5 (2026-11-06)

The first error report from the father's box (0.5.4, SDMC Dcolor GD2) explained his S02E02: it asked for episode id 370097, which the provider now lists as 370180; the old id answers with something that is not a video (PARSING_CONTAINER_UNSUPPORTED). A series refresh only upserted episodes, so a replaced episode kept its old id on the TV, listed first. A refresh now replaces the series' seasons and episodes in one transaction (an empty answer never wipes them), and an episode failing as "not a video" or "not found" reloads its series once and plays the episode now listed at the same season and number. Not reproduced on the emulator (no stale id there).

## Release 0.5.6 (2026-11-07)

A cousin's video (Xiaomi Mi Box 3 / ZTE zBOX 2): a new tab showed grey cards for 3-4 s, and scrolling fast through rows that still had to load stuttered; once loaded it was smooth. Almost every poster comes from the provider's one host (4,884 of ~5,000 series covers; 600x900, ~0.35 s each), and OkHttp's default of 5 requests per host fetched a screen of ~30 posters in six waves; meanwhile Coil decoded on the IO dispatcher, many JPEGs at once, taking the CPU from the UI thread. Coil now has its own OkHttp dispatcher (12 per host, same connection pool and User-Agent) and decodes on two threads. Measured on the emulator (debug build, empty image cache, fast scroll through the Series tab, two runs each): janky frames 43-50 % → 10-13 %, 90th percentile frame 150 ms → 40-48 ms. Not measured on a box.

## Release 0.5.7 (2026-11-13)

Asked by the father. The in-channel catch-up list ("תוכניות קודמות") moves a day at a time: day tabs under the channel name ("היום", "אתמול", "יום ג׳ 10.11"…, today on the right), ◀ for an earlier day and ▶ back, ▲ ▼ for a show; it now covers the channel's whole archive (up to 14 days; the guide keeps 33) instead of 3 days. A day with no guide data has no tab (the emulator, off for two days, had none for them). Movies and episodes have a playback speed button in the player (×1, ×1.25, ×1.5, ×2, ×0.75): a new title starts at ×1, the next episode of the same series keeps it; not available with tunneled playback (a notice says so). Verified on the emulator: Kan 11's days, and ×1.5 rendering 188 frames per 5 s instead of 125.

Episode start measured on the emulator (Play press → first frame, a 3.2 GB HEVC episode resumed at 2 h): 3.2 s: provider redirect 0.3 s, reading the MP4 index 1.5 s, a second redirect and the seek to the resume point 0.7 s, first frame 1 s. Not changed yet; preloading the episode's source on the title page (Media3 PreloadMediaSource, no decoder) would take most of it.
