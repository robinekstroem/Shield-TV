package se.norrsken.tv.desktop.data

import java.io.File

/**
 * Var appen sparar saker.
 *
 * %APPDATA% (roamar med kontot): källorna och inställningarna — litet och
 * värt att behålla. %LOCALAPPDATA%: biblioteket, som alltid går att hämta
 * om från leverantören.
 */
object AppDirs {
    private const val FOLDER = "se.norrsken.tv"

    val config: File by lazy { base("APPDATA", ".config").resolve(FOLDER).also { it.mkdirs() } }
    val cache: File by lazy { base("LOCALAPPDATA", ".cache").resolve(FOLDER).also { it.mkdirs() } }

    private fun base(variable: String, fallback: String): File =
        System.getenv(variable)?.let { File(it) }?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"), fallback)
}
