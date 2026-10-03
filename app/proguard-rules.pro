# Strip all android.util.Log calls from the release build.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}

# JNA (native binding used by lazysodium) — reflection + native, keep intact.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# libsodium / lazysodium.
-keep class com.goterl.lazysodium.** { *; }
-dontwarn com.goterl.lazysodium.**

# Tor (tor-android service + jtorctl control library).
-keep class org.torproject.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-dontwarn org.torproject.**

# ZXing.
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }
-dontwarn com.journeyapps.**

# kotlinx.serialization — keep generated serializers for our models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class org.cmchat.app.** {
    *** Companion;
}
-keepclasseswithmembers class org.cmchat.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.cmchat.app.**$$serializer { *; }
