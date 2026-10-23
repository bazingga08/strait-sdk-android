plugins {
    kotlin("jvm") version "1.9.24"
    `maven-publish`
    signing
}

// Public identity (owner, repo, URLs, copyright holder) comes from brand.json only.
val brand = groovy.json.JsonSlurper().parse(file("brand.json")) as Map<*, *>
val repoUrl = "https://github.com/${brand["githubOwner"]}/${rootProject.name}"

group = "com.github.${brand["githubOwner"]}"
version = "0.8.1"
// JitPack builds from a tag and passes its own coordinates (com.github.<owner>:<repo>:<tag>).
if (System.getenv("JITPACK") == "true") {
    group = System.getenv("GROUP") ?: group
    version = System.getenv("VERSION") ?: version
}
// Maven Central (SDK-26, alongside JitPack, off by default): CENTRAL=true switches to the verified
// namespace in.straitlink and requires a signature. See PUBLISHING.md, "Maven Central".
val central = System.getenv("CENTRAL") == "true"
if (central) group = "in.straitlink"

repositories { mavenCentral() }

dependencies {
    // org.json ships with Android; compile against it, don't bundle it.
    // Only the API subset present on Android is used (JSONObject(String), opt, put, toString).
    compileOnly("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(17) }

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            artifactId = rootProject.name
            pom {
                name.set("${brand["brand"]} SDK for Android")
                description.set("Deep linking for Android: verified App Links, custom schemes, deferred links (Play Install Referrer, fingerprint fallback) and link-open analytics.")
                url.set(repoUrl)
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("$repoUrl/blob/main/LICENSE")
                    }
                }
                developers {
                    developer {
                        name.set(brand["legalName"].toString())
                        url.set(brand["website"].toString())
                    }
                }
                scm {
                    url.set(repoUrl)
                    connection.set("scm:git:$repoUrl.git")
                    developerConnection.set("scm:git:$repoUrl.git")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("$repoUrl/issues")
                }
            }
        }
    }
    // Central upload bundle: a local Maven layout (jars, .pom, .module, checksums, .asc) the release
    // workflow zips and sends to the Central Portal. JitPack never uses it.
    repositories {
        maven {
            name = "centralStaging"
            url = uri(layout.buildDirectory.dir("central-staging"))
        }
    }
}

signing {
    // Signs only when a key is given (SIGNING_KEY = ASCII-armored private key, SIGNING_PASSWORD).
    // JitPack and normal builds have none and skip signing; CENTRAL=true refuses to publish unsigned.
    isRequired = central
    val key = System.getenv("SIGNING_KEY")
    if (central && key.isNullOrBlank()) throw GradleException("CENTRAL=true needs SIGNING_KEY: Maven Central rejects unsigned files")
    if (!key.isNullOrBlank()) {
        useInMemoryPgpKeys(key, System.getenv("SIGNING_PASSWORD") ?: "")
        sign(publishing.publications["release"])
    }
}
