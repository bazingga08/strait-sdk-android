package com.straitlink.example

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.strait.sdk.AppLifecycleState
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val app get() = application as App
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var log: TextView
    private var offLink: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this)
        val purchase = Button(this).apply { text = "Log a purchase event" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(purchase)
            addView(log)
        })

        if (!app.configured) {
            show("Copy strait.example.properties to strait.properties, set your publishable key, rebuild.")
            return
        }
        val strait = app.strait
        // Direct links (straitsample://… or verified https links) and the deferred link after an install.
        offLink = strait.onLink { e -> runOnUiThread { show("${e.kind} link (${e.route}, ${e.appState}): matched=${e.matched} path=${e.path}") } }
        // The launch link (or null), then the deferred check once per install.
        if (savedInstanceState == null) strait.start(intent?.dataString)

        purchase.setOnClickListener {
            io.execute {
                val ok = strait.trackEvent("purchase", value = 4.99, currency = "USD")
                runOnUiThread { show("event purchase sent: $ok") }
            }
        }
    }

    // A link while the app is running (background or foreground).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (app.configured) intent.dataString?.let(app.strait::handleUrl)
    }

    // App-state labels (closed / background / foreground) for each link.
    override fun onResume() { super.onResume(); if (app.configured) app.strait.onAppState(AppLifecycleState.ACTIVE) }
    override fun onPause() { super.onPause(); if (app.configured) app.strait.onAppState(AppLifecycleState.INACTIVE) }
    override fun onStop() { super.onStop(); if (app.configured) app.strait.onAppState(AppLifecycleState.BACKGROUND) }

    // The client outlives this Activity (rotation recreates it): drop the listener so it isn't leaked.
    override fun onDestroy() { offLink?.invoke(); io.shutdown(); super.onDestroy() }

    private fun show(line: String) { log.append(line + "\n") }
}
