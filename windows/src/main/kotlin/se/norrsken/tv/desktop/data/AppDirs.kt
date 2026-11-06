package se.norrsken.tv.desktop.data

import java.io.File

/**
 * Var appen sparar saker.
 *
 * Windows: %APPDATA% (roamar med kontot) för källorna och inställningarna —
 * litet och värt att behålla — och %LOCALAPPDATA% för biblioteket, som alltid
 * går att hämta om från leverantören.
 *
 * Mac: allt under ~/Library/Application Support/se.norrsken.tv, biblioteket i
 * en undermapp. Inte i Caches: där får macOS rensa när disken blir full, och
 * egna bakgrundsbilder ligger bland biblioteksfilerna.
 */
object AppDirs {
    private const val FOLDER = "se.norrsken.tv"

    private val isMac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

    val config: File by lazy {
        (if (isMac) macSupport() else base("APPDATA", ".config").resolve(FOLDER)).also { it.mkdirs() }
    }

    val cache: File by lazy {
        (if (isMac) macSupport().resolve("Bibliotek") else base("LOCALAPPDATA", ".cache").resolve(FOLDER)).also { it.mkdirs() }
    }

    private fun macSupport(): File =
        File(System.getProperty("user.home"), "Library/Application Support").resolve(FOLDER)

    private fun base(variable: String, fallback: String): File =
        System.getenv(variable)?.let { File(it) }?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"), fallback)
}
