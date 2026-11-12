# Seerr integration — implementation status

A map of how the [Seerr](https://github.com/seerr-team/seerr) integration is currently built in Wholphin, and where the
unfinished edges are. Written as a baseline for continuing the integration.

Related docs: [DEVELOPMENT.md](DEVELOPMENT.md) for general architecture, [CLAUDE.md](CLAUDE.md) for build commands.

## Feature gating

Seerr sits behind two independent gates:

1. **`BuildConfig.DISCOVER_ENABLED`** — a product-flavor flag set in [`app/build.gradle.kts`](app/build.gradle.kts).
   True for `default` and `appstore`, **false for `firetv`**.
2. **A configured, connected server** — `SeerrApi.active` requires the flag *and* a non-blank base URL
   (`services/SeerrApi.kt`). `SeerrServerRepository.active` additionally requires a successful connection.

Every UI entry point observes `SeerrServerRepository.active` (or `SeerrService.active`, which delegates to it), so the
whole Discover surface disappears cleanly when Seerr is unconfigured or the flavor disables it.

## Layering

Four layers, cleanly separated:

| Layer | Location | Responsibility |
| --- | --- | --- |
| Generated client | `api/seerr/` (generated into `build/generated/seerr_api`) | Raw endpoint bindings |
| `SeerrApiClient` | `api/seerr/SeerrApiClient.kt` | Hand-written wrapper: auth headers, cookie jar, sub-API composition |
| `SeerrApi` | `services/SeerrApi.kt` | Mutable singleton holder for the current client |
| `SeerrServerRepository` | `services/SeerrServerRepository.kt` | Connection state, persistence, login, URL probing |
| `SeerrService` | `services/SeerrService.kt` | Domain-level API used by ViewModels |

### Generated client

The OpenAPI spec lives at `app/src/main/seerr/seerr-api.yml` (OpenAPI 3.0.2, **155 paths**) with custom Kotlin templates
in `app/src/main/seerr/templates/jvm-common`. `openApiGenerate` runs as a `preBuild` dependency, so editing the spec
requires a build before the new classes resolve.

`SeerrApiClient` composes **18 sub-APIs** lazily. It injects the `X-Api-Key` header when an API key is configured, and
holds a private `SeerrCookieJar` that persists only the `connect.sid` cookie, keyed by domain. `hasValidCredentials`
checks either an API key or a non-expired cookie.

### `SeerrApi`

A thin mutable holder. `update(baseUrl, apiKey)` replaces the entire `SeerrApiClient` instance — which also discards the
cookie jar, so a re-login is required after any update. Provided as a singleton from `services/hilt/AppModule.kt` using
the `@StandardOkHttpClient` (unauthenticated) OkHttp instance.

### `SeerrServerRepository`

Owns the connection state machine:

```
SeerrConnectionStatus = NotConfigured | Error(server, user, ex) | Success(CurrentSeerr)
```

`CurrentSeerr` bundles the `SeerrServer`, `SeerrUser`, the `authMe` response (`SeerrUserConfig` = generated `User`), and
the server's `PublicSettings`. It derives `request4kMovieEnabled` / `request4kTvEnabled` from the server config **and**
the user's permission bits — both must allow it.

Also handles URL probing on setup (see [Server setup](#server-setup)) and `testConnection` with short timeouts
(2s connect / 6s read).

### `SeerrService`

The layer ViewModels actually depend on:

- **Discover queries** — `discoverMovies`, `discoverTv`, `trending`, `upcomingMovies`, `upcomingTv`
- **`similar(item: BaseItem)`** — maps a Jellyfin item to TMDB via `providerIds["Tmdb"]`, then fetches similar
  movies/series or a person's combined credits. Short-circuits to `null` when Seerr is inactive.
- **`getTvSeries(item)`** — TMDB-keyed lookup of full `TvDetails` for a Jellyfin series
- **`createImageUrl`** — prefers a Jellyfin image URL when the item exists locally; otherwise routes through the
  server's `/imageproxy/tmdb` when `cacheImages` is on, else direct to `image.tmdb.org`
- **`getProfilesAndFolders(type)`** — queries Radarr/Sonarr services for quality profiles and root folders, resolving
  both the default and 4K instances. Gated on `REQUEST_ADVANCED`.
- **`createDiscoverItem(...)`** — an overload family normalizing 7 generated model types (`MovieResult`, `MovieDetails`,
  `TvResult`, `TvDetails`, `SearchGet200ResponseResultsInner`, `CreditCast`, `CreditCrew`) into the single `DiscoverItem`
  domain model

## Domain models

**`DiscoverItem`** (`data/model/DiscoverItem.kt`) is the app-side normalization of anything Seerr returns. It implements
`CardGridItem` so it drops into the shared grid components. Its `destination` property is the key routing decision:

```kotlin
val destination: Destination
    get() = if (jellyfinItemId != null && type.baseItemKind != null) {
        Destination.MediaItem(...)   // owned — go to the native Jellyfin page
    } else {
        Destination.DiscoveredItem(this)  // not owned — go to the discover page
    }
```

**`SeerrItemType`** — `MOVIE` / `TV` / `PERSON` / `UNKNOWN`, each mapped to a Jellyfin `BaseItemKind`.

**`SeerrAvailability`** — mirrors Seerr's media status constants: `UNKNOWN(1)`, `PENDING(2)`, `PROCESSING(3)`,
`PARTIALLY_AVAILABLE(4)`, `AVAILABLE(5)`, `BLOCKLISTED(6)`, `DELETED(7)`.

**`SeerrPermission`** (`data/model/SeerrPermission.kt`) — the full permission bitmask from Seerr's `permissions.ts`,
with parent relationships (e.g. `REQUEST_4K_MOVIE` has parent `REQUEST_4K`). The `hasPermission` extension grants a
permission if the flag matches, any parent matches, or the user is `ADMIN`. Unit-tested in `TestSeerrPermission.kt`.

## Persistence & authentication

Two Room tables (`data/model/SeerrServer.kt`):

- **`seerr_servers`** — `id`, `url` (unique index), `name`, `version`
- **`seerr_users`** — composite primary key `(jellyfinUserRowId, serverId)`, cascade-deleted from either parent

The composite key means **Seerr configuration is per Jellyfin user**. Three auth methods: `LOCAL`, `JELLYFIN`, `API_KEY`.

`UserSwitchListener` re-establishes the Seerr connection on every Jellyfin user switch: it looks up the `SeerrUser` for
the new Jellyfin user, updates `SeerrApi`, and either replays the login (cookie methods) or just calls `authMeGet`
(API key). Failures land in `SeerrConnectionStatus.Error` rather than throwing.

> **Note on credential storage.** Because cookie auth must be replayed on each user switch, `SeerrUser` stores the raw
> `password`. This differs from `JellyfinUser`, which only keeps an `accessToken`. Options if this becomes a concern:
> persist the `connect.sid` cookie instead, or steer users toward API-key auth.

### Server setup

`SwitchSeerrViewModel.createUrls` probes URL permutations — scheme (`http`/`https`) × port (default / `5055`) — and
takes the first that authenticates. `createSeerrApiUrl` appends `/api/v1` if absent. `migrateSeerrUrl` strips it back off
and is used by `AppUpgradeHandler` to migrate URLs stored by older versions.

Covered by `app/src/test/java/com/github/damontecres/wholphin/test/TestSeerr.kt`.

Setup is reached **only from settings** — `AppPreference.SeerrIntegration`, a clickable preference that opens
add/remove/error dialogs in `ui/preferences/PreferencesContent.kt`. There is no Seerr step in first-run onboarding.

## UI surfaces

### Dedicated Discover section

`Destination.Discover` → `ui/discover/DiscoverPage.kt`, a five-tab page. The nav drawer entry is added by
`NavDrawerService` only when `seerrServerRepository.active` emits true.

| Tab | Composable | Notes |
| --- | --- | --- |
| Discover | `SeerrDiscoverPage` | 5 parallel rows + movie/TV genre sliders |
| Requests | `SeerrRequestsPage` | Current user's requests |
| Search | `DiscoverSearchPage` | Seerr-only search |
| Movies | `DiscoverRequestGrid` | Paged `DISCOVER_MOVIES` grid |
| TV | `DiscoverRequestGrid` | Paged `DISCOVER_TV` grid |

**`SeerrDiscoverPage`** fires five independent row fetches (discover movies, discover TV, trending, upcoming movies,
upcoming TV) plus two genre-slider queries, each updating its own slice of `DiscoverState` so rows render as they
arrive. Ratings are fetched lazily on focus and memoized in a 100-entry cache.

**`SeerrRequestsPage`** calls `requestGet()` then fans out — bounded by a `Semaphore(3)` — to fetch full details for
each request so it can render a card.

**`DiscoverRequestGrid`** pages through results using `DiscoverRequestPager` (a `RequestPager<DiscoverItem>` subclass).
Page size is 20 and pages are converted to Seerr's 1-indexed scheme in `fetchPage`. Request shapes are described by
`DiscoverRequestHandler` implementations in `util/DiscoverRequestPager.kt`.

### Discover detail pages

`Destination.DiscoveredItem` dispatches on `SeerrItemType` in `DestinationContent.kt`:

- **`DiscoverMovieDetails`** / `DiscoverMovieViewModel`
- **`DiscoverSeriesDetails`** / `DiscoverSeriesViewModel`
- **`DiscoverPersonPage`**

Both media ViewModels load details, ratings, similar, recommendations, credits and trailers concurrently, and expose
`canCancelRequest` computed by `canUserCancelRequest` (either `MANAGE_REQUESTS`, or `REQUEST` plus a request the user
made themselves).

### Request flow

1. `requestOnClick()` → `SeerrService.getProfilesAndFolders(type)` → populates `SeerrRequestData`
   (server ids, quality profiles, root folders — separate sets for standard and 4K)
2. UI pickers in `RequestMovie.kt` / `RequestSeasons.kt`; TV additionally offers per-season selection
3. Submit → `requestApi.requestPost(...)`, or for TV with an existing own request, `requestApi.requestRequestIdPut(...)`
   so seasons are merged into the existing request rather than duplicated
4. Re-fetch details and recompute cancel state

Profile/root-folder selection is only offered when the user has `REQUEST_ADVANCED`; otherwise `serverId`, `profileId`
and `rootFolder` are sent as null and Seerr applies its defaults.

### Woven into Jellyfin screens

- **`MovieViewModel`, `SeriesViewModel`, `PersonPage`** — call `seerrService.similar()` to supplement Jellyfin's own
  similar-items row
- **`SearchPage`** — runs a Seerr search in parallel with the Jellyfin search (`SearchResult.SuccessSeerr`)
- **`FilterOptionCache`** — sources genres and watch providers from Seerr for filter UIs
- **`SeriesViewModel`** — also pulls `TvDetails` for extra series metadata

## API coverage

Call-site counts across `app/src/main/java`:

| Sub-API | Call sites | |
| --- | --- | --- |
| `searchApi` | 15 | discover, trending, upcoming, genre sliders, keywords |
| `tvApi` | 9 | details, similar, ratings |
| `moviesApi` | 8 | details, similar, recommendations, ratings |
| `requestApi` | 6 | list, create, update, delete |
| `serviceApi` | 6 | Radarr/Sonarr profiles & root folders |
| `usersApi` | 5 | `authMe` |
| `authApi` | 2 | local & Jellyfin login |
| `personApi` | 2 | combined credits |
| `otherApi` | 2 | filter options |
| `settingsApi` | 1 | `settingsPublicGet` |

**Generated but entirely unused:** `watchlistApi`, `issueApi`, `blacklistApi`, `collectionApi`, `mediaApi`, `tmdbApi`,
`publicApi`, `overrideruleApi`. This is the obvious surface area for expansion.

## Known gaps

### Unfinished features

- **Discover home row.** `MetaRowType.DISCOVER` exists with a menu entry, but both `HomeSettingsPage.kt` (the
  `ChooseDiscover` branch) and `HomeSettingsViewModel.kt` (the `addRow` branch) are literal `TODO()`. Not a live crash —
  `HomeSettingsPage.kt` hardcodes `discoverEnabled = false` with the real `viewModel.discoverEnabled` flow commented
  out. Finishing this means implementing both branches and restoring the flag.
- **Blocklist.** `SeerrAvailability.BLOCKLISTED` / `DELETED` are modeled and reach the button logic in
  `ExpandableDiscoverButtons.kt`, but the actions are `// TODO` no-ops and `blacklistApi` is never called. PR #1671 was
  explicitly "start blocklist handling logic".
- **"More" button** on discover detail pages is fully commented out; `moreOnClick` is a dead parameter threaded through
  the call sites.

### Correctness

- **Cancel picks the wrong request when several exist.** Both `DiscoverMovieViewModel.cancelRequest` and
  `DiscoverSeriesViewModel.cancelRequest` use `requests.firstOrNull()` rather than matching the current user — marked
  `// TODO handle multiple requests? Or just delete self's request?`. Note the TV *request* path already does this
  correctly, so the matching logic exists to copy.
- **API key is stored before validation.** `SeerrServerRepository.addAndChangeServer(url, apiKey)` carries a
  `// TODO test api key`; the key is persisted, then `authMeGet` is called.
- **Filters hardcode US.** `watchRegion` and `certificationCountry` are pinned to `"US"` in `DiscoverFilter.kt`, with
  the real fields commented out. Date-range filters (`firstAirDateGte`/`Lte`) are commented out entirely.

### Performance

- **Requests tab is N+1** — one detail fetch per request row, bounded only by `Semaphore(3)`.
- **Rating fetch is per-item on focus**, flagged `// TODO this is not very efficient` in `SeerrDiscoverPage.kt`.

### Cosmetic

- Series logo never loaded on the discover page (`logoImageUrl = null, // TODO`).
- Season count string should use a plurals resource (`RequestSeasons.kt`).
- Commented-out page title in `SeerrRequestsPage.kt`.

## Test coverage

- `test/TestSeerr.kt` — URL permutation building, `createSeerrApiUrl`, `migrateSeerrUrl`
- `test/TestSeerrPermission.kt` — permission bitmask and parent/admin inheritance

No tests currently cover `SeerrService` mapping, the request flow, or `SeerrServerRepository` state transitions.
