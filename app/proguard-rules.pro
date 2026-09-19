# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Room-genererade klasser
-keep class * extends androidx.room.RoomDatabase { *; }

# Behåll radnummer så stacktraces i diagnostikläget går att läsa
-keepattributes SourceFile,LineNumberTable
