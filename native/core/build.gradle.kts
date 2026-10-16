import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :core is deliberately free of Android dependencies. Everything that decides
// where a pixel goes, when an act begins, or what a sample sounds like lives
// here, so it is tested on a laptop in milliseconds instead of a phone in minutes.
dependencies {
    testImplementation(libs.junit)
    // Tests only: parses the session export back to prove it is valid JSON.
    testImplementation(libs.org.json)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
