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

/**
 * This device's class from the system configuration (contract B14):
 * `Resources.getSystem().configuration.smallestScreenWidthDp` through
 * [deviceClassForSize], so "tablet" from 600 dp. Read by reflection because the
 * SDK has no Android dependency; null on a plain JVM or if anything fails.
 * The default of [StraitConfig.deviceClass].
 */
internal fun systemDeviceClass(): String? = try {
    val res = Class.forName("android.content.res.Resources").getMethod("getSystem").invoke(null)
    val conf = res.javaClass.getMethod("getConfiguration").invoke(res)
    deviceClassForSize(conf.javaClass.getField("smallestScreenWidthDp").getInt(conf).toDouble())
} catch (_: Throwable) {
    null
}

/** Entry points that need no [StraitClient] state. Everything else lives on [StraitClient]. */
object Strait {
    /** Store sheet (beta): same as [StraitClient.openStoreSheet]. */
    fun openStoreSheet(
        client: StraitClient,
        url: String,
        launcher: StoreLauncher,
        options: StoreSheetOptions = StoreSheetOptions(),
    ): StoreSheetResult = client.openStoreSheet(url, launcher, options)
}
