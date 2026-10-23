package dev.strait.sdk

import android.content.res.Resources
import org.json.JSONObject
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract B14 deviceClass (engine ADR 0039): sent with /v1/resolve, /v1/open and /v1/store-sheet. */
class DeviceClassTest {
    private data class Call(val path: String, val body: JSONObject)

    private class Engine(val routes: MutableMap<String, Any>) : HttpTransport {
        val calls = mutableListOf<Call>()
        fun of(path: String) = calls.filter { it.path == path }
        override fun request(url: String, method: String, jsonBody: String?): HttpResponse {
            val path = URI(url).path
            calls += Call(path, JSONObject(jsonBody ?: "{}"))
            val r = routes[path] ?: return HttpResponse(404, "{}")
            if (r == "offline") throw java.io.IOException("offline")
            return HttpResponse(if (path == "/v1/open") 202 else 200, r.toString())
        }
    }

    private val returning get() = MemoryStore().apply { set("strait.deferredChecked", "1") }
    private val resolved = JSONObject().put("matched", true).put("longUrl", "https://shop.example/p/42")
        .put("linkId", "lnk_42").put("recorded", true)

    private fun client(engine: Engine, deviceClass: String?, storage: MemoryStore = returning) = StraitClient(
        StraitConfig(
            publishableKey = "bk_pub_test_appowner01",
            endpoint = "https://links.test",
            storage = storage,
            device = { DeviceFields(411, 2.625, "en", "Asia/Kolkata") },
            transport = engine,
            deviceClass = deviceClass,
        ),
    )

    @AfterTest fun reset() { Resources.smallestWidthDp = 0 }

    @Test fun systemDeviceClassUsesSmallestScreenWidthDp() {
        assertNull(systemDeviceClass(), "undefined (0) -> not sent")
        Resources.smallestWidthDp = 411
        assertEquals("phone", systemDeviceClass())
        Resources.smallestWidthDp = 599
        assertEquals("phone", systemDeviceClass())
        Resources.smallestWidthDp = 600
        assertEquals("tablet", systemDeviceClass())
        Resources.smallestWidthDp = 800
        assertEquals("tablet", systemDeviceClass())
        // The config default reads it.
        assertEquals("tablet", StraitConfig(publishableKey = "k", endpoint = "https://x", device = { DeviceFields(1, 1.0, "en", "UTC") }).deviceClass)
    }

    @Test fun resolveAndItsOfflineRetryCarryIt() {
        val engine = Engine(mutableMapOf("/v1/resolve" to "offline"))
        val c = client(engine, "tablet")
        c.start("https://links.test/sale")
        assertEquals("tablet", engine.of("/v1/resolve")[0].body.getString("deviceClass"))
        engine.routes["/v1/open"] = JSONObject().put("ok", true)
        c.onAppState(AppLifecycleState.ACTIVE)
        val open = engine.of("/v1/open").last().body
        assertEquals("network", open.getString("reason"))
        assertEquals("tablet", open.getString("deviceClass"))
    }

    @Test fun directOpenReportCarriesIt() {
        val engine = Engine(mutableMapOf("/v1/open" to JSONObject().put("ok", true)))
        client(engine, "phone").start("straitlink://shop.example/p/1")
        assertEquals("phone", engine.of("/v1/open")[0].body.getString("deviceClass"))
    }

    @Test fun neverQueuedOnDevice() {
        val storage = returning
        val engine = Engine(mutableMapOf("/v1/open" to "offline"))
        client(engine, "tablet", storage).start("straitlink://shop.example/p/1")
        val queued = storage.get("strait.pendingOpens")!!
        assertTrue(queued.contains("openId"))
        assertFalse(queued.contains("deviceClass"), "added at send time, not stored")
    }

    @Test fun unknownOrInvalidIsLeftOut() {
        for (dc in listOf(null, "watch", "")) {
            val engine = Engine(mutableMapOf("/v1/resolve" to resolved))
            client(engine, dc).start("https://links.test/sale")
            assertFalse(engine.of("/v1/resolve")[0].body.has("deviceClass"), "deviceClass=$dc")
        }
    }

    @Test fun storeSheetBodyCarriesIt() {
        val engine = Engine(mutableMapOf("/v1/store-sheet" to JSONObject().put("ok", false).put("reason", "not_found")))
        client(engine, "tablet").openStoreSheet("https://links.test/promo", { true })
        assertEquals("tablet", engine.of("/v1/store-sheet")[0].body.getString("deviceClass"))
        assertFalse(JSONObject(StoreSheet.buildBody("k", "u", "watch")).has("deviceClass"))
        assertFalse(JSONObject(StoreSheet.buildBody("k", "u")).has("deviceClass"))
    }
}
