package se.norrsken.tv.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp


/** Leverantörer anger trailern som YouTube-id eller som hel adress. */
internal fun trailerUrl(value: String?): String? {
    val trailer = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (trailer.startsWith("http", ignoreCase = true)) trailer else "https://www.youtube.com/watch?v=$trailer"
}

/** 12 345 — med svenskt tusentalsmellanrum. */
fun Int.grouped(): String {
    // Mellanslag som tusentalsavgränsare, som på svenska.
    val digits = kotlin.math.abs(toLong()).toString()
    val out = StringBuilder()
    digits.forEachIndexed { i, c ->
        if (i > 0 && (digits.length - i) % 3 == 0) out.append('\u00A0')
        out.append(c)
    }
    return if (this < 0) "-$out" else out.toString()
}

/**
 * Basen för allt klickbart, som FocusableSurface på Android. Med mus finns
 * två lägen fokus inte har: hovring och tangentbordsfokus. Båda syns som
 * ljus, aldrig kulör.
 */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(10.dp),
    hoverScale: Float = 1f,
    /** Glöd kring elementet i glödtemat. Av för det som ritar sin egen. */
    glowing: Boolean = true,
    content: @Composable BoxScope.(hovered: Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    // Markeringen från fjärrkontrollen eller Tab räknas som hovring.
    val active = hovered || focused
    val background by animateColorAsState(
        when {
            selected -> palette.focus.copy(alpha = 0.12f)
            active && enabled -> palette.focus.copy(alpha = 0.07f)
            else -> Color.Transparent
        },
    )
    val scale by animateFloatAsState(if (active && enabled) hoverScale else 1f)
    // Glöd i glödtemat: tydlig där musen står, svagare på det som är valt.
    val glowStrength by animateFloatAsState(
        when {
            !glowing || palette.glow.alpha <= 0f -> 0f
            active && enabled -> 0.55f
            selected -> 0.3f
            else -> 0f
        },
    )

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (glowStrength > 0.01f) Modifier.glow(palette.glowOf(glowStrength), shape) else Modifier)
            .clip(shape)
            .background(background)
            .then(if (focused) Modifier.border(2.dp, palette.focus, shape) else Modifier)
            .hoverable(interaction, enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier),
    ) {
        content(active)
    }
}

/** Vit knapp för den primära handlingen — samma regel som på Android. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(22.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .glow(if (enabled) palette.glowOf(0.45f) else Color.Transparent, shape, 22.dp)
            .clip(shape)
            .background(if (enabled) palette.focus else palette.surfaceRaised)
            .then(if (focused) Modifier.border(3.dp, palette.textPrimary, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .heightIn(min = 40.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        ButtonContent(text, icon, if (enabled) palette.onFocus else palette.textTertiary)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = LocalPalette.current.textPrimary,
) {
    val palette = LocalPalette.current
    Pressable(
        onClick = onClick,
        modifier = modifier.border(1.dp, palette.hairline, RoundedCornerShape(22.dp)),
        enabled = enabled,
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(
            Modifier.heightIn(min = 40.dp).padding(horizontal = 18.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            ButtonContent(text, icon, if (enabled) color else palette.textTertiary)
        }
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Rund ikonknapp, för spelaren och verktygsrader. */
@Composable
fun IconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
    emphasized: Boolean = false,
) {
    val palette = LocalPalette.current
    Pressable(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        selected = emphasized,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = palette.textPrimary,
            modifier = Modifier.size(iconSize).align(Alignment.Center),
        )
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Pressable(
        onClick = onClick,
        modifier = modifier.border(1.dp, if (selected) palette.focus else palette.hairline, shape),
        selected = selected,
        shape = shape,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) palette.textPrimary else palette.textSecondary,
            maxLines = 1,
        )
    }
}

