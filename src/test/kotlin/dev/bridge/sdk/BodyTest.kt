package dev.bridge.sdk

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Request bodies must be valid JSON carrying the publishable key, whatever the inputs. */
class BodyTest {
    private val pk = "bk_pub_test_0123456789abcdef0123456789abcdef"

    @Test
    fun matchBodyCarriesPublishableKeyAndDevice() {
        val body = JSONObject(
            Bridge.buildMatchBody(pk, DeviceFields(411, 2.625, "en", "Asia/Kolkata")),
        )
        assertEquals(pk, body.getString("publishableKey"))
        assertEquals("android", body.getString("platform"))
        assertEquals(411, body.getInt("screenWidth"))
        assertEquals(2.625, body.getDouble("pixelRatio"))
        assertEquals("Asia/Kolkata", body.getString("timezone"))
        assertFalse(body.has("appId"))
    }

    @Test
    fun referrerBodyEscapesHostileLinkId() {
        // The referrer comes from a click URL anyone can craft.
        val hostile = "abc\",\"publishableKey\":\"bk_pub_live_attacker\\\n"
        val body = JSONObject(Bridge.buildReferrerBody(pk, hostile))
        assertEquals(hostile, body.getString("linkId"))
        assertEquals(pk, body.getString("publishableKey"))
    }
}
