package se.norrsken.tv.data.metadata

enum class MetadataKind { MOVIE, SERIES }

data class MetadataCandidate(
    val providerId: String,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val rating: Double?,
    /** Används bara för att skilja två lika troliga träffar åt. */
    val popularity: Double,
)

/** Varför en hämtning inte gav något. Skiljer "inget hittat" från "gick fel". */
sealed interface MetadataOutcome {
    data class Matched(val candidate: MetadataCandidate, val confidence: Int) : MetadataOutcome
    data class NoMatch(val reason: String) : MetadataOutcome
    /** Nyckel saknas, avvisad eller nätfel — försök igen senare. */
    data class Unavailable(val reason: String) : MetadataOutcome
}
