package dev.strait.sdk

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
 * Referrer with strait_link) is preferred; otherwise the fingerprint /v1/match.
 * The full Android wrapper (Context, InstallReferrerClient, display metrics) is
 * provided in the app layer; this core stays pure + testable.
 *
 * `publishableKey` is the workspace publishable key (`bk_pub_live_…` /
 * `bk_pub_test_…`) from Dashboard → Get started. It is safe to ship in apps;
 * never pass your secret key (`bk_live_…`).
 */
object Strait {
    /** JSON body for POST /v1/match (device-fingerprint match). */
    fun buildMatchBody(publishableKey: String, device: DeviceFields): String =
        "{\"publishableKey\":${jsonString(publishableKey)},\"platform\":\"android\"," +
            "\"screenWidth\":${device.screenWidth},\"pixelRatio\":${device.pixelRatio}," +
            "\"language\":${jsonString(device.language)},\"timezone\":${jsonString(device.timezone)}}"

    /**
     * JSON body for POST /v1/referrer (Play Install Referrer match). `linkId`
     * comes from the referrer, which originates in a URL anyone can craft, so
     * every string is JSON-escaped.
     */
    fun buildReferrerBody(publishableKey: String, linkId: String): String =
        "{\"publishableKey\":${jsonString(publishableKey)},\"linkId\":${jsonString(linkId)}," +
            "\"platform\":\"android\"}"
}

/** Quote + escape a string as a JSON string literal (RFC 8259). */
internal fun jsonString(value: String): String {
    val sb = StringBuilder(value.length + 2).append('"')
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.append('"').toString()
}
