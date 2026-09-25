# NakashTV development

Read README.md, docs/DEVELOPMENT.md, docs/spec.md, and docs/CHANGES-0.2-claude.md.

Preserve the current Android TV experience and user data. The player is LTR; Hebrew browsing is RTL. Source updates and APK release publication are separate actions. Never bundle provider credentials or signing keys. Exception (owner decision, 2026-09-25): the TMDB API key may be built in from `tmdb.apiKey` in local.properties so no one types it on a TV; never commit it. Stream URLs come from StreamUrls, not direct_source. Keep the fixed User-Agent and the scoped network security policy. Run assembleDebug and testDebugUnitTest for source changes. Do not run destructive instrumentation against a signed-in user emulator.
