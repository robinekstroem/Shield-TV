package se.norrsken.tv.desktop.data

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64

/**
 * Windows motsvarighet till Android Keystore i CredentialStore.
 *
 * DPAPI krypterar med en nyckel som hör till det inloggade Windows-kontot.
 * Filen går inte att läsa av ett annat konto eller på en annan dator —
 * kopierar någon %APPDATA% får de chiffertext, inte lösenord.
 *
 * Samma regel som på Android: går skyddet inte att använda sparas
 * hemligheten inte alls, aldrig i klartext som reserv.
 */
object SecretBox {

    val available: Boolean by lazy {
        Platform.isWindows() && runCatching {
            val probe = "tv".toByteArray()
            Crypt32Util.cryptUnprotectData(Crypt32Util.cryptProtectData(probe)).contentEquals(probe)
        }.getOrDefault(false)
    }

    fun seal(bytes: ByteArray): ByteArray? =
        if (!available) null else runCatching { Crypt32Util.cryptProtectData(bytes) }.getOrNull()

    fun unseal(bytes: ByteArray): ByteArray? =
        if (!available) null else runCatching { Crypt32Util.cryptUnprotectData(bytes) }.getOrNull()

    fun sealText(text: String): String? =
        seal(text.toByteArray(Charsets.UTF_8))?.let { Base64.getEncoder().encodeToString(it) }

    fun unsealText(sealed: String?): String? {
        if (sealed.isNullOrEmpty()) return null
        val bytes = runCatching { Base64.getDecoder().decode(sealed) }.getOrNull() ?: return null
        return unseal(bytes)?.toString(Charsets.UTF_8)
    }
}
