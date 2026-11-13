# Keep WebView JS bridge entry points (registered by name via WebMessageListener).
-keepclassmembers class com.arena.core.webview.ArenaBridge {
    *;
}

# Hilt / Dagger
-dontwarn dagger.**
-keep class dagger.** { *; }

# Coroutines debug agent noise
-dontwarn kotlinx.coroutines.debug.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Compose stability
-dontwarn androidx.compose.**

# Never leak session material into crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
