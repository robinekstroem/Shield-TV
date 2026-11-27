package se.norrsken.tv.desktop.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import se.norrsken.tv.data.playlist.ImportState
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.data.LibraryFilter
import se.norrsken.tv.desktop.data.AppDirs
import se.norrsken.tv.desktop.data.SecretBox
import se.norrsken.tv.desktop.data.Source
import se.norrsken.tv.desktop.data.SourceType
import se.norrsken.tv.desktop.player.VlcRuntime
import se.norrsken.tv.desktop.player.YuvRenderer
import se.norrsken.tv.desktop.player.languageName
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val syncFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM 'kl.' HH:mm", Locale.forLanguageTag("sv-SE")).withZone(ZoneId.systemDefault())

@Composable
fun SettingsScreen(state: AppState) {
    val palette = LocalPalette.current
    val scroll = rememberScrollState()
    // Kategorierna väljs på en egen sida: listorna är långa. Den står i AppState,
    // så att Tillbaka på tv:n stänger den; lämnar man Inställningar stängs den.
    DisposableEffect(Unit) {
        onDispose { state.managingCategories = null }
    }
    // Tv: tillbaka från kategorisidan hamnar markeringen på knappen som öppnade den.
    var lastManaged by remember { mutableStateOf<Char?>(null) }
    val managedFocus = remember { FocusRequester() }
    fun manage(kind: Char?) {
        if (kind != null) lastManaged = kind
        state.managingCategories = kind
    }
    state.managingCategories?.let { kind ->
        CategoryManager(state, kind, onKind = { manage(it) }, onClose = { manage(null) })
        return
    }
    if (tvLayout) {
        LaunchedEffect(Unit) {
            if (lastManaged != null) {
                repeat(2) { withFrameNanos { } }
                runCatching { managedFocus.requestFocus() }
            }
        }
    }
    fun managedButton(kind: Char): Modifier =
        if (kind == (lastManaged ?: LibraryFilter.LIVE)) Modifier.focusRequester(managedFocus) else Modifier
    // Tv: tappas markeringen hamnar den på kategoriknapparna, som alltid finns.
    TvFocusFallback { runCatching { managedFocus.requestFocus() }.isSuccess }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 36.dp, vertical = 28.dp),
        ) {
            Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Inställningar", style = MaterialTheme.typography.displaySmall)
                    Text("Källor & uppspelning", style = MaterialTheme.typography.bodyLarge, color = palette.textTertiary)
                }

                Section("Källor") {
                    if (state.sources.isEmpty()) {
                        Text(
                            "Inga källor än. Lägg till en spellista nedan — en M3U-länk eller ett Xtream-konto från din leverantör.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.textSecondary,
                        )
                    }
                    state.sources.forEach { source -> SourceCard(state, source) }
                }

                // Shield: källor och bilder skickas från telefonen. Windows visar inget här.
                if (remoteInputAvailable) {
                    Section("Lägg till från telefonen") {
                        Card { RemoteInputPanel(state) }
                    }
                }

                Section("Lägg till spellista") {
                    AddSourceForm(state)
                }

                Section("Utseende") {
                    // Tre i bredd.
                    AppTheme.entries.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEach { option ->
                                ThemeCard(option, selected = state.theme == option, onClick = { state.selectTheme(option) })
                            }
                        }
                    }
                    // Shield: menyernas upplösning. Windows visar inget här.
                    DisplayOptions()
                }

                Section("Omslagsväggen") {
                    WallStyleChooser(state)
                }

                Section("Menyn") {
                    MenuStyleChooser(state)
                }

                Section("Egna bakgrundsbilder") {
                    BackgroundsCard(state)
                }

                Section("Bibliotek") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Språk",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textTertiary,
                            modifier = Modifier.width(140.dp),
                        )
                        Chip("Alla", selected = !state.hideForeign, onClick = { state.updateHideForeign(false) })
                        Chip("Svenska, nordiska och engelska", selected = state.hideForeign, onClick = { state.updateHideForeign(true) })
                    }
                    Text(
                        "Döljer filmer och serier som bara finns på andra språk, som polsk speakerröst eller arabisk text. " +
                            "Inget tas bort: välj Alla för att se allt igen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Kategorier",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textTertiary,
                            modifier = Modifier.width(140.dp),
                        )
                        SecondaryButton(
                            "Live-tv" + hiddenSuffix(state.hiddenCount(LibraryFilter.LIVE)),
                            onClick = { manage(LibraryFilter.LIVE) },
                            modifier = managedButton(LibraryFilter.LIVE),
                        )
                        SecondaryButton(
                            "Filmer" + hiddenSuffix(state.hiddenCount(LibraryFilter.MOVIE)),
                            onClick = { manage(LibraryFilter.MOVIE) },
                            modifier = managedButton(LibraryFilter.MOVIE),
                        )
                        SecondaryButton(
                            "Serier" + hiddenSuffix(state.hiddenCount(LibraryFilter.SERIES)),
                            onClick = { manage(LibraryFilter.SERIES) },
                            modifier = managedButton(LibraryFilter.SERIES),
                        )
                    }
                    Text(
                        "Välj vilka kategorier som syns. Dolda kategorier försvinner ur listorna, tablån och startsidan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary,
                    )
                }

                Section("Bild och ljud") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Bildkvalitet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textTertiary,
                            modifier = Modifier.width(140.dp),
                        )
                        Chip("Max", selected = state.sharpScaling, onClick = { state.updateSharpScaling(true) })
                        Chip("Sparsam", selected = !state.sharpScaling, onClick = { state.updateSharpScaling(false) })
                    }
                    Text(
                        "Max skalar upp bilden bikubiskt och ger skarpast bild. Välj Sparsam om bilden hackar på en äldre dator.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary,
                    )
                    InfoRow(
                        "Bildbehandling",
                        if (YuvRenderer.available) {
                            "På grafikkortet: full upplösning upp till 4K, rätt färger för HD och SD, avflätning med dubbel bildfrekvens."
                        } else {
                            "Kompatibilitetsläge: VLC gör om bilden till RGB. Avflätning är på."
                        },
                    )
                    InfoRow("Språk", languageSummary(state))
                    if (state.preferredAudio != null || state.preferredSubtitle != null) {
                        SecondaryButton("Glöm språkval", onClick = { state.forgetLanguages() })
                    }
                }

                Section("Uppspelning") {
                    // Tv: rader utan knappar går ändå att nå med pilarna.
                    TvFocusBox {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfoRow("Spelarmotor", VlcRuntime.description)
                            InfoRow("User-Agent", state.userAgent)
                            InfoRow("Lösenord", SecretBox.description)
                        }
                    }
                }

                Section("Tablå") {
                    if (state.sources.isEmpty()) {
                        Text("Tablån hämtas från källorna när de är tillagda.", style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
                    }
                    state.sources.forEach { source ->
                        InfoRow(source.name, state.guideStatus[source.id] ?: "Inte hämtad än")
                    }
                    if (state.sources.isNotEmpty()) {
                        SecondaryButton(
                            text = if (state.guideLoading) "Hämtar tablån…" else "Hämta tablån nu",
                            icon = Glyphs.Refresh,
                            enabled = !state.guideLoading,
                            onClick = { state.refreshGuides() },
                        )
                    }
                }

                Section("Filminfo från TMDB") {
                    TmdbCard(state)
                }

                Section("Om appen") {
                    // Tv: versionen ska gå att se, längst ner på sidan.
                    TvFocusBox {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfoRow("Version", System.getProperty("tv.version") ?: "Utvecklingsversion")
                            state.factsProgress?.let { (done, total) ->
                                InfoRow(
                                    "Filmgenrer",
                                    if (done < total) "Hämtas i bakgrunden: ${done.grouped()} av ${total.grouped()} filmer" else "Klara för ${total.grouped()} filmer",
                                )
                            }
                            InfoRow("Data", AppDirs.config.absolutePath)
                            InfoRow("Bibliotek", AppDirs.cache.absolutePath)
                            state.coverStatus?.let { InfoRow("Omslag", it) }
                        }
                    }
                }
            }
        }
        Scrollbar(scroll, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 6.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = palette.textTertiary)
        content()
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.surface)
            .border(1.dp, palette.hairline, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun SourceCard(state: AppState, source: Source) {
    val palette = LocalPalette.current
    val content = state.contentFor(source.id)
    val busy = state.importingId != null
    var confirmRemove by remember(source.id) { mutableStateOf(false) }
    // Ta bort kräver två klick. Andra klicket måste komma inom fyra sekunder.
    LaunchedEffect(confirmRemove) {
        if (confirmRemove) {
            delay(4_000)
            confirmRemove = false
        }
    }

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(source.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Tag(source.type.label, palette.textSecondary)
                }
                val address = if (source.username != null) "${source.displayUrl}  ·  ${source.username}" else source.displayUrl
                Text(
                    address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val summary = buildList {
                    if (content != null) {
                        add("${content.channels.size.grouped()} kanaler")
                        if (source.type == SourceType.XTREAM) {
                            add("${content.movies.size.grouped()} filmer")
                            add("${content.series.size.grouped()} serier")
                        }
                    } else if (state.importingId != source.id) {
                        add("Inte hämtad")
                    }
                    source.lastSyncUtc?.let { add("uppdaterad " + syncFormat.format(Instant.ofEpochMilli(it))) }
                }.joinToString("  ·  ")
                if (summary.isNotEmpty()) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
                }
            }
            Spacer(Modifier.width(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = if (state.importingId == source.id) "Hämtar…" else "Uppdatera",
                    icon = Glyphs.Refresh,
                    enabled = !busy,
                    onClick = { state.refresh(source) },
                )
                SecondaryButton(
                    text = if (confirmRemove) "Bekräfta" else "Ta bort",
                    icon = Glyphs.Delete,
                    color = if (confirmRemove) palette.alert else palette.textPrimary,
                    enabled = state.importingId != source.id,
                    onClick = {
                        if (confirmRemove) state.remove(source) else confirmRemove = true
                    },
                )
            }
        }

        if (source.type == SourceType.XTREAM) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Live-format", style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
                Spacer(Modifier.width(4.dp))
                Chip("TS", selected = source.liveExtension == "ts", onClick = { state.setLiveExtension(source, "ts") })
                Chip("HLS", selected = source.liveExtension == "m3u8", onClick = { state.setLiveExtension(source, "m3u8") })
                Text(
                    "Byt om kanaler hackar eller inte startar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }

        if (state.importingId == source.id || (state.importingId == null && isResultFor(state, source))) {
            ImportLine(state.importState)
        }
    }
}

