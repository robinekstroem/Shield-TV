package se.norrsken.tv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import se.norrsken.tv.desktop.AppState

/**
 * Musik: Spotify öppnas med en knapp, och musiken fortsätter när man går
 * tillbaka hit — till exempel julmusik medan man bläddrar. Ljudet spelas av
 * Spotify själv; appen bara öppnar den.
 */
@Composable
fun MusicScreen(state: AppState) {
    val palette = LocalPalette.current
    val first = remember { FocusRequester() }
    var message by remember { mutableStateOf<String?>(null) }
    // Tv: tappas markeringen hamnar den på knappen.
    TvFocusFallback {
        runCatching { first.requestFocus() }
        true
    }
    Column(
        Modifier.fillMaxSize().padding(start = if (tvLayout) 46.dp else 32.dp, end = 32.dp, top = if (tvLayout) 40.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Musik", style = if (tvLayout) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium)
        Text(
            "Spela musik från Spotify medan du tittar runt i appen. Musiken fortsätter när du går tillbaka hit.",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.textSecondary,
            modifier = Modifier.widthIn(max = 760.dp),
        )
        PrimaryButton(
            "Öppna Spotify",
            onClick = {
                message = if (openSpotify()) null else "Spotify gick inte att öppna. Installera Spotify och försök igen."
            },
            icon = Glyphs.Music,
            modifier = Modifier.padding(top = 6.dp).focusRequester(first),
        )
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.alert) }
        Column(
            Modifier
                .padding(top = 10.dp)
                .widthIn(max = 760.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.surface)
                .border(1.dp, palette.hairline, RoundedCornerShape(16.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MusicTip(
                1,
                if (tvLayout) {
                    "Välj en spellista i Spotify och tryck sedan på Tillbaka tills du är här igen. Musiken spelar vidare."
                } else {
                    "Välj en spellista i Spotify och gå tillbaka hit. Musiken spelar vidare i Spotify."
                },
            )
            MusicTip(
                2,
                if (tvLayout) {
                    "Eller styr från mobilen: öppna Spotify där, tryck på högtalaren och välj Shield. Då behöver du inte lämna appen alls."
                } else {
                    "Eller styr från mobilen: öppna Spotify där, tryck på högtalaren och välj datorn."
                },
            )
        }
    }
}

/** Ett tips med numret i en ring. */
@Composable
private fun MusicTip(number: Int, text: String) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(MenuAccents.music.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelLarge, color = MenuAccents.music)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = palette.textPrimary, modifier = Modifier.padding(top = 3.dp))
    }
}