/** Kompakt sökfält. Materials fält är byggda för formulär och tar för stor plats här. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .height(40.dp)
            .clip(shape)
            .background(palette.surfaceRaised)
            .border(1.dp, palette.hairline, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Glyphs.Search, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, color = palette.textTertiary, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.textPrimary),
                cursorBrush = SolidColor(palette.focus),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Glyphs.Close,
                contentDescription = "Rensa",
                tint = palette.textSecondary,
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable { onValueChange("") }
                    .pointerHoverIcon(PointerIcon.Hand),
            )
        }
    }
}

@Composable
fun fieldColors(): TextFieldColors {
    val palette = LocalPalette.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = palette.textPrimary,
        unfocusedTextColor = palette.textPrimary,
        focusedContainerColor = palette.surface,
        unfocusedContainerColor = palette.surface,
        cursorColor = palette.focus,
        focusedBorderColor = palette.focus,
        unfocusedBorderColor = palette.hairline,
        focusedLabelColor = palette.textPrimary,
        unfocusedLabelColor = palette.textTertiary,
        focusedPlaceholderColor = palette.textTertiary,
        unfocusedPlaceholderColor = palette.textTertiary,
    )
}

@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        placeholder = placeholder?.let { hint -> { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        colors = fieldColors(),
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = LocalPalette.current.focus,
        strokeWidth = 3.dp,
    )
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionIcon: ImageVector? = Glyphs.Add,
    onAction: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = palette.textSecondary, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                PrimaryButton(actionLabel, onAction, icon = actionIcon)
            }
        }
    }
}

/** Rubrik över en lista eller ett rutnät, med sökfältet till höger. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = palette.textTertiary)
        }
        SearchField(query, onQuery, placeholder, Modifier.width(300.dp))
    }
}

/** En egen kategori överst i listan, som Disney och dess samlingar. [indent]: en underkategori. */
class PinnedGroup(val key: String, val title: String, val count: Int, val indent: Boolean = false)

/** Kategorispalten till vänster i Live TV, Filmer och Serier. [pinned] står överst, efter "Alla". */
@Composable
fun GroupColumn(
    allLabel: String,
    total: Int,
    groups: List<String>,
    counts: Map<String, Int>,
    selected: String?,
    onSelect: (String?) -> Unit,
    title: String = "KATEGORIER",
    pinned: List<PinnedGroup> = emptyList(),
) {
    if (tvLayout) {
        TvGroupPanel(allLabel, total, groups, counts, selected, onSelect, title, pinned)
        return
    }
    val palette = LocalPalette.current
    val listState = rememberLazyListState()
    Column(Modifier.width(264.dp).fillMaxHeight().background(palette.surface)) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textTertiary,
            modifier = Modifier.padding(start = 24.dp, top = 28.dp, bottom = 10.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 12.dp, end = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    GroupRow(allLabel, total, selected == null) { onSelect(null) }
                }
                items(pinned, key = { it.key }) { group ->
                    GroupRow(group.title, group.count, selected == group.key, indent = group.indent) { onSelect(group.key) }
                }
                if (pinned.isNotEmpty()) item { Spacer(Modifier.height(10.dp)) }
                items(groups, key = { it }) { group ->
                    GroupRow(group, counts[group] ?: 0, selected == group) { onSelect(group) }
                }
            }
            Scrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun GroupRow(name: String, count: Int, selected: Boolean, indent: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Pressable(onClick = onClick, selected = selected, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (indent) 26.dp else 12.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) palette.textPrimary else palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(count.grouped(), style = MaterialTheme.typography.labelSmall, color = palette.textTertiary)
        }
    }
}

/**
 * Tv: kategorierna som en smal kant till vänster om innehållet. Pil vänster
 * från omslagen fäller ut listan över innehållet, med markeringen på den
 * valda kategorin; pil höger tillbaka fäller in den. Ett steg till vänster
 * till går till menyn.
 */
