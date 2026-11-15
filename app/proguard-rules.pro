# kotlinx.serialization keeps its generated serializers reflectively for generic lookups.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class dev.immich.picker.api.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class dev.immich.picker.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,allowobfuscation,allowshrinking class kotlinx.serialization.descriptors.SerialDescriptor
-keep,allowobfuscation,allowshrinking class kotlinx.serialization.encoding.**

# OkHttp platform internals are optional on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
