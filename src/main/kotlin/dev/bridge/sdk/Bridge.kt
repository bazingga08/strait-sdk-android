package dev.bridge.sdk

/**
 * Device fields collected on-device (the Android app supplies these from
 * Resources.displayMetrics + Locale + TimeZone). Kept as a plain data class so
 * the resolver is unit-testable without the Android runtime.
 */
data class DeviceFields(
    val screenWidth: Int,
    val pixelRatio: Double,
    val language: String,
    val timezone: String,
)

data class MatchResult(
    val matched: Boolean,
    val longUrl: String?,
    val linkId: String?,
    val matchMethod: String,
) {
    companion object {
        val NONE = MatchResult(false, null, null, "none")
    }
}

/** Extract the Bridge link id from a Play Install Referrer string. */
fun parseBridgeLink(referrer: String?): String? {
    if (referrer.isNullOrEmpty()) return null
    return referrer.split("&")
        .map { it.split("=", limit = 2) }
        .firstOrNull { it.size == 2 && it[0] == "bridge_link" }
        ?.get(1)
        ?.takeIf { it.isNotEmpty() }
}

/**
 * HTTP poster abstraction so the resolver is testable. The real Android wrapper
 * supplies one backed by OkHttp/HttpURLConnection. Returns the raw response
 * body, or null on any failure.
 */
fun interface HttpPoster {
    fun post(url: String, jsonBody: String): String?
}

/**
 * Resolve the deferred deep link. Android deterministic path (Play Install
 * Referrer with bridge_link) is preferred; otherwise the fingerprint /v1/match.
 * The full Android wrapper (Context, InstallReferrerClient, display metrics) is
 * provided in the app layer; this core stays pure + testable.
 */
object Bridge {
    fun buildMatchBody(appId: String, device: DeviceFields): String =
        """{"appId":"$appId","platform":"android","screenWidth":${device.screenWidth},""" +
            """"pixelRatio":${device.pixelRatio},"language":"${device.language}","timezone":"${device.timezone}"}"""

    fun buildReferrerBody(appId: String, linkId: String): String =
        """{"appId":"$appId","linkId":"$linkId","platform":"android"}"""
}
