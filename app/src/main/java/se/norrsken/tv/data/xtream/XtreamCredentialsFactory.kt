package se.norrsken.tv.data.xtream

import se.norrsken.tv.data.db.entity.PlaylistEntity
import se.norrsken.tv.data.playlist.XtreamCredentials
import se.norrsken.tv.data.security.CredentialStore
import se.norrsken.tv.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enda stallet dar en spellista blir till inloggningsuppgifter.
 *
 * Fanns tidigare i tva kopior — i importen och i avsnittshamtningen — vilket
 * gor att nasta falt hamnar i den ena och inte i den andra. Nu ligger ocksa
 * dekrypteringen har, sa losenordet i klartext existerar bara i minnet och
 * bara sa lange anropet pagar.
 */
@Singleton
class XtreamCredentialsFactory @Inject constructor(
    private val credentials: CredentialStore,
    private val settings: SettingsRepository,
) {
    suspend fun forPlaylist(playlist: PlaylistEntity): XtreamCredentials =
        XtreamCredentials(
            baseUrl = playlist.url,
            username = playlist.username.orEmpty(),
            password = credentials.decrypt(playlist.secret),
            userAgent = settings.current().userAgent,
        )
}
