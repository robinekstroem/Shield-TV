import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Kotlin och Compose Multiplatform hör ihop: Compose-kompilatorn har alltid
// samma version som Kotlin. Säger Gradle "Could not find" på någon rad —
// felmeddelandet listar vilka versioner som finns. Byt här.
plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.12.1"
}

group = "se.norrsken.tv"

// Varje bygge får ett högre versionsnummer: 1.0.<nummer>. "Installera Tv"
// räknar upp TV_BUILD_NUMBER, GitHub använder körningens nummer. Windows
// Installer ersätter bara en installerad version med en nyare, så utan det
// skulle en ny Tv.msi inte gå att installera ovanpå den gamla.
val appVersion = "1.0." + (System.getenv("TV_BUILD_NUMBER") ?: System.getenv("GITHUB_RUN_NUMBER") ?: "0")
version = appVersion

/**
 * Kod som Windows-versionen delar med Android-appen, ordagrant.
 *
 * Filerna kopieras in från app/ vid varje bygge i stället för att dubbleras,
 * så en rättning i t.ex. M3U-parsern eller Xtream-klienten gäller båda
 * plattformarna direkt. Förutsättningen är att filerna förblir ren Kotlin:
 * en android.*-import i någon av dem och Windows-bygget slutar kompilera.
 * Varje fil har en kommentar högst upp som säger det.
 */
val sharedFromAndroid = listOf(
    "se/norrsken/tv/data/playlist/M3uParser.kt",
    "se/norrsken/tv/data/playlist/SourceDetection.kt",
    "se/norrsken/tv/data/playlist/ImportState.kt",
    "se/norrsken/tv/data/remote/PlaylistDownloader.kt",
    "se/norrsken/tv/data/xtream/XtreamClient.kt",
    "se/norrsken/tv/data/xtream/XtreamModels.kt",
    "se/norrsken/tv/data/security/UrlSanitizer.kt",
)

val androidSources: File = file("../app/src/main/java")

val syncSharedSources by tasks.registering(Sync::class) {
    description = "Kopierar koden som delas med Android-appen."
    from(androidSources) {
        include(sharedFromAndroid)
    }
    into(layout.buildDirectory.dir("shared-android/kotlin"))

    // Flyttas eller döps en fil om i Android-projektet ska felet säga vilken,
    // inte dyka upp som hundra "Unresolved reference" längre ned.
    val expected = sharedFromAndroid.map { androidSources.resolve(it) }
    doFirst {
        val missing = expected.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Delad kod från Android-appen saknas:\n" +
                    missing.joinToString("\n") { "  " + it.path } +
                    "\nUppdatera listan sharedFromAndroid i windows/build.gradle.kts.",
            )
        }
    }
}

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(syncSharedSources)
    }
}

dependencies {
    // Skia-renderaren för det operativsystem bygget körs på: Windows hos
    // "Installera Tv", Mac (Apple Silicon eller Intel) hos GitHub.
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
    implementation("org.jetbrains.compose.ui:ui:1.12.1")
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // Spelaren. vlcj 4.x hör till VLC 3.x; vlcj 5 kräver VLC 4 som inte är släppt.
    // 4.8.3 hålls fast medvetet: 4.10 ändrade callback-API:t som
    // VideoPlayer.kt bygger på.
    implementation("uk.co.caprica:vlcj:4.8.3")
    // Windows DPAPI (lösenord), energisparläge och mörk titelrad; på Mac
    // laddningen av VLC:s bibliotek.
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // Beroenden för den delade Android-koden. På Android finns org.json i
    // systemet och javax.inject kommer med Hilt.
    implementation("org.json:json:20240303")
    implementation("javax.inject:javax.inject:1")
}

compose.desktop {
    application {
        mainClass = "se.norrsken.tv.desktop.MainKt"
        // Visas under Inställningar → Om appen.
        jvmArgs("-Dtv.version=$appVersion")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Tv"
            packageVersion = appVersion
            description = "Live TV, filmer och serier"
            vendor = "Norrsken"

            // Hela Java-runtimen följer med. Större paket, men inget fel av typen
            // "NoClassDefFoundError" för att en modul fattades — det går inte att
            // felsöka på distans. Går att banta senare med suggestRuntimeModules.
            includeAllModules = true

            // VLC följer med appen. Windows: libvlc.dll, libvlccore.dll och plugins/
            // läggs i app-resources/windows-x64/vlc av "Installera Tv". Mac: Macens
            // VLC (lib/ och plugins/) läggs i Tv.app av GitHub-bygget, se
            // .github/workflows/mac.yml, så att filerna behåller sina länkar.
            appResourcesRootDir.set(layout.projectDirectory.dir("app-resources"))

            // Mac: samma app, som Tv.app. Byggs av GitHub på en Mac, en gång för
            // Apple Silicon och en för Intel (.github/workflows/mac.yml).
            macOS {
                bundleID = "se.norrsken.tv"
                dockName = "Tv"
                appCategory = "public.app-category.entertainment"
                iconFile.set(project.file("icon.icns"))
            }

            // Installationsfilen: en .msi som installerar för den inloggade
            // användaren — inga administratörsrättigheter, inga frågor om mapp.
            // Genväg i Start-menyn och på skrivbordet.
            windows {
                iconFile.set(project.file("icon.ico"))
                menu = true
                menuGroup = "Tv"
                shortcut = true
                perUserInstall = true
                // Fast id: en ny version ersätter den gamla i stället för att
                // installeras bredvid. Ändra aldrig.
                upgradeUuid = "3f1b0c62-5a4e-4f7a-9a64-6c1f0b1e7d21"
            }
        }
    }
}
