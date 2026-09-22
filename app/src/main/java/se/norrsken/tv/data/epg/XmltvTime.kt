package se.norrsken.tv.data.epg

/**
 * Tolkar XMLTV:s tidsformat till UTC-millisekunder.
 *
 * Formatet är `YYYYMMDDhhmmss ±HHMM`, men leverantörer varierar: sekunder
 * saknas ibland, tidszonen likaså, mellanslaget före den likaså. Saknas
 * tidszon tolkas tiden som UTC — det är vad standarden säger, och felet
 * syns i så fall som en jämn förskjutning som EPG-offset rättar.
 *
 * Räknar själv i stället för att använda java.time, som kräver Android 8.
 * Appen stödjer Android 7, och en krasch på äldre boxar vore ett högt pris
 * för en bekvämare rad.
 *
 * Returnerar null för allt som inte går att tolka. Ett trasigt program ska
 * hoppas över, inte krascha hela tablån.
 */
fun parseXmltvTime(value: String?): Long? {
    val text = value?.trim() ?: return null
    val digits = text.takeWhile { it.isDigit() }
    if (digits.length < 12) return null

    val year = digits.substring(0, 4).toInt()
    val month = digits.substring(4, 6).toInt()
    val day = digits.substring(6, 8).toInt()
    val hour = digits.substring(8, 10).toInt()
    val minute = digits.substring(10, 12).toInt()
    val second = if (digits.length >= 14) digits.substring(12, 14).toInt() else 0

    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) {
        return null
    }

    val offsetMinutes = parseOffset(text.substring(digits.length).trim()) ?: return null

    val days = daysFromCivil(year, month, day)
    val seconds = days * 86_400L + hour * 3_600L + minute * 60L + second - offsetMinutes * 60L
    return seconds * 1_000L
}

/** "+0200" → 120, "-0530" → -330, tomt → 0, skräp → null. */
private fun parseOffset(text: String): Int? {
    if (text.isEmpty()) return 0
    val sign = when (text[0]) {
        '+' -> 1
        '-' -> -1
        else -> return null
    }
    val digits = text.substring(1).takeWhile { it.isDigit() }
    if (digits.length != 4) return null
    val hours = digits.substring(0, 2).toInt()
    val minutes = digits.substring(2, 4).toInt()
    if (hours > 14 || minutes > 59) return null
    return sign * (hours * 60 + minutes)
}

/**
 * Dagar sedan 1970-01-01 för ett gregorianskt datum. Howard Hinnants
 * algoritm — ren heltalsaritmetik, rätt för alla år appen kan möta.
 */
internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val monthFromMarch = (month + 9) % 12
    val dayOfYear = (153 * monthFromMarch + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}
