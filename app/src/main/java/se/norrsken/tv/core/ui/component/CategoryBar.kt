package se.norrsken.tv.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.data.db.entity.CategoryEntity

/**
 * Kategorilistan. Samma komponent for kanaler, filmer och serier — de tre
 * skarmarna hade annars drivit isar i utseende utan att nagon bestamt det.
 *
 * Kolumn pa TV, rad pa telefon: en sidokolumn ater for mycket bredd pa en
 * liten skarm, och en chiprad ar svar att traffa med D-pad.
 */
@Composable
fun CategoryColumn(
    categories: List<CategoryEntity>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    allLabel: String,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalMetrics.current
    LazyColumn(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceXs),
    ) {
        item { CategoryChip(allLabel, selected == null) { onSelect(null) } }
        items(categories, key = { it.id }) { category ->
            CategoryChip(category.name, selected == category.id) { onSelect(category.id) }
        }
    }
}

@Composable
fun CategoryRow(
    categories: List<CategoryEntity>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    allLabel: String,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalMetrics.current
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm),
    ) {
        item { CategoryChip(allLabel, selected == null) { onSelect(null) } }
        items(categories, key = { it.id }) { category ->
            CategoryChip(category.name, selected == category.id) { onSelect(category.id) }
        }
    }
}

@Composable
private fun CategoryChip(label: String, active: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    FocusableSurface(onClick = onClick, focusScale = 1f) { focused ->
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                focused || active -> palette.textPrimary
                else -> palette.textSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                horizontal = metrics.spaceMd,
                vertical = metrics.spaceSm,
            ),
        )
    }
}
