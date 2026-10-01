package se.norrsken.tv.desktop.player

/**
 * Windows ritar bildrutorna själv på grafikkortet. På Shield gör
 * hårdvaruavkodaren och tv:ns bildbehandling det, så inställningen för skarp
 * skalning visas inte här.
 */
object YuvRenderer {
    val available: Boolean = false
}
