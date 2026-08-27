# 混淆规则（Room/kotlinx.serialization/OkHttp 需要保留规则）
-keep class com.kotlin_livez.app.models.db.** { *; }
-keep class com.kotlin_livez.core.model.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.kotlin_livez.**$$serializer { *; }
-keepclassmembers class com.kotlin_livez.** { *** Companion; }
-keepclasseswithmembers class com.kotlin_livez.** { kotlinx.serialization.KSerializer serializer(...); }
