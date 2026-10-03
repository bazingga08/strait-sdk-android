package dev.strait.sdk

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden-vector parity with the server + every other SDK. If Kotlin drifts,
 * deferred match breaks silently — this is the cross-language contract.
 */
class SignatureTest {
    private fun vectors(): JSONObject {
        val text = this::class.java.getResourceAsStream("/test-vectors.json")!!
            .bufferedReader().readText()
        return JSONObject(text)
    }

    @Test
    fun h32GoldenVectors() {
        val arr = vectors().getJSONArray("h32")
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            assertEquals(v.getString("expected"), h32(v.getString("input")), "h32(${v.getString("input")})")
        }
    }

    @Test
    fun signatureGoldenVectors() {
        val arr = vectors().getJSONArray("signatures")
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val inp = v.getJSONObject("input")
            val exp = v.getJSONObject("expected")
            val sig = computeSignature(
                SignatureInputs(
                    screenWidth = inp.getDouble("screenWidth"),
                    pixelRatio = inp.getDouble("pixelRatio"),
                    language = inp.getString("language"),
                    ip = inp.getString("ip"),
                    timezone = inp.getString("timezone"),
                )
            )
            val name = v.getString("name")
            assertEquals(exp.getString("coreRaw"), sig.coreRaw, name)
            assertEquals(exp.getString("extRaw"), sig.extRaw, name)
            assertEquals(exp.getString("coreHash"), sig.coreHash, name)
            assertEquals(exp.getString("extHash"), sig.extHash, name)
        }
    }

    @Test
    fun numStrParity() {
        assertEquals("3", numStr(3.0))
        assertEquals("2.625", numStr(2.625))
        assertEquals("1176", numStr(1176.0))
    }
}
