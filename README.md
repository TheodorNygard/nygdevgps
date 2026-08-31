# GPS Push

Minimal personal Android app: two buttons and a status line. Start records GPS
fixes and POSTs them to your own HTTPS endpoint; Stop ends it. Built to keep
logging with the screen off, phone locked, in a pocket, for 6+ hours.

- Kotlin, Jetpack Compose, Gradle Kotlin DSL
- `com.example.gpspush`, minSdk 34, targetSdk 36
- Dependencies: Compose, `play-services-location`, coroutines. No Room, Retrofit,
  Hilt or WorkManager — `HttpURLConnection` and a JSONL file in `filesDir`.

## Build

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

or open the project in Android Studio and run it.

### Or let GitHub build it for you

`.github/workflows/build.yml` builds a debug APK on every push, and on demand
from the Actions tab ("Build APK" → Run workflow). Open the finished run and
download the `gpspush-debug-<run number>` artifact — a zip containing
`app-debug.apk`.

Straight onto the phone, no computer involved: open the run page in Chrome on
the Pixel, tap the artifact to download it, open it from Files, extract, and tap
the APK. Android will ask once whether Files (or Chrome) may install unknown
apps. With a cable it's just `adb install -r app-debug.apk`.

One wrinkle: CI signs with a throwaway debug key that differs from run to run,
and from the one Android Studio uses. Installing a CI build over an existing
install fails with a signature mismatch — `adb uninstall com.example.gpspush`
first, or long-press the icon and uninstall. That wipes the spool file and the
last-upload timestamp, so do it when nothing is queued. If you install from CI
often enough for that to grate, generate a keystore once, put it in repo secrets
and add a real `signingConfig` — then updates install straight over the top.

> The sandbox this was written in blocks `dl.google.com`, so the Android SDK and
> the AGP/androidx artifacts could not be downloaded and the project was never
> put through a compiler here. If Android Studio complains about a version, the
> pins are AGP 8.9.1 / Kotlin 2.1.20 / Gradle 8.11.1 in `build.gradle.kts`,
> `gradle/wrapper/gradle-wrapper.properties` and `app/build.gradle.kts` — bump
> them and accept the IDE's upgrade prompt.

## Configuration

Everything you'll want to edit is at the top of
`app/src/main/java/com/example/gpspush/Config.kt`:

| Constant | Default | What it does |
| --- | --- | --- |
| `ENDPOINT` | `…/api/gps/locations` | Where batches are POSTed. Currently the Azure Function App. |
| `INTERVAL_MS` | `10_000` | How often the GPS chip produces a fix. |
| `BATCH_WINDOW_MS` | `60_000` | How long the chip may hold fixes before handing them over as a batch. |
| `AUTH_HEADER_NAME` | `"x-functions-key"` | Auth header name — the Azure function key goes in this header rather than in a `?code=` query param, so it stays out of the request URL and the App Insights log. |
| `AUTH_HEADER_VALUE` | *(injected at build time)* | Auth header value. **If blank, no auth header is sent at all.** |

### The function key

This repo is public, so the key is never committed — GitHub's push protection
blocks it anyway. It is injected into `BuildConfig` at build time, first hit
winning:

1. `-Pgpspush.authKey=<key>` on the Gradle command line
2. `gpspush.authKey=<key>` in `local.properties` (gitignored — use this in
   Android Studio)
3. `GPSPUSH_AUTH_KEY` in the environment — how CI gets it, from the repository
   secret of the same name (Settings → Secrets and variables → Actions)

Build with none of them set and you get a working APK that sends no auth header,
which the Function App answers with 401; Gradle prints a warning saying so, and
the CI run adds a warning annotation. Rotating the key means updating whichever
of the three you use — nothing in git changes.

Below those are things you'll rarely touch: spool file name, the ~4 MB / 20000
line cap, HTTP timeouts, the 30 s wakelock timeout.

## What your server receives

One request per batch window (and one more right after you hit Stop):

```
POST /your/path HTTP/1.1
Content-Type: application/json; charset=utf-8
X-Api-Key: <AUTH_HEADER_VALUE>      # omitted entirely when the value is blank
```

Body is a JSON array of every fix currently spooled — normally the last
`BATCH_WINDOW_MS` worth, but the whole backlog if earlier uploads failed:

