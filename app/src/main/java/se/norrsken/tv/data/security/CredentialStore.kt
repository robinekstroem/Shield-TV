package se.norrsken.tv.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Krypterar leverantorslosenord med en nyckel i Android Keystore.
 *
 * Nyckeln lamnar aldrig Keystore — appen skickar text dit och far tillbaka
 * chiffer. Far nagon tag i databasfilen ar losenorden darfor inte lasbara.
 *
 * Formatet ar versionsmarkt: "v1:<iv>:<chiffer>". Prefixet gor att gamla
 * rader i klartext kanns igen och kan las som de ar, i stallet for att
 * dekrypteras till skrap.
 */
@Singleton
class CredentialStore @Inject constructor() {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val bytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            "$PREFIX:${encode(cipher.iv)}:${encode(bytes)}"
        }.getOrElse { cause ->
            // Spara aldrig leverantörslösenord i klartext. Om Keystore är
            // trasig får anroparen ett tydligt fel och kan be användaren
            // försöka igen, i stället för att säkerheten tyst degraderas.
            throw IllegalStateException("Kunde inte skydda kontouppgifterna med Android Keystore", cause)
        }
    }

    fun decrypt(stored: String?): String {
        val value = stored.orEmpty()
        if (!value.startsWith("$PREFIX:")) return value

        val parts = value.split(':')
        if (parts.size != 3) return ""

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, decode(parts[1])),
            )
            String(cipher.doFinal(decode(parts[2])), Charsets.UTF_8)
        }.getOrElse { "" }
    }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Egen IV per kryptering. Ateranvand IV med GCM ar ett av de
                // fa satt att gora AES direkt osakert.
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "norrsken.provider.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val PREFIX = "v1"
    }
}
