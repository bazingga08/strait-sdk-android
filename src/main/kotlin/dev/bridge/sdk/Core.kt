package dev.bridge.sdk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.math.ceil

/*
 * Pure, platform-free link logic, ported 1:1 from sdk-react-native/src/core.ts.
 * shared-spec/conformance-vectors.json is the cross-language contract
 * (see shared-spec/SDK-CONTRACT.md). No android.net.Uri / java.net.URI here:
 * behaviour must equal splitUrl exactly (B12).
 */

/**
 * Screen width as a browser reports it (`screen.width`). Chrome rounds
 * fractional logical widths UP (1080 px at 2.625 = 411.43 -> 412). Matching
 * needs the app and the browser at the tap to agree (B2).
 */
fun browserScreenWidth(logicalWidth: Double): Int = ceil(logicalWidth - 0.001).toInt()

data class SplitUrl(
    val scheme: String,
    val host: String,
    val path: String,
    val params: Map<String, String>,
)

private val URL_RE = Regex("^([a-z][a-z0-9+.-]*)://([^/?#]*)([^?#]*)(?:\\?([^#]*))?", RegexOption.IGNORE_CASE)
private val SCHEME_RE = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)

/**
 * Split a URL without platform URL classes. Scheme and host are lower-cased;
 * '+' and %-escapes in the query are decoded; the fragment is dropped.
 */
fun splitUrl(u: String): SplitUrl? {
    val m = URL_RE.find(u.trim()) ?: return null
    val params = LinkedHashMap<String, String>()
    for (pair in m.groupValues[4].split('&')) {
        if (pair.isEmpty()) continue
        val i = pair.indexOf('=')
        val k = if (i < 0) pair else pair.substring(0, i)
        val v = if (i < 0) "" else pair.substring(i + 1)
        params[decode(k)] = decode(v)
    }
    val path = m.groupValues[3].ifEmpty { "/" }
    return SplitUrl(m.groupValues[1].lowercase(), m.groupValues[2].lowercase(), path, params)
}

/**
 * JS `decodeURIComponent(s.replace(/\+/g, ' '))`, returning `s` unchanged when
 * the escapes are malformed or not valid UTF-8 (where JS would throw).
 */
internal fun decode(s: String): String {
    val plus = s.replace('+', ' ')
    if ('%' !in plus) return plus
    val out = StringBuilder(plus.length)
    var i = 0
    while (i < plus.length) {
        val c = plus[i]
        if (c != '%') {
            out.append(c); i++; continue
        }
        val bytes = ByteArrayOutputStream()
        while (i < plus.length && plus[i] == '%') {
            if (i + 2 >= plus.length) return s
            val b = plus.substring(i + 1, i + 3).toIntOrNull(16) ?: return s
            if (plus[i + 1] == '+' || plus[i + 1] == '-') return s
            bytes.write(b)
            i += 3
        }
        try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            out.append(dec.decode(ByteBuffer.wrap(bytes.toByteArray())))
        } catch (_: CharacterCodingException) {
            return s
        }
    }
    return out.toString()
}

/**
 * The hosts that serve this app's short links: the endpoint's host plus any
 * configured link domains, given as URLs ("https://go.brand.com") or bare
 * hosts ("go.brand.com"). Lower-cased, de-duplicated, order kept; anything
 * else (blank, paths, spaces) is ignored.
 */
fun normalizeLinkHosts(endpoint: String, linkHosts: List<String> = emptyList()): List<String> {
    val bare = Regex("^[a-z0-9.-]+(:\\d+)?$", RegexOption.IGNORE_CASE)
    val out = mutableListOf<String>()
    for (h in listOf(endpoint) + linkHosts) {
        val t = h.trim()
        val host = splitUrl(h)?.host ?: (if (bare.matches(t)) t.lowercase() else null)
        if (host != null && host !in out) out += host
    }
    return out
}

/** The bridge_link id inside a Play Install Referrer string, or null. */
fun parseBridgeLink(referrer: String?): String? {
    if (referrer.isNullOrEmpty()) return null
    for (pair in referrer.split('&')) {
        val i = pair.indexOf('=')
        if (i < 0 || pair.substring(0, i) != "bridge_link") continue
        return decode(pair.substring(i + 1)).ifEmpty { null }
    }
    return null
}

