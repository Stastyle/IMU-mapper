# Minification is disabled for this app (see build.gradle.kts). Rules kept for the future.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class com.google.ar.** { *; }
-keep,includedescriptorclasses class com.stastyle.imumapper.**$$serializer { *; }
-keepclassmembers class com.stastyle.imumapper.** { *** Companion; }
-keepclasseswithmembers class com.stastyle.imumapper.** { kotlinx.serialization.KSerializer serializer(...); }
