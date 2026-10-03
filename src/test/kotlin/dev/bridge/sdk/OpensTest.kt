package dev.bridge.sdk

import org.json.JSONObject
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of sdk-react-native/test/opens.test.ts. Contract B14: every link open is
 * reported exactly once, retried until it gets through, and never delays
 * navigation. Plus the B6/B7 revisions.
 */
class OpensTest {
    private val pk = "bk_pub_test_appowner01"
    private val endpoint = "https://links.test"
    private val click = "3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f"
    private val openIdRe = Regex("^o_[a-z0-9]+_[a-z0-9]{12}$")
    private val device = DeviceFields(411, 2.625, "en", "Asia/Kolkata")

    private sealed class Reply {
        data class Status(val status: Int = 200, val body: JSONObject = JSONObject()) : Reply()
        object Offline : Reply()
        class Hang(val release: CountDownLatch) : Reply()
    }

    private data class Call(val path: String, val body: JSONObject?)

    /** Fake engine whose answer per path can change mid-test. */
    private class FakeEngine(vararg routes: Pair<String, Reply>) : HttpTransport {
        val routes = mutableMapOf(*routes)
        val calls = java.util.Collections.synchronizedList(mutableListOf<Call>())
        fun of(path: String) = synchronized(calls) { calls.filter { it.path == path } }
        override fun request(url: String, method: String, jsonBody: String?): HttpResponse {
            val path = URI(url).path
            calls += Call(path, jsonBody?.let { JSONObject(it) })
            return when (val r = routes[path] ?: Reply.Status(404, JSONObject().put("error", "not found"))) {
                is Reply.Offline -> throw java.io.IOException("Network request failed")
                is Reply.Hang -> {
                    r.release.await(10, TimeUnit.SECONDS); throw java.io.IOException("timeout")
                }
                is Reply.Status -> HttpResponse(r.status, r.body.toString())
            }
        }
    }

