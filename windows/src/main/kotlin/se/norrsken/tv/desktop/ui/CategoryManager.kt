package se.norrsken.tv.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.data.LibraryFilter

/**
 * Vilka kategorier som syns, per slag: live-tv, filmer eller serier. Ett
 * klick döljer eller visar. "Dölj andra språk" tar bort kategorierna som
 * uppenbart är på andra språk ("PL | Filmy", "AR | …") i ett svep.
 */
@Composable
internal fun CategoryManager(state: AppState, kind: Char, onKind: (Char) -> Unit, onClose: () -> Unit) {
    val palette = LocalPalette.current
    val library = state.library
    val groups = when (kind) {
        LibraryFilter.LIVE -> library.liveGroups
        LibraryFilter.MOVIE -> library.movieGroups
        else -> library.seriesGroups
    }
    val counts = when (kind) {
        LibraryFilter.LIVE -> library.liveCounts
        LibraryFilter.MOVIE -> library.movieCounts
        else -> library.seriesCounts
    }
    val hidden = state.hiddenGroups
    val shown = groups.count { LibraryFilter.groupKey(kind, it) !in hidden }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Kategorier", style = MaterialTheme.typography.displaySmall)
                Text(
                    "${shown.grouped()} av ${groups.size.grouped()} visas. Välj en kategori för att dölja eller visa den.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textTertiary,
                )
            }
            PrimaryButton("Klar", onClick = onClose, icon = Glyphs.Check)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Live-tv", selected = kind == LibraryFilter.LIVE, onClick = { onKind(LibraryFilter.LIVE) })
            Chip("Filmer", selected = kind == LibraryFilter.MOVIE, onClick = { onKind(LibraryFilter.MOVIE) })
            Chip("Serier", selected = kind == LibraryFilter.SERIES, onClick = { onKind(LibraryFilter.SERIES) })
            Spacer(Modifier.width(18.dp))
            SecondaryButton("Dölj andra språk", onClick = { state.hideForeignGroups(kind, groups) })
            SecondaryButton("Visa alla", onClick = { state.showAllGroups(kind) })
        }
        if (groups.isEmpty()) {
            Text("Inga kategorier ännu.", style = MaterialTheme.typography.bodyLarge, color = palette.textTertiary)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(260.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(groups) { group ->
                    val isHidden = LibraryFilter.groupKey(kind, group) in hidden
                    CategoryToggle(
                        name = group,
                        count = counts[group] ?: 0,
                        visible = !isHidden,
                        onClick = { state.setGroupHidden(kind, group, !isHidden) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryToggle(name: String, count: Int, visible: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Pressable(onClick = onClick, selected = visible, modifier = Modifier.fillMaxWidth()) { _ ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (visible) Glyphs.Check else Glyphs.Close,
                contentDescription = if (visible) "Visas" else "Dold",
                tint = if (visible) palette.focus else palette.textTertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (visible) palette.textPrimary else palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (count > 0) {
                Spacer(Modifier.width(8.dp))
                Text(count.grouped(), style = MaterialTheme.typography.labelSmall, color = palette.textTertiary)
            }
        }
    }
}