private fun languageSummary(state: AppState): String {
    val audio = state.preferredAudio
    val subtitle = state.preferredSubtitle
    if (audio == null && subtitle == null) {
        return "Väljs i spelaren under Ljud och undertext. Det du väljer där gäller sedan även nästa kanal och film."
    }
    val parts = buildList {
        if (audio != null) add("Ljud: " + languageName(audio))
        if (subtitle != null) add("Undertext: " + if (subtitle.isEmpty()) "av" else languageName(subtitle))
    }
    return parts.joinToString("  ·  ") + " — väljs automatiskt när det finns."
}

/** Visar resultatet på kortet det gäller, inte på alla. */
private fun isResultFor(state: AppState, source: Source): Boolean =
    state.lastImportedId == source.id && state.importState !is ImportState.Idle

@Composable
private fun AddSourceForm(state: AppState) {
    val palette = LocalPalette.current
    var xtreamTab by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val busy = state.importingId != null

    // Formuläret töms först när källan faktiskt är tillagd — misslyckas
    // hämtningen ska ingen behöva skriva in allt igen.
    LaunchedEffect(state.addedCount) {
        if (state.addedCount > 0) {
            name = ""
            url = ""
            server = ""
            username = ""
            password = ""
        }
    }

    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("M3U-länk", selected = !xtreamTab, onClick = { xtreamTab = false })
            Chip("Xtream-konto", selected = xtreamTab, onClick = { xtreamTab = true })
        }
        Text(
            if (xtreamTab) "Server, användarnamn och lösenord från leverantören. Ger kanaler, filmer och serier."
            else "Klistra in länken från leverantören. En get.php-länk känns igen som ett Xtream-konto och ger då även filmer och serier.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
        )
        LabeledField("Namn (valfritt)", name, { name = it }, placeholder = "T.ex. Hemma", enabled = !busy)
        if (xtreamTab) {
            LabeledField("Server", server, { server = it }, placeholder = "http://leverantor.se:8080", enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledField("Användarnamn", username, { username = it }, Modifier.weight(1f), enabled = !busy)
                LabeledField("Lösenord", password, { password = it }, Modifier.weight(1f), password = true, enabled = !busy)
            }
        } else {
            LabeledField("Adress", url, { url = it }, placeholder = "http://…/get.php?username=…", enabled = !busy)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PrimaryButton(
                text = if (busy) "Hämtar…" else "Lägg till",
                icon = Glyphs.Add,
                enabled = !busy,
                onClick = {
                    if (xtreamTab) state.addXtream(name, server, username, password) else state.addM3u(name, url)
                },
            )
            val notice = state.notice
            if (notice != null) {
                Text(notice, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary, modifier = Modifier.weight(1f))
            }
        }
        if (state.lastImportedId == null || state.sources.none { it.id == state.lastImportedId }) {
            val importState = state.importState
            if (importState !is ImportState.Idle && (busy || importState is ImportState.Failed)) {
                ImportLine(importState)
            }
        }
    }
}

