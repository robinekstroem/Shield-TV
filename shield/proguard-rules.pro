# Optimera, men behåll namnen: felrapporten på Shield ska gå att läsa.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Annoteringar som bara finns vid byggtid i biblioteken under Media3.
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**

# FFmpeg-ljudet: Media3 hittar avkodaren via reflektion, och den anropas från
# maskinkoden. Inget i den får tas bort eller döpas om.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
