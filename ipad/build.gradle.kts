plugins {
    kotlin("multiplatform") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.12.1"
}

/**
 * Utseendet delas med Windows och Shield: teman, dekorationer, ikoner och
 * komponenter tas ordagrant från windows/ vid varje bygge. Det som är
 * iPadens eget (nätverk, spelare, lagring, sidorna) ligger i src/iosMain.
 */
val windowsSources: File = file("../windows/src/main/kotlin")
val sharedUi = listOf(
    "se/norrsken/tv/desktop/ui/Theme.kt",
    "se/norrsken/tv/desktop/ui/Decorations.kt",
    "se/norrsken/tv/desktop/ui/Glyphs.kt",
    "se/norrsken/tv/desktop/ui/Components.kt",
)

val syncSharedUi by tasks.registering(Sync::class) {
    description = "Hämtar det delade utseendet från Windows-appen."
    from(windowsSources) {
        include(sharedUi)
    }
    into(layout.buildDirectory.dir("shared/kotlin"))
}

kotlin {
    iosArm64 {
        binaries.framework {
            // Swift-delen i iosApp importerar det här: import TvKit.
            baseName = "TvKit"
            isStatic = false
        }
    }
    sourceSets {
        val iosMain by getting {
            kotlin.srcDir(syncSharedUi)
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
                implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
                implementation("org.jetbrains.compose.ui:ui:1.12.1")
                implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
            }
        }
    }
}