@Composable
private fun ImportLine(importState: ImportState) {
    val palette = LocalPalette.current
    val text = importState.describe()
    if (text.isEmpty()) return
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = when (importState) {
            is ImportState.Failed -> palette.alert
            is ImportState.Done -> palette.secondary
            else -> palette.textSecondary
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.textTertiary, modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary, modifier = Modifier.weight(1f))
    }
}

/** Ett tema, med en liten förhandsvisning i temats egna färger. */
@Composable
private fun ThemeCard(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val preview = paletteFor(theme)
    val shape = RoundedCornerShape(14.dp)
    val small = RoundedCornerShape(4.dp)
    Pressable(
        onClick = onClick,
        selected = selected,
        shape = shape,
        modifier = Modifier.width(240.dp).border(1.dp, if (selected) palette.focus else palette.hairline, shape),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Miniatyr av appen: sidomeny med vald rad, rubrik, text och en knapp.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(preview.background)
                    .border(1.dp, preview.hairline, RoundedCornerShape(10.dp))
                    .drawWithContent {
                        drawContent()
                        decorationHint(preview)
                    },
            ) {
                Column(
                    Modifier.width(62.dp).fillMaxHeight().background(preview.ambient).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(Modifier.fillMaxWidth().height(9.dp).glow(preview.glowOf(0.6f), small, 10.dp).clip(small).background(preview.focus.copy(alpha = 0.35f)))
                    Box(Modifier.fillMaxWidth(0.8f).height(6.dp).clip(small).background(preview.textTertiary))
                    Box(Modifier.fillMaxWidth(0.6f).height(6.dp).clip(small).background(preview.textTertiary))
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(10.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(Modifier.fillMaxWidth(0.7f).height(9.dp).glow(preview.glowOf(0.5f), small, 12.dp).clip(small).background(preview.textPrimary))
                    Box(Modifier.fillMaxWidth(0.9f).height(6.dp).clip(small).background(preview.textSecondary))
                    Box(
                        Modifier.width(56.dp).height(15.dp)
                            .glow(preview.glowOf(0.7f), RoundedCornerShape(8.dp), 14.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(preview.focus),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(theme.label, style = MaterialTheme.typography.titleMedium)
                if (theme == AppTheme.CRYSTAL) Tag("NYTT", preview.signal)
            }
            Text(theme.description, style = MaterialTheme.typography.bodySmall, color = palette.textTertiary)
        }
    }
}

/**
 * Omslagsväggen bakom sidorna: Filmväggen, eller Filmsiden med sidenbandet
 * bakom omslagen i lila och rosa, isblått eller guld. Fyra kort i rad, med
 * en liten bild av var och en.
 */
@Composable
internal fun WallStyleChooser(state: AppState) {
    val palette = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        WallStyle.entries.forEach { option ->
            WallStyleCard(option, selected = state.wallStyle == option, onClick = { state.selectWallStyle(option) })
        }
    }
    if (state.backgrounds.isNotEmpty()) {
        Text(
            "Dina egna bakgrundsbilder visas i stället för väggen. Ta bort dem nedan för att se den.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textTertiary,
        )
    }
}

@Composable
private fun WallStyleCard(style: WallStyle, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    val band = remember { SilkBand() }
    Pressable(
        onClick = onClick,
        selected = selected,
        shape = shape,
        modifier = Modifier.width(188.dp).border(1.dp, if (selected) palette.focus else palette.hairline, shape),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .drawBehind { wallMiniature(style.silk, band) },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(style.label, style = MaterialTheme.typography.titleMedium)
                if (style == WallStyle.SILK) Tag("NYTT", palette.signal)
            }
            Text(style.description, style = MaterialTheme.typography.bodySmall, color = palette.textTertiary)
        }
    }
}

