package dev.strait.sdk

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreSheetTest {
    private val referrer = "strait_link=lnk_42&strait_click=9a1c7e52-4b3d-4f8e-a6d1-0c2b5e7f9a34"

    private class Engine(val reply: Any?) : HttpTransport {
        val bodies = mutableListOf<JSONObject>()
        override fun request(url: String, method: String, jsonBody: String?): HttpResponse {
            assertEquals("/v1/store-sheet", URI(url).path)
            jsonBody?.let { bodies += JSONObject(it) }
            if (reply == "throw") throw java.io.IOException("offline")
            return HttpResponse(200, reply.toString())
        }
    }

    private fun client(engine: Engine) = StraitClient(
        StraitConfig(
            publishableKey = "bk_pub_test_appowner01",
            endpoint = "https://hilltop.strait.link",
            device = { DeviceFields(411, 2.625, "en", "Asia/Kolkata") },
            transport = engine,
        ),
    )

    private val ok = JSONObject()
        .put("ok", true).put("beta", true).put("clickId", "9a1c7e52-4b3d-4f8e-a6d1-0c2b5e7f9a34").put("linkId", "lnk_42")
        .put("android", JSONObject().put("package", "shoes.hilltop.app").put("referrer", referrer))

    private fun query(data: String): Map<String, String> =
        data.substringAfter('?').split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }

    @Test fun inlineInstallIntentMatchesPlayContract() {
        val i = StoreSheet.inlineInstallIntent("shoes.hilltop.app", referrer, "com.partner.app", "autumn")
        assertEquals("inline_install", i.kind)
        assertEquals("android.intent.action.VIEW", i.action)
        assertEquals("com.android.vending", i.packageName)
        assertTrue(i.data.startsWith("https://play.google.com/d?id=shoes.hilltop.app&"))
        val q = query(i.data)
        assertEquals(referrer, q["referrer"]) // round-trips exactly: parseStraitClick reads it back
        assertEquals("autumn", q["listing"])
        assertEquals(mapOf("overlay" to true, "callerId" to "com.partner.app"), i.extras)
        assertEquals("lnk_42", parseStraitLink(q["referrer"]))
        assertEquals("9a1c7e52-4b3d-4f8e-a6d1-0c2b5e7f9a34", parseStraitClick(q["referrer"]))
    }

    @Test fun planOrderAndInlineNeedsCallerId() {
        assertEquals(listOf("inline_install", "market", "web"), StoreSheet.plan("a.b", referrer, "c.d").map { it.kind })
        assertEquals(listOf("market", "web"), StoreSheet.plan("a.b", referrer, null).map { it.kind })
        assertEquals(listOf("market", "web"), StoreSheet.plan("a.b", referrer, "c.d", inline = false).map { it.kind })
        val market = StoreSheet.marketIntent("a.b", referrer)
        assertTrue(market.data.startsWith("market://details?id=a.b&referrer="))
        assertEquals(referrer, query(market.data)["referrer"])
        assertNull(StoreSheet.webIntent("a.b", null).packageName)
        assertEquals("https://play.google.com/store/apps/details?id=a.b", StoreSheet.webIntent("a.b", null).data)
    }

    @Test fun opensInlineSheetWithEngineReferrer() {
        val engine = Engine(ok)
        val seen = mutableListOf<StoreIntent>()
        val r = client(engine).openStoreSheet(
            "https://hilltop.strait.link/promo",
            { seen += it; true },
            StoreSheetOptions(callerId = "com.partner.app"),
        )
        assertTrue(r.opened)
        assertEquals("inline_install", r.method)
        assertEquals(referrer, r.referrer)
        assertEquals("lnk_42", r.linkId)
        assertNull(r.reason)
        assertEquals(1, seen.size)
        assertEquals("android", engine.bodies[0].getString("platform"))
        assertEquals("https://hilltop.strait.link/promo", engine.bodies[0].getString("url"))
    }

    @Test fun fallsBackToMarketWhenInlineCannotStart() {
        val r = client(Engine(ok)).openStoreSheet(
            "https://hilltop.strait.link/promo",
            { it.kind != "inline_install" },
            StoreSheetOptions(callerId = "com.partner.app"),
        )
        assertEquals("market", r.method)
        assertEquals(referrer, r.referrer)
    }

    @Test fun launcherThatThrowsFallsThrough() {
        val r = client(Engine(ok)).openStoreSheet("https://hilltop.strait.link/promo", {
            if (it.kind != "web") throw IllegalStateException("ActivityNotFound") else true
        })
        assertEquals("web", r.method)
    }

    @Test fun offlineStillOpensStoreWithoutDeepLink() {
        val r = client(Engine("throw")).openStoreSheet(
            "https://hilltop.strait.link/promo",
            { true },
            StoreSheetOptions(androidPackage = "shoes.hilltop.app"),
        )
        assertTrue(r.opened)
        assertEquals("market", r.method)
        assertNull(r.referrer)
        assertEquals("offline", r.reason)
    }

    @Test fun unknownLinkWithoutPackageOpensNothing() {
        val r = client(Engine(JSONObject().put("ok", false).put("reason", "not_found")))
            .openStoreSheet("https://hilltop.strait.link/nope", { true })
        assertFalse(r.opened)
        assertEquals("none", r.method)
        assertEquals("no_package", r.reason)
    }

    @Test fun nothingStartsReportsNoStore() {
        val r = client(Engine(ok)).openStoreSheet("https://hilltop.strait.link/promo", { false })
        assertFalse(r.opened)
        assertEquals("no_store", r.reason)
        assertEquals(referrer, r.referrer)
    }

    @Test fun rejectsBadPackageNames() {
        assertTrue(StoreSheet.isPackageName("shoes.hilltop.app"))
        assertFalse(StoreSheet.isPackageName("nodots"))
        assertFalse(StoreSheet.isPackageName("a.b&referrer=x"))
        assertFalse(StoreSheet.isPackageName(null))
    }

    @Test fun staticEntryPointDelegates() {
        val r = Strait.openStoreSheet(client(Engine(ok)), "https://hilltop.strait.link/promo", { true })
        assertEquals("market", r.method)
    }
}