/** How the app received a link (`route` on [LinkEvent]). */
object LinkRoute {
    const val APP_LINK = "app_link"
    const val CUSTOM_SCHEME = "custom_scheme"
    const val INSTALL_REFERRER = "install_referrer"
    const val FINGERPRINT = "fingerprint"
}

/**
 * Result of [classifyUrl]. When [needsResolve] is true the URL is a Bridge
 * short link and [url]/[path]/[params] are null; ask /v1/resolve.
 */
data class ClassifiedUrl(
    val route: String,
    val needsResolve: Boolean,
    val url: String? = null,
    val path: String? = null,
    val params: Map<String, String>? = null,
)

/**
 * What a URL handed to the app means (B4):
 * - https on a Bridge link host -> a short link; ask /v1/resolve for the destination.
 * - other https (a verified link on the customer's own site) -> it IS the destination.
 * - yourapp://host/path (browser hand-off) -> destination https://host/path.
 * Returns null for anything that isn't a URL.
 */
fun classifyUrl(raw: String, linkHosts: List<String>): ClassifiedUrl? {
    val p = splitUrl(raw) ?: return null
    val isWeb = p.scheme == "https" || p.scheme == "http"
    if (isWeb && linkHosts.any { it.lowercase() == p.host }) {
        return ClassifiedUrl(LinkRoute.APP_LINK, needsResolve = true)
    }
    val trimmed = raw.trim()
    val url = if (isWeb) trimmed else SCHEME_RE.replaceFirst(trimmed, "https://")
    return ClassifiedUrl(
        route = if (isWeb) LinkRoute.APP_LINK else LinkRoute.CUSTOM_SCHEME,
        needsResolve = false,
        url = url,
        path = p.path,
        params = p.params,
    )
}

/** A link arriving this soon after the app came back to the front came "from background". */
const val RESUME_WINDOW_MS: Long = 2000

/** Pauses shorter than this are Android delivering the link, not the user leaving. */
const val TRANSIENT_PAUSE_MS: Long = 1000

/** App lifecycle state, as reported by ProcessLifecycleOwner / Activity callbacks. */
enum class AppLifecycleState(val wire: String) {
    ACTIVE("active"), BACKGROUND("background"), INACTIVE("inactive");

    companion object {
        fun fromWire(s: String): AppLifecycleState? = entries.firstOrNull { it.wire == s }
    }
}

/** What the app was doing when a link arrived (`appState` on [LinkEvent]). */
object AppStateAtLink {
    const val CLOSED = "closed"
    const val BACKGROUND = "background"
    const val FOREGROUND = "foreground"
}

/**
 * Tracks app lifecycle to label a link delivered while the app is running (B5).
 * Android wraps link delivery in a brief pause/resume, and the link can arrive
 * before or after the resume: a pause under [TRANSIENT_PAUSE_MS] is that
 * delivery (app was on screen); a longer one means the user had left.
 */
class AppStateTracker {
    private var state = AppLifecycleState.ACTIVE
    private var backgroundAt = Double.NEGATIVE_INFINITY
    private var resumeAt = Double.NEGATIVE_INFINITY
    private var backgroundFor = 0.0

    @Synchronized
    fun onState(s: AppLifecycleState, now: Long) {
        if (s != AppLifecycleState.ACTIVE && state == AppLifecycleState.ACTIVE) backgroundAt = now.toDouble()
        if (s == AppLifecycleState.ACTIVE && state != AppLifecycleState.ACTIVE) {
            resumeAt = now.toDouble()
            backgroundFor = now - backgroundAt
        }
        state = s
    }

    /** Label ("background" / "foreground") for a link delivered (while running) at [now]. */
    @Synchronized
    fun classify(now: Long): String {
        var away: Double? = null
        if (state != AppLifecycleState.ACTIVE) away = now - backgroundAt
        else if (now - resumeAt <= RESUME_WINDOW_MS) away = backgroundFor
        return if (away != null && away >= TRANSIENT_PAUSE_MS) AppStateAtLink.BACKGROUND else AppStateAtLink.FOREGROUND
    }
}
