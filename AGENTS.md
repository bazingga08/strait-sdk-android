# AGENTS.md: Strait Android SDK (strait-sdk-android, package dev.strait.sdk)

Instructions for AI coding agents (Claude Code, Cursor, Codex, Copilot…) that add this SDK to an app or work on
this repo. Humans: see README.md.

Plain Kotlin (JDK 17+). One client per process; the activity feeds it links; `onLink` delivers every open.

## Install

Served by JitPack from this public repo.

```kotlin
// settings.gradle.kts → dependencyResolutionManagement.repositories
maven("https://jitpack.io")

// app/build.gradle.kts
implementation("com.github.bazingga08:strait-sdk-android:v0.7.2")
implementation("com.android.installreferrer:installreferrer:2.2")
implementation("androidx.lifecycle:lifecycle-process:2.8.7")
```

Manifest: a `singleTask` activity with an `autoVerify` intent filter for `https://<handle>.strait.link` and a
second intent filter for the custom scheme (https://straitlink.in/docs/sdks/android/, step 4).

## Keys (the rule agents get wrong most)

- **Publishable key** `st_pub_live_…` (Dashboard → Get started): goes in the app. It is the only key this SDK takes (`publishableKey`).
- **Secret key** `st_live_…` (Dashboard → Settings → Secret keys): server only. Never put it in an app: anyone can extract it and change your links.
- Never commit either key's real value to this repo, tests or examples. Use placeholders like `st_pub_live_…`.

## Receive links: the one pattern

```kotlin
// Application.onCreate: create once
links = StraitClient(StraitConfig(
    publishableKey = "st_pub_live_…",       // never the secret key
    endpoint = "https://acme.strait.link",      // the workspace's link domain
    storage = /* KeyValueStore over SharedPreferences */,
    installReferrer = { readInstallReferrer(this) },
    device = { DeviceFields(/* portraitScreenWidth(...), density, locale tag, TimeZone id */) },
    executor = Executors.newSingleThreadExecutor(),
))

// MainActivity
links.onLink { e -> runOnUiThread { if (e.matched) navigate(e.path, e.params) } }
if (savedInstanceState == null) links.start(intent?.dataString)   // launch link + deferred check
override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); intent.dataString?.let(links::handleUrl) }
```

Also feed `ProcessLifecycleOwner` events to `links.onAppState(...)` (docs show the observer). Network calls run on
the executor, never the main thread.

## Verify

Run these; don't assume.

```sh
# 1. The link domain serves the verification files with this app in them
curl https://<handle>.strait.link/.well-known/assetlinks.json              # Android: package + every SHA-256
curl https://<handle>.strait.link/.well-known/apple-app-site-association   # iPhone: TeamID.bundleId
#    (or the free checker: https://straitlink.in/tools/  ·  MCP tool: check_app_links)

# 2. Android verified the host (fresh install). Want: verified
adb shell pm get-app-links <package.name>
```

3. Tap a link from WhatsApp or Gmail on a real phone: the app opens on the right screen and `onLink` fires
   with `matched: true`. The tap and the open appear in Dashboard → Analytics.
4. Deferred (Android): install from a Google Play internal-testing build, tap the link before installing, open
   the app: `onLink` fires with `kind: deferred`, `route: install_referrer`. iPhone install matching is in beta.

If links open the browser: a missing SHA-256 (most often the Play App Signing key from Play Console → App
integrity), a typo in the host, or the app was installed before the files were right (reinstall). See
https://straitlink.in/docs/troubleshooting/.

## Working on this repo

- Test: `./gradlew test   # JDK 17` (must pass before any commit; check the exit code).
- The match signature and the pure helpers are pinned by shared golden vectors
  (`src/test/resources/*vectors*.json`): byte-identical copies live in every app and web SDK (the signature vectors in the
  engine too). Never edit a vector file
  here alone; vectors change only through `shared-spec/` and land in every repo together.
- The package's public identity (name, scope, owner, domain) lives only in `brand.json`; change it with
  `shared-spec/scripts/rename-brand.sh` (all SDKs) or `node scripts/brand.mjs --write`.
- Wire names are part of the contract: query params `strait_click` / `strait_link`, storage keys `strait.*`,
  headers `X-Strait-*`. Don't rename them.
- Brand: Strait (never "Straight"). Don't write superlatives ("best", "cheapest") or speed / match-rate numbers in
  docs or comments. iPhone install matching is in beta.

## More

- Docs for this SDK: https://straitlink.in/docs/sdks/android/
- All docs: https://straitlink.in/docs/ · REST API: https://straitlink.in/docs/api/
- Strait from AI tools (MCP server: create links, check App Links files, trace taps): https://straitlink.in/ai/
