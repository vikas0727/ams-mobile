# ── kotlinx.serialization ────────────────────────────────────────────────────
# The generated serializers are referenced reflectively through the companion object; R8 cannot see
# that link and would strip them, which surfaces at runtime as "Serializer for class X not found"
# on the first API call of a release build only.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.example.digi.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.digi.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.digi.data.remote.dto.**$$serializer { *; }
-keep class com.example.digi.data.remote.dto.** { *; }

# ── Retrofit / OkHttp ────────────────────────────────────────────────────────
-keepattributes Signature, Exceptions, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# ── Room ─────────────────────────────────────────────────────────────────────
-keep class androidx.room.RoomDatabase { *; }
-keep class com.example.digi.data.local.entity.** { *; }

# ── Media3 ───────────────────────────────────────────────────────────────────
-dontwarn androidx.media3.**

# ── Reflection used by the device controller ─────────────────────────────────
# REBOOT_DEVICE and the kiosk paths reach hidden framework APIs by reflection; keeping the call
# sites out of R8's hands avoids a silently no-op command on a release build.
-keep class com.example.digi.device.DeviceController { *; }
