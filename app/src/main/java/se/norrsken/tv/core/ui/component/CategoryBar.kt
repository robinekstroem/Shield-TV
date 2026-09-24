package se.norrsken.tv.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import se.norrsken.tv.core.ui.theme.LocalMetrics
import se.norrsken.tv.core.ui.theme.LocalPalette
import se.norrsken.tv.core.ui.theme.LocalThemeStyle
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
    sortLabel: String? = null,
    onCycleSort: (() -> Unit)? = null,
    categorySortLabel: String? = null,
    onCycleCategorySort: (() -> Unit)? = null,
    onManage: (() -> Unit)? = null,
) {
    val metrics = LocalMetrics.current
    val palette = LocalPalette.current
    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            // V5 glass panel: mörk men genomskinlig så backdropens färg och
            // ljus lever kvar bakom nivå två. Gradientens transparenta kant
            // ger en mjuk "glow" i stället för en hård låda.
            .background(
                Brush.horizontalGradient(
                    listOf(
                        palette.background.copy(alpha = 0.84f),
                        palette.surface.copy(alpha = 0.62f),
                        palette.focus.copy(alpha = 0.045f),
                        Color.Transparent,
                    )
                ),
                RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
            )
            .padding(horizontal = metrics.spaceSm, vertical = metrics.spaceMd),
        verticalArrangement = Arrangement.spacedBy(metrics.spaceXs),
    ) {
        if (sortLabel != null && onCycleSort != null) {
            item { SortChip("Sortering", sortLabel, onCycleSort) }
        }
        if (categorySortLabel != null && onCycleCategorySort != null) {
            item { SortChip("Kategorier", categorySortLabel, onCycleCategorySort) }
        }
        if (onManage != null) {
            item { SortChip("Visa och dölj", "Hantera", onManage) }
        }
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
    sortLabel: String? = null,
    onCycleSort: (() -> Unit)? = null,
    onManage: (() -> Unit)? = null,
) {
    val metrics = LocalMetrics.current
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(metrics.spaceSm),
    ) {
        if (sortLabel != null && onCycleSort != null) {
            item { SortChip("Sortering", sortLabel, onCycleSort) }
        }
        if (onManage != null) {
            item { SortChip("Visa och dölj", "Hantera", onManage) }
        }
        item { CategoryChip(allLabel, selected == null) { onSelect(null) } }
        items(categories, key = { it.id }) { category ->
            CategoryChip(category.name, selected == category.id) { onSelect(category.id) }
        }
    }
}

/**
 * Sortering som en rad i samma lista som kategorierna.
 *
 * Skalet: pa en fjarrkontroll ar det ett stalle att navigera till i stallet
 * for tva. Tryck stegar till nasta ordning — ingen meny som ska oppnas,
 * valjas i och stangas.
 */
@Composable
private fun SortChip(prefix: String, value: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    FocusableSurface(onClick = onClick, focusScale = 1f) { focused ->
        Column(
            modifier = Modifier.padding(
                horizontal = metrics.spaceMd,
                vertical = metrics.spaceSm,
            ),
        ) {
            // Etiketten ovanför värdet försvinner i minimalt läge. Att det
            // står "Senast tillagda" i kategorilistan räcker för att förstå
            // att det handlar om sortering.
            if (!LocalThemeStyle.current.minimal) {
                Text(
                    text = prefix,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textTertiary,
                    maxLines = 1,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) palette.focus else palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CategoryChip(label: String, active: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val metrics = LocalMetrics.current

    FocusableSurface(
        onClick = onClick,
        focusScale = 1f,
        // Ingen egen yta: kategorierna är en lista, inte en samling
        // knappar. Det valda markeras med ljus text, det fokuserade med
        // fokusringen — inte med en fylld platta per rad.
        background = active,
        cornerRadius = metrics.radiusCard,
    ) { focused ->
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                focused -> palette.focus
                active -> palette.textPrimary
                else -> palette.textSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (focused || active) palette.focus.copy(alpha = if (focused) 0.14f else 0.07f)
                    else Color.Transparent,
                    RoundedCornerShape(metrics.radiusCard),
                )
                .padding(horizontal = metrics.spaceMd, vertical = metrics.spaceSm),
        )
    }
}
