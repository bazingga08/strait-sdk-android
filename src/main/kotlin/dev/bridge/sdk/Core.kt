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
fun parseBridgeLink(referrer: String?): String? = referrerParam(referrer, "bridge_link")

/**
 * The tap id (bridge_click) inside a Play Install Referrer string, or null.
 * Joins the install to the exact tap that sent the user to the store.
 */
fun parseBridgeClick(referrer: String?): String? =
    referrerParam(referrer, "bridge_click")?.takeIf { CLICK_ID.matches(it) }

private fun referrerParam(referrer: String?, key: String): String? {
    if (referrer.isNullOrEmpty()) return null
    for (pair in referrer.split('&')) {
        val i = pair.indexOf('=')
        if (i < 0 || pair.substring(0, i) != key) continue
        return decode(pair.substring(i + 1)).ifEmpty { null }
    }
    return null
}

/** A tap id as Bridge issues it (uuid); anything else is ignored. */
private val CLICK_ID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

/** Result of [takeClickId]: the URL without its tap id, and the tap id (or null). */
data class TakenClickId(val url: String, val clickId: String?)

/**
 * Remove every `bridge_click` parameter from a URL's query, keeping the rest
 * of the URL byte-for-byte (fragment included). Returns the cleaned URL and
 * the tap id (null when absent or malformed). The app never sees the tap id.
 */
fun takeClickId(raw: String): TakenClickId {
    val s = raw.trim()
    val hash = s.indexOf('#')
    val beforeHash = if (hash < 0) s else s.substring(0, hash)
    val frag = if (hash < 0) "" else s.substring(hash)
    val q = beforeHash.indexOf('?')
    if (q < 0) return TakenClickId(s, null)
    var clickId: String? = null
    val kept = beforeHash.substring(q + 1).split('&').filter { pair ->
        val i = pair.indexOf('=')
        if (decode(if (i < 0) pair else pair.substring(0, i)) != "bridge_click") return@filter true
        val v = decode(if (i < 0) "" else pair.substring(i + 1))
        if (CLICK_ID.matches(v)) clickId = v.lowercase()
        false
    }
    val query = kept.joinToString("&")
    return TakenClickId(beforeHash.substring(0, q) + (if (query.isNotEmpty()) "?$query" else "") + frag, clickId)
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
 * short link and [url]/[path]/[params]/[clickId] are null; ask /v1/resolve.
 */
data class ClassifiedUrl(
    val route: String,
    val needsResolve: Boolean,
    val url: String? = null,
    val path: String? = null,
    val params: Map<String, String>? = null,
    /** Tap id from a Bridge hand-off (removed from url/params), else null. */
    val clickId: String? = null,
)

/**
 * What a URL handed to the app means (B4):
 * - https on a Bridge link host -> a short link; ask /v1/resolve for the destination.
 * - other https (a verified link on the customer's own site) -> it IS the destination.
 * - yourapp://host/path (browser hand-off) -> destination https://host/path.
 * A `bridge_click` tap id is removed from the destination and returned apart.
 * Returns null for anything that isn't a URL.
 */
fun classifyUrl(raw: String, linkHosts: List<String>): ClassifiedUrl? {
    val p0 = splitUrl(raw) ?: return null
    val isWeb = p0.scheme == "https" || p0.scheme == "http"
    if (isWeb && linkHosts.any { it.lowercase() == p0.host }) {
        return ClassifiedUrl(LinkRoute.APP_LINK, needsResolve = true)
    }
    val (clean, clickId) = takeClickId(raw)
    val p = splitUrl(clean)!!
    val url = if (isWeb) clean else SCHEME_RE.replaceFirst(clean, "https://")
    return ClassifiedUrl(
        route = if (isWeb) LinkRoute.APP_LINK else LinkRoute.CUSTOM_SCHEME,
        needsResolve = false,
        url = url,
        path = p.path,
        params = p.params,
        clickId = clickId,
    )
}

/** Open reports waiting to be sent are kept at most this long... */
const val OPEN_QUEUE_MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

/** ...and at most this many (oldest dropped first). */
const val OPEN_QUEUE_MAX: Int = 100

/**
 * Prune a pending-report queue: drop reports older than [OPEN_QUEUE_MAX_AGE_MS]
 * (by their `at`), then keep the newest [OPEN_QUEUE_MAX]. Order is kept.
 */
fun <T> pruneOpenQueue(queue: List<T>, now: Long, at: (T) -> Long): List<T> =
    queue.filter { now - at(it) <= OPEN_QUEUE_MAX_AGE_MS }.takeLast(OPEN_QUEUE_MAX)

/** Whether a failed report should be kept for retry: no answer (null), 429 or 5xx. */
fun shouldRetryReport(status: Int?): Boolean = status == null || status == 429 || status >= 500

/** A unique id for one link open (the engine de-duplicates retries by it). */
fun newOpenId(now: Long, random: () -> Double = Math::random): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    val r = StringBuilder(12)
    repeat(12) { r.append(alphabet[kotlin.math.floor(random() * 36).toInt()]) }
    return "o_${now.toString(36)}_$r"
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
