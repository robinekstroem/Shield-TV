package se.norrsken.tv.feature.settings

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.norrsken.tv.core.device.LocalDeviceProfile
import se.norrsken.tv.core.ui.component.AuroraLoader
import se.norrsken.tv.core.ui.component.AuroraProgress
import se.norrsken.tv.core.ui.component.FocusableSurface
import se.norrsken.tv.core.ui.component.SectionHeading
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.data.db.entity.PlaylistEntity
import se.norrsken.tv.data.db.entity.PlaylistType
import se.norrsken.tv.data.device.DeviceCapabilities
import se.norrsken.tv.data.epg.EpgState
import se.norrsken.tv.data.playlist.ImportState
import se.norrsken.tv.data.remote.PlaylistDownloader
import se.norrsken.tv.data.security.sanitizeUrl
import se.norrsken.tv.data.settings.AccentChoice
import se.norrsken.tv.data.settings.AppPreferences
import se.norrsken.tv.data.settings.BufferProfile
import se.norrsken.tv.data.settings.CategorySort
import se.norrsken.tv.data.settings.ChannelSort
import se.norrsken.tv.data.settings.DecoderMode
import se.norrsken.tv.data.settings.EffectLevel
import se.norrsken.tv.data.settings.EpgDays
import se.norrsken.tv.data.settings.InterfaceStyle
import se.norrsken.tv.data.settings.LibrarySort
import se.norrsken.tv.data.settings.MediaLanguage
import se.norrsken.tv.data.settings.SubtitleBackground
import se.norrsken.tv.data.settings.SubtitleSize
import se.norrsken.tv.data.settings.TextScale
import se.norrsken.tv.data.settings.VideoQuality
import se.norrsken.tv.data.settings.VideoScale
import se.norrsken.tv.data.settings.VolumeBoost

/** Strängarna leverantörer faktiskt filtrerar på. */
private val USER_AGENTS = listOf(
    PlaylistDownloader.DEFAULT_USER_AGENT,
    "Lavf/60.16.100",
    "Mozilla/5.0 (SmartHub; SMART-TV)",
    "okhttp/4.12.0",
)

private enum class SourceForm(val label: String) {
    M3u("M3U-länk"),
    Xtream("Xtream"),
}

/**
 * Inställningarnas områden. Varje område är en egen sida — en enda lista
 * med trettio rader går inte att hitta i med en fjärrkontroll.
 */
enum class SettingsSection(val title: String) {
    SOURCES("Källor"),
    EPG("Tablå"),
    PICTURE("Bild"),
    AUDIO("Ljud"),
    SUBTITLES("Undertext"),
    APPEARANCE("Utseende"),
    LIBRARY("Bibliotek"),
    PLAYBACK("Uppspelning"),
    GENERAL("Allmänt"),
}

/**
 * Översikten. Varje område visar sina aktuella värden direkt i raden, så
 * man ser läget utan att öppna något.
 */
@Composable
fun SettingsScreen(
    onOpenSection: (SettingsSection) -> Unit,
    onManageCategories: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    // TV: områdena till vänster, innehållet till höger — allt på en skärm.
    // Att gå in i ett område, ändra en sak och backa ut igen är mycket
    // arbete med en fjärrkontroll.
    if (LocalDeviceProfile.current.isTv) {
        SettingsTwoPane(onManageCategories, viewModel, modifier)
    } else {
        SettingsHub(onOpenSection, viewModel, modifier)
    }
}

