import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Every build gets a newer versionCode (minutes since the epoch), so a sideload
// over WiFi always upgrades instead of failing as "already installed" - what
// serve_apk.sh did by editing build.gradle for the web build.
val buildMinutes = (System.currentTimeMillis() / 60_000L).toInt()
val buildStamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm").apply {
    timeZone = TimeZone.getTimeZone("Asia/Manila")
}.format(Date())

android {
    namespace = "dev.alfieprojects.mirroredlotus"
    compileSdk = 35

    defaultConfig {
        // Distinct from the Capacitor build (com.alfie.anniversary) so both
        // install side by side for a field A/B, and either is the other's rollback.
        applicationId = "dev.alfieprojects.mirroredlotus"
        // Galaxy A56 and A07 both ship Android 15+; 26 keeps adaptive vector icons simple.
        minSdk = 26
        targetSdk = 35
        versionCode = buildMinutes
        versionName = "0.1.1-native"
        buildConfigField("String", "BUILD_STAMP", "\"$buildStamp\"")
        // The HUD, session export, field tests, test mode and the adb launch
        // switches. Off only in `release`, the build that gets published.
        buildConfigField("boolean", "DEV_TOOLS", "true")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A committed debug key, so a build from the laptop, the cloud or any
        // clone can update an install from any other. Per-machine debug keys made
        // installs fail with INSTALL_FAILED_UPDATE_INCOMPATIBLE. Debug and field
        // builds only; it must never sign a release.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The published key lives outside the repo, because the debug key above
        // is public and anyone could sign an "update" with it. Point the
        // lotus.release.* properties in ~/.gradle/gradle.properties at it;
        // without them assembleRelease still builds, unsigned.
        val releaseStore = providers.gradleProperty("lotus.release.storeFile").orNull
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.gradleProperty("lotus.release.storePassword").get()
                keyAlias = providers.gradleProperty("lotus.release.keyAlias").get()
                keyPassword = providers.gradleProperty("lotus.release.keyPassword").get()
            }
        }
    }

    buildTypes {
        // The public build. DEV_TOOLS=false turns the debug surfaces into dead
        // code, and R8 deletes it along with every android.util.Log call.
        release {
            buildConfigField("boolean", "DEV_TOOLS", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // What goes on the phone for field tests. A debuggable build runs under
        // ART's debug mode. On the A07 that halved the frame rate and tripled the
        // photon age (31 fps / 155 ms against 59 fps / 58 ms for the same code).
        // Same app id and key as debug, so either replaces the other.
        create("field") {
            initWith(getByName("debug"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            versionNameSuffix = "-field"
            matchingFallbacks += "debug"
        }
    }

    // Instrumented tests run against the field build by default, so
    // DeviceSmokeTest's timing checks measure what the phone will actually run.
    // `-Plotus.testBuildType=debug` tests the debuggable build instead.
    testBuildType = providers.gradleProperty("lotus.testBuildType").getOrElse("field")

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Portrait lock is the design (the pose maths assumes the device frame is
        // the camera frame); lint's large-screen advice doesn't apply to this app.
        disable += setOf("DiscouragedApi", "LockedOrientationActivity")
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.play.services.location)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
