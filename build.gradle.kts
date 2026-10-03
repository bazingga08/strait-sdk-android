plugins {
    kotlin("jvm") version "1.9.24"
    `maven-publish`
}

// Public identity (owner, repo, URLs, copyright holder) comes from brand.json only.
val brand = groovy.json.JsonSlurper().parse(file("brand.json")) as Map<*, *>
val repoUrl = "https://github.com/${brand["githubOwner"]}/${rootProject.name}"

group = "com.github.${brand["githubOwner"]}"
version = "0.5.0"
// JitPack builds from a tag and passes its own coordinates (com.github.<owner>:<repo>:<tag>).
if (System.getenv("JITPACK") == "true") {
    group = System.getenv("GROUP") ?: group
    version = System.getenv("VERSION") ?: version
}

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
}
