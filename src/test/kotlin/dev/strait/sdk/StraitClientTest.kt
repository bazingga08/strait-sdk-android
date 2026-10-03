package dev.strait.sdk

import org.json.JSONObject
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of sdk-react-native/test/strait.test.ts with a fake engine + clock. */
class StraitClientTest {
    private val pk = "bk_pub_test_appowner01"
    private val endpoint = "https://links.test"
    private val device = DeviceFields(411, 2.625, "en", "Asia/Kolkata")

    private data class Call(val method: String, val path: String, val body: JSONObject?)

    /** Fake engine: routes by path; unknown path -> 404; "throw" -> network failure. */
    private class FakeEngine(val routes: Map<String, Any>) : HttpTransport {
        val calls = mutableListOf<Call>()
        override fun request(url: String, method: String, jsonBody: String?): HttpResponse {
            val path = URI(url).path
            calls += Call(method, path, jsonBody?.let { JSONObject(it) })
            val payload = routes[path] ?: return HttpResponse(404, "{\"error\":\"not_found\"}")
            if (payload == "throw") throw java.io.IOException("offline")
            return HttpResponse(200, payload.toString())
        }
    }

    private inner class Harness(
        initialReferrer: String? = null,
        routes: Map<String, Any> = emptyMap(),
        val storage: MemoryStore = MemoryStore(),
    ) {
        var t = 1_000_000L
        val engine = FakeEngine(routes)
        val client = StraitClient(
            StraitConfig(
                publishableKey = pk,
                endpoint = endpoint,
                storage = storage,
                installReferrer = { initialReferrer },
                device = { device },
                transport = engine,
                clock = { t },
            ),
        )
        val events = mutableListOf<LinkEvent>()
        init { client.onLink { events += it } }
        fun setState(s: AppLifecycleState) = client.onAppState(s)
    }

    private val resolved = mapOf(
        "/v1/resolve" to JSONObject()
            .put("matched", true).put("longUrl", "https://shop.example/p/42?color=red")
            .put("linkId", "lnk_42").put("slug", "sale"),
    )
    private val referrerHit = mapOf(
        "/v1/referrer" to JSONObject()
            .put("matched", true).put("longUrl", "https://shop.example/promo/DIWALI20")
            .put("linkId", "lnk_7").put("matchMethod", "install_referrer"),
    )

    // --- direct links -------------------------------------------------------

    @Test
    fun closedVerifiedLinkResolvesShortUrl() {
        val h = Harness(routes = resolved)
        h.client.start("https://links.test/sale")
        val e = h.events.first()
        assertEquals("direct", e.kind)
        assertEquals("app_link", e.route)
        assertEquals("closed", e.appState)
        assertTrue(e.matched)
        assertEquals("https://links.test/sale", e.rawUrl)
        assertEquals("https://shop.example/p/42?color=red", e.url)
        assertEquals("/p/42", e.path)
        assertEquals(mapOf("color" to "red"), e.params)
        assertEquals("lnk_42", e.linkId)
        assertEquals(1, h.events.size)
        val body = h.engine.calls.first { it.path == "/v1/resolve" }.body!!
        assertEquals(pk, body.getString("publishableKey"))
        assertEquals("https://links.test/sale", body.getString("url"))
        assertEquals("android", body.getString("platform"))
        assertFalse(body.has("appId"))
    }

    @Test
    fun backgroundResumeThenLink() {
        val h = Harness(routes = resolved)
        h.client.start(null)
        h.setState(AppLifecycleState.BACKGROUND)
        h.t += 60_000
        h.setState(AppLifecycleState.ACTIVE)
        h.t += 300
        h.client.handleUrl("https://links.test/sale")
        val e = h.events.last()
        assertEquals("direct", e.kind)
        assertEquals("background", e.appState)
        assertTrue(e.matched)
    }

    @Test
    fun backgroundLinkBeforeResume() {
        val h = Harness(routes = resolved)
        h.client.start(null)
        h.setState(AppLifecycleState.BACKGROUND)
        h.t += 60_000
        h.client.handleUrl("https://links.test/sale") // onNewIntent fires before onResume
        h.setState(AppLifecycleState.ACTIVE)
        assertEquals("background", h.events.last().appState)
    }

    @Test
    fun briefDeliveryPauseIsForeground() {
        val h = Harness(routes = resolved)
        h.client.start(null)
        h.t += 30_000
        h.setState(AppLifecycleState.BACKGROUND) // onPause caused by the incoming intent
        h.t += 40
        h.client.handleUrl("https://links.test/sale")
        h.t += 30
        h.setState(AppLifecycleState.ACTIVE)
        assertEquals("foreground", h.events.last().appState)
    }

