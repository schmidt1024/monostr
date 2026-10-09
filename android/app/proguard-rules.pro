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
