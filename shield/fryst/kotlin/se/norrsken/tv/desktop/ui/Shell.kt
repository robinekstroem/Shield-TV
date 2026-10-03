package se.norrsken.tv.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.norrsken.tv.data.playlist.ImportState
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.Screen

@Composable
fun AppRoot(state: AppState) {
    val palette = LocalPalette.current
    Box(Modifier.fillMaxSize().background(palette.background)) {
        // Temats dekor rör sig bara när spelaren inte täcker allt.
        val decorated = state.nowPlaying == null || state.miniPlayer
        CustomBackdrop(state.backgrounds, running = decorated, modifier = Modifier.fillMaxSize())
        ThemeBackdrop(palette.decoration, running = decorated, modifier = Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize()) {
            Sidebar(state)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (state.loading) {
                    Spinner(Modifier.align(Alignment.Center))
                } else {
                    when (state.screen) {
                        Screen.LIVE -> LiveTvScreen(state)
                        Screen.GUIDE -> GuideScreen(state)
                        Screen.MOVIES -> MoviesScreen(state)
                        Screen.SERIES -> SeriesScreen(state)
                        Screen.GENRES -> GenreScreen(state)
                        Screen.HISTORY -> HistoryScreen(state)
                        Screen.SETTINGS -> SettingsScreen(state)
                    }
                }
            }
        }
        ThemeOverlay(palette.decoration, running = decorated, modifier = Modifier.fillMaxSize())
        // Spelaren ligger ovanpå allt. Skärmen under står kvar — med sin
        // scrollposition — så man kommer tillbaka dit man var.
        if (state.nowPlaying != null) {
            if (state.fullscreen) {
                // Bilden visas i helskärmsfönstret. Här blir det bara svart, och
                // klick går inte igenom till listan bakom.
                Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } })
            } else {
                PlayerHost(state)
            }
        }
    }
}

@Composable
private fun Sidebar(state: AppState) {
    val palette = LocalPalette.current
    val library = state.library
    Column(
        Modifier
            .width(236.dp)
            .fillMaxHeight()
            .background(palette.ambient)
            .padding(horizontal = 14.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.padding(start = 10.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(rememberVectorPainter(Glyphs.AppMark), contentDescription = null, modifier = Modifier.size(36.dp).glow(palette.glowOf(0.55f), RoundedCornerShape(9.dp), 22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Tv", style = MaterialTheme.typography.titleLarge)
                Text("för Windows", style = MaterialTheme.typography.labelSmall, color = palette.textTertiary)
            }
        }

        NavItem(Glyphs.Live, "Live TV", "Direktsändning", library.channels.size, state.screen == Screen.LIVE) {
            state.screen = Screen.LIVE
        }
        NavItem(Glyphs.Guide, "Tablå", "Vad som sänds", state.guide.channelCount, state.screen == Screen.GUIDE) {
            state.screen = Screen.GUIDE
        }
        NavItem(Glyphs.Movies, "Filmer", "Filmbiblioteket", library.movies.size, state.screen == Screen.MOVIES) {
            state.screen = Screen.MOVIES
        }
        NavItem(Glyphs.Series, "Serier", "Säsonger & avsnitt", library.series.size, state.screen == Screen.SERIES) {
            state.screen = Screen.SERIES
        }
        NavItem(Glyphs.Genres, "Genrer", "Topplistor efter betyg", null, state.screen == Screen.GENRES) {
            state.screen = Screen.GENRES
        }
        NavItem(Glyphs.History, "Historik", "Fortsätt där du slutade", null, state.screen == Screen.HISTORY) {
            state.screen = Screen.HISTORY
        }

        Spacer(Modifier.weight(1f))

        if (state.importingId != null) {
            ImportIndicator(state.importState)
            Spacer(Modifier.height(8.dp))
        }

        NavItem(Glyphs.Settings, "Inställningar", "Källor & uppspelning", null, state.screen == Screen.SETTINGS) {
            state.screen = Screen.SETTINGS
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    subtitle: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Pressable(onClick = onClick, selected = selected, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) palette.focus else palette.textSecondary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) palette.textPrimary else palette.textSecondary,
                )
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = palette.textTertiary, maxLines = 1)
            }
            if (count != null && count > 0) {
                Text(count.grouped(), style = MaterialTheme.typography.labelSmall, color = palette.textTertiary)
            }
        }
    }
}

@Composable
private fun ImportIndicator(importState: ImportState) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.surfaceRaised)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            importState.describe().ifEmpty { "Hämtar…" },
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = palette.signal,
            trackColor = palette.hairline,
        )
    }
}

/** Importläget i ord. Siffrorna kommer från arbetet självt, som på Android. */
fun ImportState.describe(): String = when (this) {
    ImportState.Idle -> ""
    is ImportState.Fetching -> "Hämtar $what…"
    is ImportState.Parsing -> "Läser $what: ${count.grouped()}"
    is ImportState.Saving -> "Sparar $what: ${saved.grouped()} av ${total.grouped()}"
    is ImportState.Done -> buildList {
        add("${channels.grouped()} kanaler")
        if (movies > 0) add("${movies.grouped()} filmer")
        if (series > 0) add("${series.grouped()} serier")
    }.joinToString(prefix = "Klart: ", separator = ", ", postfix = ".")
    is ImportState.Failed -> message
}
