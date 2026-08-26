# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\kande\AppData\Local\Android\Sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Jetpack Compose rules
-keep class androidx.compose.** { *; }

# Health Connect records and models
-keep class androidx.health.connect.client.records.** { *; }
-keep interface androidx.health.connect.client.records.** { *; }
-keep class androidx.health.connect.client.units.** { *; }

# Room Database entities and DAOs
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }

# Gson serialization rules
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.vivohealthbridge.data.model.** { *; }

# Keep data models
-keep class com.vivohealthbridge.** { *; }
