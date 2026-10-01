// Windows-versionen är ett eget Gradle-bygge, helt skilt från Android-bygget
// i roten. Går något fel här påverkas inte APK:n — och tvärtom.
//
// Koden som delas med Android-appen hämtas i build.gradle.kts (se
// sharedFromAndroid), inte via ett gemensamt modul. Det gör att Android-
// projektet inte behövde byggas om för att Windows skulle kunna testas.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // androidx-biblioteken som Compose Multiplatform drar in ligger bara hos Google.
        google()
    }
}

rootProject.name = "tv-windows"
