# JNA: rust-nostr's UniFFI bindings resolve native symbols and callbacks by name
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
# rust-nostr (UniFFI): generated Kotlin classes are looked up reflectively from the FFI layer
-keep class rust.nostr.** { *; }
-dontwarn rust.nostr.**
# kotlinx.serialization: PendingTip and other @Serializable classes (rules from the library docs)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.monostr.**$$serializer { *; }
-keepclassmembers class com.monostr.** { *** Companion; }
-keepclasseswithmembers class com.monostr.** { kotlinx.serialization.KSerializer serializer(...); }

# Debug logging (per-relay publish errors etc.) is stripped from release builds.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# Media3 keeps LogSessionId (Android 12+) in fields. R8's class merging turned such fields into
# Object and read them back with a check-cast to LogSessionId, which fails on Android 8-11 even
# for null: the video encode closed the app there (OnePlus, Android 11, 0.26.0-0.28.1).
# A kept field keeps its type, so no cast is added.
-keepclassmembers class androidx.media3.** { android.media.metrics.** *; }
