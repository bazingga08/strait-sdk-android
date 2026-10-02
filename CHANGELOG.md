# Changelog

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
