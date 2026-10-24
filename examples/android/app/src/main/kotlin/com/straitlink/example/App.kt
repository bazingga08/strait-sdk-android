package com.straitlink.example

import android.app.Application
import dev.strait.sdk.DeviceFields
import dev.strait.sdk.KeyValueStore
import dev.strait.sdk.StraitClient
import dev.strait.sdk.StraitConfig
import dev.strait.sdk.portraitScreenWidth
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/** One Strait client for the app's lifetime. */
class App : Application() {
    lateinit var strait: StraitClient

    val configured: Boolean
        get() = !BuildConfig.STRAIT_PUBLISHABLE_KEY.contains("REPLACE_ME")

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("strait", MODE_PRIVATE)
        strait = StraitClient(StraitConfig(
            publishableKey = BuildConfig.STRAIT_PUBLISHABLE_KEY,
            endpoint = BuildConfig.STRAIT_ENDPOINT,
            // Keeps the once-per-install deferred flag and unsent open reports.
            storage = object : KeyValueStore {
                override fun get(key: String) = prefs.getString(key, null)
                override fun set(key: String, value: String) { prefs.edit().putString(key, value).apply() }
            },
            device = {
                val m = resources.displayMetrics
                DeviceFields(
                    screenWidth = portraitScreenWidth(m.widthPixels / m.density.toDouble(), m.heightPixels / m.density.toDouble()),
                    pixelRatio = m.density.toDouble(),
                    language = Locale.getDefault().toLanguageTag(),
                    timezone = TimeZone.getDefault().id,
                )
            },
            // Network calls block, so run them off the main thread.
            executor = Executors.newSingleThreadExecutor(),
        ))
    }
}
