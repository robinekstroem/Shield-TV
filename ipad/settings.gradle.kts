// iPad-versionen: ett eget Gradle-bygge för Apples plattform, med Kotlin
// Multiplatform och Compose för iOS. GitHub bygger den på en Mac, se
// .github/workflows/ipad.yml. Samma versioner som Windows-bygget.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tv-ipad"
