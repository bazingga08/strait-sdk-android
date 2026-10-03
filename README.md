# strait-sdk-android (Kotlin) · v0.5.0

Deep linking for native Android, part of [Strait](../). It covers direct links
(verified App Links and custom-scheme hand-offs), deferred links (Play Install
Referrer, falling back to a fingerprint match), app-state labelling, analytics
events, open reporting (every link open recorded once, with an offline retry
queue) and the fingerprint debug check.

The library is **pure JVM**: every Android piece (Intents, lifecycle, Install
Referrer, SharedPreferences, display metrics, HTTP) is injected through
`StraitConfig`, so `gradle test` checks the logic on a plain JVM against the
shared vectors. It is a 1:1 port of the React Native reference
(`sdk-react-native/src/core.ts` + `strait.ts`).

Dependencies: Kotlin stdlib only. `org.json` is `compileOnly` because Android
already ships it. Outside Android, add `org.json:json` yourself.

## Contract behaviours ([SDK-CONTRACT](../shared-spec/SDK-CONTRACT.md))

| # | Status | Where |
|---|---|---|
| B1 publishableKey in every body (never appId or the secret key) | ✓ | `StraitClient` |
| B2 `browserScreenWidth = ceil(w - 0.001)` | ✓ | `Core.kt`, vectors |
| B3 short link → `POST /v1/resolve {publishableKey,url,platform}`, engine `reason` reported | ✓ | `StraitClient.handleUrl/start` |
| B4 `classifyUrl` (custom scheme → https destination; `strait_click` removed via `takeClickId`, returned as `clickId`) | ✓ | `Core.kt`, vectors |
| B5 `closed` / `AppStateTracker` (2000 / 1000 ms) | ✓ | `Core.kt`, vectors |
| B6 deferred once per install (`strait.deferredChecked`), skipped but marked when launched by a link; marked only once the engine answered (no answer / 429 / 5xx → `reason:"network"`, retried next launch); `checkDeferred()` sends no `openId` | ✓ | `StraitClient.start` |
| B7 referrer `strait_link` → `/v1/referrer {linkId, clickId, openId, at}`, else / on miss → `/v1/match {…device, openId, at}` (same `openId`) | ✓ | `StraitClient` |
| B8 iOS fingerprint | n/a (Android). `/v1/match` sends the device fields | |
| B9 one `LinkEvent` shape, replay to late subscribers, `onLinkStart` with the same id | ✓ | `StraitClient` |
| B10 never throws; network failure → `matched:false, reason:"network"` | ✓ | `StraitClient` |
| B11 JSON built by an encoder (org.json / escaped builders) | ✓ | `StraitClient`, `Strait.build*Body` |
| B12 `splitUrl` without `Uri`/`URI` (lower-cased, `+`/`%xx` decoded, fragment dropped) | ✓ | `Core.kt`, vectors |
| B13 `trackEvent`, `reportFingerprint` (origin `app`), `compareFingerprint` | ✓ | `StraitClient` |
| B14 every open reported once (`newOpenId` = event id), retry queue `strait.pendingOpens` (`pruneOpenQueue`, `shouldRetryReport`), `pendingOpenReports()` / `flushOpenReports()` | ✓ | `StraitClient`, `Core.kt`, vectors |

Vectors: `src/test/resources/test-vectors.json` (signature) and
`conformance-vectors.json` v2 (pure helpers). Both are byte-identical copies from
`shared-spec`, so don't edit them here.

## Install

