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

## Maven Central (ready; switched on by the founder's steps)

Built and tested (SDK-26, 21 Oct): `CENTRAL=true` switches the group to **`in.straitlink`** (the reverse
of straitlink.in) and refuses to publish without a signature; `publishReleasePublicationToCentralStagingRepository`
writes a signed bundle (jar, sources, javadoc, .pom, .module, md5/sha1/sha256/sha512, .asc) to
`build/central-staging/`. JitPack and normal builds are unchanged (unsigned, `com.github.<owner>`).
`.github/workflows/central.yml` (run by hand on a tag) tests, builds the bundle and uploads it as
**USER_MANAGED**: nothing goes public until you press Publish on central.sonatype.com. Without the
secrets it tests and stops. Installs would then read `implementation("in.straitlink:strait-sdk-android:X.Y.Z")`.

### Founder steps (about 20 minutes, once)

1. **Account (5 min):** central.sonatype.com → *Sign in* → sign up with **dev@straitlink.in**.
2. **Namespace (5 min + DNS wait):** *Namespaces* → *Add namespace* → `in.straitlink`. It shows a
   verification code: add a **TXT** record on **straitlink.in** (root `@`) with that code, then press
   *Verify*. Works once the DNS change is visible (minutes to an hour).
3. **Token (2 min):** *Account* (top right) → *Generate User Token* → it shows a username and a password.
   Put them in the safe file as `MAVEN_CENTRAL_USERNAME=` and `MAVEN_CENTRAL_PASSWORD=`
   (`! open -a TextEdit ~/Desktop/work/strait/redirect-engine/.env`), save.
4. **Signing key (Claude does it):** Claude makes the key on the Mac
   (`gpg --quick-gen-key "Strait <dev@straitlink.in>" ed25519 sign 2y`), publishes the public half to
   keyserver.ubuntu.com, and puts `SIGNING_KEY` / `SIGNING_PASSWORD` plus the two token values into the
   GitHub repo's Actions secrets (needs your OK for secret writes).
5. **First release:** Actions → *Maven Central* → Run workflow on the tag. Then central.sonatype.com →
   *Deployments* → check it says *Validated* → **Publish**. How you know it worked: the version shows on
   central.sonatype.com/artifact/in.straitlink/strait-sdk-android within about 30 minutes.

If the GitHub repo moves to the strait-link org, nothing here changes (the group is the domain, not GitHub).
