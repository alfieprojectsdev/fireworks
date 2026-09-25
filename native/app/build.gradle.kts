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
        versionName = "0.1.0-native"
        buildConfigField("String", "BUILD_STAMP", "\"$buildStamp\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

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
