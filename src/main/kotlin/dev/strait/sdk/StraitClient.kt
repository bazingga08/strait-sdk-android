package dev.strait.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/*
 * The Strait link client: a pure-JVM port of sdk-react-native/src/strait.ts.
 * Everything Android-specific (Intents, ProcessLifecycleOwner,
 * InstallReferrerClient, SharedPreferences, display metrics) is injected
 * through StraitConfig, so the logic runs and is tested on a plain JVM.
 * See README.md for the Android wiring.
 */

/** One link the app received, for every case (B9). */
data class LinkEvent(
    /** Unique per open (`newOpenId`); also the id Strait records this open under. */
    val id: String,
    /** "direct" = the app was opened by a link; "deferred" = link tapped before install. */
    val kind: String,
    /** app_link · custom_scheme · install_referrer · fingerprint (see [LinkRoute]). */
    val route: String,
    /** closed · background · foreground (see [AppStateAtLink]). */
    val appState: String,
    val matched: Boolean,
    /** Why it didn't match: not_found, expired, password_protected, network, no_match, invalid_url, ... */
    val reason: String? = null,
    /** The URL the OS gave the app (direct links). */
    val rawUrl: String? = null,
    /** The destination to navigate to. */
    val url: String? = null,
    val path: String? = null,
    val params: Map<String, String>? = null,
    val linkId: String? = null,
    /** Time spent resolving, ms. */
    val ms: Long,
    val at: Long,
)

/**
 * Fired the moment a link arrives, before it's resolved: show an
 * "Opening link..." state until the [LinkEvent] with the same [id] arrives.
 */
data class LinkStart(
    val id: String,
    val kind: String,
    val appState: String,
    val rawUrl: String? = null,
    val at: Long,
)

/** Persistent key/value storage (SharedPreferences on Android). */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

/** In-memory store (tests / when no persistence is supplied). */
class MemoryStore : KeyValueStore {
    val data = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = data[key]
    override fun set(key: String, value: String) { data[key] = value }
}

data class HttpResponse(val status: Int, val body: String?) {
    val ok: Boolean get() = status in 200..299
}

/**
 * Blocking HTTP transport. Throw on network failure (no connection, timeout);
 * return any HTTP status (4xx/5xx included) as an [HttpResponse].
 */
fun interface HttpTransport {
    fun request(url: String, method: String, jsonBody: String?): HttpResponse
}

/** Default transport: java.net.HttpURLConnection (available on Android + JVM). */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
) : HttpTransport {
    override fun request(url: String, method: String, jsonBody: String?): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (jsonBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            return HttpResponse(status, body)
        } finally {
            conn.disconnect()
        }
    }
}

class StraitConfig(
    /** Workspace publishable key (bk_pub_live_...), Dashboard -> Get started. Never the secret key. */
    val publishableKey: String,
    /** Your Strait link host, e.g. https://go.yourbrand.com */
    val endpoint: String,
    /** Extra hosts that serve your short links (custom domains), e.g. "https://go.brand.com". */
    val linkHosts: List<String> = emptyList(),
    /** Persists "deferred check done" and unsent open reports across launches (SharedPreferences). */
    val storage: KeyValueStore = MemoryStore(),
    /** Blocking read of the Play Install Referrer string, or null. Called off the main thread. */
    val installReferrer: () -> String? = { null },
    /** "android" for the Android SDK. */
    val platform: String = "android",
    /** Device fields for the fingerprint match (use browserScreenWidth for screenWidth). */
    val device: () -> DeviceFields,
    val transport: HttpTransport = UrlConnectionTransport(),
    /**
     * Where network work runs. Default runs inline (blocking the caller); on
     * Android pass a background executor so the main thread never blocks.
     */
    val executor: Executor = Executor { it.run() },
    val clock: () -> Long = System::currentTimeMillis,
)

private const val DEFERRED_FLAG = "strait.deferredChecked"
private const val QUEUE_KEY = "strait.pendingOpens"
private const val TAP_KEY = "strait.lastTap"

/**
 * Strait link client (contract B1-B15). Never throws from link handling.
 * Listeners are called on the thread that resolved the link (the [StraitConfig.executor]).
 */
class StraitClient(private val config: StraitConfig) {
    private val base = config.endpoint.trimEnd('/')
    private val linkHosts: List<String> = normalizeLinkHosts(base, config.linkHosts)
    private val events = ArrayList<LinkEvent>()
    private val listeners = CopyOnWriteArrayList<(LinkEvent) -> Unit>()
    private val startListeners = CopyOnWriteArrayList<(LinkStart) -> Unit>()
    private val tracker = AppStateTracker()
    private val queueLock = Any()
    private val flushing = AtomicBoolean(false)
    @Volatile private var stopped = false

