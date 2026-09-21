package se.norrsken.tv.feature.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.norrsken.tv.core.ui.component.FocusableSurface
import se.norrsken.tv.core.ui.component.SectionHeading
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.data.db.dao.PlaylistDao
import se.norrsken.tv.data.db.entity.CategoryEntity
import se.norrsken.tv.data.db.entity.ContentKind
import se.norrsken.tv.data.repository.matchingKeyword
import se.norrsken.tv.data.repository.visibilityKey
import se.norrsken.tv.data.repository.visibilityPrefix
import se.norrsken.tv.data.settings.SettingsRepository
import javax.inject.Inject

data class CategoryRowState(
    val category: CategoryEntity,
    val hiddenByUser: Boolean,
    /** Filterordet som döljer kategorin, eller null. */
    val hiddenByKeyword: String?,
) {
    val hidden: Boolean get() = hiddenByUser || hiddenByKeyword != null
}

data class KeywordState(val word: String, val matches: Int)

data class ManagerState(
    val rows: List<CategoryRowState> = emptyList(),
    val keywords: List<KeywordState> = emptyList(),
) {
    val hiddenCount: Int get() = rows.count { it.hidden }
    val visibleCount: Int get() = rows.size - hiddenCount
}

@HiltViewModel
class CategoryManagerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    playlistDao: PlaylistDao,
    private val settings: SettingsRepository,
) : ViewModel() {

    val kind: ContentKind = savedStateHandle.get<String>("kind")
        ?.let { runCatching { ContentKind.valueOf(it) }.getOrNull() }
        ?: ContentKind.MOVIE

    val state: StateFlow<ManagerState> =
        combine(playlistDao.observeAllCategories(kind), settings.preferences) { categories, prefs ->
            ManagerState(
                rows = categories.map { category ->
                    CategoryRowState(
                        category = category,
                        hiddenByUser = category.visibilityKey() in prefs.hiddenCategories,
                        hiddenByKeyword = matchingKeyword(category.name, prefs.hiddenKeywords),
                    )
                },
                // Antalet visas per ord, så man ser direkt om filtret gör
                // det man tänkt — eller träffar mer än man trodde.
                keywords = prefs.hiddenKeywords.map { word ->
                    KeywordState(
                        word = word,
                        matches = categories.count { it.name.contains(word, ignoreCase = true) },
                    )
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManagerState())

    fun toggle(row: CategoryRowState) {
        // En kategori som döljs av ett filterord går inte att visa härifrån —
        // det vore ett val som inte gör något så länge filtret finns kvar.
        if (row.hiddenByKeyword != null) return
        viewModelScope.launch {
            settings.setCategoryHidden(row.category.visibilityKey(), !row.hiddenByUser)
        }
    }

    fun addKeyword(word: String) {
        viewModelScope.launch { settings.addHiddenKeyword(word) }
    }

    fun removeKeyword(word: String) {
        viewModelScope.launch { settings.removeHiddenKeyword(word) }
    }

    fun showAll() {
        val playlists = state.value.rows.map { it.category.playlistId }.distinct()
        viewModelScope.launch {
            playlists.forEach { settings.unhideCategoriesWithPrefix(visibilityPrefix(it, kind)) }
        }
    }
}

private fun ContentKind.title(): String = when (this) {
    ContentKind.LIVE -> "Kanalkategorier"
    ContentKind.MOVIE -> "Filmkategorier"
    ContentKind.SERIES -> "Seriekategorier"
}

@Composable
fun CategoryManagerScreen(
    modifier: Modifier = Modifier,
    viewModel: CategoryManagerViewModel = hiltViewModel(),
) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var word by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(metrics.screenEdge),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceSm),
    ) {
        item {
            SectionHeading(title = viewModel.kind.title())
            Text(
                text = "${state.visibleCount} visas · ${state.hiddenCount} dolda",
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = metrics.spaceXs),
            )
        }

        item {
            Text(
                text = "Dölj med ord",
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                modifier = Modifier.padding(top = metrics.spaceLg),
            )
            Text(
                text = "Varje kategori vars namn innehåller ordet döljs – även nya " +
                    "som dyker upp nästa gång spellistan uppdateras. Filtret gäller " +
                    "kanaler, filmer och serier.",
                style = MaterialTheme.typography.labelMedium,
                color = palette.textTertiary,
            )
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm),
            ) {
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("Ord, t.ex. arab") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        viewModel.addKeyword(word)
                        word = ""
                    },
                    enabled = word.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.surfaceRaised,
                        contentColor = palette.textPrimary,
                    ),
                ) { Text("Dölj") }
            }
        }

        items(state.keywords, key = { "kw:" + it.word }) { keyword ->
            FocusableSurface(
                onClick = { viewModel.removeKeyword(keyword.word) },
                modifier = Modifier.fillMaxWidth(),
            ) { focused ->
                Row(
                    modifier = Modifier.padding(metrics.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "\"${keyword.word}\"",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary,
                        )
                        Text(
                            text = when (keyword.matches) {
                                0 -> "Träffar inget här"
                                1 -> "Döljer 1 kategori här"
                                else -> "Döljer ${keyword.matches} kategorier här"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textTertiary,
                        )
                    }
                    Text(
                        text = "Ta bort",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (focused) palette.focus else palette.signal,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.padding(top = metrics.spaceLg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Alla kategorier",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (state.rows.any { it.hiddenByUser }) {
                    FocusableSurface(onClick = viewModel::showAll) { focused ->
                        Text(
                            text = "Visa alla igen",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (focused) palette.focus else palette.signal,
                            modifier = Modifier.padding(
                                horizontal = metrics.spaceMd,
                                vertical = metrics.spaceSm,
                            ),
                        )
                    }
                }
            }
        }

        items(state.rows, key = { it.category.id }) { row ->
            FocusableSurface(
                onClick = { viewModel.toggle(row) },
                modifier = Modifier.fillMaxWidth(),
            ) { focused ->
                Row(
                    modifier = Modifier.padding(metrics.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.category.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            focused -> palette.focus
                            row.hidden -> palette.textTertiary
                            else -> palette.textPrimary
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = when {
                            row.hiddenByKeyword != null -> "Dold av \"${row.hiddenByKeyword}\""
                            row.hiddenByUser -> "Dold"
                            else -> "Visas"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (row.hidden) palette.textTertiary else palette.signal,
                    )
                }
            }
        }
    }
}