    private inner class Harness(
        val engine: FakeEngine,
        val storage: MemoryStore = MemoryStore(),
        referrer: String? = null,
        platform: String = "android",
        executor: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() },
    ) {
        var t = 1_800_000_000_000L
        val client = BridgeClient(
            BridgeConfig(
                publishableKey = pk,
                endpoint = endpoint,
                storage = storage,
                installReferrer = { referrer },
                platform = platform,
                device = { device },
                transport = engine,
                executor = executor,
                clock = { t },
            ),
        )
        val events = java.util.Collections.synchronizedList(mutableListOf<LinkEvent>())
        init { client.onLink { events += it } }
        fun setState(s: AppLifecycleState) = client.onAppState(s)
    }

    private val resolved = Reply.Status(body = JSONObject().put("matched", true)
        .put("longUrl", "https://shop.example/p/42").put("linkId", "lnk_42").put("slug", "sale").put("recorded", true))
    private val accepted = Reply.Status(202, JSONObject().put("ok", true).put("duplicate", false))
    private val noMatch = Reply.Status(body = JSONObject().put("matched", false).put("matchMethod", "none"))

    /** Not the first launch. */
    private fun returning() = MemoryStore().apply { set("bridge.deferredChecked", "1") }

    private fun assertBody(expected: Map<String, Any>, body: JSONObject) {
        for ((k, v) in expected) assertEquals(v, body.opt(k), "body.$k in $body")
    }

    // --- browser hand-off (custom scheme) with a tap id ---------------------

    @Test
    fun handOffReportsTapIdAppNeverSeesIt() {
        val h = Harness(FakeEngine("/v1/open" to accepted), returning())
        h.client.start(null)
        h.setState(AppLifecycleState.BACKGROUND); h.t += 5000; h.setState(AppLifecycleState.ACTIVE); h.t += 200
        h.client.handleUrl("bridgelink://shop.example/p/42?color=red&bridge_click=$click")
        val e = h.events.last()
        assertEquals("custom_scheme", e.route)
        assertEquals("https://shop.example/p/42?color=red", e.url)
        assertEquals(mapOf("color" to "red"), e.params)
        assertEquals("background", e.appState)
        assertTrue(openIdRe.matches(e.id), e.id)
        assertEquals(1, h.engine.of("/v1/open").size)
        assertBody(mapOf(
            "publishableKey" to pk, "openId" to e.id, "kind" to "direct", "route" to "custom_scheme",
            "appState" to "background", "platform" to "android", "url" to "https://shop.example/p/42?color=red",
            "clickId" to click, "matched" to true, "firstLaunch" to false,
        ), h.engine.of("/v1/open")[0].body!!)
    }

    @Test
    fun navigationNeverWaitsForTheReport() {
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val h = Harness(FakeEngine("/v1/open" to Reply.Hang(release)), returning(), executor = pool)
            val got = CountDownLatch(1)
            h.client.onLink { got.countDown() }
            h.client.start("bridgelink://shop.example/p/1?bridge_click=$click")
            assertTrue(got.await(5, TimeUnit.SECONDS), "event emitted while the report hangs")
            assertEquals(1, h.events.size)
            assertEquals("https://shop.example/p/1", h.events[0].url)
        } finally {
            release.countDown()
            pool.shutdown()
        }
    }

    @Test
    fun ownHttpsLinkIsReportedWithoutTapId() {
        val h = Harness(FakeEngine("/v1/open" to accepted), returning())
        h.client.start("https://shop.example/p/9")
        val body = h.engine.of("/v1/open")[0].body!!
        assertBody(mapOf("route" to "app_link", "url" to "https://shop.example/p/9", "appState" to "closed"), body)
        assertFalse(body.has("clickId"))
    }

    // --- short link: the lookup is the report -------------------------------

    @Test
    fun resolveCarriesOpenIdAndNothingElseOnceRecorded() {
        val h = Harness(FakeEngine("/v1/resolve" to resolved, "/v1/open" to accepted), returning())
        h.client.start("https://links.test/sale")
        val e = h.events[0]
        assertBody(mapOf("openId" to e.id, "appState" to "closed", "firstLaunch" to false, "at" to e.at),
            h.engine.of("/v1/resolve")[0].body!!)
        assertEquals(0, h.engine.of("/v1/open").size)
    }

    @Test
    fun answeredButNotRecordedIsRetriedViaOpen() {
        val notRecorded = Reply.Status(body = JSONObject(resolved.body.toString()).put("recorded", false))
        val h = Harness(FakeEngine("/v1/resolve" to notRecorded, "/v1/open" to accepted), returning())
        h.client.start("https://links.test/sale")
        val e = h.events[0]
        assertTrue(e.matched)
        assertBody(mapOf("openId" to e.id, "route" to "app_link", "url" to "https://links.test/sale",
            "matched" to true, "linkId" to "lnk_42"), h.engine.of("/v1/open")[0].body!!)
    }

    @Test
    fun offlineShortLinkIsSavedThenSentOnResume() {
        val engine = FakeEngine("/v1/resolve" to Reply.Offline, "/v1/open" to Reply.Offline)
        val h = Harness(engine, returning())
        h.client.start(null)
        h.client.handleUrl("https://links.test/sale")
        val e = h.events.last()
        assertFalse(e.matched)
        assertEquals("network", e.reason)
        assertEquals(1, h.client.pendingOpenReports())
        // network returns; user leaves and comes back
        engine.routes["/v1/open"] = accepted
        h.setState(AppLifecycleState.BACKGROUND); h.t += 10_000; h.setState(AppLifecycleState.ACTIVE)
        val sent = engine.of("/v1/open").filter { it.body!!.optString("openId") == e.id }
        assertBody(mapOf("route" to "app_link", "url" to "https://links.test/sale", "matched" to false, "reason" to "network"),
            sent.last().body!!)
        assertEquals(0, h.client.pendingOpenReports())
    }

    // --- the retry queue ----------------------------------------------------

    @Test
    fun keepsOn5xxAnd429DropsOn4xx() {
        val engine = FakeEngine("/v1/open" to Reply.Status(503))
        val h = Harness(engine, returning())
        h.client.start(null)
        h.client.handleUrl("bridgelink://a.b/1")
        assertEquals(1, h.client.pendingOpenReports())
        engine.routes["/v1/open"] = Reply.Status(429)
        h.client.flushOpenReports()
        assertEquals(1, h.client.pendingOpenReports())
        engine.routes["/v1/open"] = Reply.Status(400, JSONObject().put("error", "bad"))
        h.client.flushOpenReports()
        assertEquals(0, h.client.pendingOpenReports())
    }

    @Test
    fun queueSurvivesRestartAndIsSentOnNextStart() {
        val storage = returning()
        val first = Harness(FakeEngine("/v1/open" to Reply.Offline), storage)
        first.client.start(null)
        first.client.handleUrl("bridgelink://a.b/1")
        first.client.handleUrl("bridgelink://a.b/2")
        assertEquals(2, first.client.pendingOpenReports())
        first.client.stop()

        val second = Harness(FakeEngine("/v1/open" to accepted), storage)
        second.client.start(null)
        assertEquals(listOf("https://a.b/1", "https://a.b/2"), second.engine.of("/v1/open").map { it.body!!.getString("url") })
        assertEquals(0, second.client.pendingOpenReports())
    }

    @Test
    fun successfulReportAlsoSendsEarlierOnes() {
        val engine = FakeEngine("/v1/open" to Reply.Offline)
        val h = Harness(engine, returning())
        h.client.start(null)
        h.client.handleUrl("bridgelink://a.b/old")
        engine.routes["/v1/open"] = accepted
        h.client.handleUrl("bridgelink://a.b/new")
        assertEquals(0, h.client.pendingOpenReports())
        val old = engine.of("/v1/open").filter { it.body!!.getString("url") == "https://a.b/old" }
        assertEquals(1, old.map { it.body!!.getString("openId") }.toSet().size)
    }

    @Test
    fun everyOpenHasItsOwnId() {
        val h = Harness(FakeEngine("/v1/open" to accepted), returning())
        h.client.start(null)
        for (i in 0 until 5) h.client.handleUrl("bridgelink://a.b/$i")
        assertEquals(5, h.events.map { it.id }.toSet().size)
    }

    // --- first launch and the deferred check (B6/B7 revised) ----------------

    @Test
    fun firstLaunchOpenedByLinkCountsAsInstall() {
        val h = Harness(FakeEngine("/v1/resolve" to resolved))
        h.client.start("https://links.test/sale")
        assertEquals(true, h.engine.of("/v1/resolve")[0].body!!.opt("firstLaunch"))
        assertEquals(0, h.engine.of("/v1/referrer").size)
        assertEquals(0, h.engine.of("/v1/match").size)
        assertEquals("1", h.storage.get("bridge.deferredChecked"))
    }

    @Test
    fun playReferrerSendsTapIdAndOpenId() {
        val hit = Reply.Status(body = JSONObject().put("matched", true).put("longUrl", "https://shop.example/p/42")
            .put("linkId", "lnk_42").put("matchMethod", "install_referrer"))
        val h = Harness(FakeEngine("/v1/referrer" to hit), referrer = "utm_source=google-play&bridge_link=lnk_42&bridge_click=$click")
        h.client.start(null)
        val e = h.events[0]
        assertBody(mapOf("linkId" to "lnk_42", "clickId" to click, "openId" to e.id, "at" to e.at, "platform" to "android"),
            h.engine.of("/v1/referrer")[0].body!!)
        assertEquals("deferred", e.kind)
        assertEquals("install_referrer", e.route)
        assertTrue(e.matched)
    }

    @Test
    fun fingerprintMatchSendsOpenId() {
        val h = Harness(FakeEngine("/v1/match" to noMatch), platform = "ios")
        h.client.start(null)
        val body = h.engine.of("/v1/match")[0].body!!
        assertBody(mapOf("openId" to h.events[0].id, "platform" to "ios", "screenWidth" to 411,
            "language" to "en", "timezone" to "Asia/Kolkata"), body)
        assertEquals(2.625, body.getDouble("pixelRatio"))
    }

    @Test
    fun offlineDeferredIsNotMarkedDone() {
        val storage = MemoryStore()
        val first = Harness(FakeEngine("/v1/match" to Reply.Offline), storage)
        first.client.start(null)
        assertEquals("deferred", first.events[0].kind)
        assertEquals("network", first.events[0].reason)
        assertNull(storage.get("bridge.deferredChecked"))

        val second = Harness(FakeEngine("/v1/match" to noMatch), storage)
        second.client.start(null)
        assertEquals(1, second.engine.of("/v1/match").size)
        assertEquals("1", storage.get("bridge.deferredChecked"))

        val third = Harness(FakeEngine("/v1/match" to noMatch), storage)
        third.client.start(null)
        assertEquals(0, third.engine.of("/v1/match").size) // once per install
    }

    @Test
    fun serverErrorCountsAsNotAnswered() {
        val storage = MemoryStore()
        val h = Harness(FakeEngine("/v1/match" to Reply.Status(502)), storage)
        h.client.start(null)
        assertEquals("network", h.events[0].reason)
        assertNull(storage.get("bridge.deferredChecked"))
    }

    @Test
    fun debugRecheckNeverRecordsAnInstall() {
        val h = Harness(FakeEngine("/v1/match" to noMatch), returning())
        h.client.start(null)
        h.client.checkDeferred()
        assertEquals(1, h.engine.of("/v1/match").size)
        val body = h.engine.of("/v1/match")[0].body!!
        assertFalse(body.has("openId"))
        assertFalse(body.has("at"))
    }
}
