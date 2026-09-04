# ============================================================
# 老郑分享 Release 版 ProGuard / R8 混淆规则
# ============================================================

# ---------- Room 数据库：保留实体/DAO ----------
-keep class com.shangyin.app.data.db.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep class androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ---------- Kotlinx Serialization / JSON ----------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.shangyin.app.**$$serializer { *; }
-keepclassmembers class com.shangyin.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.shangyin.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class kotlinx.serialization.** { *; }

# ---------- OkHttp ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---------- Jsoup ----------
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }

# ---------- Coil 图片加载 ----------
-keep class coil.** { *; }
-dontwarn coil.**

# ---------- Compose / Navigation ----------
-keep class androidx.compose.** { *; }
-keep class androidx.navigation.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# ---------- 数据模型类：所有数据类字段名通过 JSON 使用 ----------
-keep class com.shangyin.app.data.** { *; }
-keep class com.shangyin.app.data.douban.** { *; }

# ---------- SettingsStore 使用 SharedPreferences 反射字段无需保留，但序列化对象保 ----------
-keep class com.shangyin.app.ui.settings.SettingsStore { *; }
