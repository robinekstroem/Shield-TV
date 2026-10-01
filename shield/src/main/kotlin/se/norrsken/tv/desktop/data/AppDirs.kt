package se.norrsken.tv.desktop.data

import android.content.Context
import java.io.File

/**
 * Shield-versionen av appens mappar. Samma namn som Windows-filen, så att
 * den delade koden hittar den. Allt ligger i appens egen lagring, som
 * Android tar bort om appen avinstalleras.
 */
object AppDirs {
    lateinit var context: Context
        private set

    /** Anropas först av allt i MainActivity. */
    fun init(context: Context) {
        this.context = context.applicationContext
    }

    val config: File
        get() = File(context.filesDir, "config").also { it.mkdirs() }

    val cache: File
        get() = File(context.filesDir, "library").also { it.mkdirs() }
}
