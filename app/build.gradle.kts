import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/*
 * Build configuration comes from local.properties (git-ignored) so the AES secret that pairs this
 * player with the AMS backend never lands in the repository. gradle.properties holds harmless
 * defaults so a fresh clone still configures; a build with the placeholder key will fail to decrypt
 * any response, which is the intended loud failure rather than a silent wrong one.
 *
 * Required keys in local.properties:
 *   digi.apiBaseUrl=http://10.0.2.2:5000/api/v1/
 *   digi.aesSecretKey=<the backend's AES_SECRET_KEY, verbatim>
 *   digi.aesIv=<the backend's AES_IV, 32 hex chars>
 *   digi.aesEnabled=true
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun buildValue(key: String, fallback: String): String =
    (localProps.getProperty(key) ?: project.findProperty(key) as String? ?: fallback).trim()

android {
    namespace = "com.example.digi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.digi"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", "\"${buildValue("digi.apiBaseUrl", "http://10.0.2.2:5000/api/v1/")}\"")
        buildConfigField("String", "AES_SECRET_KEY", "\"${buildValue("digi.aesSecretKey", "CHANGE_ME")}\"")
        buildConfigField("String", "AES_IV", "\"${buildValue("digi.aesIv", "00000000000000000000000000000000")}\"")
        buildConfigField("boolean", "AES_ENABLED", buildValue("digi.aesEnabled", "true"))
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time on API 24 — the schedule engine works in the screen's own IANA timezone and
        // the backend sends wall-clock "HH:mm" strings, so ZoneId/LocalTime are not optional.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Real-time content nudges. Optional in practice: every failure path in RealtimeChannel falls
    // back to the heartbeat, so a network that blocks sockets costs latency, not function.
    implementation(libs.socketio.client) {
        // engine.io-client ships its own OkHttp; the app already has one and two on a classpath is
        // a coin toss over which TLS stack the interceptors actually run against.
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
        exclude(group = "org.json", module = "json")
    }

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
