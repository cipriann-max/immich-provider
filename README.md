# Immich File Picker

> **Not affiliated with or endorsed by the Immich project.** This is an independent,
> third-party client for the Immich API. "Immich" is used here only to describe what the app
> connects to. The official Immich app is a separate product, and this app neither modifies nor
> depends on it.

An Android app that lets you pick photos and videos from your own [Immich](https://immich.app)
server in any app's file picker — Chrome upload forms, email attachments, chat apps, anything
that opens a file chooser.

It is a **standalone, read-only** client. It does not touch the official Immich app, does not
upload, delete or edit anything, and needs no root, no ADB and no allowlist overrides.

- **Package:** `com.virtunio.immichpicker`
- **Version:** `1.2.0` — see [CHANGELOG.md](CHANGELOG.md)
- **Publisher:** Virtunio Ltd
- **Min Android:** 8.0 (API 26)
- **Tested against Immich server:** `v3.4.1`

The package id is reverse-DNS of a domain Virtunio Ltd controls, not `dev.immich.*`, because
this app is not part of the Immich project. The Kotlin package stays `dev.immich.picker`
internally — that is a source-code detail with no user-visible effect. The public
`applicationId` is what Android, the launcher and the document provider authority use.

---

## What it does

Sign in once with your server URL and either your email/password or an API key. After that,
Immich appears as a source wherever Android asks for a file.

The app contributes **one Storage Access Framework root**. Whether the calling app uses
`GET_CONTENT` or `OPEN_DOCUMENT`, you browse the same tree in the same UI:

```
Immich              the newest photos and videos, newest first
├── Albums          one folder per album, most recently active first
└── Favorites       starred photos and videos
```

Opening the root shows the 50 most recent files; further pages load in the background as
DocumentsUI re-queries, up to **400 assets per folder**. Files are numbered from `0001` in
view order so the list is chronological by default — see
[Why file names get a numeric prefix](#why-file-names-get-a-numeric-prefix). Assets in the
trash are excluded.

A small bridge activity also makes the app visible to choosers that list *apps* rather than
SAF roots — the system photo picker some browsers use for media uploads. It has no UI of its
own: it opens the same root and forwards the result unchanged.

### Screens

| Screen | Purpose |
|---|---|
| Settings | Server URL, credentials, connection test, cache size, clear cache, sign out |
| DocumentsUI (system) | The root above: newest-first grid, paging, Albums and Favorites |

There is no custom picker screen. An earlier version had one for `GET_CONTENT`, which made the
library look different depending on which action the calling app used; it was removed so both
paths behave identically. Settings is reachable from DocumentsUI through the root's settings
affordance, and from the app's launcher icon.

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

### Building a release APK

Use [`release.sh`](release.sh). It builds, signs, verifies the signature and stages the
APK with a checksum — no GitHub and no CI required:

```bash
./release.sh            # version from app/build.gradle.kts (currently 1.2.0)
./release.sh 1.2.3      # explicit version name; versionCode derived as 10203
./release.sh 1.2.3 7    # explicit name and code
```

Output:

```
dist/immich-file-picker-1.2.0.apk
dist/immich-file-picker-1.2.0.apk.sha256
```

The script refuses to finish if the APK is unsigned, or if it is signed by a certificate
that does not carry `O=Virtunio Ltd`. That guard exists because a release signed with the
wrong key cannot be installed over an existing one — better to fail at build time.

### Signing keys

| Property | Value |
|---|---|
| Publisher | **Virtunio Ltd** |
| Certificate DN | `CN=Immich File Picker, O=Virtunio Ltd, OU=Applications, L=Nicosia, C=CY` |
| Alias | `immich-picker` |
| Algorithm | RSA 4096, self-signed, SHA384withRSA, 10000 days |
| Certificate SHA-256 | `4eb44b6de93977f9f0890ecbff3b045730879e669d64f2e5cd5223a5cf7ae931` |

The keystore lives **outside the repository**, in `$HOME/keystores/immich-picker-release.jks`,
so it cannot be committed by accident even if `.gitignore` changes. The password is in
`$HOME/keystores/immich-picker-release.password` (mode 600).

**Back this file up somewhere durable.** If it is lost, no future release can update an
existing install; every user would have to uninstall first. There is no recovery path —
Google Play is not involved, so no key-escrow service exists for it.

To create a replacement keystore (only if you are starting over, since existing installs
cannot be updated with a new key):

```bash
keytool -genkeypair -v \
  -keystore ~/keystores/immich-picker-release.jks \
  -alias immich-picker \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12 \
  -dname "CN=Immich File Picker, O=Virtunio Ltd, OU=Applications, L=Nicosia, C=CY"
```

### Local signing configuration

[`release.sh`](release.sh) and Gradle read signing material from either of two places, so
the same build works on a laptop and in CI:

1. **`keystore.properties`** in the project root (gitignored) — normal local use:

   ```properties
   storeFile=/home/you/keystores/immich-picker-release.jks
   storePassword=…
   keyAlias=immich-picker
   keyPassword=…
   ```

2. **Environment variables** — used by CI, and by anyone who prefers not to write a file:

   | Variable | Meaning |
   |---|---|
   | `RELEASE_KEYSTORE_FILE` | path to the `.jks` |
   | `RELEASE_KEYSTORE_PASSWORD` | keystore password |
   | `RELEASE_KEY_ALIAS` | key alias |
   | `RELEASE_KEY_PASSWORD` | key password |

   Environment variables win when the full set is present, so a stale local file cannot
   quietly produce a differently-signed APK in a release pipeline.

If neither is configured, `./gradlew :app:assembleRelease` still compiles but yields an
**unsigned** APK, which Android will refuse to install. That is intentional: a contributor
without signing material can still build and run tests.

Verify any APK's publisher yourself:

```bash
"$ANDROID_HOME"/build-tools/36.0.0/apksigner verify --print-certs \
  dist/immich-file-picker-1.2.0.apk
```

### Publishing with GitHub Actions

Two workflows:

| Workflow | Trigger | Signs | Publishes |
|---|---|---|---|
| [`ci.yml`](.github/workflows/ci.yml) | push to `main`, every PR | no | no |
| [`release.yml`](.github/workflows/release.yml) | push tag `v*`, or manual run | yes | GitHub Release |

Cutting a release is tagging:

```bash
git tag v1.2.0
git push origin v1.2.0
```

The tag is the single source of truth: `release.yml` strips the leading `v`, sets
`versionName` from it, derives `versionCode` as `major*10000 + minor*100 + patch`, runs the
unit tests, builds via [`release.sh`](release.sh), verifies the `Virtunio Ltd` signature,
and creates the Release with the APK and checksum attached. Re-running a tag replaces the
assets rather than failing.

### Action versions

Pin every action to a major that runs on **Node.js 24**. GitHub forces Node 20 actions onto
Node 24 on hosted runners and logs a deprecation warning for each one, so an old pin makes
every job noisy and sits on a removal path. The floors are `checkout` 5, `cache` 5,
`setup-java` 5 and `upload-artifact` 6; the workflows pin the current majors rather than
those minimums.

| Action | Pinned | Why it cannot go lower |
|---|---|---|
| `actions/checkout` | `v7` | `v5`+ is the Node 24 runtime; older majors are force-migrated |
| `actions/setup-java` | `v6` | `v5`+ is Node 24. `v6` also renames the `jdkFile` input to `jdk-file`, keeping the old spelling as an alias — this project sets neither |
| `actions/cache` | `v6` | `v5`+ is Node 24; `v6` additionally moves the bundle to ESM |
| `actions/upload-artifact` | `v7` | `v6` is the first major whose default runtime is Node 24; `v7` adds single-file uploads. Do **not** use `v3`, which is Node 20 and targets the retired v1 artifact backend |
| `android-actions/setup-android` | `v4` | No `v5` exists yet, and `v4` already declares `node24`. `v3` is not an option: it runs `sdkmanager tools`, but Google no longer serves the `tools` package, so the job dies with `Failed to find package 'tools'` before Gradle runs |

These majors require Actions Runner `2.327.1` or later on self-hosted runners;
`ubuntu-latest` is well past that. Only `ubuntu-latest` is used here, and the keys this
project relies on — `uses`, `with`, `if`, `env` — behave identically across them.

The `Install required SDK platforms` step still calls `sdkmanager`, so the log contains
`WARNING: The SDK Manager CLI tool (sdkmanager) is deprecated. Use Android CLI instead.`
That is expected and harmless. The stated replacement is the `android` binary's
`android sdk install` subcommand, but it is not reliably available yet: `android` is
missing from several `cmdline-tools` releases (including the 10.0 on the author's machine)
and `setup-android` itself still invokes `sdkmanager` internally, so the warning cannot be
removed without adding a dependency on a binary that may not exist on the runner. Note the
package-path syntax differs — `sdkmanager` takes `platforms;android-36`, whereas
`android sdk install` takes `platforms/android-36`.

Add these four repository secrets (Settings → Secrets and variables → Actions):

| Secret | How to produce it |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 ~/keystores/immich-picker-release.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | same password as `storePassword` |
| `RELEASE_KEY_ALIAS` | `immich-picker` |
| `RELEASE_KEY_PASSWORD` | same password as `keyPassword` |

If `RELEASE_KEYSTORE_BASE64` is missing the release job fails on purpose rather than
publishing an unsigned APK.

Note that GitHub secrets are write-only: once uploaded, the value cannot be read back. That
is why the keystore must also exist locally — the Actions secret is a copy, not the backup.

The `Validate keystore` step exists because a mangled secret surfaces as
`DerInputStream.getLength(): lengthTag=N, too big` from deep inside
`PackageAndroidArtifact`, which points at nothing actionable. The step prints the decoded
file's size, SHA-256 and first four bytes, then fails early with a specific message if the
bytes are not a keystore (`30 82 …` for PKCS12, `fe ed fe ed` for JKS) or if `keytool`
cannot open it with the supplied password and alias.

To confirm an upload is correct, compare fingerprints:

```bash
sha256sum ~/keystores/immich-picker-release.jks     # local
# compare with "Keystore sha256" printed by the Validate keystore step
```

Common causes of the mismatch are a newline or a `wc -c` line captured into the secret, a
line-wrapped file copied by hand, or base64 decoded before upload as well as after.

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

Whatever action the calling app uses, you get the same place:

1. In the app you are using, tap its attach/file button.
2. Look for **Immich**. Two chooser styles exist, and the app appears in both:
   - **DocumentsUI** lists provider roots, so **Immich** is under *Browse files in other apps*.
   - **The system photo picker** lists apps that answer the intent. Some browsers open it first
     for media uploads; **Immich** appears in its app list and opens the same root when tapped.
3. If you only see your own gallery, use a menu (⋮) or a **Browse…** / **Files** entry to reach
   a full file chooser.

`GET_CONTENT` and `OPEN_DOCUMENT` are both routed to DocumentsUI, and both find this app
through its provider root. There is no separate in-app picker to choose between, so there is
exactly one Immich layout.

### Why there is no custom picker any more

Earlier versions shipped their own `GET_CONTENT` activity, which had two problems. It could not
be reached from `OPEN_DOCUMENT` at all — that action is reserved for the system
`DocumentsUI`, and even `android:priority="1000"` does not change the resolution (verified
on-device). Worse, it meant the same library rendered differently depending on which action the
caller used, and the two could never agree on ordering because DocumentsUI owns the sort of any
root it displays. Deleting it left one surface to maintain and one behaviour to explain.

What replaced it is a root for the roots-based chooser, plus a **UI-less bridge activity** for
the app-list-based one. The bridge renders nothing, opens DocumentsUI at the Immich root and
forwards the result untouched, so it adds reach without adding a second interface.

### Types

The root is registered for **every** MIME type, not just `image/*` and `video/*`, so it appears
in generic "attach a file" sheets — chat apps, e-mail clients, browser upload forms that accept
documents. Because an Immich library only contains photos and videos, the type is filtered
against exactly what the caller asked for (`image/*`, a concrete type, or the
`EXTRA_MIME_TYPES` list). If the caller only accepts a type Immich cannot supply, such as
`application/pdf`, the root says so instead of showing a list where nothing is selectable.

Immich does **not** appear as a *native media provider* inside the system photo picker. That
integration requires a `CloudMediaProvider` plus an ADB allowlist override, which this app
deliberately avoids. What it does instead is answer the picker's intent through the bridge
activity, so the app is selectable and hands off to the SAF root. The SAF/file-picker route
needs no special privileges and works everywhere.

---

## Behaviour and limits

### Sorting

The list reads newest-first by default. Assets are requested from Immich newest-first, sorted
again locally, and numbered so that DocumentsUI's name sort agrees with that order — see
[Why file names get a numeric prefix](#why-file-names-get-a-numeric-prefix).

DocumentsUI's own sort menu still works and can reorder the list; choosing anything other than
**File name (A to Z)** will disagree with the numbering shown.

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

There are no notifications and no background reporting. Every failure is shown where the
user is already looking — the root's summary line, the folder's `EXTRA_ERROR` text, or the
Settings screen — which is why the app requests no permission beyond `INTERNET`.

| Situation | What happens |
|---|---|
| Server unreachable | `EXTRA_ERROR` in the folder, "Test connection" reports it in Settings, retry with backoff |
| Token rejected (HTTP 401) | Credential marked invalid; the root summary changes to "Session expired…", the folder says to sign in again, Settings shows the login form |
| Permission missing (HTTP 403) | Credential kept; the missing permission is named and a re-login is **not** requested |
| Server returns unexpected JSON | Error names the endpoint and server version; the version mismatch is also shown as a card in Settings |
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
| Newer major version | Keeps working, shows a warning card in Settings |
| Older than the tested version | Asks you to update your server |

The version this build was generated from and tested against is compiled into
`BuildConfig.TESTED_IMMICH_VERSION` and is currently **3.4.1**.

### What the app registers

| Component | What it does |
|---|---|
| `ImmichDocumentsProvider` | The SAF root — the picker surface for both `GET_CONTENT` and `OPEN_DOCUMENT` |
| `SafPickerBridgeActivity` | UI-less bridge so app-list pickers show Immich; opens the root and forwards the result |
| `MainActivity` | Settings, also the target of DocumentsUI's root settings affordance |

`SafPickerBridgeActivity` is registered for `GET_CONTENT` and `OPEN_DOCUMENT` on `*/*`, which
is what makes the app appear in choosers that enumerate activities. It shows no UI: it
re-validates the caller's MIME types, opens DocumentsUI at the Immich root, and returns the
result verbatim. The provider root remains the only place the library is rendered, so type
filtering still happens per request, where the caller's exact request is known.

### The DocumentsUI root

```
Immich              the newest assets, newest first
├── Albums          one folder per album, most recently active first
└── Favorites       starred assets
```

The first page is 50 assets. Further pages load in the background and DocumentsUI re-queries
as each arrives, up to **400 assets per folder**. Trashed assets are excluded everywhere.

400 is a ceiling, not a limit on the library: DocumentsUI has no lazy paging for providers
(it does not even re-query when you scroll to the bottom — measured: three swipes produced zero
queries), so growth has to be driven by content-change notifications, and an unbounded version
loaded a 60k library within seconds of opening. 400 is deep enough to find a recent file while
keeping an accidental open cheap. Older items remain reachable through Albums.

The root is always present, even signed out: its summary then says a sign-in is needed, and the
sign-in screen is one tap away. If the server has rejected the stored credential the summary
says **"Session expired — open Immich File Picker to sign in again"** instead, so the failure is
visible in the picker itself rather than only after opening a folder. Timeline, Albums and
Favorites open to that message rather than looking broken.

### Why file names get a numeric prefix

Files in the picker are displayed as `0001_IMG_20261117_154600.jpg`,
`0002_IMG_20261117_154331.jpg`, and so on. **This is cosmetic.** It is not a rename on your
server, and it does not change the file you attach.

The cause is a platform constraint. DocumentsUI applies **its own sort** to whatever a
`DocumentsProvider` returns, defaulting to **"File name (A to Z)"**, and ignores the order of
the rows themselves. Since camera filenames start with a date, A–Z is chronological *ascending*
— a photo library would open at the oldest picture. No provider-side ordering can override
this; it was confirmed on-device by logging the cursor (assets returned newest-first,
`WA0010 → WA0009 → WA0008`) and watching DocumentsUI render them as Sep 19, Sep 23, Sep 25.

The one lever a provider has is the name, because that is what the sort compares. Numbering the
rows `0001`, `0002`, … makes A–Z *identical* to newest-first, so the default view is correct
with no tap and no setting to remember.

The prefix is applied in exactly one place — the folder listing that fills the grid. The name
returned when a picked file is opened comes from a separate call and is left untouched, so the
receiving app sees the real filename. The file's bytes are never modified either way.

If the prefix bothers you, the alternative is one tap: ⋮ → **Sort by…** → **Modified (newest
first)** would order correctly without it, but DocumentsUI forgets that choice between launches,
so the default is wrong again next time.

### Known limitations

- **No native system photo-picker integration.** The app is reachable through the bridge
  activity, but it is not a `CloudMediaProvider`; that would need an ADB allowlist.
- **Displayed filenames carry a numeric prefix.** Cosmetic, and explained above; the attached
  file keeps its real name.
- **The tree has no year/month nesting.** It is root → Albums → album → assets, plus the
  Favorites folder at the root.
- **Each folder stops at 400 assets.** See the root section above for why more cannot be paged
  lazily.
- **The trash is excluded, not browsable.** Deleted items are filtered out rather than offered
  as their own folder.
- **Search is limited** to what has already been loaded in this session.
- **Single server per install.** Multi-server would need a credential-per-root model.
- **The picker is DocumentsUI.** Its layout, grid density and sort menu are not ours to change.
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
| `POST` | `/api/search/metadata` | Paging the timeline or an album |
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
release.sh                      Build + sign + verify a release APK (no CI needed)
CHANGELOG.md                    Release notes, Keep a Changelog format
.github/workflows/ci.yml        Tests and debug build on push/PR
.github/workflows/release.yml   Tag-driven signed release with GitHub Release

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
│   └── ImmichDocumentsProvider.kt  SAF root, paging, content:// URIs (read-only)
├── util/
│   ├── DocumentIds.kt      Document-id scheme for root/albums/assets
│   ├── MimeFilters.kt      Caller MIME parsing and asset matching
│   └── RankedNames.kt      Numeric prefixes that pin DocumentsUI's sort order
├── ui/
│   ├── MainActivity.kt         Settings, and DocumentsUI's root settings target
│   ├── SafPickerBridgeActivity.kt  Makes app-list pickers show Immich, then hands off
│   ├── SettingsScreen.kt       Compose login/status/cache UI
│   └── SettingsViewModel.kt    Login, validation, version checks
└── tools/openapi/
    ├── generate_models.py          Spec → Kotlin generator
    └── immich-v3.4.1.json          Vendored spec

app/src/test/kotlin/dev/immich/picker/
├── net/ApiFailuresTest.kt      Status mapping: 401 vs 403, permission naming
└── util/
    ├── DocumentIdsTest.kt      SAF document-id round trip and edge cases
    ├── MimeFiltersTest.kt      Caller MIME parsing and matching rules
    └── RankedNamesTest.kt      Prefix width and sort-reproducing order
```

### Icon assets

[`immich-provider.png`](immich-provider.png) in the project root is the **source** artwork
(1024×1024). It is not used by the build directly; it is the master file the launcher
resources were generated from:

| Destination | Size | Purpose |
|---|---|---|
| `res/drawable-*/ic_launcher_foreground.png` | 108–432 px | Adaptive-icon foreground, pre-inset for the safe zone |
| `res/mipmap-*/ic_launcher.png` | 48–192 px | Legacy square icon (API < 26) |

Keep the source PNG so the icons can be regenerated at other densities or with a changed
inset, rather than upscaling a small derivative.

### Why the DocumentsProvider is the whole UI

The provider does two jobs, and they are the reason it, rather than an activity, is where the
library is rendered:

1. **Serving bytes.** It resolves
   `content://com.virtunio.immichpicker.documents/document/asset:<id>` and streams the original
   through `openDocument()`, so the receiving app reads bytes over a normal SAF grant. Nothing
   is copied.
2. **Being selectable.** DocumentsUI lists only provider *roots* for `OPEN_DOCUMENT` — an
   activity cannot be the system picker for that action, and `android:priority` does not help.
   A root is the only way to be offered.

Once the root exists, a second `GET_CONTENT` activity would be a competing implementation of
the same library. Earlier versions had exactly that, and the two surfaces could never agree on
ordering, because DocumentsUI re-sorts any root it renders. Removing the activity left one
implementation, one layout and one set of behaviours.

The root stays minimal: read-only, no `FLAG_SUPPORTS_CREATE`, no write path. The bridge
activity is the one component that answers intents directly, and it only forwards.

---

## Security notes

- **One permission: `INTERNET`.** There is no `POST_NOTIFICATIONS`, no storage permission and
  no background component that could show anything while the app is not in front of the user.
  Nothing here has a reason to reach the user outside a picker, so the permission list stays at
  the single one the app cannot function without.
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
