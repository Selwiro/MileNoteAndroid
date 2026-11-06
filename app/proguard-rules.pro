# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.milenote.android.**$$serializer { *; }
-keepclassmembers class com.milenote.android.** { *** Companion; }
-keepclasseswithmembers class com.milenote.android.** { kotlinx.serialization.KSerializer serializer(...); }

# jxl / ML Kit / Room 基础规则由各自 AAR 提供，若 release 构建报错在此补充。

# jxl（xls 读取）：使用了少量反射/工厂，保守保留。
-keep class jxl.** { *; }
-dontwarn jxl.**

# ML Kit Text Recognition（AAR 自带 consumer rules，这里兜底）
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# kotlinx.serialization 生成的 serializer 已被 domain 规则覆盖（com.milenote.android.**）
-dontwarn org.jetbrains.kotlinx.**