    /**
     * Handle the launch link (Activity.onCreate intent data, or null), then run
     * the deferred check once per install (B6), then send saved open reports
     * (B14). Work runs on the executor.
     */
    fun start(initialUrl: String?) {
        stopped = false
        val initial = initialUrl?.takeIf { it.isNotBlank() }
        config.executor.execute {
            safely {
                // Unreadable storage counts as "already checked": never risk a stale
                // deferred jump on every launch. Write failures are ignored (never throw).
                val firstLaunch = runCatching { config.storage.get(DEFERRED_FLAG) }.getOrElse { "1" } != "1"
                if (initial != null) {
                    // Opened by a link on first launch = the user's intent right now: no
                    // deferred check, but this open still counts as the install's first.
                    if (firstLaunch) runCatching { config.storage.set(DEFERRED_FLAG, "1") }
                    resolveDirect(initial, AppStateAtLink.CLOSED, firstLaunch)
                } else if (firstLaunch) {
                    // Marked done only once the engine answered: offline -> next launch.
                    val e = runDeferred(record = true)
                    if (e.reason != "network") runCatching { config.storage.set(DEFERRED_FLAG, "1") }
                }
            }
            flush()
        }
    }

    /**
     * A URL delivered while the app is running (Activity.onNewIntent). The app
     * state is classified now, at delivery; resolving runs on the executor.
     */
    fun handleUrl(raw: String) {
        if (stopped) return
        val appState = tracker.classify(config.clock())
        config.executor.execute { safely { resolveDirect(raw, appState) } }
    }

    /**
     * Lifecycle hook (ProcessLifecycleOwner / Activity onResume/onPause).
     * Becoming active also sends saved open reports (on the executor).
     */
    fun onAppState(state: AppLifecycleState, now: Long = config.clock()) {
        if (stopped) return
        tracker.onState(state, now)
        if (state == AppLifecycleState.ACTIVE) config.executor.execute { flush() }
    }

    /** Every link event, including ones that happened before you subscribed. Returns an unsubscribe. */
    fun onLink(listener: (LinkEvent) -> Unit): () -> Unit {
        val past = synchronized(events) {
            listeners.add(listener)
            events.toList()
        }
        for (e in past) safely { listener(e) }
        return { listeners.remove(listener) }
    }

    /** A link just arrived and is being resolved (for a loading state). Returns an unsubscribe. */
    fun onLinkStart(listener: (LinkStart) -> Unit): () -> Unit {
        startListeners.add(listener)
        return { startListeners.remove(listener) }
    }

    /**
     * Re-run the deferred check now (blocking; debugging). Doesn't touch the
     * once-per-install flag and sends no openId, so it never adds installs.
     */
    fun checkDeferred(): LinkEvent = runDeferred(record = false)

    /** Send this app's fingerprint to the engine (blocking). Empty object on failure. */
    fun reportFingerprint(): JSONObject = try {
        val body = JSONObject().put("publishableKey", config.publishableKey).put("origin", "app")
        putDevice(body, config.device())
        call("POST", "/v1/debug/fingerprint", body).second
    } catch (_: Exception) {
        JSONObject()
    }

    /** Engine's comparison of the app and browser fingerprints on this network (blocking). */
    fun compareFingerprint(): JSONObject = try {
        call("GET", "/v1/debug/fingerprint?publishableKey=${URLEncoder.encode(config.publishableKey, "UTF-8")}", null).second
    } catch (_: Exception) {
        JSONObject()
    }

    /**
     * Conversion / revenue event (blocking). True when accepted. Carries the tap
     * id of the last attributed link open (<=7 days, contract B15) unless you
     * pass [clickId] yourself.
     */
    fun trackEvent(
        name: String, value: Double? = null, currency: String? = null, linkId: String? = null, clickId: String? = null,
    ): Boolean = try {
        val body = JSONObject()
            .put("publishableKey", config.publishableKey)
            .put("event", name)
            .put("platform", config.platform)
        if (value != null) body.put("value", value)
        if (currency != null) body.put("currency", currency)
        if (linkId != null) body.put("linkId", linkId)
        val stored = runCatching { config.storage.get(TAP_KEY) }.getOrNull()
        eventClickId(stored, config.clock(), clickId)?.let { body.put("clickId", it) }
        call("POST", "/v1/event", body).first.ok
    } catch (_: Exception) {
        false
    }

    /** Open reports saved while offline, waiting to be sent (blocking; debugging). */
    fun pendingOpenReports(): Int = synchronized(queueLock) { readQueue().size }

    /** Send saved open reports now (blocking; also happens on start and on resume). */
    fun flushOpenReports() = flush()

    /** Stop handling new URLs and lifecycle changes. Already-emitted events stay replayable. */
    fun stop() {
        stopped = true
    }

