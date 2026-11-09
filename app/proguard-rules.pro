# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.milenote.android.**$$serializer { *; }
-keepclassmembers class com.milenote.android.** { *** Companion; }
-keepclasseswithmembers class com.milenote.android.** { kotlinx.serialization.KSerializer serializer(...); }

# Room 等基础规则由各自 AAR 提供，若 release 构建报错在此补充。

# Tesseract4Android：TessBaseAPI / PageIterator 的 native 方法靠类名与方法名绑定（JNI），
# AAR 不带 consumer rules，必须显式保留，否则 R8 混淆后调用原生库会 UnsatisfiedLinkError。
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-dontwarn com.googlecode.tesseract.android.**
-dontwarn com.googlecode.leptonica.android.**

# kotlinx.serialization 生成的 serializer 已被 domain 规则覆盖（com.milenote.android.**）
-dontwarn org.jetbrains.kotlinx.**
