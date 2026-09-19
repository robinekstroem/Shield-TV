// AGP 9 kompilerar Kotlin själv. Applicerar man org.jetbrains.kotlin.android
// ovanpå det kraschar bygget: "The 'org.jetbrains.kotlin.android' plugin is no
// longer required for Kotlin support since AGP 9.0."
//
// Compose-pluginen är däremot kvar — den ingår inte i det inbyggda stödet.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}
