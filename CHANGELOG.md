# Changelog

## 0.7.1

- Report the portrait screen width so a first launch in landscape still matches the tap
  (shared-spec/SDK-CONTRACT.md B17): new `Core.kt` function `portraitScreenWidth(w, h)`
  (the shorter side, rounded like the browser; conformance vectors v5). Build
  `DeviceFields.screenWidth` with it from `widthPixels` and `heightPixels`, as the README
  now shows.

## 0.7.0

- Every attributed open now supplies the tap id (shared-spec/SDK-CONTRACT.md B16):
  when `/v1/resolve` (verified short link), `/v1/match` (fingerprint) or
  `/v1/referrer` (Play install) returns `clickId`, it is remembered as
  `strait.lastTap` and sent with conversion events. A reply without one (older
  engine) keeps the 0.6.0 behaviour.
- New core function: `replyClickId` (conformance vectors v4).

## 0.6.0 (2026-10-04)

- Conversion events carry the tap id (shared-spec/SDK-CONTRACT.md B15): the tap id
  of the last attributed link open (browser hand-off `strait_click`, or the Play
  referrer on a deferred install) is remembered under `strait.lastTap` and sent as
  `clickId` with `trackEvent` for 7 days. A newer short-link or fingerprint open
  forgets it. `trackEvent(name, clickId = …)` overrides it.
- New in `Core.kt`: `eventClickId`, `rememberTap`, `ATTRIBUTION_WINDOW_MS`
  (conformance vectors v3).

## 0.5.0 (2026-10-03)

**Renamed to Strait** (breaking, clean break; no aliases for the old names).

- Kotlin package `dev.bridge.sdk` → `dev.strait.sdk`; `BridgeClient` → `StraitClient`,
  `BridgeConfig` → `StraitConfig`, `object Bridge` → `object Strait`,
  `parseBridgeLink` → `parseStraitLink`, `parseBridgeClick` → `parseStraitClick`.
- Wire params: the Play referrer / hand-off params are now `strait_link` and
  `strait_click` only.
- Storage keys: `strait.deferredChecked`, `strait.pendingOpens` (old values are ignored).
- Artifact / repo: `com.github.bazingga08:strait-sdk-android`; brand.json is final
  (Strait, https://usestrait.com, strait.link, Strait Technologies).
- Shared vectors (`conformance-vectors.json`) follow the rename.

## 0.4.0 (2026-10-03)

Report every link open exactly once (shared-spec/SDK-CONTRACT.md B14, plus the
B4/B6/B7 revisions). Shared vectors are now `conformance-vectors.json` v2.

- Every open gets an id from `newOpenId` (`o_<base36 ms>_<12 × [a-z0-9]>`),
  which is also `LinkEvent.id` (was `evt_<ms>_<n>`).
- Short links send `openId`, `appState`, `firstLaunch` and `at` with
  `/v1/resolve`. If the reply lacks `recorded: true`, the open is reported via
  `/v1/open`.
- Custom-scheme hand-offs and your own https links are reported via
  `POST /v1/open` after the event is emitted, so navigation never waits.
- A `bridge_click` tap id is removed from the destination (`takeClickId`) and
  sent as `clickId`. `classifyUrl` now returns `clickId`.
- Reports that get no answer, 429 or 5xx are saved under `bridge.pendingOpens`
  in `storage` and retried on start, on `onAppState(ACTIVE)` and after any
  successful report (pruned to 7 days / 100). New `pendingOpenReports()` and
  `flushOpenReports()`.
- Deferred: `/v1/referrer` now sends `clickId` (`parseBridgeClick`), `openId`
  and `at`; `/v1/match` sends the same `openId` and `at`. The once-per-install
  flag is set only once the engine answered (no answer / 429 / 5xx → `network`,
  retried next launch). `checkDeferred()` sends no `openId`.
- New pure helpers: `parseBridgeClick`, `takeClickId`, `pruneOpenQueue`,
  `shouldRetryReport`, `newOpenId`, `OPEN_QUEUE_MAX`, `OPEN_QUEUE_MAX_AGE_MS`.

### Packaging

- Publish-ready on JitPack: `maven-publish` with sources + javadoc jars and full
  POM metadata (name, description, URL, MIT license, developer, SCM, issues),
  Gradle wrapper and `jitpack.yml` (JDK 17). Coordinates
  `com.github.<owner>:<repo>:v<version>`. Test vectors never ship.
- Owner, repo name, URLs and copyright holder come from `brand.json` (read by
  Gradle directly; README install block + LICENSE applied by `scripts/brand.mjs`).
- Tag `vX.Y.Z` → GitHub Actions runs the tests, builds the Maven artifacts and
  (once switched on) asks JitPack to build the tag. See PUBLISHING.md.

## 0.3.0 (2026-10-03)

Parity with the React Native SDK (shared-spec/SDK-CONTRACT.md B1–B13).

- **New `BridgeClient`**: `start(initialUrl)`, `handleUrl`, `onAppState`,
  `onLink` (with replay), `onLinkStart`, `checkDeferred`, `reportFingerprint`,
  `compareFingerprint`, `trackEvent` and `stop`. It is pure JVM, and the Android
  pieces are injected through `BridgeConfig`.
- Short links on the endpoint host or a configured `linkHosts` entry are
  resolved via `POST /v1/resolve`. The engine's `reason` (`expired`,
  `not_found`, …) is reported.
- Deferred check runs once per install (`bridge.deferredChecked`) and is skipped
  when the first launch was opened by a link. The referrer `bridge_link` goes to
  `/v1/referrer`, with a fallback to `/v1/match`.
- App-state labels (`closed` / `background` / `foreground`) come from
  `AppStateTracker`, which handles Android's brief delivery pause and links
  delivered before resume.
- Pure helpers in `Core.kt` (`browserScreenWidth`, `splitUrl`, `parseBridgeLink`,
  `classifyUrl`, `AppStateTracker`) pass `conformance-vectors.json`.
- Link handling never throws. A network failure gives `matched=false`,
  `reason="network"`.
- `parseBridgeLink` now %-decodes the link id (`lnk%5F7` → `lnk_7`), matching
  the reference.
- Build: `org.json` is now `compileOnly` (Android provides it). Version is set
  to 0.3.0.
