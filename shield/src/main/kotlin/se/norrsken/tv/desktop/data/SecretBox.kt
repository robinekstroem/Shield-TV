package se.norrsken.tv.desktop.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Shield-versionen av lösenordsskyddet: Androids nyckellager i stället för
 * Windows DPAPI. Nyckeln lämnar aldrig Shield, så kopierade filer går inte
 * att läsa någon annanstans. Samma regel som på Windows: går skyddet inte
 * att använda sparas ingen hemlighet alls, aldrig i klartext.
 */
object SecretBox {
    private const val ALIAS = "tv-shield-hemligheter"

    val available: Boolean by lazy {
        runCatching {
            val probe = "tv".toByteArray()
            unseal(seal(probe) ?: return@runCatching false)?.contentEquals(probe) == true
        }.getOrDefault(false)
    }

    private fun key(): SecretKey {
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
    fun seal(bytes: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(bytes)
        ByteArray(1 + iv.size + sealed.size).also {
            it[0] = iv.size.toByte()
            iv.copyInto(it, 1)
            sealed.copyInto(it, 1 + iv.size)
        }
    }.getOrNull()

    fun unseal(bytes: ByteArray): ByteArray? = runCatching {
        val ivSize = bytes[0].toInt()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, ivSize))
        cipher.doFinal(bytes, 1 + ivSize, bytes.size - 1 - ivSize)
    }.getOrNull()

    fun sealText(text: String): String? =
        seal(text.toByteArray(Charsets.UTF_8))?.let { Base64.getEncoder().encodeToString(it) }

    fun unsealText(sealed: String?): String? {
        if (sealed.isNullOrEmpty()) return null
        val bytes = runCatching { Base64.getDecoder().decode(sealed) }.getOrNull() ?: return null
        return unseal(bytes)?.toString(Charsets.UTF_8)
    }
}
