pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "strait-example-android"
include(":app")

// The SDK comes from this repo's source, not JitPack: a change that breaks the public API breaks this build in CI.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("com.github.strait-link:strait-sdk-android")).using(project(":"))
    }
}
