package android.content.res

// A stand-in for the two Android framework classes systemDeviceClass() reads by
// reflection, so the JVM tests exercise that path. smallestScreenWidthDp = 0 is
// Android's SMALLEST_SCREEN_WIDTH_DP_UNDEFINED, so other tests see no device class.
class Configuration {
    @JvmField var smallestScreenWidthDp: Int = 0
}

class Resources {
    fun getConfiguration(): Configuration = Configuration().also { it.smallestScreenWidthDp = smallestWidthDp }

    companion object {
        @JvmField var smallestWidthDp: Int = 0

        @JvmStatic fun getSystem(): Resources = Resources()
    }
}
