package se.norrsken.tv.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.data.db.entity.PlaylistEntity
import se.norrsken.tv.data.db.entity.PlaylistType
import se.norrsken.tv.data.playlist.ImportState

private enum class SourceForm(val label: String) {
    M3u("M3U-länk"),
    Xtream("Xtream"),
}

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val hint by viewModel.hint.collectAsStateWithLifecycle()

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

/**
 * Omslag som leverantören inte gav fylls i härifrån.
 *
 * Nyckeln är användarens egen. Ingen följer med appen: en API-nyckel i en
 * APK är inte hemlig, och kvoten och avstängningen hade landat på den som
 * lade in den.
 */
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
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is ImportState.Parsing -> Column(
            verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
        ) {
            Text("Läser ${state.count} ${state.what}", color = palette.textSecondary)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is ImportState.Saving -> Column(
            verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
        ) {
            Text("Sparar ${state.what}: ${state.saved} av ${state.total}", color = palette.textSecondary)
            LinearProgressIndicator(
                progress = { state.saved.toFloat() / state.total.coerceAtLeast(1) },
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
                text = when (playlist.type) {
                    PlaylistType.XTREAM -> "Xtream · ${playlist.url}"
                    PlaylistType.M3U -> "M3U · ${playlist.url.substringBefore('?')}"
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