/**
 * Menyns utseende: Original, eller någon av de nya — Minimal, Multiversum och
 * Stark. Fyra kort i rad med en liten bild av var och en. Gäller direkt.
 */
@Composable
internal fun MenuStyleChooser(state: AppState) {
    val palette = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        MenuStyle.entries.forEach { option ->
            MenuStyleCard(option, selected = state.menuStyle == option, onClick = { state.selectMenuStyle(option) })
        }
    }
    Text(
        if (tvLayout) "Syns när du går till menyn med pil vänster." else "Gäller menyn till vänster direkt.",
        style = MaterialTheme.typography.bodySmall,
        color = palette.textTertiary,
    )
}

@Composable
private fun MenuStyleCard(style: MenuStyle, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Pressable(
        onClick = onClick,
        selected = selected,
        shape = shape,
        modifier = Modifier.width(188.dp).border(1.dp, if (selected) palette.focus else palette.hairline, shape),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .drawBehind { menuMiniature(style) },
            ) {
                // Märket i bildens hörn: namnen får plats på en rad bredvid.
                if (style.fresh) Tag("NYTT", palette.signal, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Text(style.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(style.description, style = MaterialTheme.typography.bodySmall, color = palette.textTertiary)
        }
    }
}

/** Nyckeln till TMDB och hur långt hämtningen kommit. */
@Composable
private fun TmdbCard(state: AppState) {
    val palette = LocalPalette.current
    var key by remember { mutableStateOf("") }
    LaunchedEffect(state.tmdbConnected) {
        if (state.tmdbConnected) key = ""
    }
    Card {
        Text(
            "Med en gratis nyckel från themoviedb.org hämtar appen genrer, betyg med antal röster, popularitet, " +
                "svenska beskrivningar och omslag som saknas. Det sker i bakgrunden, några titlar i sekunden, " +
                "och sparas så att bara nya titlar behöver hämtas nästa gång.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
        )
        if (state.tmdbConnected) {
            val progress = state.tmdbProgress
            val text = when {
                progress == null -> "Ansluten."
                progress.first < progress.second -> "Ansluten · hämtar ${progress.first.grouped()} av ${progress.second.grouped()} titlar"
                else -> "Ansluten · klart för ${progress.second.grouped()} titlar"
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                SecondaryButton("Ta bort nyckeln", onClick = { state.disconnectTmdb() }, icon = Glyphs.Delete)
            }
        } else {
            LabeledField("API-nyckel", key, { key = it }, placeholder = "32 tecken från themoviedb.org → Inställningar → API", password = true)
            PrimaryButton("Anslut", onClick = { state.connectTmdb(key) }, icon = Glyphs.Add)
            // Shield: nyckeln kan klistras in från telefonen i stället.
            TmdbPhoneHint(state)
        }
        state.tmdbStatus?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.startsWith("TMDB")) palette.alert else palette.textSecondary,
            )
        }
        // TMDB:s villkor: tjänsten ska nämnas, och att den inte står bakom appen.
        Text(
            "Den här appen använder TMDB:s API men är inte godkänd eller certifierad av TMDB.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textTertiary,
        )
    }
}

