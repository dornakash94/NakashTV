# NakashTV development

Read README.md, docs/DEVELOPMENT.md, docs/spec.md, and docs/CHANGES-0.2-claude.md.

Preserve the current Android TV experience and user data. The player is LTR; Hebrew browsing is RTL. Source updates and APK release publication are separate actions. Never bundle credentials or signing keys. Stream URLs come from StreamUrls, not direct_source. Keep the fixed User-Agent and the scoped network security policy. Run assembleDebug and testDebugUnitTest for source changes. Do not run destructive instrumentation against a signed-in user emulator.
