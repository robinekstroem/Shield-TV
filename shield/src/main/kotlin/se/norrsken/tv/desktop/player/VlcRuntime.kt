package se.norrsken.tv.desktop.player

/**
 * På Shield finns ingen VLC: spelaren är Androids egen, Media3, som använder
 * Shields hårdvaruavkodare. Objektet heter som Windows-filen, så att den
 * delade koden skapar spelaren på samma sätt på båda.
 */
object VlcRuntime {
    val description: String = "Media3 med Shields hårdvaruavkodning"
    val failure: String? = null

    fun prepare() = Unit

    fun factory(): PlayerEngine? = PlayerEngine

    fun release() = Unit
}

/** Det VideoPlayer skapas med på Shield; bär inget, finns för samma anrop som på Windows. */
object PlayerEngine