```json
[
  {"lat":59.913868,"lon":10.752245,"acc":4.2,"alt":23.7,"spd":1.35,"ts":1756645200000},
  {"lat":59.913901,"lon":10.752310,"acc":3.9,"alt":23.9,"spd":1.41,"ts":1756645210000}
]
```

| Field | Type | Meaning |
| --- | --- | --- |
| `lat` / `lon` | number | Degrees. |
| `acc` | number \| null | Horizontal accuracy, metres. |
| `alt` | number \| null | Altitude, metres (WGS84 ellipsoid). |
| `spd` | number \| null | Speed, m/s. |
| `ts` | number | `Location.time`, epoch milliseconds. |

`acc`, `alt` and `spd` are `null` when the fix didn't carry that value.

Things the server should honour:

- **Answer with any 2xx.** The spool is cleared only after a confirmed 2xx.
  Anything else — 4xx, 5xx, timeout, TLS error, no network — leaves the file
  intact and the whole backlog is retried on the next batch.
- **Answer within 20 s** (`READ_TIMEOUT_MS`), or the batch is treated as failed
  and resent.
- **Deduplicate on `ts`.** If your server commits a batch but the response is
  lost in transit, the app retries it. `(ts, lat, lon)` is a fine dedupe key.
- **Accept large bodies.** After an hour offline at the defaults the array is
  ~360 objects; the cap is 20000 (~4 MB), at which point the oldest lines are
  dropped.

## Manual Pixel settings (code cannot set these)

On first launch the app asks for location and notifications, and fires the
battery-optimization exemption dialog — tap **Allow** on "Allow app to always
run in the background". It re-asks on every launch until you do. Then, by hand:

1. **Settings → Apps → GPS Push → App battery usage → Unrestricted.**
   Not "Optimised". This is the setting that matters most for a 6-hour run.
2. **Settings → Apps → GPS Push → turn OFF "Pause app activity if unused".**
   Otherwise the OS can revoke permissions and stop the app after a few days
   of not opening it.
3. **Location permission: "While using the app" plus Precise.** That's enough —
   a foreground service with `location` type keeps access while it runs, which
   is why the app never asks for background location.
4. Leave the ongoing notification alone. Dismissing the channel would kill the
   service's foreground status.

Sanity check before a long day out: start it, lock the phone, wait a couple of
batch windows, and confirm the status line's "last upload" is moving.

## Battery

At the defaults (`INTERVAL_MS = 10_000`, `BATCH_WINDOW_MS = 60_000`) on a
Pixel 9 Pro, screen off, expect roughly **4–6 % of the battery per hour**
(~200–300 mA). A 6-hour session lands around 25–35 %. That is dominated by the
GNSS engine itself, which is essentially on continuously at a 10 s fix rate; the
hardware batching (`setMaxUpdateDelayMillis`) is what keeps the CPU and the
modem out of it, waking the application processor once a minute instead of six
times a minute. Measure your own — terrain, sky view and cell conditions move
this a lot.

To cut it roughly in half, change these two constants in `Config.kt`:

- `INTERVAL_MS`: `10_000` → `30_000`. Fewer fixes lets the GNSS engine duty-cycle
  between them instead of tracking continuously. This is the bigger of the two.
- `BATCH_WINDOW_MS`: `60_000` → `300_000`. Five minutes of fixes per wakeup and
  per upload, so a twelfth as many radio wakeups.

The cost is coarser tracks and up to 5 minutes of fixes sitting on the phone if
it dies mid-session — the spool is on disk, but it hasn't been uploaded yet.

## How it stays alive

- Foreground service, `android:foregroundServiceType="location"`, started with
  `startForegroundService` from the visible activity, `START_STICKY` so the
  system restarts it if the process is killed.
- `FusedLocationProviderClient` with `PRIORITY_HIGH_ACCURACY` and
  `setMaxUpdateDelayMillis(BATCH_WINDOW_MS)` for hardware batching.
- Every fix is appended to `filesDir/queue.jsonl` **before** any network attempt.
  Upload sends the whole file as one array and removes the uploaded lines only
  after a 2xx. All reads, writes and clears are behind a `Mutex`, since batched
  callbacks can overlap an in-flight upload.
- A `PARTIAL_WAKE_LOCK` with a 30 s timeout is taken around the upload only, and
  released in a `finally`. Nothing holds a wakelock between batches — that would
  defeat the batching and eat the battery.
- The running flag lives in SharedPreferences, written by the service, so
  reopening the app after killing it shows the real state.
