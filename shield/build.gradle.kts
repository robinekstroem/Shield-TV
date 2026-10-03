plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Shield-appen är Windows-appen. Skärmar, teman, tablå, genrer, historik och
 * TMDB tas ordagrant från windows/ vid varje bygge, så det som byggs in i
 * Windows följer med hit. Bara filerna nedan har egna Android-versioner i
 * src/main/kotlin, med samma namn och samma funktioner: spelaren, bildytan,
 * lagringen och det som rör Windows själv.
 */
val windowsSources: File = file("../windows/src/main/kotlin")
val platformFiles = listOf(
    "se/norrsken/tv/desktop/Main.kt",
    "se/norrsken/tv/desktop/data/AppDirs.kt",
    "se/norrsken/tv/desktop/data/SecretBox.kt",
    "se/norrsken/tv/desktop/data/XmltvReader.kt",
    "se/norrsken/tv/desktop/player/VideoPlayer.kt",
    "se/norrsken/tv/desktop/player/VlcRuntime.kt",
    "se/norrsken/tv/desktop/player/YuvRenderer.kt",
    "se/norrsken/tv/desktop/platform/WindowsIntegration.kt",
    "se/norrsken/tv/desktop/ui/Images.kt",
    "se/norrsken/tv/desktop/ui/Platform.kt",
)

/** Koden som telefonappen och Windows redan delar (se windows/build.gradle.kts). */
val androidSources: File = file("../app/src/main/java")
val sharedFromAndroid = listOf(
    "se/norrsken/tv/data/playlist/M3uParser.kt",
    "se/norrsken/tv/data/playlist/SourceDetection.kt",
    "se/norrsken/tv/data/playlist/ImportState.kt",
    "se/norrsken/tv/data/remote/PlaylistDownloader.kt",
    "se/norrsken/tv/data/xtream/XtreamClient.kt",
    "se/norrsken/tv/data/xtream/XtreamModels.kt",
    "se/norrsken/tv/data/security/UrlSanitizer.kt",
)

/** Dit den delade koden kopieras före varje bygge. */
val sharedDir: File = layout.buildDirectory.dir("shared/kotlin").get().asFile

val syncSharedSources by tasks.registering(Sync::class) {
    description = "Hämtar Windows-appens gemensamma kod och den delade koden från telefonappen."
    from(windowsSources) {
        exclude(platformFiles)
    }
    from(androidSources) {
        include(sharedFromAndroid)
    }
    into(sharedDir)
}

android {
    namespace = "se.norrsken.tv.shield"
    compileSdk = 37

    defaultConfig {
        // Eget id: Shield-appen installeras bredvid telefonappen, aldrig över den.
        applicationId = "se.norrsken.tv.shield"
        // Shield kör Android 11. 26 räcker för java.time och java.nio.file i den delade koden.
        minSdk = 26
        targetSdk = 37
        // Alltid högre än förra versionen, så att en ny version installeras ovanpå
        // den gamla utan att något behöver raderas: minuter sedan 2024 när GitHub
        // bygger. Samma fasta nyckel (debug.keystore) gör resten.
        versionCode = if (System.getenv("GITHUB_ACTIONS") == "true") {
            (System.currentTimeMillis() / 60_000L - 28_401_120L).toInt()
        } else {
            1
        }
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        getByName("debug") {
            // Fast nyckel i repot. Med den slumpade nyckel varje ny byggmaskin
            // skapar vägrar Shield att installera en uppdatering ovanpå.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Den riktiga versionen: optimerad, och märkbart snabbare än en
            // felsökningsversion. Samma nyckel som förut, så den installeras
            // ovanpå den gamla. Namnen behålls, så att felrapporten går att läsa.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets {
        getByName("main") {
            // En vanlig sökväg: AGP 9 tar inte emot Providers här. Att koden
            // finns på plats före kompileringen ordnar preBuild längre ned.
            kotlin.srcDir(sharedDir)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// Den delade koden måste vara på plats innan något kompileras.
tasks.named("preBuild") {
    dependsOn(syncSharedSources)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.coroutines.android)
    // @Inject i koden som delas med telefonappen.
    implementation("javax.inject:javax.inject:1")
}
