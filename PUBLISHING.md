# Publishing (JitPack)

**Registry: JitPack** — chosen for launch speed. It needs no account, no signing key
and no secrets: once the repo is public, any version tag is installable as
`com.github.<owner>:<repo>:v<version>` (JitPack runs `./gradlew publishToMavenLocal`
on the tag, see `jitpack.yml`). The build already produces everything Maven Central
also requires (sources + javadoc jars, full POM), so moving to Central later is
additive (see the end).

Owner, repo name, URLs and copyright holder come from `brand.json` (Gradle reads it
directly; `scripts/brand.mjs` applies it to the README install block and LICENSE).

## One-time owner setup

1. **Pick the brand.** From the workspace root: `shared-spec/scripts/rename-brand.sh … --final --apply`.
   If the GitHub repo is renamed or moved to an org, the coordinates change with it
   (`com.github.<org>:<repo>`), so do that before the first public tag.
2. **Make the GitHub repo public** (JitPack's free tier only builds public repos).
3. Optional: GitHub → *Settings → Secrets and variables → Actions → Variables*:
   `JITPACK_ENABLED` = `true`, so each release asks JitPack to build immediately
   (otherwise the first person to request a version waits for the build).

## Every release

1. Bump `version = "X.Y.Z"` in build.gradle.kts, add a `## X.Y.Z` entry to
   CHANGELOG.md, run `node scripts/brand.mjs --write` (updates the README version), commit.
2. `./gradlew test publishToMavenLocal` and look in
   `~/.m2/repository/com/github/<owner>/<repo>/X.Y.Z/` (jar, -sources, -javadoc, .pom).
3. `git tag vX.Y.Z && git push origin main vX.Y.Z`.
4. *Actions → Release* tests the tag and builds the artifacts; check
   `https://jitpack.io/#<owner>/<repo>` shows the tag as green.

## Later: Maven Central (when you want `com.<brand>` coordinates)

1. central.sonatype.com → sign up → *Namespaces* → add `com.<brand>` (verify with a
   DNS TXT record) or use `io.github.<owner>`.
2. Generate a user token (Account → *Generate User Token*) and a GPG key
   (`gpg --full-generate-key`; publish it: `gpg --keyserver keyserver.ubuntu.com --send-keys <id>`).
3. Add the `com.vanniktech.maven.publish` plugin (handles Central upload + signing),
   set `group = "com.<brand>"`, and add secrets `MAVEN_CENTRAL_USERNAME`,
   `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` (armored private key), `SIGNING_PASSWORD`;
   run `./gradlew publishAndReleaseToMavenCentral` from the release workflow.
4. Swap `withJavadocJar()` for Dokka if you want real API docs in the javadoc jar.
