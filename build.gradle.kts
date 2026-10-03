plugins {
    kotlin("jvm") version "1.9.24"
}

group = "dev.bridge"
version = "0.4.0"

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