<!-- brand:install -->
Published on [JitPack](https://jitpack.io) from this repo's version tags.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}

// app/build.gradle.kts
dependencies { implementation("com.github.bazingga08:strait-sdk-android:v0.5.0") }
```
<!-- /brand:install -->

## Android integration

Calls that hit the network block the calling thread. Pass a background
`executor` so `start`/`handleUrl` never run on the main thread. Listeners are
called on that executor, so post to the main thread before you navigate.

### 1. Create the client once (Application)

```kotlin
class App : Application() {
    lateinit var strait: StraitClient

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("strait", MODE_PRIVATE)
        strait = StraitClient(StraitConfig(
            publishableKey = "bk_pub_live_…",                       // Dashboard → Get started
            endpoint = "https://bridge-redirect-engine.onrender.com",
            linkHosts = listOf("https://go.yourbrand.com"),          // custom short-link domains
            storage = object : KeyValueStore {
                override fun get(key: String) = prefs.getString(key, null)
                override fun set(key: String, value: String) { prefs.edit().putString(key, value).apply() }
            },
            installReferrer = { readInstallReferrer(this) },
            device = {
                val m = resources.displayMetrics
                DeviceFields(
                    screenWidth = browserScreenWidth(m.widthPixels / m.density.toDouble()),
                    pixelRatio = m.density.toDouble(),
                    language = java.util.Locale.getDefault().toLanguageTag(),
                    timezone = java.util.TimeZone.getDefault().id,
                )
            },
            executor = java.util.concurrent.Executors.newSingleThreadExecutor(),
        ))

        // App state for B5: ProcessLifecycleOwner (androidx.lifecycle:lifecycle-process)
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> strait.onAppState(AppLifecycleState.ACTIVE)
                Lifecycle.Event.ON_PAUSE -> strait.onAppState(AppLifecycleState.INACTIVE)
                Lifecycle.Event.ON_STOP -> strait.onAppState(AppLifecycleState.BACKGROUND)
                else -> Unit
            }
        })
    }
}
```

> `ProcessLifecycleOwner` delays ON_PAUSE/ON_STOP by about 700 ms. If you need
> the brief pause that wraps link delivery to be reported exactly, also forward
> your link Activity's `onPause`/`onResume`. The tracker treats pauses shorter
> than 1000 ms as foreground either way.

### 2. Feed it links (Activity)

```kotlin
class MainActivity : AppCompatActivity() {
    private val strait get() = (application as App).strait

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        strait.onLinkStart { runOnUiThread { showOpeningLink() } }
        strait.onLink { e -> runOnUiThread { if (e.matched) navigate(e.path, e.params) else hideOpeningLink() } }
        // Launched from closed: the intent's URL (or null). Runs the deferred check once per install.
        if (savedInstanceState == null) strait.start(intent?.dataString)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let(strait::handleUrl)   // app was running: background / foreground
    }
}
```

`LinkEvent` fields: `id, kind (direct|deferred), route (app_link|custom_scheme|install_referrer|fingerprint),
appState (closed|background|foreground), matched, reason, rawUrl, url, path, params, linkId, ms, at`.
`onLink` replays past events to late subscribers. Both `onLink` and `onLinkStart` return an unsubscribe function.

### What Strait records automatically (no extra code)

Every time a link opens the app, the SDK reports it once (contract B14):

| How the app opened | Reported via | Joined to |
|---|---|---|
| Verified link tapped in WhatsApp, Gmail, Messages… | `/v1/resolve` (the lookup is the report) | the link; also counted as a tap |
| Browser handed off to the app (`yourapp://…`) | `/v1/open` | the exact tap (`strait_click`, removed before your app sees the URL) |
| First open after a Play install | `/v1/referrer` | the exact tap that sent the user to the store |
| First open with no Play referrer link | `/v1/match` | the matched tap |
| Your own https links | `/v1/open` | host + path only (never the query) |

Reports that can't be sent (offline, server busy) are saved in `storage` under
`strait.pendingOpens` and retried on the next `start`, whenever
`onAppState(ACTIVE)` is called, and after any report that gets through, for up
to 7 days (max 100). The engine de-duplicates by open id (`LinkEvent.id`), so
nothing is counted twice. Navigation never waits for a report: `onLink` fires
before it is sent. The first launch of an install is marked as such, so
dashboards can tell **new users** (installed and opened) from **existing
users** (already had the app). The deferred check is only marked done once the
server answered, so an offline first launch is retried on the next launch.
`strait.pendingOpenReports()` (count) and `strait.flushOpenReports()` (send
now) are blocking; call them off the main thread.

### 3. Play Install Referrer (`com.android.installreferrer:installreferrer`)

`installReferrer` is called on the executor, so it may block:

```kotlin
fun readInstallReferrer(context: Context): String? {
    val latch = java.util.concurrent.CountDownLatch(1)
    var result: String? = null
    val client = InstallReferrerClient.newBuilder(context).build()
    try {
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(code: Int) {
                if (code == InstallReferrerClient.InstallReferrerResponse.OK) {
                    result = runCatching { client.installReferrer.installReferrer }.getOrNull()
                }
                latch.countDown()
            }
            override fun onInstallReferrerServiceDisconnected() { latch.countDown() }
        })
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
    } catch (_: Exception) {
    } finally {
        runCatching { client.endConnection() }
    }
    return result
}
```

### 4. Events and the fingerprint check (call off the main thread)

```kotlin
strait.trackEvent("purchase", value = 49.99, currency = "USD", linkId = lastLinkId)
strait.reportFingerprint()    // POST /v1/debug/fingerprint, origin "app"
strait.compareFingerprint()   // GET  /v1/debug/fingerprint → JSONObject
strait.checkDeferred()        // re-run the deferred check (debug; ignores the once-per-install flag)
```

HTTP defaults to `UrlConnectionTransport` (`HttpURLConnection`). To use OkHttp,
implement `HttpTransport`: throw on network failure and return every HTTP
status as an `HttpResponse`.

**Publishable key:** Dashboard → Get started → Publishable key (`bk_pub_live_…`).
It's safe to include in your app. Never put your secret key (`bk_live_…`) in an app.

## Lower-level helpers (unchanged)

`computeSignature` / `h32` (the cross-language deferred-match signature),
`Strait.buildMatchBody` / `Strait.buildReferrerBody` (escaped JSON bodies) and
`parseStraitLink` (it now also %-decodes the id, as the vectors require).

## Test

```
gradle test     # JDK 17; CI runs the same on Temurin 17
```
