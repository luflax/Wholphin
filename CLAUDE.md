# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Wholphin is an open-source Android TV client for Jellyfin (Kotlin, Jetpack Compose for TV, single Activity + MVVM). It talks to the server via the official [Jellyfin Kotlin SDK](https://github.com/jellyfin/jellyfin-sdk-kotlin) and optionally to a Seerr instance for discovery.

`DEVELOPMENT.md` is the canonical developer guide — read it for the settings/preferences details it covers in depth. This file covers the build system and cross-cutting architecture.

## Commands

Gradle wrapper (`./gradlew` under the Bash tool, `.\gradlew.bat` under PowerShell). Build variants are `<flavor><BuildType>`, e.g. `DefaultDebug`.

```bash
./gradlew assembleDefaultDebug            # build the debug APK (what CI builds on PRs)
./gradlew testDefaultDebugUnitTest        # unit tests (JVM + Robolectric)
./gradlew connectedDefaultDebugAndroidTest # instrumented tests (needs a device/emulator)
./gradlew lintDefaultDebug                # Android lint

# Single test class / method
./gradlew testDefaultDebugUnitTest --tests "com.github.damontecres.wholphin.test.NextUpTest"
./gradlew testDefaultDebugUnitTest --tests "*.PlaybackViewModelTests.someTest"
```

Formatting/checks are enforced by `pre-commit` (ktlint 1.8.0 via `pretty-format-kotlin`), which CI runs before the build:

```bash
pre-commit run --all-files
```

Two local pre-commit hooks fail the commit if `val DEBUG = true` or `debugLogging = true` is left in any `.kt` file — flip those back before committing.

### Build gotchas

- **Git tags are required.** `versionCode` is the count of `v*`/`p*` tags and `versionName` comes from `git describe --tags --match=v*`. A shallow clone or a tagless fork produces a broken/`0.0.0` version — CI checks out with `fetch-depth: 0`.
- **Native extensions are optional.** ffmpeg/AV1 Media3 decoders and `libmpv` are resolved in this order: local `app/libs/*.aar` → the `wholphin-extensions` GitHub Maven repo (enabled only when the `WholphinExtensionsUsername` Gradle property is set) → for MPV only, the `:wholphin-mpv-stub` module. With the stub, the app compiles but any MPV playback throws at runtime.
- **Generated sources.** `preBuild` depends on `openApiGenerate`, which generates the Seerr API client from `app/src/main/seerr/seerr-api.yml` (+ custom templates in `app/src/main/seerr/templates`) into `build/generated/seerr_api`. Protobuf (`app/src/main/proto/WholphinDataStore.proto`), Room, and Hilt also generate code at build time — after editing any of them, build before expecting the new classes to resolve.
- **Product flavors** (`version` dimension) differ only in feature flags: `default` (updates + discover, no leanback launcher), `appstore` (leanback, no in-app updates), `firetv` (leanback, no updates, no discover). Flags are `BuildConfig.UPDATING_ENABLED` / `BuildConfig.DISCOVER_ENABLED` and the `leanback` manifest placeholder. Debug builds use the `.debug` application ID suffix, which matters for `adb shell am start` (see `Intents.md`).

## Architecture

### Navigation

`MainActivity` is the only Activity. Every screen is a `@Serializable` subclass of `Destination` (`ui/nav/Destination.kt`), which implements Navigation 3's `NavKey`. `DestinationContent.kt` is the single big `when` that maps a `Destination` to its composable. `Destination.fullScreen` decides whether the nav drawer wraps the page.

Navigation goes through the injected `NavigationManager` singleton, which owns the `NavBackStack` (`navigateTo`, `navigateToFromDrawer`, `goBack`, `goToHome`, `reloadHome`, `replace`) and reports the current destination to ACRA. Don't manipulate the back stack directly from composables. Setup/onboarding has its own `SetupNavigationManager`/`SetupDestination`.

### DI and coroutines

Hilt with `SingletonComponent`; modules live in `services/hilt/`. Qualifiers you'll need: `@AuthOkHttpClient` (token attached) vs `@StandardOkHttpClient`, and `@IoDispatcher`/`@DefaultDispatcher`/`@IoCoroutineScope`/`@DefaultCoroutineScope`.

Use `WholphinDispatchers.IO/Default/Main` rather than `kotlinx.coroutines.Dispatchers` — tests swap them. `CoroutineScope.launchIO` / `launchDefault` (in `ui/Extensions.kt`) and `ExceptionHandler` (optionally auto-toasting) are the standard way to launch work from a ViewModel.

### State

ViewModels are `@HiltViewModel` and expose `StateFlow`s of the sealed loading states in `util/LoadingState.kt`: `LoadingState`, `DataLoadingState<T>` (with `successValue`), `RowLoadingState`, `HomeRowLoadingState`. Prefer these over ad-hoc nullable/boolean state.

Strings that must be resolved outside a composable (row titles, error text on state objects) use `StringProvider` (`ui/util/StringProvider.kt`) — a serializable sealed interface with a `@Composable getString()`.

### Data

- `ServerRepository` owns the current Jellyfin server/user (`current: StateFlow<CurrentUser?>`) plus add/remove/switch; the Jellyfin SDK `ApiClient` is injected and re-pointed as the user switches.
- `AppDatabase` (Room) holds servers, users, and per-user settings. Schemas are exported to `app/schemas` and `TestDbMigrations` (androidTest) validates them. Bumping `version` requires adding the matching `AutoMigration(n, n+1)` entry (or a manual `Migration`) and committing the new schema JSON. A few models (`GetItemsFilter`, `ViewOptions`) are stored as JSON columns — use sparingly.
- Three settings mechanisms exist (proto DataStore `AppPreferences`, Room tables, key-value DataStore via `KeyValueService`). `DEVELOPMENT.md` documents when to use which and the 5-step recipe for adding a new `AppPreference`. Experimental settings live under `AppPreferences.experimentalPreferences` and every access must be gated on `ExperimentalPreferences.enabled`.
- Preference *screens* are declared as `AppPreference` objects grouped into `PreferenceGroup`s (`preferences/AppPreference.kt`) and rendered generically by `ui/preferences/` — adding a setting is data, not new UI.

### Playback

`PlayerFactory` builds either a Media3 `ExoPlayer` (with optional ffmpeg/AV1 renderers and libass SSA/ASS support) or an `MpvPlayer`, chosen by the `PlayerBackend` preference. `ui/playback/` holds `PlaybackViewModel`, the D-pad key handling (`PlaybackKeyHandler`, `SeekAcceleration`), track/subtitle selection, and the overlay UI. Extension availability is surfaced through `MediaExtensionStatus`.

### Logging & crash reporting

Timber for logging (`Timber.i("...%s", arg)`), ACRA for crash reports. slf4j is routed to Timber.

## Conventions

- Only edit `app/src/main/res/values/strings.xml`. All other `values-*` locales are managed by Weblate (translate.codeberg.org) — `MissingTranslation` lint is disabled for this reason.
- ktlint rules apply, with Compose-specific rules recommended; `@Composable` functions are exempt from the function-naming rule (`.editorconfig`).
- Per `CONTRIBUTING.md`, the project follows Jellyfin's LLM policy: AI-assisted contributions are allowed but must be disclosed, and PR descriptions must be written without AI assistance.
