package dev.bridge.sdk

import kotlin.math.roundToInt

/**
 * Bridge deferred-match signature — Kotlin port of shared-spec/RECIPE.md.
 * MUST be byte-identical to the JS reference + every other SDK (golden vectors).
 *
 * Kotlin `Int` IS 32-bit and overflows by wrapping, exactly like JS's `h |= 0`,
 * so the hash math ports verbatim.
 */

data class Signature(
    val coreRaw: String,
    val extRaw: String,
    val coreHash: String,
    val extHash: String,
)

data class SignatureInputs(
    val screenWidth: Double,
    val pixelRatio: Double,
    val language: String,
    val ip: String,
    val timezone: String,
)

private val REGION_MAP = mapOf(
    "Asia/Kolkata" to "IN", "Asia/Karachi" to "PK", "Asia/Dhaka" to "BD",
    "America/New_York" to "US", "America/Chicago" to "US", "America/Denver" to "US",
    "America/Los_Angeles" to "US", "Europe/London" to "GB", "Europe/Paris" to "EU",
    "Europe/Berlin" to "EU", "Asia/Singapore" to "SG", "Asia/Dubai" to "AE",
    "Australia/Sydney" to "AU",
)

/** Deterministic 32-bit string hash (Java hashCode → abs → hex), matching JS. */
fun h32(s: String): String {
    var h = 0
    for (c in s) {                       // Kotlin Char iterates UTF-16 code units
        h = (h shl 5) - h + c.code       // wraps at 32 bits like JS
    }
    // Math.abs(Int.MIN_VALUE) overflows; use Long magnitude to match JS abs().
    val magnitude = if (h < 0) -h.toLong() else h.toLong()
    return magnitude.toString(16)
}

/** Mirror JS String(Number): whole numbers print with no decimal point. */
fun numStr(n: Double): String {
    return if (n == Math.floor(n) && !n.isInfinite()) n.toLong().toString() else n.toString()
}

fun regionFromTimezone(timezone: String): String {
    val tz = if (timezone == "Asia/Calcutta") "Asia/Kolkata" else timezone
    return REGION_MAP[tz] ?: "XX"
}

fun computeSignature(input: SignatureInputs): Signature {
    val screenWidth = input.screenWidth.roundToInt()
    val rawLang = if (input.language.isEmpty()) "en" else input.language
    val language = rawLang.take(2).lowercase()

    val coreFields = listOf("universal", numStr(screenWidth.toDouble()), numStr(input.pixelRatio), language, input.ip)
    val coreRaw = coreFields.joinToString("|")

    val physWidth = (screenWidth * input.pixelRatio / 8.0).roundToInt() * 8
    val region = regionFromTimezone(input.timezone)
    val extRaw = (coreFields + listOf(numStr(physWidth.toDouble()), region)).joinToString("|")

    return Signature(coreRaw, extRaw, h32(coreRaw), h32(extRaw))
}
