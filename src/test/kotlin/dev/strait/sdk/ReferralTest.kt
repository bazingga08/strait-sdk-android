package dev.strait.sdk

import org.json.JSONObject
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Contract B21 (proposal): a matched deferred reply's referralCode reaches the app, unchanged, only when valid. */
class ReferralTest {
    private class Engine(val routes: Map<String, JSONObject>) : HttpTransport {
        override fun request(url: String, method: String, jsonBody: String?): HttpResponse {
            val payload = routes[URI(url).path] ?: return HttpResponse(404, "{}")
            return HttpResponse(200, payload.toString())
        }
    }

    private fun matched() = JSONObject()
        .put("matched", true).put("longUrl", "https://shop.example/invite")
        .put("linkId", "lnk_42").put("clickId", "3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f")

    private fun firstEvent(routes: Map<String, JSONObject>, referrer: String? = null): LinkEvent {
        val client = StraitClient(
            StraitConfig(
                publishableKey = "st_pub_test_appowner01",
                endpoint = "https://links.test",
                storage = MemoryStore(),
                installReferrer = { referrer },
                device = { DeviceFields(411, 2.625, "en", "Asia/Kolkata") },
                transport = Engine(routes),
                clock = { 1_000_000L },
            ),
        )
        val events = mutableListOf<LinkEvent>()
        client.onLink { events += it }
        client.start(null)
        return events.first()
    }

    @Test
    fun replyReferralCodeKeepsValidCodesExactly() {
        for (c in listOf("ASHA42", "a", "user_12-b", "x".repeat(64))) assertEquals(c, replyReferralCode(c))
        for (c in listOf(null, "", "x".repeat(65), "a b", "me@example.com", "+919999", "ü", 42, JSONObject.NULL)) {
            assertNull(replyReferralCode(c), "$c")
        }
    }

    @Test
    fun playReferrerCarriesTheCode() {
        val e = firstEvent(mapOf("/v1/referrer" to matched().put("matchMethod", "install_referrer").put("referralCode", "ASHA42")), "strait_link=lnk_42")
        assertEquals("install_referrer", e.route)
        assertEquals("ASHA42", e.referralCode)
    }

    @Test
    fun fingerprintMatchCarriesTheCode() {
        val e = firstEvent(mapOf("/v1/match" to matched().put("matchMethod", "exact_ext").put("referralCode", "RAVI7")))
        assertEquals("fingerprint", e.route)
        assertEquals("RAVI7", e.referralCode)
    }

    @Test
    fun noCodeInvalidCodeOrNoMatchGivesNull() {
        for (reply in listOf(
            matched(),
            matched().put("referralCode", "not valid"),
            matched().put("referralCode", JSONObject.NULL),
            JSONObject().put("matched", false).put("referralCode", "ASHA42"),
        )) {
            assertNull(firstEvent(mapOf("/v1/match" to reply)).referralCode, reply.toString())
        }
    }
}
