package se.norrsken.tv.desktop.data

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Lösenordsskyddet, motsvarigheten till Android Keystore i CredentialStore.
 *
 * Windows: DPAPI krypterar med en nyckel som hör till det inloggade
 * Windows-kontot. Filen går inte att läsa av ett annat konto eller på en
 * annan dator — kopierar någon %APPDATA% får de chiffertext, inte lösenord.
 *
 * Mac: en slumpad nyckel ligger i Macens nyckelring, som är låst med
 * inloggningen, och lösenorden krypteras med den (AES-GCM). Kopierar någon
 * appens mapp får de chiffertext; nyckeln följer inte med.
 *
 * Samma regel överallt: går skyddet inte att använda sparas hemligheten inte
 * alls, aldrig i klartext som reserv.
 */
object SecretBox {

    private val isWindows: Boolean = Platform.isWindows()
    private val isMac: Boolean = Platform.isMac()

    private val random = SecureRandom()

    /** Macens nyckel, hämtad ur nyckelringen (eller skapad där) första gången den behövs. */
    private val macKey: SecretKey? by lazy { if (isMac) runCatching { MacKeychain.loadOrCreateKey() }.getOrNull() else null }

    val available: Boolean by lazy {
        val probe = "tv".toByteArray()
        when {
            isWindows -> runCatching {
                Crypt32Util.cryptUnprotectData(Crypt32Util.cryptProtectData(probe)).contentEquals(probe)
            }.getOrDefault(false)
            isMac -> macKey != null && runCatching {
                val key = macKey ?: return@runCatching false
                aesUnseal(key, aesSeal(key, probe))?.contentEquals(probe) == true
            }.getOrDefault(false)
            else -> false
        }
    }

    /** Vad inställningarna visar om lösenorden. */
    val description: String
        get() = when {
            isWindows && available -> "Skyddas med Windows DPAPI — bara ditt Windows-konto kan läsa dem."
            isWindows -> "Windows lösenordsskydd är inte tillgängligt. Inloggningar sparas inte."
            isMac && available -> "Skyddas med en nyckel i Macens nyckelring — bara ditt Mac-konto kan läsa dem."
            isMac -> "Macens nyckelring gick inte att använda. Inloggningar sparas inte."
            else -> "Lösenordsskydd finns inte här. Inloggningar sparas inte."
        }

    fun seal(bytes: ByteArray): ByteArray? {
        if (!available) return null
        return when {
            isWindows -> runCatching { Crypt32Util.cryptProtectData(bytes) }.getOrNull()
            isMac -> macKey?.let { key -> runCatching { aesSeal(key, bytes) }.getOrNull() }
            else -> null
        }
    }

    fun unseal(bytes: ByteArray): ByteArray? {
        if (!available) return null
        return when {
            isWindows -> runCatching { Crypt32Util.cryptUnprotectData(bytes) }.getOrNull()
            isMac -> macKey?.let { key -> aesUnseal(key, bytes) }
            else -> null
        }
    }

    fun sealText(text: String): String? =
        seal(text.toByteArray(Charsets.UTF_8))?.let { Base64.getEncoder().encodeToString(it) }

    fun unsealText(sealed: String?): String? {
        if (sealed.isNullOrEmpty()) return null
        val bytes = runCatching { Base64.getDecoder().decode(sealed) }.getOrNull() ?: return null
        return unseal(bytes)?.toString(Charsets.UTF_8)
    }

    // --- AES-GCM: 12 byte slumpad IV först, sedan chiffertexten med sin kontrollsumma ---

    private fun aesSeal(key: SecretKey, bytes: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(bytes)
    }

    private fun aesUnseal(key: SecretKey, bytes: ByteArray): ByteArray? {
        if (bytes.size <= IV_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_BYTES))
            cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        }.getOrNull()
    }

    private const val IV_BYTES = 12

    /**
     * Nyckeln i Macens nyckelring, via Apples eget kommandoverktyg security.
     * Posten skapas av verktyget, och nyckelringen låter därför samma verktyg
     * läsa den igen utan att fråga.
     */
    private object MacKeychain {
        private const val SECURITY = "/usr/bin/security"
        private const val SERVICE = "se.norrsken.tv"
        private const val ACCOUNT = "Tv-nyckel"

        /** security svarar 44 när posten inte finns. */
        private const val NOT_FOUND = 44

        fun loadOrCreateKey(): SecretKey? {
            val found = security("find-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w")
            if (found.exit == 0) return keyOf(found.output.trim())
            // Bara när posten bevisligen saknas skapas en ny. Vid alla andra fel —
            // låst nyckelring, nekad åtkomst — skulle en ny nyckel göra allt som
            // redan är sparat oläsbart, så då används inget skydd alls.
            if (found.exit != NOT_FOUND) return null
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val hex = raw.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            val added = security(
                "add-generic-password", "-s", SERVICE, "-a", ACCOUNT,
                "-l", "Tv", "-j", "Nyckeln som skyddar lösenorden i appen Tv",
                "-w", hex,
            )
            if (added.exit != 0) return null
            // Läs tillbaka: först när nyckeln bevisligen finns i nyckelringen används den.
            val check = security("find-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w")
            return if (check.exit == 0 && check.output.trim() == hex) SecretKeySpec(raw, "AES") else null
        }

        private fun keyOf(hex: String): SecretKey? {
            if (hex.length != 64 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val raw = ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            return SecretKeySpec(raw, "AES")
        }

        private class Result(val exit: Int, val output: String)

        private fun security(vararg args: String): Result {
            val process = ProcessBuilder(listOf(SECURITY) + args)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            // Svaret är högst en rad: det ryms i rörets buffert medan vi väntar.
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Result(-1, "")
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return Result(process.exitValue(), output)
        }
    }
}
