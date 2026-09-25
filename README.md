# NakashTV

Source repository: https://github.com/dornakash94/NakashTV

```sh
git clone https://github.com/dornakash94/NakashTV.git
cd NakashTV
```

For existing checkouts, commit or stash your changes, then run `git pull --ff-only`.

Development source: **0.3.0-beta.2**. Published APK: [0.3.0-beta.2](https://github.com/dornakash94/NakashTV/releases/tag/v0.3.0-beta.2). See [development notes](docs/DEVELOPMENT.md) for the current workflow.


Native Android TV client in Kotlin, Compose for TV, Media3, Room and Hilt. Hebrew RTL interface with a bundled Heebo font and dark/gold theme.

## Open and build

Open this folder in Android Studio. Use JDK 17 or 21, Android SDK 35 and the included Gradle wrapper:

```sh
./gradlew assembleDebug testDebugUnitTest
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
`local.properties` is machine-specific; Android Studio can generate it for another computer.

## Included references

- `docs/spec.md`: full Hebrew product specification.
- `docs/prototype.html`: original Claude prototype, preserved as a reference.
- `CLAUDE.md`: original ordered implementation plan.
- `docs/PROGRESS.md`: implementation and verification status.

## Current implementation

- Original login, local catalog, home, preview and player infrastructure.
- Live TV categories, folded world categories, channel grid and numeric selection.
- Favorite and hide/restore actions, source selection, and category-scoped zapping.
- Netflix-style top navigation bar over the hero; bundled typography and responsive home hero.
- Credentials are restricted to the configured API/XMLTV origin and paths.

Movies and series now include searchable libraries, genre filtering, details, resume/start-over, favorites, seasons and episode selection. The guide includes channel/day selection, program details and archive eligibility. Search, My List and Settings are connected to the local catalog.

The player includes interactive mini-guide navigation, audio/subtitle selection, retry feedback, next-episode access and a four-hour inactivity prompt. See `docs/TV-TESTING.md` for the isolated emulator test runner.

This remains a development build until provider playback and physical TV testing are complete. Live playback requires the user's server account; no subscriber credentials are bundled. Advanced features from the specification, including automatic thumbnail generation, parental controls and phone/QR sign-in, are not complete.

Font license: `docs/licenses/Heebo-OFL.txt` (also bundled in APK assets).
