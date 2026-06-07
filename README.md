# bridge-sdk-android (Kotlin)

Deferred deep linking for native Android. Part of [Bridge](../). The match
signature is a Kotlin port kept in lockstep with the server + every other SDK via
[`shared-spec`](../shared-spec) golden vectors (run by `gradle test` in CI).
Kotlin `Int` is 32-bit and wraps like JS `|0`, so the hash ports verbatim.

> ⚠️ Pure-JVM core (signature + resolver helpers) is CI-tested against the golden
> vectors. The full Android wrapper (Context, InstallReferrerClient, display
> metrics, OkHttp) lives in the app layer and needs Android Studio to verify.

## Use (sketch)

```kotlin
val device = DeviceFields(screenWidth, pixelRatio, language, timezone)
// deterministic path: read Play Install Referrer → parseBridgeLink → POST /v1/referrer
// else: POST Bridge.buildMatchBody(appId, device) to /v1/match
```

`computeSignature` / `h32` are the cross-language-verified core; `parseBridgeLink`
extracts `bridge_link` from the Play Install Referrer for the deterministic match.
