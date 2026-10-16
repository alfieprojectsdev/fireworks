pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // :core resolves entirely from Maven Central, so `./gradlew :core:test`
        // works on a box with no Android SDK and no access to dl.google.com.
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing.*")
            }
        }
    }
}

rootProject.name = "mirrored-lotus-native"

include(":core")

// :app needs an SDK. Include it only when one is actually available, so the
// pure-JVM maths and story logic stay testable anywhere (same rule as
// stable-still). Android Studio always satisfies this check.
val hasAndroidSdk = file("local.properties").let { lp ->
    lp.exists() && lp.readLines().any { it.trimStart().startsWith("sdk.dir") }
} || System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null

if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle(
        "mirrored-lotus: no Android SDK detected - skipping :app. " +
            "Only the pure-JVM :core module is configured."
    )
}
