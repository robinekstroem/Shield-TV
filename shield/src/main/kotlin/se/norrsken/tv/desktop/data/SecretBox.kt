package se.norrsken.tv.desktop.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shield-versionen av lösenordsskyddet. Samma regel som på Windows: går
 * skyddet inte att använda sparas ingen hemlighet alls, aldrig i klartext.
 *
 * Androids nyckellager är säkert men långsamt för stora mängder data, som
 * hela biblioteket. Därför låses allt med en slumpad nyckel som i sin tur är
 * låst av nyckellagret: lika säkert, men snabbt. Nyckeln lämnar aldrig
 * Shield olåst, så kopierade filer går inte att läsa någon annanstans.
 * Det som låstes direkt med nyckellagret förut går fortfarande att läsa.
 */
object SecretBox {
    private const val ALIAS = "tv-shield-hemligheter"
    private const val KEY_FILE = "nyckel.bin"

    /** Första byten i data låst med den snabba nyckeln. Äldre data börjar med IV-längden, 12. */
    private const val FAST: Byte = 0x7F

    private val random = SecureRandom()

    val available: Boolean by lazy {
        runCatching {
            val probe = "tv".toByteArray()
            unseal(seal(probe) ?: return@runCatching false)?.contentEquals(probe) == true
        }.getOrDefault(false)
    }

    /** Den snabba nyckeln: slumpad en gång och sparad låst med nyckellagret. */
    private val fastKey: SecretKey? by lazy { runCatching { loadOrCreateFastKey() }.getOrNull() }

    private fun loadOrCreateFastKey(): SecretKey {
        val file = File(AppDirs.context.filesDir, KEY_FILE)
        if (file.exists()) {
            val raw = keystoreUnseal(file.readBytes()) ?: error("Nyckeln gick inte att låsa upp")
            return SecretKeySpec(raw, "AES")
        }
        val raw = ByteArray(32).also { random.nextBytes(it) }
        val sealed = keystoreSeal(raw) ?: error("Nyckeln gick inte att låsa")
        val temp = File(file.path + ".tmp")
        temp.writeBytes(sealed)
        if (!temp.renameTo(file)) error("Nyckeln gick inte att spara")
        return SecretKeySpec(raw, "AES")
    }

    fun seal(bytes: ByteArray): ByteArray? {
        val key = fastKey ?: return keystoreSeal(bytes)
        return runCatching {
            val iv = ByteArray(12).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val sealed = cipher.doFinal(bytes)
            ByteArray(2 + iv.size + sealed.size).also {
                it[0] = FAST
                it[1] = iv.size.toByte()
                iv.copyInto(it, 2)
                sealed.copyInto(it, 2 + iv.size)
            }
        }.getOrNull()
    }

    fun unseal(bytes: ByteArray): ByteArray? {
        if (bytes.size < 2) return null
        if (bytes[0] != FAST) return keystoreUnseal(bytes)
        val key = fastKey ?: return null
        return runCatching {
            val ivSize = bytes[1].toInt()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 2, ivSize))
            cipher.doFinal(bytes, 2 + ivSize, bytes.size - 2 - ivSize)
        }.getOrNull()
    }

    fun sealText(text: String): String? =
        seal(text.toByteArray(Charsets.UTF_8))?.let { Base64.getEncoder().encodeToString(it) }

    fun unsealText(sealed: String?): String? {
        if (sealed.isNullOrEmpty()) return null
        val bytes = runCatching { Base64.getDecoder().decode(sealed) }.getOrNull() ?: return null
        return unseal(bytes)?.toString(Charsets.UTF_8)
    }

    // --- Nyckellagret själv: bara för den snabba nyckeln och för äldre data ---

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** IV-längd, IV och chiffertext i ett block. */
    private fun keystoreSeal(bytes: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        val sealed = cipher.doFinal(bytes)
        ByteArray(1 + iv.size + sealed.size).also {
            it[0] = iv.size.toByte()
            iv.copyInto(it, 1)
            sealed.copyInto(it, 1 + iv.size)
        }
    }.getOrNull()

    private fun keystoreUnseal(bytes: ByteArray): ByteArray? = runCatching {
        val ivSize = bytes[0].toInt()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, bytes, 1, ivSize))
        cipher.doFinal(bytes, 1 + ivSize, bytes.size - 1 - ivSize)
    }.getOrNull()
}
