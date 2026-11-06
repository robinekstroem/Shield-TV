package se.norrsken.tv.ipad

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.norrsken.tv.desktop.ui.AppTheme
import se.norrsken.tv.desktop.ui.Chip
import se.norrsken.tv.desktop.ui.EmptyState
import se.norrsken.tv.desktop.ui.Glyphs
import se.norrsken.tv.desktop.ui.GroupColumn
import se.norrsken.tv.desktop.ui.LabeledField
import se.norrsken.tv.desktop.ui.LocalPalette
import se.norrsken.tv.desktop.ui.Pressable
import se.norrsken.tv.desktop.ui.PrimaryButton
import se.norrsken.tv.desktop.ui.ScreenHeader
import se.norrsken.tv.desktop.ui.SecondaryButton
import se.norrsken.tv.desktop.ui.Spinner
import se.norrsken.tv.desktop.ui.ThemeBackdrop
import se.norrsken.tv.desktop.ui.ThemeOverlay
import se.norrsken.tv.desktop.ui.TvTheme
import se.norrsken.tv.desktop.ui.grouped

/**
 * Tv för iPad: samma teman och samma utseende som Windows och Shield, med
 * live-tv och Apples egen spelare. Styrs med fingret.
 */
@Composable
internal fun IpadApp() {
    val state = remember { IpadState() }
    TvTheme(state.theme, translucent = false) {
        val palette = LocalPalette.current
        Box(Modifier.fillMaxSize().background(palette.background)) {
            ThemeBackdrop(palette.decoration, running = true, modifier = Modifier.fillMaxSize())
            // Utanför klockan, kameran och tangentbordet.
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Sidebar(state)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (state.screen) {
                        IpadScreen.LIVE -> LiveScreen(state)
                        IpadScreen.SETTINGS -> IpadSettings(state)
                    }
                }
            }
            ThemeOverlay(palette.decoration, running = true, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Sidebar(state: IpadState) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .width(232.dp)
            .fillMaxHeight()
            .background(palette.surface.copy(alpha = 0.9f))
            .padding(horizontal = 14.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 20.dp)) {
            Image(rememberVectorPainter(Glyphs.AppMark), contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Tv", style = MaterialTheme.typography.headlineSmall)
                Text("för iPad", style = MaterialTheme.typography.labelMedium, color = palette.textTertiary)
            }
        }
        NavRow(Glyphs.Live, "Live-tv", state.screen == IpadScreen.LIVE) { state.screen = IpadScreen.LIVE }
        NavRow(Glyphs.Settings, "Inställningar", state.screen == IpadScreen.SETTINGS) { state.screen = IpadScreen.SETTINGS }
    }
}

@Composable
private fun NavRow(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Pressable(onClick = onClick, selected = selected, modifier = Modifier.fillMaxWidth()) { _ ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (selected) palette.focus else palette.textSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = if (selected) palette.textPrimary else palette.textSecondary)
        }
    }
}

@Composable
private fun LiveScreen(state: IpadState) {
    val palette = LocalPalette.current
    val channels = state.channels
    if (state.sources.isEmpty()) {
        EmptyState(
            title = "Ingen källa än",
            body = "Lägg till din M3U-länk eller ditt Xtream-konto under Inställningar, så visas kanalerna här.",
            actionLabel = "Lägg till källa",
            onAction = { state.screen = IpadScreen.SETTINGS },
        )
        return
    }
    if (channels.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.loading) {
                Spinner()
            } else {
                Text(
                    state.message ?: "Inga kanaler hittades.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textTertiary,
                    modifier = Modifier.padding(32.dp),
                )
            }
        }
        return
    }
    val groups = remember(channels) { channels.map { it.group }.distinct() }
    val counts = remember(channels) { channels.groupingBy { it.group }.eachCount() }
    val group = state.group
    val query = state.query
    val shown = remember(channels, group, query) {
        channels.filter { (group == null || it.group == group) && (query.isBlank() || it.name.contains(query, ignoreCase = true)) }
    }
    Row(Modifier.fillMaxSize()) {
        GroupColumn(
            allLabel = "Alla kanaler",
            total = channels.size,
            groups = groups,
            counts = counts,
            selected = group,
            onSelect = { state.group = it },
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ScreenHeader(
                title = group ?: "Alla kanaler",
                subtitle = "${shown.size.grouped()} kanaler",
                query = query,
                onQuery = { state.query = it },
                placeholder = "Sök kanal",
            )
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.alert, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 24.dp, top = 4.dp, bottom = 40.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(shown, key = { it.key }) { channel ->
                    ChannelRow(channel) { state.play(channel) }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(channel: Channel, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) { _ ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(width = 72.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)).background(palette.surfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                Logo(channel.logo, Modifier.fillMaxSize().padding(5.dp)) {
                    Text(channel.name.take(2).uppercase(), style = MaterialTheme.typography.labelLarge, color = palette.textTertiary)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(channel.group, style = MaterialTheme.typography.labelMedium, color = palette.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Glyphs.Play, contentDescription = "Spela", tint = palette.textTertiary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun IpadSettings(state: IpadState) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Inställningar", style = MaterialTheme.typography.headlineMedium)

        SectionTitle("Utseende")
        for (row in AppTheme.entries.chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (option in row) {
                    Chip(option.label, selected = state.theme == option, onClick = { state.selectTheme(option) })
                }
            }
        }

        SectionTitle("Källor")
        if (state.sources.isEmpty()) {
            Text("Inga källor än.", style = MaterialTheme.typography.bodyMedium, color = palette.textTertiary)
        }
        for (source in state.sources) {
            Row(
                Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (source.xtream) "Xtream  ·  ${source.server}" else source.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SecondaryButton("Ta bort", onClick = { state.remove(source) }, icon = Glyphs.Delete)
            }
        }
        if (state.sources.isNotEmpty()) {
            SecondaryButton(
                if (state.loading) "Uppdaterar…" else "Uppdatera kanalerna",
                onClick = { state.refresh() },
                icon = Glyphs.Refresh,
                enabled = !state.loading,
            )
        }

        AddSource(state)

        SectionTitle("Om appen")
        Text(
            "Tv för iPad, första versionen: live-tv med Apples egen spelare, AirPlay och bild-i-bild. " +
                "Startsidan, filmer, serier och tablå kommer i nästa steg.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            modifier = Modifier.widthIn(max = 720.dp),
        )
    }
}

@Composable
private fun AddSource(state: IpadState) {
    val palette = LocalPalette.current
    var xtream by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Lägg till källa")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("M3U-länk", selected = !xtream, onClick = { xtream = false })
            Chip("Xtream-konto", selected = xtream, onClick = { xtream = true })
        }
        LabeledField("Namn (valfritt)", name, { name = it })
        if (xtream) {
            LabeledField("Server", server, { server = it }, placeholder = "http://")
            LabeledField("Användarnamn", username, { username = it })
            LabeledField("Lösenord", password, { password = it }, password = true)
        } else {
            LabeledField("Länk till spellistan", url, { url = it }, placeholder = "http://")
        }
        problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.alert) }
        PrimaryButton(
            "Lägg till",
            onClick = {
                problem = if (xtream) state.addXtream(name, server, username, password) else state.addM3u(name, url)
                if (problem == null) {
                    name = ""
                    url = ""
                    server = ""
                    username = ""
                    password = ""
                    state.screen = IpadScreen.LIVE
                }
            },
            icon = Glyphs.Add,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
}
