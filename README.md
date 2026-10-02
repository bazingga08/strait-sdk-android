# bridge-sdk-android (Kotlin) · v0.3.0

Deep linking for native Android, part of [Bridge](../). It covers direct links
(verified App Links and custom-scheme hand-offs), deferred links (Play Install
Referrer, falling back to a fingerprint match), app-state labelling, analytics
events and the fingerprint debug check.

The library is **pure JVM**: every Android piece (Intents, lifecycle, Install
Referrer, SharedPreferences, display metrics, HTTP) is injected through
`BridgeConfig`, so `gradle test` checks the logic on a plain JVM against the
shared vectors. It is a 1:1 port of the React Native reference
(`sdk-react-native/src/core.ts` + `bridge.ts`).

Dependencies: Kotlin stdlib only. `org.json` is `compileOnly` because Android
already ships it. Outside Android, add `org.json:json` yourself.

## Contract behaviours ([SDK-CONTRACT](../shared-spec/SDK-CONTRACT.md))

| # | Status | Where |
|---|---|---|
| B1 publishableKey in every body (never appId or the secret key) | ✓ | `BridgeClient` |
| B2 `browserScreenWidth = ceil(w - 0.001)` | ✓ | `Core.kt`, vectors |
| B3 short link → `POST /v1/resolve {publishableKey,url,platform}`, engine `reason` reported | ✓ | `BridgeClient.handleUrl/start` |
| B4 `classifyUrl` (custom scheme → https destination) | ✓ | `Core.kt`, vectors |
| B5 `closed` / `AppStateTracker` (2000 / 1000 ms) | ✓ | `Core.kt`, vectors |
| B6 deferred once per install (`bridge.deferredChecked`), skipped but marked when launched by a link | ✓ | `BridgeClient.start` |
| B7 referrer `bridge_link` → `/v1/referrer`, else / on miss → `/v1/match` | ✓ | `BridgeClient` |
| B8 iOS fingerprint | n/a (Android). `/v1/match` sends the device fields | |
| B9 one `LinkEvent` shape, replay to late subscribers, `onLinkStart` with the same id | ✓ | `BridgeClient` |
| B10 never throws; network failure → `matched:false, reason:"network"` | ✓ | `BridgeClient` |
| B11 JSON built by an encoder (org.json / escaped builders) | ✓ | `BridgeClient`, `Bridge.build*Body` |
| B12 `splitUrl` without `Uri`/`URI` (lower-cased, `+`/`%xx` decoded, fragment dropped) | ✓ | `Core.kt`, vectors |
| B13 `trackEvent`, `reportFingerprint` (origin `app`), `compareFingerprint` | ✓ | `BridgeClient` |

Vectors: `src/test/resources/test-vectors.json` (signature) and
`conformance-vectors.json` (pure helpers). Both are byte-identical copies from
`shared-spec`, so don't edit them here.

## Android integration

Calls that hit the network block the calling thread. Pass a background
`executor` so `start`/`handleUrl` never run on the main thread. Listeners are
called on that executor, so post to the main thread before you navigate.

### 1. Create the client once (Application)

```kotlin
class App : Application() {
    lateinit var bridge: BridgeClient

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("bridge", MODE_PRIVATE)
        bridge = BridgeClient(BridgeConfig(
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
                Lifecycle.Event.ON_RESUME -> bridge.onAppState(AppLifecycleState.ACTIVE)
                Lifecycle.Event.ON_PAUSE -> bridge.onAppState(AppLifecycleState.INACTIVE)
                Lifecycle.Event.ON_STOP -> bridge.onAppState(AppLifecycleState.BACKGROUND)
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
    private val bridge get() = (application as App).bridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridge.onLinkStart { runOnUiThread { showOpeningLink() } }
        bridge.onLink { e -> runOnUiThread { if (e.matched) navigate(e.path, e.params) else hideOpeningLink() } }
        // Launched from closed: the intent's URL (or null). Runs the deferred check once per install.
        if (savedInstanceState == null) bridge.start(intent?.dataString)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let(bridge::handleUrl)   // app was running: background / foreground
    }
}
```

`LinkEvent` fields: `id, kind (direct|deferred), route (app_link|custom_scheme|install_referrer|fingerprint),
appState (closed|background|foreground), matched, reason, rawUrl, url, path, params, linkId, ms, at`.
`onLink` replays past events to late subscribers. Both `onLink` and `onLinkStart` return an unsubscribe function.

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
bridge.trackEvent("purchase", value = 49.99, currency = "USD", linkId = lastLinkId)
bridge.reportFingerprint()    // POST /v1/debug/fingerprint, origin "app"
bridge.compareFingerprint()   // GET  /v1/debug/fingerprint → JSONObject
bridge.checkDeferred()        // re-run the deferred check (debug; ignores the once-per-install flag)
```

HTTP defaults to `UrlConnectionTransport` (`HttpURLConnection`). To use OkHttp,
implement `HttpTransport`: throw on network failure and return every HTTP
status as an `HttpResponse`.

**Publishable key:** Dashboard → Get started → Publishable key (`bk_pub_live_…`).
It's safe to include in your app. Never put your secret key (`bk_live_…`) in an app.

## Lower-level helpers (unchanged)

`computeSignature` / `h32` (the cross-language deferred-match signature),
`Bridge.buildMatchBody` / `Bridge.buildReferrerBody` (escaped JSON bodies) and
`parseBridgeLink` (it now also %-decodes the id, as the vectors require).

## Test

```
gradle test     # JDK 17; CI runs the same on Temurin 17
```
