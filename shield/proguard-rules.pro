# Optimera, men behåll namnen: felrapporten på Shield ska gå att läsa.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Annoteringar som bara finns vid byggtid i biblioteken under Media3.
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
