# Add project specific ProGuard rules here.
# By default, the flags in this file are applied to all build types.

# Keep Room entities
-keep class com.harvest.rns.data.model.** { *; }
-keep class com.harvest.rns.data.db.** { *; }

# Keep LXMF/RNS parsing classes
-keep class com.harvest.rns.network.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