@Composable
private fun SettingsTwoPane(
    onManageCategories: (String) -> Unit,
    viewModel: PlaylistsViewModel,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalMetrics.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val epgState by viewModel.epgState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val display = remember { DeviceCapabilities.display(context) }
    val audio = remember { DeviceCapabilities.audio(context) }

    var section by rememberSaveable { mutableStateOf(SettingsSection.SOURCES) }

    Row(modifier = modifier.fillMaxSize().padding(metrics.screenEdge)) {
        LazyColumn(
            modifier = Modifier.width(260.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(metrics.spaceXs),
        ) {
            item {
                SectionHeading(
                    title = "Inställningar",
                    modifier = Modifier.padding(bottom = metrics.spaceMd),
                )
            }
            items(SettingsSection.entries) { entry ->
                SectionRow(
                    title = entry.title,
                    summary = summaryOf(entry, prefs, playlists.size),
                    active = entry == section,
                    onClick = { section = entry },
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(start = metrics.spaceLg),
            verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
        ) {
            item {
                SectionHeading(
                    title = section.title,
                    modifier = Modifier.padding(bottom = metrics.spaceMd),
                )
            }
            if (section == SettingsSection.SOURCES) {
                item { SourcesPanel(viewModel) }
            } else {
                sectionRows(
                    section, prefs, viewModel, epgState, playlists,
                    isTv = true, display = display, audio = audio,
                    onManageCategories = onManageCategories,
                )
            }
        }
    }
}

/**
 * Översikten på telefonen, där två spalter inte får plats. Varje område
 * visar sina aktuella värden direkt i raden.
 */
@Composable
private fun SettingsHub(
    onOpenSection: (SettingsSection) -> Unit,
    viewModel: PlaylistsViewModel,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalMetrics.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(metrics.screenEdge),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
    ) {
        item {
            SectionHeading(
                title = "Inställningar",
                modifier = Modifier.padding(bottom = metrics.spaceMd),
            )
        }
        items(SettingsSection.entries) { entry ->
            SectionRow(
                title = entry.title,
                summary = summaryOf(entry, prefs, playlists.size),
                active = false,
                onClick = { onOpenSection(entry) },
            )
        }
    }
}

private fun summaryOf(section: SettingsSection, prefs: AppPreferences, sources: Int): String =
    when (section) {
        SettingsSection.SOURCES -> when (sources) {
            0 -> "Ingen källa än"
            1 -> "1 källa"
            else -> "$sources källor"
        }
        SettingsSection.EPG -> prefs.epgLastUpdateUtc
            ?.let { "Uppdaterad " + java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) }
            ?: "Aldrig hämtad"
        SettingsSection.PICTURE -> "${prefs.videoQuality.label} · ${prefs.videoScale.label}"
        SettingsSection.AUDIO -> "${prefs.audioLanguage.label} · Förstärkning ${prefs.volumeBoost.label}"
        SettingsSection.SUBTITLES ->
            if (prefs.autoSubtitles) "${prefs.subtitleLanguage.label} · ${prefs.subtitleSize.label}"
            else "Av · ${prefs.subtitleSize.label}"
        SettingsSection.APPEARANCE ->
            "${prefs.interfaceStyle.label} · Glöd ${prefs.effects.label.lowercase()}"
        SettingsSection.LIBRARY -> prefs.librarySort.label
        SettingsSection.PLAYBACK -> "Buffert ${prefs.buffer.label.lowercase()}"
        SettingsSection.GENERAL -> if (prefs.confirmExit) "Bekräfta avslut på" else "Bekräfta avslut av"
    }

@Composable
private fun SectionRow(title: String, summary: String, active: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    FocusableSurface(
        onClick = onClick,
        focusScale = 1f,
        cornerRadius = metrics.radiusCard,
        background = active,
        modifier = Modifier.fillMaxWidth(),
    ) { focused ->
        Column(
            modifier = Modifier.padding(horizontal = metrics.spaceMd, vertical = metrics.spaceSm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    focused -> palette.focus
                    active -> palette.textPrimary
                    else -> palette.textSecondary
                },
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = if (active || focused) palette.signal else palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** En sida per område. */
@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    onManageCategories: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    if (section == SettingsSection.SOURCES) {
        SourcesPanel(viewModel = viewModel)
        return
    }

    val metrics = LocalMetrics.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val isTv = LocalDeviceProfile.current.isTv
    val context = LocalContext.current
    // Läses en gång per sida. Skärm och ljudkedja byts inte medan man tittar
    // på inställningarna, och frågan mot systemet är inte gratis.
    val epgState by viewModel.epgState.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val display = remember { DeviceCapabilities.display(context) }
    val audio = remember { DeviceCapabilities.audio(context) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(metrics.screenEdge),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
    ) {
        item {
            SectionHeading(
                title = section.title,
                modifier = Modifier.padding(bottom = metrics.spaceMd),
            )
        }
        sectionRows(section, prefs, viewModel, epgState, playlists, isTv, display, audio, onManageCategories)
    }
}

/** Raderna för ett område. Samma kod på telefonens undersida och TV:ns vy. */
private fun LazyListScope.sectionRows(
    section: SettingsSection,
    prefs: AppPreferences,
    viewModel: PlaylistsViewModel,
    epgState: EpgState,
    playlists: List<PlaylistEntity>,
    isTv: Boolean,
    display: se.norrsken.tv.data.device.DisplayInfo?,
    audio: se.norrsken.tv.data.device.AudioOutputInfo,
    onManageCategories: (String) -> Unit,
) {
    when (section) {
        SettingsSection.EPG -> epgRows(prefs, viewModel, epgState, playlists)
        SettingsSection.PICTURE -> pictureRows(prefs, viewModel, isTv, display)
        SettingsSection.AUDIO -> audioRows(prefs, viewModel, audio)
        SettingsSection.SUBTITLES -> subtitleRows(prefs, viewModel)
        SettingsSection.APPEARANCE -> appearanceRows(prefs, viewModel)
        SettingsSection.LIBRARY -> libraryRows(prefs, viewModel, onManageCategories)
        SettingsSection.PLAYBACK -> playbackRows(prefs, viewModel)
        SettingsSection.GENERAL -> generalRows(prefs, viewModel)
        SettingsSection.SOURCES -> Unit
    }
}

// ---------------------------------------------------------------------------
// Tablå
// ---------------------------------------------------------------------------

private fun LazyListScope.epgRows(
    prefs: AppPreferences,
    vm: PlaylistsViewModel,
    state: EpgState,
    playlists: List<PlaylistEntity>,
) {
    item {
        InfoCard(
            title = "Status",
            lines = listOf(
                when (state) {
                    is EpgState.Working ->
                        state.step + (state.programmes?.let { " · $it program" } ?: "") + " …"
                    is EpgState.Done -> "${state.programmes} program för ${state.channels} kanaler"
                    is EpgState.Failed -> state.message
                    EpgState.Idle -> prefs.epgLastUpdateUtc?.let {
                        "Senast uppdaterad " + java.text.SimpleDateFormat(
                            "d MMM HH:mm", java.util.Locale.getDefault(),
                        ).format(java.util.Date(it))
                    } ?: "Tablån har inte hämtats än"
                },
            ),
            note = "Uppdateras automatiskt när appen startar om den är äldre än tolv " +
                "timmar, och efter varje uppdatering av en spellista.",
        )
    }
    item {
        if (state is EpgState.Working) {
            AuroraLoader(modifier = Modifier.fillMaxWidth())
        } else {
            LinkRow(
                title = "Uppdatera tablån nu",
                description = null,
                onClick = vm::updateEpg,
            )
        }
    }
    item {
        OptionRow(
            title = "Spara framåt",
            description = "Fler dagar tar mer plats. Med tusentals kanaler märks sju dagar på en TV-box.",
            options = EpgDays.entries,
            selected = prefs.epgDays,
            label = { it.label },
            onSelect = vm::setEpgDays,
        )
    }
    item {
        OptionRow(
            title = "Tidsförskjutning",
            description = "Ligger hela tablån en jämn tid fel har leverantören fel tidszon i filen. " +
                "Justera här — programmen sparas orörda.",
            options = listOf(-120, -60, -30, 0, 30, 60, 120),
            selected = prefs.epgOffsetMinutes,
            label = { if (it > 0) "+$it min" else "$it min" },
            onSelect = vm::setEpgOffset,
        )
    }
    items(playlists, key = { "epg:" + it.id }) { playlist ->
        EpgSourceRow(playlist = playlist, onSave = { vm.setEpgUrl(playlist, it) })
    }
}

/**
 * Tablåadressen för en källa. Fältet fylls inte i med den nuvarande
 * adressen: den kan innehålla inloggningsuppgifter, och de ska inte stå i
 * klartext på skärmen. Nuvarande adress visas maskerad ovanför.
 */
@Composable
private fun EpgSourceRow(playlist: PlaylistEntity, onSave: (String?) -> Unit) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    var text by remember(playlist.id) { mutableStateOf("") }
    val stored = playlist.epgUrl?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier.padding(top = metrics.spaceMd),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceXs),
    ) {
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
        )
        Text(
            text = when {
                stored != null -> "Adress: " + sanitizeUrl(stored)
                playlist.type == PlaylistType.XTREAM -> "Hämtas automatiskt från Xtream-kontot"
                else -> "Ingen tablåadress — spellistan anger ingen. Lägg till en nedan."
            },
            style = MaterialTheme.typography.labelMedium,
            color = palette.textTertiary,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Egen tablåadress (XMLTV)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm)) {
            Button(
                onClick = {
                    onSave(text)
                    text = ""
                },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.surfaceRaised,
                    contentColor = palette.textPrimary,
                ),
            ) { Text("Spara och hämta") }
            if (stored != null) {
                TextButton(onClick = { onSave(null) }) { Text("Återställ") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Bild
// ---------------------------------------------------------------------------

private fun LazyListScope.pictureRows(
    prefs: AppPreferences,
    vm: PlaylistsViewModel,
    isTv: Boolean,
    display: se.norrsken.tv.data.device.DisplayInfo?,
) {
    item {
        InfoCard(
            title = "Din skärm",
            lines = if (display == null) {
                listOf("Kunde inte läsa skärmens egenskaper.")
            } else {
                listOf(
                    "${display.width} × ${display.height} · ${display.refreshRate} Hz",
                    if (display.hdrFormats.isEmpty()) {
                        "Ingen HDR rapporterad av skärmen"
                    } else {
                        "HDR: " + display.hdrFormats.joinToString(", ")
                    },
                )
            },
            note = "Styrs av enheten och TV:n, inte av appen. Står det 1920 × 1080 " +
                "på en 4K-TV skickar enheten inte 4K — kontrollera upplösningen i " +
                "enhetens egna inställningar.",
        )
    }
    item {
        OptionRow(
            title = "Bildkvalitet",
            description = "Automatisk börjar lågt och klättrar. Högsta ger skarp bild " +
                "direkt men kräver att nätet klarar det.",
            options = VideoQuality.entries,
            selected = prefs.videoQuality,
            label = { it.label },
            onSelect = vm::setVideoQuality,
        )
    }
    item {
        OptionRow(
            title = "Bildformat",
            description = "Zooma fyller skärmen och beskär kanterna. Byts även i spelaren.",
            options = VideoScale.entries,
            selected = prefs.videoScale,
            label = { it.label },
            onSelect = vm::setVideoScale,
        )
    }
    item {
        ToggleRow(
            title = "Matcha bildfrekvens",
            description = "Byter skärmens frekvens efter filmen när det går sömlöst. " +
                "På Shield finns också \"Matcha innehållets bildfrekvens\" i systemets " +
                "inställningar, som avgör om byten som blinkar till får göras.",
            checked = prefs.matchFrameRate,
            onChange = vm::setMatchFrameRate,
        )
    }
    item {
        if (isTv) {
            ToggleRow(
                title = "Tunnlad uppspelning",
                description = "Låter TV-hårdvaran synka bild och ljud själv. Jämnare på " +
                    "de flesta Android TV-enheter. Stäng av om bilden hackar.",
                checked = prefs.tunneling,
                onChange = vm::setTunneling,
            )
        } else {
            UnavailableRow(
                title = "Tunnlad uppspelning",
                reason = "Gäller Android TV. Telefoner stödjer det sällan.",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Ljud
// ---------------------------------------------------------------------------

private fun LazyListScope.audioRows(
    prefs: AppPreferences,
    vm: PlaylistsViewModel,
    audio: se.norrsken.tv.data.device.AudioOutputInfo,
) {
    item {
        InfoCard(
            title = "Din ljudkedja",
            lines = when {
                !audio.hasHdmi -> listOf("Ingen HDMI-utgång. Ljudet avkodas i enheten.")
                audio.passthroughFormats.isEmpty() ->
                    listOf("HDMI ansluten. Inga surroundformat rapporterade — ljudet avkodas i enheten.")
                else -> listOf("Tar emot direkt: " + audio.passthroughFormats.joinToString(", "))
            },
            note = "Formaten kommer från TV:n eller förstärkaren via HDMI. Saknas " +
                "Dolby här fast förstärkaren klarar det, kontrollera ljudinställningen " +
                "för surround i enhetens systeminställningar.",
        )
    }
    item {
        OptionRow(
            title = "Ljudspråk",
            description = "Väljs automatiskt när strömmen har flera ljudspår.",
            options = MediaLanguage.entries,
            selected = prefs.audioLanguage,
            label = { it.label },
            onSelect = vm::setAudioLanguage,
        )
    }
    item {
        ToggleRow(
            title = "Föredra surroundljud",
            description = "Väljer flerkanalsspår när det finns. Enheten avgör om den klarar formatet.",
            checked = prefs.preferSurround,
            onChange = vm::setPreferSurround,
        )
    }
    item {
        OptionRow(
            title = "Volymförstärkning",
            description = "För kanaler som sänds med låg nivå. Fungerar när enheten själv " +
                "avkodar ljudet — inte när Dolby skickas orört till förstärkaren, " +
                "och inte med tunnlad uppspelning.",
            options = VolumeBoost.entries,
            selected = prefs.volumeBoost,
            label = { it.label },
            onSelect = vm::setVolumeBoost,
        )
    }
}

// ---------------------------------------------------------------------------
// Undertext
// ---------------------------------------------------------------------------

private fun LazyListScope.subtitleRows(prefs: AppPreferences, vm: PlaylistsViewModel) {
    item {
        ToggleRow(
            title = "Automatisk undertext",
            description = "Av: bara tvingade undertexter visas, som när någon talar ett " +
                "tredje språk. Du kan alltid slå på dem i spelaren.",
            checked = prefs.autoSubtitles,
            onChange = vm::setAutoSubtitles,
        )
    }
    item {
        OptionRow(
            title = "Undertextspråk",
            description = null,
            // "Originalspråk" är inget språk för undertext.
            options = MediaLanguage.entries.filter { it.code != null },
            selected = prefs.subtitleLanguage,
            label = { it.label },
            onSelect = vm::setSubtitleLanguage,
        )
    }
    item {
        OptionRow(
            title = "Storlek",
            description = null,
            options = SubtitleSize.entries,
            selected = prefs.subtitleSize,
            label = { it.label },
            onSelect = vm::setSubtitleSize,
        )
    }
    item {
        OptionRow(
            title = "Stil",
            description = "Kontur och skugga syns på alla bakgrunder. Ruta är tydligast men täcker mest.",
            options = SubtitleBackground.entries,
            selected = prefs.subtitleBackground,
            label = { it.label },
            onSelect = vm::setSubtitleBackground,
        )
    }
}

// ---------------------------------------------------------------------------
// Utseende
// ---------------------------------------------------------------------------

private fun LazyListScope.appearanceRows(prefs: AppPreferences, vm: PlaylistsViewModel) {
    item {
        OptionRow(
            title = "Gränssnitt",
            description = "Minimal tar bort förklarande text — underrubriker i menyn, " +
                "etiketter ovanför värden, beskrivningar här. Inga funktioner försvinner.",
            options = InterfaceStyle.entries,
            selected = prefs.interfaceStyle,
            label = { it.label },
            onSelect = vm::setInterfaceStyle,
        )
    }
    item {
        OptionRow(
            title = "Bakgrundsglöd",
            description = "En svag glöd bakom innehållet. Svag tar bort filmkornet, Av släcker den helt.",
            options = EffectLevel.entries,
            selected = prefs.effects,
            label = { it.label },
            onSelect = vm::setEffects,
        )
    }
    item {
        OptionRow(
            title = "Textstorlek",
            description = "Slår igenom i hela gränssnittet direkt.",
            options = TextScale.entries,
            selected = prefs.textScale,
            label = { it.label },
            onSelect = vm::setTextScale,
        )
    }
    item {
        OptionRow(
            title = "Accentfärg",
            description = "Progress, live-markör och markerat läge.",
            options = AccentChoice.entries,
            selected = prefs.accent,
            label = { it.label },
            onSelect = vm::setAccent,
        )
    }
    item {
        OptionRow(
            title = "Ytornas genomskinlighet",
            description = "Gäller paneler och kort, aldrig texten.",
            options = listOf(100, 90, 80, 70),
            selected = prefs.uiOpacity,
            label = { "$it %" },
            onSelect = vm::setUiOpacity,
        )
    }
}

// ---------------------------------------------------------------------------
// Bibliotek
// ---------------------------------------------------------------------------

private fun LazyListScope.libraryRows(
    prefs: AppPreferences,
    vm: PlaylistsViewModel,
    onManageCategories: (String) -> Unit,
) {
    item {
        OptionRow(
            title = "Sortera filmer och serier",
            description = "Populära på TMDB kräver att titeln matchats. Omatchade hamnar sist.",
            options = LibrarySort.entries,
            selected = prefs.librarySort,
            label = { it.label },
            onSelect = vm::setLibrarySort,
        )
    }
    item {
        OptionRow(
            title = "Omslag per rad",
            description = "På 1080p ger åtta ungefär 90 dp per omslag. Färre gör dem större.",
            options = listOf(5, 6, 7, 8, 10),
            selected = prefs.gridColumns,
            label = { "$it" },
            onSelect = vm::setGridColumns,
        )
    }
    item {
        OptionRow(
            title = "Sortera kanaler",
            description = null,
            options = ChannelSort.entries,
            selected = prefs.channelSort,
            label = { it.label },
            onSelect = vm::setChannelSort,
        )
    }
    item {
        OptionRow(
            title = "Sortera kategorier",
            description = null,
            options = CategorySort.entries,
            selected = prefs.categorySort,
            label = { it.label },
            onSelect = vm::setCategorySort,
        )
    }
    item {
        LinkRow(
            title = "Kanalkategorier",
            description = "Dölj enskilda kategorier eller allt som innehåller ett ord.",
            onClick = { onManageCategories("LIVE") },
        )
    }
    item { LinkRow(title = "Filmkategorier", description = null, onClick = { onManageCategories("MOVIE") }) }
    item { LinkRow(title = "Seriekategorier", description = null, onClick = { onManageCategories("SERIES") }) }
}

// ---------------------------------------------------------------------------
// Uppspelning
// ---------------------------------------------------------------------------

private fun LazyListScope.playbackRows(prefs: AppPreferences, vm: PlaylistsViewModel) {
    item {
        OptionRow(
            title = "Buffert",
            description = "Stor buffert ger stabilare bild, långsammare kanalbyte.",
            options = BufferProfile.entries,
            selected = prefs.buffer,
            label = { it.label },
            onSelect = vm::setBuffer,
        )
    }
    item {
        OptionRow(
            title = "Videoavkodare",
            description = "Automatisk faller tillbaka till mjukvara när hårdvaran säger nej.",
            options = DecoderMode.entries,
            selected = prefs.decoder,
            label = { it.label },
            onSelect = vm::setDecoder,
        )
    }
    item {
        OptionRow(
            title = "User-Agent",
            description = "Många leverantörer svarar 403 om anropet inte ser ut som en spelare.",
            options = if (prefs.userAgent in USER_AGENTS) USER_AGENTS else USER_AGENTS + prefs.userAgent,
            selected = prefs.userAgent,
            label = { it.substringBefore('/') },
            onSelect = vm::setUserAgent,
        )
    }
    item {
        OptionRow(
            title = "Infopanelens tid",
            description = "Hur länge panelen visas innan den tonar bort.",
            options = listOf(2, 4, 6, 10),
            selected = prefs.infoPanelSeconds,
            label = { "$it s" },
            onSelect = vm::setInfoPanelSeconds,
        )
    }
    item {
        ToggleRow(
            title = "Klocka i spelaren",
            description = null,
            checked = prefs.showClock,
            onChange = vm::setShowClock,
        )
    }
}

// ---------------------------------------------------------------------------
// Allmänt
// ---------------------------------------------------------------------------

private fun LazyListScope.generalRows(prefs: AppPreferences, vm: PlaylistsViewModel) {
    item {
        ToggleRow(
            title = "Bekräfta avslut",
            description = "Två tryck på Back krävs. Back är den knapp som trycks av misstag oftast.",
            checked = prefs.confirmExit,
            onChange = vm::setConfirmExit,
        )
    }
    item {
        UnavailableRow(
            title = "TV-tablå, grupper, logotypkällor, språk",
            reason = "Byggs när funktionerna bakom dem finns. Ett reglage " +
                "utan funktion är sämre än ingen rad alls.",
        )
    }
}

/**
 * Information om enheten — ingen inställning. Ser annorlunda ut än raderna
 * runt omkring och går inte att trycka på, så ingen tror att den gör något.
 */
@Composable
private fun InfoCard(title: String, lines: List<String>, note: String) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = metrics.spaceMd),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceXs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textTertiary,
        )
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
            )
        }
        Text(
            text = note,
            style = MaterialTheme.typography.labelMedium,
            color = palette.textTertiary,
        )
    }
}

