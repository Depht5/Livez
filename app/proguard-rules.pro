# 混淆规则（Room/kotlinx.serialization/OkHttp 需要保留规则）
-keep class dev.livefork.app.models.db.** { *; }
-keep class dev.livefork.core.model.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.livefork.**$$serializer { *; }
-keepclassmembers class dev.livefork.** { *** Companion; }
-keepclasseswithmembers class dev.livefork.** { kotlinx.serialization.KSerializer serializer(...); }
