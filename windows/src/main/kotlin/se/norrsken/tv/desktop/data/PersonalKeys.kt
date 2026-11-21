package se.norrsken.tv.desktop.data

/**
 * Egna kategorier överst i Filmer, Serier och Live TV: det man håller på
 * med och det man sparat — i stället för som rader på startsidan. Nycklarna
 * börjar med \u0001, som samlingarnas, så att de aldrig krockar med en
 * kategori från leverantören.
 */
object PersonalKeys {
    /** Filmer och serier man är mitt i, senast sedda först. */
    const val RESUME = "\u0001fortsätt"

    /** Filmer och serier i Min lista, senast sparade först. */
    const val MY_LIST = "\u0001minlista"

    /** Kanalerna i Min lista. */
    const val FAVORITES = "\u0001favoriter"

    /** Kanalerna man tittat på senast. */
    const val RECENT = "\u0001senaste"

    fun isPersonal(group: String?): Boolean = group == RESUME || group == MY_LIST || group == FAVORITES || group == RECENT

    /** Namnet som visas för en egen kategori, eller null för en vanlig. */
    fun titleOf(group: String?): String? = when (group) {
        RESUME -> "Fortsätt titta"
        MY_LIST -> "Min lista"
        FAVORITES -> "Favoriter"
        RECENT -> "Senast sedda"
        else -> null
    }
}