// ---------------------------------------------------------------------------
// Källor
// ---------------------------------------------------------------------------

@Composable
private fun SourcesPanel(viewModel: PlaylistsViewModel) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val hint by viewModel.hint.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()

    val storedKey by viewModel.tmdbKey.collectAsStateWithLifecycle()
    val metadataProblem by viewModel.metadataProblem.collectAsStateWithLifecycle()

    var form by remember { mutableStateOf(SourceForm.M3u) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val busy = importState is ImportState.Fetching ||
        importState is ImportState.Parsing ||
        importState is ImportState.Saving

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(metrics.screenEdge),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceMd),
    ) {
        item {
            Text(
                text = "Spellistor",
                style = MaterialTheme.typography.headlineMedium,
                color = palette.textPrimary,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm)) {
                SourceForm.entries.forEach { option ->
                    FilterChip(
                        selected = form == option,
                        onClick = { form = option },
                        enabled = !busy,
                        label = { Text(option.label) },
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Namn") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (form == SourceForm.M3u) {
            item {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("M3U-länk") },
                    placeholder = { Text("http://...") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            item {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server") },
                    placeholder = { Text("http://server.se:8080") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Användarnamn") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Lösenord") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            Button(
                onClick = {
                    if (form == SourceForm.M3u) {
                        viewModel.addM3u(name, url)
                    } else {
                        viewModel.addXtream(name, server, username, password)
                    }
                    name = ""; url = ""; server = ""; username = ""; password = ""
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.surfaceRaised,
                    contentColor = palette.textPrimary,
                ),
            ) {
                Text("Lägg till och hämta")
            }
        }

        hint?.let { current ->
            item {
                Text(
                    text = current.message,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.signal,
                )
            }
        }

        item { ImportStatus(state = importState, onDismiss = viewModel::dismiss) }

        if (playlists.isNotEmpty()) {
            item {
                Text(
                    text = "Tillagda källor",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    modifier = Modifier.padding(top = metrics.spaceLg),
                )
            }
        }

        items(playlists, key = { it.id }) { playlist ->
            PlaylistRow(
                playlist = playlist,
                enabled = !busy,
                onRefresh = { viewModel.refresh(playlist) },
                onRemove = { viewModel.remove(playlist) },
            )
        }

        item {
            MetadataSection(
                storedKey = storedKey,
                problem = metadataProblem,
                onSave = viewModel::saveTmdbKey,
            )
        }
    }
}

@Composable
private fun MetadataSection(
    storedKey: String,
    problem: String?,
    onSave: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    var key by remember(storedKey) { mutableStateOf(storedKey) }

    Column(
        verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
        modifier = Modifier.padding(top = metrics.spaceXl),
    ) {
        Text(
            text = "Omslag och fakta",
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
        )
        Text(
            text = "Saknar en film eller serie omslag hämtas det från TMDB när " +
                "titeln visas. Registrera en gratisnyckel hos themoviedb.org och " +
                "klistra in den här.",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.textSecondary,
        )

        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("TMDB API-nyckel") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm),
        ) {
            Button(
                onClick = { onSave(key) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.surfaceRaised,
                    contentColor = palette.textPrimary,
                ),
            ) {
                Text("Spara nyckel")
            }
            if (storedKey.isNotEmpty()) {
                Text(
                    text = "Nyckel sparad",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.signal,
                )
            }
        }

        if (problem != null) {
            Text(
                text = problem,
                style = MaterialTheme.typography.labelMedium,
                color = palette.alert,
            )
        }

        // TMDB:s villkor kräver att källan anges.
        Text(
            text = "Omslag och fakta kommer från TMDB. Tjänsten står inte bakom " +
                "den här appen.",
            style = MaterialTheme.typography.labelMedium,
            color = palette.textTertiary,
        )
    }
}

@Composable
private fun ImportStatus(state: ImportState, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    when (state) {
        is ImportState.Idle -> Unit

        is ImportState.Fetching -> Column(
            verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
        ) {
            Text("Hämtar ${state.what}…", color = palette.textSecondary)
            AuroraLoader(modifier = Modifier.fillMaxWidth())
        }

        is ImportState.Parsing -> Column(
            verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
        ) {
            Text("Läser ${state.count} ${state.what}", color = palette.textSecondary)
            AuroraLoader(modifier = Modifier.fillMaxWidth())
        }

        is ImportState.Saving -> Column(
            verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
        ) {
            Text("Sparar ${state.what}: ${state.saved} av ${state.total}", color = palette.textSecondary)
            AuroraProgress(
                fraction = state.saved.toFloat() / state.total.coerceAtLeast(1),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is ImportState.Done -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
        ) {
            Text(
                text = buildString {
                    append("${state.channels} kanaler")
                    if (state.movies > 0) append(", ${state.movies} filmer")
                    if (state.series > 0) append(", ${state.series} serier")
                    append(".")
                },
                color = palette.signal,
            )
            TextButton(onClick = onDismiss) { Text("Stäng") }
        }

        is ImportState.Failed -> Column(
            verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
        ) {
            Text(text = state.message, color = palette.alert)
            TextButton(onClick = onDismiss) { Text("Stäng") }
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistEntity,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.spaceMd),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
            )
            Text(
                // Lösenordet står i URL:en på en get.php-länk. Visas aldrig,
                // loggas aldrig — samma regel som i diagnostikläget.
                // substringBefore('?') dolde bara uppgifter i query-delen.
                // Xtream lagger dem i sokvagen, dar de ser ut som mappnamn.
                text = when (playlist.type) {
                    PlaylistType.XTREAM -> "Xtream · ${sanitizeUrl(playlist.url)}"
                    PlaylistType.M3U -> "M3U · ${sanitizeUrl(playlist.url)}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = palette.textTertiary,
                maxLines = 1,
            )
        }
        TextButton(onClick = onRefresh, enabled = enabled) { Text("Uppdatera") }
        TextButton(onClick = onRemove, enabled = enabled) { Text("Ta bort") }
    }
}
