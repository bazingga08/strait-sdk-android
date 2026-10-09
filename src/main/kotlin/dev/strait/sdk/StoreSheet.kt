package dev.strait.sdk

import org.json.JSONObject
import java.net.URLEncoder

/*
 * Store sheet (beta): open Google Play INSIDE your app for one of your Strait
 * links and keep the deep link across the install.
 *
 * 1. The engine records the tap (POST /v1/store-sheet, sent_to 'store_sheet')
 *    and answers the Play Install Referrer `strait_link=<id>&strait_click=<tap>`.
 * 2. The SDK tries, in order:
 *    a. Google Play inline install: ACTION_VIEW on
 *       https://play.google.com/d?id=<pkg>&referrer=<referrer>[&listing=<csl>]
 *       with package com.android.vending and extras overlay=true, callerId=<your package>.
 *       Play shows a half-sheet over your app (Google calls this a test feature;
 *       Play falls back to the full listing when it can't show the sheet).
 *    b. market://details?id=<pkg>&referrer=<referrer> (the Play app, full screen).
 *    c. https://play.google.com/store/apps/details?id=<pkg>&referrer=<referrer> (browser).
 * 3. After install, the other app's normal deferred check reads the referrer and
 *    /v1/referrer matches it exactly: it opens on the linked screen.
 *
 * This works only where YOUR app is the host. A link tapped inside someone
 * else's app (for example a social app's in-app browser) can't open a sheet there.
 *
 * Pure JVM: the Android Intent is described by [StoreIntent]; your
 * [StoreLauncher] turns it into a real Intent (see README "Store sheet").
 */

/** A store Intent to start: `Intent(action, Uri.parse(data)).setPackage(packageName)` plus [extras]. */
data class StoreIntent(
    /** "inline_install", "market" or "web". */
    val kind: String,
    val action: String,
    val data: String,
    /** "com.android.vending" for the Play app, or null (any handler). */
    val packageName: String?,
    /** Boolean / String extras, e.g. overlay=true, callerId=<your package>. */
    val extras: Map<String, Any> = emptyMap(),
)

/**
 * Starts a [StoreIntent]. Return true when an activity started, false when none
 * could (ActivityNotFoundException): the SDK then tries the next fallback.
 */
fun interface StoreLauncher {
    fun launch(intent: StoreIntent): Boolean
}

data class StoreSheetOptions(
    /** The app to install. Default: the link workspace's Android package from the engine. */
    val androidPackage: String? = null,
    /** Your own app's package (Play's callerId), e.g. context.packageName. Needed for the inline sheet. */
    val callerId: String? = null,
    /** Optional Play custom store listing name (`listing=`). */
    val listing: String? = null,
    /** false: skip the inline half-sheet and go straight to the Play app. */
    val inline: Boolean = true,
)

data class StoreSheetResult(
    /** True when some store screen opened. */
    val opened: Boolean,
    /** "inline_install", "market", "web" or "none". */
    val method: String,
    /** The tap id carried in the referrer (null when the engine couldn't be reached). */
    val clickId: String?,
    val linkId: String?,
    /** The Play Install Referrer sent, or null (the deep link is not kept then). */
    val referrer: String?,
    /** Why the deep link is not kept or nothing opened: not_found, expired, offline, no_package, no_store… */
    val reason: String? = null,
)

object StoreSheet {
    const val PLAY_PACKAGE = "com.android.vending"
    private const val VIEW = "android.intent.action.VIEW"
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun isPackageName(s: String?): Boolean = s != null && s.length <= 255 && PACKAGE.matches(s)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** JSON body for POST /v1/store-sheet. */
    fun buildBody(publishableKey: String, url: String): String =
        JSONObject().put("publishableKey", publishableKey).put("url", url).put("platform", "android").toString()

    /** Google Play inline install (half-sheet over the calling app). */
    fun inlineInstallIntent(pkg: String, referrer: String?, callerId: String, listing: String? = null): StoreIntent {
        val q = StringBuilder("https://play.google.com/d?id=").append(enc(pkg))
        if (!referrer.isNullOrEmpty()) q.append("&referrer=").append(enc(referrer))
        if (!listing.isNullOrEmpty()) q.append("&listing=").append(enc(listing))
        return StoreIntent(
            kind = "inline_install",
            action = VIEW,
            data = q.toString(),
            packageName = PLAY_PACKAGE,
            extras = mapOf("overlay" to true, "callerId" to callerId),
        )
    }

    /** The Play app's full listing (market intent). The referrer still reaches the Install Referrer API. */
    fun marketIntent(pkg: String, referrer: String?): StoreIntent {
        val q = StringBuilder("market://details?id=").append(enc(pkg))
        if (!referrer.isNullOrEmpty()) q.append("&referrer=").append(enc(referrer))
        return StoreIntent("market", VIEW, q.toString(), PLAY_PACKAGE)
    }

    /** Last resort: the Play web listing in any browser. */
    fun webIntent(pkg: String, referrer: String?): StoreIntent {
        val q = StringBuilder("https://play.google.com/store/apps/details?id=").append(enc(pkg))
        if (!referrer.isNullOrEmpty()) q.append("&referrer=").append(enc(referrer))
        return StoreIntent("web", VIEW, q.toString(), null)
    }

    /** The Intents to try, in order. Inline only when a valid callerId is known. */
    fun plan(pkg: String, referrer: String?, callerId: String?, inline: Boolean = true, listing: String? = null): List<StoreIntent> {
        val out = ArrayList<StoreIntent>(3)
        if (inline && isPackageName(callerId)) out += inlineInstallIntent(pkg, referrer, callerId!!, listing)
        out += marketIntent(pkg, referrer)
        out += webIntent(pkg, referrer)
        return out
    }

    /** Try each Intent until one starts. A launcher that throws counts as "didn't start". */
    fun launch(plan: List<StoreIntent>, launcher: StoreLauncher): StoreIntent? {
        for (i in plan) {
            val ok = try { launcher.launch(i) } catch (_: Exception) { false }
            if (ok) return i
        }
        return null
    }
}