/** Egna bilder bakom appen, upp till tre. */
@Composable
private fun BackgroundsCard(state: AppState) {
    val palette = LocalPalette.current
    val thumbnails = rememberBackgroundImages(state.backgrounds, maxSide = 360)
    Card {
        Text(
            "Lägg till upp till tre egna bilder. De visas bakom appen och byter av varandra med en långsam övertoning. " +
                "Temats färger läggs över, så bilderna passar vilket tema du än väljer — och snö, stjärnstoft och dimma ligger kvar ovanpå.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
        )
        // Shield: bilderna skickas från telefonen eller datorn. Windows visar inget här.
        BackgroundUploadHint(state)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            for (path in state.backgrounds) {
                Box(
                    Modifier
                        .size(width = 176.dp, height = 99.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.surfaceRaised),
                ) {
                    thumbnails[path]?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    IconAction(
                        Glyphs.Close,
                        "Ta bort bilden",
                        onClick = { state.removeBackground(path) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                        size = 30.dp,
                        iconSize = 16.dp,
                        emphasized = true,
                    )
                }
            }
            if (state.backgrounds.size < 3 && canPickFiles) {
                val shape = RoundedCornerShape(10.dp)
                Pressable(
                    onClick = { state.addBackgrounds(pickImages()) },
                    shape = shape,
                    modifier = Modifier.size(width = 176.dp, height = 99.dp).border(1.dp, palette.hairline, shape),
                ) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Glyphs.Add, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(22.dp))
                        Text("Lägg till bild", style = MaterialTheme.typography.labelLarge, color = palette.textSecondary)
                    }
                }
            }
        }
        state.backgroundMessage?.let { message ->
            Text(message, style = MaterialTheme.typography.bodyMedium, color = palette.alert)
        }
    }
}

/** " · 3 dolda" efter knappens namn, eller inget. */
private fun hiddenSuffix(count: Int): String = if (count > 0) " · $count dolda" else ""