    @Test
    fun onScreenIsForeground() {
        val h = Harness(routes = resolved)
        h.client.start(null)
        h.t += 30_000
        h.client.handleUrl("https://links.test/sale")
        assertEquals("foreground", h.events.last().appState)
    }

    @Test
    fun customSchemeCarriesDestinationNoNetwork() {
        val h = Harness()
        h.client.start("straitlink://shop.example/p/42?color=red")
        val e = h.events.first()
        assertEquals("custom_scheme", e.route)
        assertEquals("closed", e.appState)
        assertTrue(e.matched)
        assertEquals("https://shop.example/p/42?color=red", e.url)
        assertEquals("/p/42", e.path)
        assertEquals(mapOf("color" to "red"), e.params)
        assertTrue(h.engine.calls.none { it.path == "/v1/resolve" })
    }

    @Test
    fun expiredShortLinkIsReported() {
        val h = Harness(routes = mapOf("/v1/resolve" to JSONObject().put("matched", false).put("reason", "expired")))
        h.client.start("https://links.test/old")
        val e = h.events.first()
        assertEquals("app_link", e.route)
        assertFalse(e.matched)
        assertEquals("expired", e.reason)
        assertNull(e.url)
    }

    @Test
    fun networkFailureIsReportedNotThrown() {
        val h = Harness(routes = mapOf("/v1/resolve" to "throw", "/v1/match" to "throw"))
        h.client.start("https://links.test/sale")
        h.client.handleUrl("https://links.test/sale")
        assertEquals(listOf("network", "network"), h.events.map { it.reason })
        assertTrue(h.events.none { it.matched })
        assertEquals("network", h.client.checkDeferred().reason)
    }

    @Test
    fun invalidUrlIsReported() {
        val h = Harness()
        h.client.handleUrl("not a url")
        assertEquals("invalid_url", h.events.single().reason)
    }

    @Test
    fun lateSubscribersGetReplay() {
        val h = Harness()
        h.client.start("straitlink://shop.example/cart")
        val late = mutableListOf<LinkEvent>()
        h.client.onLink { late += it }
        assertEquals(1, late.size)
        assertEquals("/cart", late[0].path)
    }

    @Test
    fun linkStartFiresBeforeEventWithSameId() {
        val h = Harness(routes = resolved)
        val order = mutableListOf<String>()
        h.client.onLinkStart { order += "start:${it.id}:${it.kind}:${it.appState}:${it.rawUrl}" }
        h.client.onLink { order += "event:${it.id}" }
        h.t += 30_000
        h.client.handleUrl("https://links.test/sale")
        val id = h.events.last().id
        assertEquals(listOf("start:$id:direct:foreground:https://links.test/sale", "event:$id"), order)
    }

    @Test
    fun unsubscribeStopsDelivery() {
        val h = Harness()
        val got = mutableListOf<LinkEvent>()
        val off = h.client.onLink { got += it }
        off()
        h.client.handleUrl("straitlink://shop.example/cart")
        assertTrue(got.isEmpty())
        assertEquals(1, h.events.size)
    }

    @Test
    fun throwingListenerDoesNotBreakOthers() {
        val h = Harness()
        h.client.onLink { throw IllegalStateException("app bug") }
        val got = mutableListOf<LinkEvent>()
        h.client.onLink { got += it }
        h.client.handleUrl("straitlink://shop.example/cart")
        assertEquals(1, got.size)
    }

