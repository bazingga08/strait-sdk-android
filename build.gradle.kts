plugins {
    kotlin("jvm") version "1.9.24"
}

repositories { mavenCentral() }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(17) }
