import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/*
 * Firebase is optional at build time, and deliberately so.
 *
 * Both Google plugins fail the build outright when `app/google-services.json` is absent — not a
 * warning, a hard error. That file comes out of a Firebase console project and is not in the repo,
 * so applying them unconditionally would mean nobody could build this app without first being
 * given access to a Firebase project. For a player whose whole job is to keep running, refusing to
 * compile is the worse failure.
 *
 * So: drop the file in and crash reporting builds in; leave it out and everything else still
 * compiles, with FirebaseTelemetry reporting itself unavailable at runtime. The Firebase libraries
 * stay on the classpath either way — without the generated config FirebaseApp simply never
 * initialises, which is the state that facade is written around.
 */
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
} else {
    logger.lifecycle(
        "[digi] app/google-services.json not found — building without Firebase Crashlytics/Analytics.",
    )
}

/*
 * Build configuration comes from local.properties (git-ignored) so the AES secret that pairs this
 * player with the AMS backend never lands in the repository. gradle.properties holds harmless
 * defaults so a fresh clone still configures; a build with the placeholder key will fail to decrypt
 * any response, which is the intended loud failure rather than a silent wrong one.
 *
 * Keys in local.properties:
 *   ams.<flavor>.apiBaseUrl=...    per build: ams.dev.apiBaseUrl, ams.uat.apiBaseUrl, ams.prod.apiBaseUrl
 *   digi.apiBaseUrl=...            older single address, still read when no per-flavor one is set
 *   digi.aesSecretKey=<the backend's AES_SECRET_KEY, verbatim>   legacy v1 envelope only
 *   digi.aesIv=<the backend's AES_IV, 32 hex chars>               legacy v1 envelope only
 *   digi.aesEnabled=true
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun buildValue(key: String, fallback: String): String =
    (localProps.getProperty(key) ?: project.findProperty(key) as String? ?: fallback).trim()

/*
 * The three builds - dev, uat, prod - as product flavors. Each carries its own API address, its own
 * public encryption key and its own app name, so choosing an environment is choosing a Gradle task:
 *
 *   ./gradlew assembleProdRelease     ->  app/build/outputs/apk/prod/release/app-prod-release.apk
 *   ./gradlew assembleUatRelease      ->  app/build/outputs/apk/uat/release/app-uat-release.apk
 *   ./gradlew assembleDevDebug        ->  app/build/outputs/apk/dev/debug/app-dev-debug.apk
 *
 * The address comes from `ams.<flavor>.apiBaseUrl` in local.properties, else the older
 * `digi.apiBaseUrl`, else the default below. UAT and PROD have no committed default: set them in
 * local.properties (https - release builds are HTTPS-only). The public key comes from the committed
 * crypto-server-keys.properties (written by the backend's `npm run crypto:keys`), by flavor name;
 * `ams.<flavor>.cryptoServerKey` overrides.
 *
 * All three keep the same application id, so a box holds one of them at a time - installing
 * another replaces it, and a box already paired with this app keeps its pairing.
 */
data class PlayerEnv(val name: String, val apiBaseUrl: String, val appName: String)

val playerEnvs = listOf(
    // The emulator's alias for this machine. Plain http, so use the debug build (release is HTTPS-only).
    PlayerEnv("dev", "http://10.0.2.2:5000/api/v1/", "AMS Player Dev"),
    PlayerEnv("uat", "https://set-ams.uat.apiBaseUrl-in-local.properties.invalid/api/v1/", "AMS Player UAT"),
    PlayerEnv("prod", "https://set-ams.prod.apiBaseUrl-in-local.properties.invalid/api/v1/", "AMS Player"),
)

val committedServerKeys = Properties().apply {
    val f = rootProject.file("crypto-server-keys.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/** `ams.<env>.<name>` from local.properties or -P, else the fallback. */
fun envValue(env: String, name: String, fallback: String): String =
    (localProps.getProperty("ams.$env.$name") ?: project.findProperty("ams.$env.$name") as String? ?: fallback).trim()

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

        buildConfigField("String", "AES_SECRET_KEY", "\"${buildValue("digi.aesSecretKey", "CHANGE_ME")}\"")
        buildConfigField("String", "AES_IV", "\"${buildValue("digi.aesIv", "00000000000000000000000000000000")}\"")
        buildConfigField("boolean", "AES_ENABLED", buildValue("digi.aesEnabled", "true"))
        // Lets the app say "Firebase: not configured in this build" on the diagnostics overlay
        // rather than "not reporting", which reads like a fault.
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseConfigured.toString())
    }

    // API_BASE_URL, CRYPTO_SERVER_KEY, APP_ENV and the app name come from the flavor - see playerEnvs.
    flavorDimensions += "env"
    productFlavors {
        playerEnvs.forEach { env ->
            create(env.name) {
                dimension = "env"
                if (env.name != "prod") versionNameSuffix = "-${env.name}"
                buildConfigField("String", "APP_ENV", "\"${env.name}\"")
                buildConfigField(
                    "String", "API_BASE_URL",
                    "\"${envValue(env.name, "apiBaseUrl", buildValue("digi.apiBaseUrl", env.apiBaseUrl))}\"",
                )
                buildConfigField(
                    "String", "CRYPTO_SERVER_KEY",
                    "\"${envValue(env.name, "cryptoServerKey", committedServerKeys.getProperty(env.name, ""))}\"",
                )
                resValue("string", "app_name", env.appName)
            }
        }
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
        // The per-flavor app name is a resValue.
        resValues = true
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

    /*
     * Crash and usage reporting.
     *
     * Present whether or not google-services.json is, so the code compiles either way; inert
     * without it, because FirebaseApp never initialises and FirebaseTelemetry checks for that.
     *
     * Worth knowing before trusting the numbers: firebase-analytics needs Google Play services,
     * which plenty of cheap Android TV boxes do not ship. Crashlytics does not — it uploads through
     * its own transport — so on a Play-less box crashes still arrive and analytics events do not.
     * The CMS event log (EventReporter) remains the reporting path that works on every device.
     */
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