    // --- internals ---------------------------------------------------------

    private fun announce(s: LinkStart) {
        for (cb in startListeners) safely { cb(s) }
    }

    private fun emit(e: LinkEvent): LinkEvent {
        val targets = synchronized(events) {
            events.add(e)
            listeners.toList()
        }
        for (cb in targets) safely { cb(e) }
        return e
    }

    private fun call(method: String, path: String, body: JSONObject?): Pair<HttpResponse, JSONObject> {
        val res = config.transport.request("$base$path", method, body?.toString())
        val json = try {
            JSONObject(res.body ?: "")
        } catch (_: Exception) {
            JSONObject()
        }
        return res to json
    }

    // --- remembered tap (B15): the tap id of the last attributed link open,
    // sent with conversion events. An attributed open without a known tap id
    // (short link, fingerprint match) forgets it: the newer touch wins.
    // Storage failures are ignored.
    private fun noteTap(clickId: String?, at: Long) {
        runCatching { config.storage.set(TAP_KEY, if (clickId != null) rememberTap(clickId, at) else "") }
    }

    // --- open reports (B14): every open is reported once; failures are saved
    // and retried. Queue read-modify-writes hold queueLock (no lost writes).

    private fun readQueue(): List<JSONObject> = try {
        val a = JSONArray(config.storage.get(QUEUE_KEY) ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeQueue(q: List<JSONObject>) {
        val a = JSONArray()
        for (r in q) a.put(r)
        runCatching { config.storage.set(QUEUE_KEY, a.toString()) }
    }

    private fun enqueue(report: JSONObject) = synchronized(queueLock) {
        writeQueue(pruneOpenQueue(readQueue() + report, config.clock()) { it.optLong("at") })
    }

    /** POST /v1/open; the HTTP status, or null when there was no answer. */
    private fun sendReport(report: JSONObject): Int? = try {
        val body = JSONObject(report.toString()).put("publishableKey", config.publishableKey)
        call("POST", "/v1/open", body).first.status
    } catch (_: Exception) {
        null
    }

    /** Report an open now; keep it for retry if it doesn't get through. */
    private fun report(rep: JSONObject) {
        if (shouldRetryReport(sendReport(rep))) enqueue(rep)
        else flush() // the network works: send anything saved earlier
    }

    private fun flush() {
        if (!flushing.compareAndSet(false, true)) return
        try {
            synchronized(queueLock) {
                val queue = pruneOpenQueue(readQueue(), config.clock()) { it.optLong("at") }
                val keep = ArrayList<JSONObject>()
                var offline = false
                for (rep in queue) {
                    // Once one gets no answer at all, keep the rest for later.
                    if (offline) {
                        keep += rep; continue
                    }
                    val status = sendReport(rep)
                    offline = status == null
                    if (shouldRetryReport(status)) keep += rep
                }
                writeQueue(keep)
            }
        } catch (_: Exception) {
            /* B10 */
        } finally {
            flushing.set(false)
        }
    }

    private fun openReport(
        id: String, route: String, appState: String, url: String, matched: Boolean, firstLaunch: Boolean, at: Long,
        clickId: String? = null, linkId: String? = null, reason: String? = null,
    ): JSONObject {
        val r = JSONObject()
            .put("openId", id).put("kind", "direct").put("route", route).put("appState", appState)
            .put("platform", config.platform).put("url", url).put("matched", matched)
            .put("firstLaunch", firstLaunch).put("at", at)
        if (clickId != null) r.put("clickId", clickId)
        if (linkId != null) r.put("linkId", linkId)
        if (reason != null) r.put("reason", reason)
        return r
    }

    private fun resolveDirect(raw: String, appState: String, firstLaunch: Boolean = false): LinkEvent {
        val t0 = config.clock()
        val id = newOpenId(t0)
        announce(LinkStart(id, "direct", appState, raw, t0))
        val c = classifyUrl(raw, linkHosts)
            ?: return emit(LinkEvent(id, "direct", LinkRoute.APP_LINK, appState, false, reason = "invalid_url",
                rawUrl = raw, ms = config.clock() - t0, at = t0))
        if (!c.needsResolve) {
            if (c.clickId != null) noteTap(c.clickId, t0)
            val e = emit(LinkEvent(id, "direct", c.route, appState, true, rawUrl = raw, url = c.url, path = c.path,
                params = c.params, ms = config.clock() - t0, at = t0))
            // Navigation never waits for the report: it is sent after the event.
            safely { report(openReport(id, c.route, appState, c.url!!, true, firstLaunch, t0, clickId = c.clickId)) }
            return e
        }
        // The lookup is also the open report (openId); the engine says whether
        // it recorded it, and anything short of that is retried via /v1/open.
        return try {
            val body = JSONObject()
                .put("publishableKey", config.publishableKey)
                .put("url", raw)
                .put("platform", config.platform)
                .put("openId", id)
                .put("appState", appState)
                .put("firstLaunch", firstLaunch)
                .put("at", t0)
            val json = call("POST", "/v1/resolve", body).second
            val matched = json.opt("matched") == true
            val reason = if (matched) null else (str(json, "reason") ?: str(json, "error"))
            val linkId = str(json, "linkId")
            val dest = destination(if (matched) str(json, "longUrl") else null)
            if (matched) noteTap(null, t0)
            val e = emit(LinkEvent(id, "direct", LinkRoute.APP_LINK, appState, matched,
                reason = reason, rawUrl = raw, url = dest.url, path = dest.path, params = dest.params,
                linkId = linkId, ms = config.clock() - t0, at = t0))
            if (json.opt("recorded") != true) {
                safely { report(openReport(id, LinkRoute.APP_LINK, appState, raw, matched, firstLaunch, t0, linkId = linkId, reason = reason)) }
            }
            e
        } catch (_: Exception) {
            safely { enqueue(openReport(id, LinkRoute.APP_LINK, appState, raw, false, firstLaunch, t0, reason = "network")) }
            emit(LinkEvent(id, "direct", LinkRoute.APP_LINK, appState, false, reason = "network",
                rawUrl = raw, ms = config.clock() - t0, at = t0))
        }
    }

    /**
     * The deferred check. [record] (the once-per-install run) sends the openId so
     * the engine records this first open + install exactly once; the debug
     * re-check doesn't, so it never adds installs.
     */
    private fun runDeferred(record: Boolean): LinkEvent {
        val t0 = config.clock()
        val id = newOpenId(t0)
        announce(LinkStart(id, "deferred", AppStateAtLink.CLOSED, null, t0))
        fun tag(body: JSONObject): JSONObject = if (record) body.put("openId", id).put("at", t0) else body
        // No answer, 429 or 5xx = try again next launch (reported as 'network').
        fun answered(path: String, body: JSONObject): JSONObject {
            val (res, json) = call("POST", path, body)
            if (shouldRetryReport(res.status)) throw java.io.IOException("HTTP ${res.status}")
            return json
        }
        return try {
            if (config.platform == "android") {
                val referrer = try { config.installReferrer() } catch (_: Exception) { null }
                val linkId = parseStraitLink(referrer)
                if (linkId != null) {
                    val body = JSONObject()
                        .put("publishableKey", config.publishableKey)
                        .put("linkId", linkId)
                        .put("platform", "android")
                    val clickId = parseStraitClick(referrer)
                    clickId?.let { body.put("clickId", it) }
                    val json = answered("/v1/referrer", tag(body))
                    if (json.opt("matched") == true) {
                        if (record) noteTap(clickId, t0)
                        val dest = destination(str(json, "longUrl"))
                        return emit(LinkEvent(id, "deferred", LinkRoute.INSTALL_REFERRER, AppStateAtLink.CLOSED, true,
                            url = dest.url, path = dest.path, params = dest.params,
                            linkId = str(json, "linkId") ?: linkId, ms = config.clock() - t0, at = t0))
                    }
                }
            }
            val body = JSONObject().put("publishableKey", config.publishableKey).put("platform", config.platform)
            putDevice(body, config.device())
            val json = answered("/v1/match", tag(body))
            val matched = json.opt("matched") == true
            if (record && matched) noteTap(null, t0)
            val dest = destination(if (matched) str(json, "longUrl") else null)
            emit(LinkEvent(id, "deferred", LinkRoute.FINGERPRINT, AppStateAtLink.CLOSED, matched,
                reason = if (matched) null else "no_match", url = dest.url, path = dest.path, params = dest.params,
                linkId = str(json, "linkId"), ms = config.clock() - t0, at = t0))
        } catch (_: Exception) {
            emit(LinkEvent(id, "deferred", LinkRoute.FINGERPRINT, AppStateAtLink.CLOSED, false, reason = "network",
                ms = config.clock() - t0, at = t0))
        }
    }

    private class Dest(val url: String? = null, val path: String? = null, val params: Map<String, String>? = null)

    private fun destination(url: String?): Dest {
        if (url == null) return Dest()
        val p = splitUrl(url) ?: return Dest(url)
        return Dest(url, p.path, p.params)
    }

    private fun putDevice(body: JSONObject, d: DeviceFields) {
        body.put("screenWidth", d.screenWidth)
            .put("pixelRatio", d.pixelRatio)
            .put("language", d.language)
            .put("timezone", d.timezone)
    }

    private fun str(json: JSONObject, key: String): String? {
        val v = json.opt(key)
        return if (v is String) v else null
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (_: Exception) { /* B10: never throw from link handling */ }
    }
}
