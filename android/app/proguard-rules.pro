# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class app.textapp.**$$serializer { *; }
-keepclassmembers class app.textapp.** { *** Companion; }
-keepclasseswithmembers class app.textapp.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit
-keepattributes Signature, Exceptions
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# Bouncy Castle (only the low-level RFC 7748 X25519 is used)
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.math.ec.rfc7748.X25519 { *; }
