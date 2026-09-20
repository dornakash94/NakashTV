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

Published the Netflix-grade UI overhaul as v0.2.0-beta.2 (versionCode 4), release-signed with the NakashTV distribution key (same certificate as prior releases, so it updates in place). APK attached to GitHub release v0.2.0-beta.2 as NakashTV.apk. Downloader code 4033247 still points at the old v0.1.0-beta.1 asset; create a new Downloader code for the v0.2.0-beta.2 asset URL (Downloader codes do not follow tags automatically).