    @Test
    fun stopIgnoresNewUrls() {
        val h = Harness()
        h.client.stop()
        h.client.handleUrl("straitlink://shop.example/cart")
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun customDomainLinkHostIsResolved() {
        val engine = FakeEngine(resolved)
        val client = StraitClient(StraitConfig(pk, endpoint, linkHosts = listOf("https://go.brand.com", "Short.Brand.com"),
            device = { device }, transport = engine))
        val got = mutableListOf<LinkEvent>()
        client.onLink { got += it }
        client.handleUrl("https://GO.brand.com/promo")
        client.handleUrl("https://short.brand.com/x")
        assertEquals(2, engine.calls.count { it.path == "/v1/resolve" })
        assertTrue(got.all { it.matched })
    }

    // --- deferred -----------------------------------------------------------

    @Test
    fun firstLaunchInstallReferrer() {
        val h = Harness(initialReferrer = "utm_source=google-play&strait_link=lnk_7", routes = referrerHit)
        h.client.start(null)
        val e = h.events.first()
        assertEquals("deferred", e.kind)
        assertEquals("install_referrer", e.route)
        assertEquals("closed", e.appState)
        assertTrue(e.matched)
        assertEquals("https://shop.example/promo/DIWALI20", e.url)
        assertEquals("/promo/DIWALI20", e.path)
        assertEquals("lnk_7", e.linkId)
        val body = h.engine.calls.first { it.path == "/v1/referrer" }.body!!
        assertEquals(pk, body.getString("publishableKey"))
        assertEquals("lnk_7", body.getString("linkId"))
        assertTrue(h.engine.calls.none { it.path == "/v1/match" })
    }

    @Test
    fun deferredRunsOncePerInstall() {
        val storage = MemoryStore()
        Harness("strait_link=lnk_7", referrerHit, storage).client.start(null)
        val second = Harness("strait_link=lnk_7", referrerHit, storage)
        second.client.start(null)
        assertTrue(second.events.none { it.kind == "deferred" })
        assertEquals("1", storage.get("strait.deferredChecked"))
    }

    @Test
    fun noReferrerLinkFallsBackToFingerprint() {
        val h = Harness("utm_source=google-play&utm_medium=organic",
            mapOf("/v1/match" to JSONObject().put("matched", false).put("matchMethod", "none")))
        h.client.start(null)
        val e = h.events.first()
        assertEquals("deferred", e.kind)
        assertEquals("fingerprint", e.route)
        assertFalse(e.matched)
        assertEquals("no_match", e.reason)
        val body = h.engine.calls.first { it.path == "/v1/match" }.body!!
        assertEquals(pk, body.getString("publishableKey"))
        assertEquals("android", body.getString("platform"))
        assertEquals(411, body.getInt("screenWidth"))
        assertEquals(2.625, body.getDouble("pixelRatio"))
        assertEquals("en", body.getString("language"))
        assertEquals("Asia/Kolkata", body.getString("timezone"))
    }

    @Test
    fun referrerMissFallsBackToFingerprintMatch() {
        val h = Harness("strait_link=lnk_gone", mapOf(
            "/v1/referrer" to JSONObject().put("matched", false),
            "/v1/match" to JSONObject().put("matched", true).put("longUrl", "https://shop.example/x?a=1").put("linkId", "lnk_9"),
        ))
        h.client.start(null)
        val e = h.events.single()
        assertEquals("fingerprint", e.route)
        assertTrue(e.matched)
        assertEquals(mapOf("a" to "1"), e.params)
        assertEquals("lnk_9", e.linkId)
    }

    @Test
    fun throwingReferrerProviderStillMatches() {
        val engine = FakeEngine(mapOf("/v1/match" to JSONObject().put("matched", false)))
        val client = StraitClient(StraitConfig(pk, endpoint, installReferrer = { error("no play services") },
            device = { device }, transport = engine))
        val e = client.checkDeferred()
        assertEquals("no_match", e.reason)
    }

    @Test
    fun firstLaunchOpenedByLinkSkipsDeferred() {
        val storage = MemoryStore()
        val h = Harness("strait_link=lnk_7", referrerHit, storage)
        h.client.start("straitlink://shop.example/cart")
        assertEquals(listOf("direct"), h.events.map { it.kind })
        assertEquals("1", storage.get("strait.deferredChecked"))
    }

    @Test
    fun deferredLinkStartHasSameId() {
        val h = Harness("strait_link=lnk_7", referrerHit)
        val starts = mutableListOf<LinkStart>()
        h.client.onLinkStart { starts += it }
        h.client.start(null)
        assertEquals(h.events.single().id, starts.single().id)
        assertEquals("deferred", starts.single().kind)
        assertEquals("closed", starts.single().appState)
    }

    // --- fingerprint check + events ----------------------------------------

    @Test
    fun reportAndCompareFingerprint() {
        val h = Harness(routes = mapOf("/v1/debug/fingerprint" to JSONObject().put("extHash", "abc").put("coreHash", "def")))
        val r = h.client.reportFingerprint()
        assertEquals("abc", r.getString("extHash"))
        val post = h.engine.calls.first { it.method == "POST" && it.path == "/v1/debug/fingerprint" }.body!!
        assertEquals(pk, post.getString("publishableKey"))
        assertEquals("app", post.getString("origin"))
        assertEquals(411, post.getInt("screenWidth"))
        assertEquals("Asia/Kolkata", post.getString("timezone"))
        h.client.compareFingerprint()
        assertTrue(h.engine.calls.any { it.method == "GET" && it.path == "/v1/debug/fingerprint" && it.body == null })
    }

    @Test
    fun trackEventSendsPublishableKey() {
        val h = Harness(routes = mapOf("/v1/event" to JSONObject().put("ok", true)))
        assertTrue(h.client.trackEvent("purchase", value = 49.99, currency = "USD", linkId = "lnk_42"))
        val body = h.engine.calls.last().body!!
        assertEquals(pk, body.getString("publishableKey"))
        assertEquals("purchase", body.getString("event"))
        assertEquals(49.99, body.getDouble("value"))
        assertEquals("USD", body.getString("currency"))
        assertEquals("lnk_42", body.getString("linkId"))
        assertEquals("android", body.getString("platform"))
    }

    @Test
    fun trackEventFailuresReturnFalse() {
        assertFalse(Harness().client.trackEvent("purchase")) // 404
        assertFalse(Harness(routes = mapOf("/v1/event" to "throw")).client.trackEvent("purchase"))
    }

    @Test
    fun hostileReferrerCannotInjectFields() {
        val hostile = "abc\",\"publishableKey\":\"bk_pub_live_attacker"
        val h = Harness("strait_link=" + java.net.URLEncoder.encode(hostile, "UTF-8"), referrerHit)
        h.client.start(null)
        val body = h.engine.calls.first { it.path == "/v1/referrer" }.body!!
        assertEquals(hostile, body.getString("linkId"))
        assertEquals(pk, body.getString("publishableKey"))
    }

    @Test
    fun malformedEngineResponseIsNotMatched() {
        val engine = object : HttpTransport {
            override fun request(url: String, method: String, jsonBody: String?) = HttpResponse(502, "<html>bad gateway</html>")
        }
        val client = StraitClient(StraitConfig(pk, endpoint, device = { device }, transport = engine))
        val got = mutableListOf<LinkEvent>()
        client.onLink { got += it }
        client.handleUrl("https://links.test/sale")
        assertFalse(got.single().matched)
        assertNotNull(got.single().id)
    }

    // --- conversion events carry the tap id (B15) ---------------------------

    private val tap = "3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f"
    private val other = "11111111-2222-4333-8444-555555555555"
    private val day = 24L * 60 * 60 * 1000
    private val eventOk = mapOf("/v1/event" to JSONObject().put("ok", true), "/v1/open" to JSONObject().put("ok", true))
    private fun lastEvent(h: Harness) = h.engine.calls.last { it.path == "/v1/event" }.body!!

    @Test
    fun handOffTapIsRememberedAndAttached() {
        val h = Harness(routes = eventOk)
        h.client.start("straitlink://shop.example/p/42?strait_click=$tap")
        h.t += day
        assertTrue(h.client.trackEvent("purchase", value = 5.0, currency = "USD"))
        assertEquals(tap, lastEvent(h).getString("clickId"))
        val stored = JSONObject(h.storage.data["strait.lastTap"]!!)
        assertEquals(tap, stored.getString("clickId"))
        assertEquals(1_000_000L, stored.getLong("at"))
    }

    @Test
    fun tapNotAttachedAfterSevenDays() {
        val h = Harness(routes = eventOk)
        h.client.start("straitlink://shop.example/p/42?strait_click=$tap")
        h.t += 7 * day + 1
        h.client.trackEvent("purchase")
        assertFalse(lastEvent(h).has("clickId"))
    }

    @Test
    fun explicitClickIdOverridesRememberedTap() {
        val h = Harness(routes = eventOk)
        h.client.start("straitlink://shop.example/p/42?strait_click=$tap")
        h.client.trackEvent("purchase", clickId = other)
        assertEquals(other, lastEvent(h).getString("clickId"))
    }

    @Test
    fun noRememberedTapNoClickId() {
        val h = Harness(routes = eventOk + ("/v1/match" to JSONObject().put("matched", false)))
        h.client.start(null)
        h.client.trackEvent("signup")
        assertFalse(lastEvent(h).has("clickId"))
    }

    @Test
    fun playReferrerTapIsRememberedOnDeferredInstall() {
        val h = Harness("strait_link=lnk_7&strait_click=$tap", referrerHit + eventOk)
        h.client.start(null)
        h.client.trackEvent("purchase")
        assertEquals(tap, lastEvent(h).getString("clickId"))
    }

    @Test
    fun newerShortLinkOpenForgetsOlderTap() {
        val h = Harness(routes = resolved + eventOk)
        h.client.start("straitlink://shop.example/p/42?strait_click=$tap")
        h.client.handleUrl("https://links.test/sale")
        h.client.trackEvent("purchase")
        assertFalse(lastEvent(h).has("clickId"))
    }

    @Test
    fun brokenStorageNeverBlocksTheEvent() {
        val broken = object : KeyValueStore {
            override fun get(key: String): String? = throw java.io.IOException("io")
            override fun set(key: String, value: String) = throw java.io.IOException("io")
        }
        val engine = FakeEngine(eventOk)
        val client = StraitClient(StraitConfig(pk, endpoint, storage = broken, device = { device }, transport = engine, clock = { 1_000_000L }))
        client.start("straitlink://x.example/?strait_click=$tap")
        assertTrue(client.trackEvent("purchase"))
        assertFalse(engine.calls.last { it.path == "/v1/event" }.body!!.has("clickId"))
    }
}
