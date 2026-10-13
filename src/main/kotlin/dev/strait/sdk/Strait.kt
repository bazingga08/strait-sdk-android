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
