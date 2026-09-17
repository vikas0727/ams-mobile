// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    // Declared here, applied conditionally by :app — see the google-services.json check in
    // app/build.gradle.kts. Both plugins hard-fail a build that has no google-services.json, and a
    // player that will not compile without a Firebase project is a worse outcome than one that
    // reports no crashes.
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}
