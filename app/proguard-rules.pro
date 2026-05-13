# Keep kotlinx.serialization metadata
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class com.lcdcode.curiodb.**$$serializer { *; }
-keepclassmembers class com.lcdcode.curiodb.** {
    *** Companion;
}
-keepclasseswithmembers class com.lcdcode.curiodb.** {
    kotlinx.serialization.KSerializer serializer(...);
}
