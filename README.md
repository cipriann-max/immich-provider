# Immich Picker

> **Not affiliated with or endorsed by the Immich project.** This is an independent,
> third-party client for the Immich API. "Immich" is used here only to describe what the app
> connects to. The official Immich app is a separate product, and this app neither modifies nor
> depends on it.

An Android app that lets you pick photos and videos from your own [Immich](https://immich.app)
server in any app's file picker — Chrome upload forms, email attachments, chat apps, anything
that opens a file chooser.

It is a **standalone, read-only** client. It does not touch the official Immich app, does not
upload, delete or edit anything, and needs no root, no ADB and no allowlist overrides.

- **Package:** `dev.immich.picker`
- **Min Android:** 8.0 (API 26)
- **Tested against Immich server:** `v3.4.1`

---

## What it does

Sign in once with your server URL and either your email/password or an API key. After that,
Immich appears as a source wherever Android asks for a file.

The picker is the app's own screen, not Android's generic file browser. That is deliberate:
a `DocumentsProvider` cannot control layout or sort order, and DocumentsUI always sorts folders
alphabetically regardless of what the provider returns. Owning the picker means your library
opens **newest first**, in a two-column grid, with album navigation.

### Screens

| Screen | Purpose |
|---|---|
| Settings | Server URL, credentials, connection test, cache size, clear cache, sign out |
| Picker | Tabbed **Timeline** and **Albums**, two-column grid, multi-select, sort toggle |

Timeline and album contents are cursor-paged 120 at a time, so a 60k-photo library opens as
fast as a small one.

---

## Install

### From a release APK

Download the APK and install it. Android will warn about installing from an unknown source;
that is expected for a self-distributed app.

### From source

Requirements:

- JDK 17 or newer
- Android SDK with **platform 36** and **build-tools 36.0.0**
- No Android Studio required

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Debug build
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Release build (unsigned unless keystore.properties exists, see below)
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release-unsigned.apk
```

Install a debug build to a connected device:

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

The debug build uses the application id suffix `.debug`, so it can sit side by side with a
release install.

### Signing a release build

Create `keystore.properties` in the **project root** (it is gitignored):

```properties
storeFile=release.jks
storePassword=your-store-password
keyAlias=immich-picker
keyPassword=your-key-password
```

Create the keystore if you do not have one:

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias immich-picker \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then `./gradlew :app:assembleRelease` produces a signed APK. If the file is absent the
release build stays unsigned instead of failing, so CI can still compile.

---

## Setup

### 1. Server URL

Enter the base URL **without** `/api`:

```
https://photos.example.com
http://192.168.1.50:2283
```

A bare host is treated as `http://` on Immich's default port, since most self-hosted servers
on a home network are plain HTTP. `https://` and a path prefix both work.

### 2. Credentials

Two options. Both end up as a single encrypted `secret`, but they are not the same kind of
value, which matters for how long sign-in lasts.

**Email and password** calls `POST /api/auth/login` once and stores the **access token the
server returns**. Your password is used for that call and then discarded — it is never
written to disk. The stored token is the session itself, not a renewable JWT.

**API key** has nothing to exchange: the key *is* the credential. It is stored as `secret`
and sent on every request as the `x-api-key` header. Create one in Immich under
**Account Settings → API Keys**. The key needs **all five** permissions:

| Permission | Needed for |
|---|---|
| `user.read` | Validating the key on sign-in, "Test connection" and every app start |
| `asset.read` | Listing timeline, albums, favourites |
| `asset.view` | Thumbnails in the grid |
| `asset.download` | Fetching the original file when you pick it |
| `album.read` | The Albums tab |

`user.read` is **not optional**. Validation reads your profile through
`GET /api/users/me`, which Immich guards with that permission. A key without it is refused
at sign-in with a `403` naming the missing permission. All three paths — sign-in, "Test
connection" and app start — use the same call, so they agree on what is required.

Nothing else is required. In particular the app never requests upload, delete or edit
permissions, and the Immich server would reject those calls anyway if it tried.

### Which credential lasts longer

| | Password login | API key |
|---|---|---|
| Expiry set by the server | None | None |
| Deleted when unused | **Yes, after 90 days** | No |
| Ends early if the server's database is reset or restored | Yes | Yes |
| Ends early if the session or key is revoked | Yes | Yes |
| Can be renewed without you | No | n/a |

The server-side detail: a password login creates a session row with no `expiresAt`, so it
does not time out on a deadline. Immich's background `SessionCleanup` job deletes sessions
whose `updatedAt` is more than **90 days** old, and every authenticated request refreshes
that timestamp (at most once an hour). So a password session stays valid indefinitely as
long as the picker is used at least once every 90 days.

An API key is stored in a table with no expiry column and is not touched by that cleanup, so
it survives any amount of idleness. **If you want to sign in once and never think about it
again, use an API key.**

The app cannot silently re-authenticate after a `401`. Recovering a password session would
require keeping the password, which this app deliberately does not do. When a credential is
rejected, the app says so and asks for a new sign-in or a switch to an API key.

### 3. Verify

"Test connection" reads `/api/server/version` and then validates the credential. That shows
the server version and, when readable, your name and email. Nothing is saved until
validation succeeds.

A `401` means the credential is gone and a new sign-in is required. A `403` is different: the
credential is valid but is missing a permission, so the app names the permission Immich
reports rather than claiming the session expired.

### 4. Self-signed certificates

If your server uses a self-signed certificate, open **Certificate options** in Settings:

- **Allow untrusted certificates** — accepts any certificate. Convenient, and no worse than
  the plain-HTTP default for a home server, but it does not protect against interception.
- **Custom CA (PEM)** — paste your CA certificate. This validates properly and is the better
  option if you have one.

---

## How to reach Immich in a picker

1. In the app you are using, tap its attach/file button.
2. Android shows a picker. If you only see the system photo picker, look for a menu (⋮) or a
   **Browse…** / **Files** entry — Chrome and some other apps open the photo picker first by
   default, and it has no way to show third-party providers.
3. Choose **Immich Picker** from the source list.

The picker is registered for **every** MIME type, not just `image/*` and `video/*`, so it also
appears in generic "attach a file" sheets — chat apps, e-mail clients, browser upload forms that
accept documents. Because an Immich library only contains photos and videos, the grid is
filtered against exactly what the caller asked for (`image/*`, a concrete type, or the
`EXTRA_MIME_TYPES` list). If the caller only accepts a type Immich cannot supply, such as
`application/pdf`, the app says so instead of showing a grid where nothing is selectable.

Immich does **not** appear as an icon inside the system photo picker. That integration
requires a `CloudMediaProvider` plus an ADB allowlist override, which this app deliberately
avoids. The SAF/file-picker route needs no special privileges and works everywhere.

---

## Behaviour and limits

### Sorting

The grid sorts newest-first by default, using Immich's own ordering so that paging stays
consistent. Tapping the sort icon switches to oldest-first.

### Caching

| Cache | Cap | Eviction |
|---|---|---|
| Thumbnails | 200 MB | LRU by last use |
| Downloaded originals | 500 MB | LRU, plus a 30-minute TTL |

Downloads are streamed to a `.part` file and renamed on completion, so an interrupted
transfer never leaves a truncated file that later looks valid. **Clear cache** in Settings
empties both.

### What gets uploaded

The **original** file, byte-for-byte as Immich stored it. The app never requests edited or
resized derivatives for downloads. Filenames come from Immich's `originalFileName` so the
receiving app sees the real name and extension; files with no original name get
`IMG_20240104_101500.jpg`-style names from the capture date.

### Errors are never silent

| Situation | What happens |
|---|---|
| Server unreachable | In-app error, notification, retry with backoff |
| Token rejected (HTTP 401) | Credential marked invalid, notification opens Settings, picker asks you to sign in again or use an API key |
| Permission missing (HTTP 403) | Credential kept; the missing permission is named and a re-login is **not** requested |
| Server returns unexpected JSON | Error names the endpoint and server version; notification explains a version mismatch |
| Request cancelled | Treated as a no-op, as DocumentsUI expects |
| Server slow | 45 s network timeout per call; the UI shows a spinner and stays responsive |

The schema-mismatch case deserves a note: it is how the app behaves when your server is a
**major version newer** than this build. Instead of silently showing an empty grid, it tells
you which endpoint disagreed and what version answered.

### Server version handling

`/api/server/version` is read at login and on each app start.

| Server vs. build | Behaviour |
|---|---|
| Same major version | Works normally |
| Newer major version | Keeps working, shows a warning in-app and as a notification |
| Older than the tested version | Asks you to update your server |

The version this build was generated from and tested against is compiled into
`BuildConfig.TESTED_IMMICH_VERSION` and is currently **3.4.1**.

### What the picker is registered for

| Action | MIME types | Effect |
|---|---|---|
| `GET_CONTENT` | `*/*` | Appears in every generic file chooser and photo picker that honours SAF |
| `OPEN_DOCUMENT` | `*/*` | Same, for callers that want a persistable grant |

The filters deliberately do **not** list only `image/*` and `video/*`. Narrow filters make the
app invisible to any caller that asks for `application/*` or `*/*`, which is why Immich never
showed up for document uploads. Type filtering is done inside the picker instead, where the
caller's exact request is known.

### Known limitations

- **No system photo-picker integration.** See above; it needs a `CloudMediaProvider` and an
  ADB allowlist.
- **No folder browsing inside the picker.** You get Timeline, Albums and Favorites as tabs,
  which is what a file picker needs.
- **Search is limited** to already-loaded grids.
- **Single server per install.** Multi-server would need a credential-per-root model.
- **Portrait and landscape are not separately tuned**; the grid reflows but the layout is
  designed for phones.
- **No upload, delete, rename or edit**, by design. There is no write path in the provider at
  all.
- **Album covers cache under a `nohash` key.** If an album's cover changes, that one cover
  re-downloads once. A deliberate trade against an unbounded set of stale keys.

---

## Immich API endpoints used

Every call below was made against a live **Immich v3.4.1** server while building this, and is
reached from real user actions — nothing here is speculative.

| Method | Path | Called when |
|---|---|---|
| `GET` | `/api/server/version` | Login, and on every app start (version gate) |
| `POST` | `/api/auth/login` | Signing in with email and password |
| `GET` | `/api/users/me` | Validating the credential and reading your name and email; requires `user.read` |
| `GET` | `/api/albums` | Opening the Albums tab |
| `POST` | `/api/search/metadata` | Paging the Timeline, an album, or Favorites |
| `GET` | `/api/assets/{id}` | Resolving metadata for a document URI the picker handed out |
| `GET` | `/api/assets/{id}/original` | The file itself, when you pick an asset |
| `GET` | `/api/assets/{id}/thumbnail` | Grid and album-cover thumbnails |

### Auth headers

- Email/password: `Authorization: Bearer <accessToken>`
- API key: `x-api-key: <key>`

Both satisfy Immich's `bearer` and `api_key` security schemes respectively; the app sends
exactly one, chosen by how you signed in.

---

## Updating for a new Immich release

The API client is generated from Immich's published OpenAPI spec, so a new release is a
three-step refresh rather than a manual edit.

### 1. Fetch the new spec

```bash
curl -L -o tools/openapi/immich-vX.Y.Z.json \
  https://raw.githubusercontent.com/immich-app/immich/vX.Y.Z/open-api/immich-openapi-specs.json
```

### 2. Regenerate the client

```bash
python3 tools/openapi/generate_models.py \
  --spec tools/openapi/immich-vX.Y.Z.json \
  --out  app/src/main/kotlin/dev/immich/picker/api \
  --version X.Y.Z
```

This rewrites `Models.kt` and `Endpoints.kt`. Do not edit those by hand — the next
regeneration overwrites them.

If Immich adds, removes or renames an endpoint, the generator stops with a message naming
the missing operation. Fix [`OPERATIONS`](tools/openapi/generate_models.py) in the generator,
then rerun.

### 3. Bump the tested version

In [`app/build.gradle.kts`](app/build.gradle.kts):

```kotlin
val testedImmichVersion = "X.Y.Z"
val testedImmichFeatureLevel = N
```

Then rebuild, sign in against a real server of that version, and update the version table in
this README.

### Why the generated models are resilient

- Unknown JSON keys are ignored (`ignoreUnknownKeys = true`), so **added** fields never break
  parsing.
- Enums are generated as `String` typealiases with constants, because kotlinx.serialization
  throws on unknown enum members and Immich adds members in patch releases.
- Every property has a default, so **missing** optional keys parse to a sensible value.

What is *not* resilient is a removed or retyped field. That surfaces as an explicit
`IncompatibleSchema` error naming the endpoint and server version — never as an empty screen.

---

## Project layout

```
app/src/main/kotlin/dev/immich/picker/
├── api/                    Generated from the OpenAPI spec — do not edit
│   ├── Models.kt           39 @Serializable models
│   ├── Endpoints.kt        8 typed operations
│   └── ApiJson.kt          Json config (ignoreUnknownKeys, explicitNulls=false)
├── net/
│   ├── ImmichApiClient.kt  OkHttp transport, retry/backoff, TLS options, downloads
│   └── ApiTypes.kt         ApiRequest, ApiException hierarchy, status→error mapping
├── data/
│   ├── Credentials.kt      EncryptedSharedPreferences + Keystore
│   ├── ImmichRepository.kt Read-only library queries, paging, mapping
│   ├── ImmichService.kt    Process-wide wiring: session, caches, error state
│   ├── VersionGate.kt      Server/build version comparison
│   └── Models.kt           Picker-layer domain types
├── cache/
│   ├── ThumbnailCache.kt   200 MB LRU disk cache
│   └── OriginalCache.kt    500 MB + 30 min TTL
├── provider/
│   └── ImmichDocumentsProvider.kt  Serves content:// URIs (rootless, read-only)
├── util/
│   └── MimeFilters.kt      Caller MIME parsing and asset matching
├── ui/
│   ├── MainActivity.kt         Settings entry point
│   ├── SettingsScreen.kt       Compose login/status/cache UI
│   ├── SettingsViewModel.kt    Login, validation, version checks
│   ├── PickerNotifications.kt  Auth/version/network notifications
│   └── picker/
│       ├── PickerActivity.kt   The picker; returns content:// URIs
│       ├── PickerScreen.kt     Compose grid, tabs, album list
│       ├── PickerViewModel.kt  Paging, selection, sort state
│       └── ThumbnailLoader.kt  Memory LRU over the disk cache
└── tools/openapi/
    ├── generate_models.py          Spec → Kotlin generator
    └── immich-v3.4.1.json          Vendored spec

app/src/test/kotlin/dev/immich/picker/
├── net/ApiFailuresTest.kt      Status mapping: 401 vs 403, permission naming
└── util/MimeFiltersTest.kt     Caller MIME parsing and matching rules
```

### Icon assets

[`immich-provider.png`](immich-provider.png) in the project root is the **source** artwork
(1024×1024). It is not used by the build directly; it is the master file the launcher
resources were generated from:

| Destination | Size | Purpose |
|---|---|---|
| `res/drawable-*/ic_launcher_foreground.png` | 108–432 px | Adaptive-icon foreground, pre-inset for the safe zone |
| `res/mipmap-*/ic_launcher.png` | 48–192 px | Legacy square icon (API < 26) |
| `res/drawable-*/ic_stat_immich.png` | 24–96 px | Status-bar notification icon |

Keep the source PNG so the icons can be regenerated at other densities or with a changed
inset, rather than upscaling a small derivative.

### Why the DocumentsProvider has no root

`queryRoots()` returns nothing on purpose. A root makes DocumentsUI render its **own** browser
for Immich with a layout and sort order you cannot control, which produces a second, worse
picker alongside this one. The provider still does the important work: it resolves
`content://dev.immich.picker.documents/document/asset:<id>` and streams the original through
`openDocument()`, so the receiving app reads bytes over a normal SAF grant. Nothing is copied
anywhere and the provider never needs to be browsable.

---

## Security notes

- Credentials live in `EncryptedSharedPreferences` backed by an Android Keystore AES-256-GCM
  master key. The secret is unreadable even from a root copy of the app's data directory.
- The app's data is excluded from cloud backup and device transfer.
- Cleartext HTTP is permitted, because self-hosted Immich is commonly plain HTTP on a LAN.
  Use HTTPS plus a custom CA if your server is reachable beyond your network.
- There is no analytics, telemetry, crash reporting or third-party SDK. The only network
  traffic is to the server URL you configured.

---

## Licence

Apache License 2.0.

You are free to use, modify, redistribute and sell this software, with or without changes,
for any purpose.

It is provided **as is**, without warranty of any kind, express or implied, including but not
limited to the warranties of merchantability, fitness for a particular purpose and
non-infringement. In no event shall the authors or copyright holders be liable for any claim,
damages or other liability, whether in an action of contract, tort or otherwise, arising from,
out of or in connection with the software or its use.

The app icon uses artwork derived from the Immich project, which is licensed under AGPL-3.0.
