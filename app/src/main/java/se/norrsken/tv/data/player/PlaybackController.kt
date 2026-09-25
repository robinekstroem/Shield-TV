package se.norrsken.tv.data.player

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.norrsken.tv.data.settings.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/** Det mini-rutan behöver veta för att visa och kunna öppnas igen. */
data class MiniPlayback(
    val title: String,
    val subtitle: String?,
    /** Rutten tillbaka till helskärm. */
    val route: String,
)

/**
 * Äger uppspelningen, i stället för uppspelningsskärmen.
 *
 * Skälet är mini-rutan: går man till kanallistan lämnar man
 * uppspelningsskärmen, och hade spelaren ägts där hade strömmen dött i
 * samma sekund. Nu lever den vidare i appen och skärmen kopplar bara på
 * sig — precis som mini-rutan gör.
 *
 * En spelare, inte flera. Att förhandsvisa varje markerad kanal hade
 * krävt en ny anslutning per kanal, och de flesta konton tillåter två
 * eller tre samtidigt.
 */
@Singleton
class PlaybackController @Inject constructor(
    private val factory: PlayerFactory,
) {
    private var held: ExoPlayer? = null
    private var signature: Int = 0

    /** Adressen som redan är laddad, så samma ström inte kopplas upp igen. */
    var currentUrl: String? = null
        private set

    private val _mini = MutableStateFlow<MiniPlayback?>(null)
    val mini: StateFlow<MiniPlayback?> = _mini.asStateFlow()

    /**
     * Spelaren för de aktuella inställningarna. Samma instans så länge
     * inställningarna är oförändrade — byggs om först när de ändras.
     */
    fun player(preferences: AppPreferences): ExoPlayer {
        val wanted = signatureOf(preferences)
        val existing = held
        if (existing != null && wanted == signature) return existing

        existing?.release()
        currentUrl = null
        return factory.create(preferences).also {
            held = it
            signature = wanted
        }
    }

    fun heldPlayer(): ExoPlayer? = held

    fun markLoaded(url: String) {
        currentUrl = url
    }

    /** Låt strömmen fortsätta i en liten ruta medan man bläddrar. */
    fun keepInMini(title: String, subtitle: String?, route: String) {
        if (held == null) return
        _mini.value = MiniPlayback(title, subtitle, route)
    }

    fun clearMini() {
        _mini.value = null
    }

    /** Stoppar och släpper. Det enda som verkligen avslutar uppspelningen. */
    fun stop() {
        _mini.value = null
        held?.release()
        held = null
        signature = 0
        currentUrl = null
    }

    /** Appen hamnade i bakgrunden. Ljudet ska inte fortsätta i fickan. */
    fun pause() {
        held?.playWhenReady = false
    }

    private fun signatureOf(p: AppPreferences): Int = listOf(
        p.buffer, p.decoder, p.matchFrameRate, p.userAgent, p.preferSurround,
        p.videoQuality, p.tunneling, p.audioLanguage, p.subtitleLanguage, p.autoSubtitles,
    ).hashCode()
}
