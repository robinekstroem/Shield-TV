package se.norrsken.tv.data.metadata

/**
 * Regeln från kravspecen, i kod: hellre ingen metadata än fel metadata.
 *
 * En felaktig affisch är värre än en tom ruta, för användaren tror på den.
 * Därför krävs att titeln stämmer helt efter normalisering — ingen fuzzy
 * matchning på "liknar tillräckligt". "Dune" och "Dune: Part Two" är olika
 * filmer, "The Office" finns i två helt skilda versioner, och "Heat" från
 * 1995 är inte "Heat" från 1986.
 */
data class MatchVerdict(
    val candidate: MetadataCandidate?,
    val confidence: Int,
    val reason: String,
)

private const val ACCEPT_THRESHOLD = 80

fun normalizeTitle(value: String): String = value
    .lowercase()
    // Apostrofer tas bort helt, inte ersatts med mellanslag: annars blir
    // "marvel s daredevil" och "marvels daredevil" olika strangar.
    .replace("'", "")
    .replace("\u2019", "")
    .replace('&', ' ')
    .replace(Regex("\\b(the|a|an|den|det|en|ett)\\b"), " ")
    .replace(Regex("[^a-z0-9åäöéèüñ ]"), " ")
    .replace(Regex(" +"), " ")
    .trim()

fun pickBestMatch(
    title: String,
    year: Int?,
    candidates: List<MetadataCandidate>,
): MatchVerdict {
    if (candidates.isEmpty()) {
        return MatchVerdict(null, 0, "Inga träffar")
    }

    val wanted = normalizeTitle(title)
    if (wanted.isEmpty()) {
        return MatchVerdict(null, 0, "Titeln gick inte att tolka")
    }

    val exact = candidates.filter {
        normalizeTitle(it.title) == wanted || normalizeTitle(it.originalTitle ?: "") == wanted
    }

    if (exact.isEmpty()) {
        return MatchVerdict(null, 0, "Ingen titel stämde exakt")
    }

    if (year != null) {
        // Releaseåret skiljer sig ofta ett år mellan regioner — en film som
        // gick på festival i december och på bio i januari får två årtal.
        val sameYear = exact.filter { it.year != null && kotlin.math.abs(it.year - year) <= 1 }
        return when (sameYear.size) {
            1 -> MatchVerdict(sameYear.first(), 100, "Titel och år stämmer")
            0 -> MatchVerdict(null, 0, "Titeln finns men inte för år $year")
            else -> {
                // Flera med samma titel och år: låt popularitet avgöra bara
                // om skillnaden är stor. Annars är det en gissning.
                val sorted = sameYear.sortedByDescending { it.popularity }
                val clear = sorted[0].popularity > sorted[1].popularity * 3
                if (clear) {
                    MatchVerdict(sorted[0], 85, "Flera träffar, en klart vanligast")
                } else {
                    MatchVerdict(null, 40, "Flera lika troliga träffar")
                }
            }
        }
    }

    // Utan årtal krävs att det bara finns en rimlig kandidat.
    return if (exact.size == 1) {
        MatchVerdict(exact.first(), 90, "Enda titelträffen")
    } else {
        MatchVerdict(null, 30, "Flera filmer heter så — årtal saknas")
    }
}

fun MatchVerdict.accepted(): Boolean = candidate != null && confidence >= ACCEPT_THRESHOLD