@Composable
private fun TvGroupPanel(
    allLabel: String,
    total: Int,
    groups: List<String>,
    counts: Map<String, Int>,
    selected: String?,
    onSelect: (String?) -> Unit,
    title: String,
    pinned: List<PinnedGroup>,
) {
    val palette = LocalPalette.current
    val listState = rememberLazyListState()
    val selectedRow = remember { FocusRequester() }
    var open by remember { mutableStateOf(false) }
    // Från menyn går pil höger förbi kanten, rakt till innehållet.
    val enterable = open || !LocalMenuFocused.current
    val width by animateDpAsState(
        targetValue = if (open) TV_GROUPS_OPEN else TV_GROUPS_CLOSED,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "kategorier",
    )
    val textAlpha = animateFloatAsState(if (open) 1f else 0f, tween(if (open) 260 else 120), label = "kategoritext")
    val shown = remember(textAlpha) { { textAlpha.value } }
    // "Alla" står först, sedan de egna (Disney), sedan kategorierna.
    val pinnedIndex = pinned.indexOfFirst { it.key == selected }
    val selectedIndex = when {
        selected == null -> 0
        pinnedIndex >= 0 -> 1 + pinnedIndex
        else -> (1 + pinned.size + (if (pinned.isNotEmpty()) 1 else 0) + groups.indexOf(selected)).coerceAtLeast(0)
    }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        // Markeringen hoppar till den valda kategorin, inte till raden man råkade komma in vid.
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }) {
            listState.scrollToItem((selectedIndex - 3).coerceAtLeast(0))
        }
        withFrameNanos { }
        runCatching { selectedRow.requestFocus() }
    }
    // Platsen i raden är kantens bredd; utfälld lägger sig listan över innehållet.
    Box(Modifier.width(TV_GROUPS_CLOSED).fillMaxHeight().zIndex(1f)) {
        Column(
            Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .width(width)
                .fillMaxHeight()
                .shadow(if (open) 24.dp else 0.dp)
                .background(if (open) palette.background else Color.Transparent)
                .background(palette.surface)
                .onFocusChanged { open = it.hasFocus }
                .focusGroup(),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textTertiary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 22.dp, top = 28.dp, bottom = 10.dp).graphicsLayer { alpha = shown() },
            )
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    TvGroupRow(allLabel, total, selected == null, shown, enterable, if (selectedIndex == 0) selectedRow else null) {
                        onSelect(null)
                    }
                }
                items(pinned, key = { it.key }) { group ->
                    TvGroupRow(
                        group.title,
                        group.count,
                        selected == group.key,
                        shown,
                        enterable,
                        if (group.key == selected) selectedRow else null,
                        indent = group.indent,
                    ) { onSelect(group.key) }
                }
                if (pinned.isNotEmpty()) item { Spacer(Modifier.height(10.dp)) }
                items(groups, key = { it }) { group ->
                    TvGroupRow(group, counts[group] ?: 0, selected == group, shown, enterable, if (group == selected) selectedRow else null) {
                        onSelect(group)
                    }
                }
            }
        }
        // Infälld: en pil som visar att kategorierna finns här.
        if (!open) {
            Icon(
                Glyphs.ChevronDown,
                contentDescription = null,
                tint = palette.textTertiary,
                modifier = Modifier.align(Alignment.Center).rotate(-90f).size(20.dp),
            )
        }
    }
}

/** En kategori i tv-listan. Texten syns bara utfälld; den valda raden syns som en ljus fläck i kanten. */
@Composable
private fun TvGroupRow(
    name: String,
    count: Int,
    selected: Boolean,
    shown: () -> Float,
    enterable: Boolean,
    focus: FocusRequester?,
    indent: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Pressable(
        onClick = onClick,
        selected = selected,
        modifier = Modifier
            .fillMaxWidth()
            .focusProperties { canFocus = enterable }
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = if (indent) 30.dp else 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
                .graphicsLayer { alpha = shown() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) palette.textPrimary else palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                count.grouped(),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textTertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private val TV_GROUPS_CLOSED = 36.dp
private val TV_GROUPS_OPEN = 340.dp

/** Liten etikett: LIVE-märket, betyg. */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Box(
        modifier
            .glow(if (palette.glow.alpha > 0f) color.copy(alpha = 0.45f) else Color.Transparent, RoundedCornerShape(4.dp), 12.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides color) {
            Text(text, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}
