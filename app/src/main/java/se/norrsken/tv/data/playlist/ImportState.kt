// Delas med Windows-versionen (se windows/build.gradle.kts). Håll filen fri
// från android.*-importer, annars slutar Windows-bygget att kompilera.

package se.norrsken.tv.data.playlist

/**
 * Verklig progress, inte en påhittad stapel. Varje läge motsvarar något som
 * faktiskt händer och siffrorna kommer från arbetet självt.
 *
 * [what] finns för att en Xtream-import har flera steg efter varandra —
 * "Sparar 4000 av 12000" säger ingenting om användaren inte vet om det är
 * kanaler eller filmer.
 */
sealed interface ImportState {
    data object Idle : ImportState
    data class Fetching(val what: String) : ImportState
    data class Parsing(val what: String, val count: Int) : ImportState
    data class Saving(val what: String, val saved: Int, val total: Int) : ImportState
    data class Done(val channels: Int, val movies: Int, val series: Int) : ImportState
    data class Failed(val message: String) : ImportState
}
