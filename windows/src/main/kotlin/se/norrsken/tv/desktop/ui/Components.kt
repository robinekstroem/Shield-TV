package se.norrsken.tv.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


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
            .then(if (focused) Modifier.focusRing(palette, 2.dp, shape) else Modifier)
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
        modifier = modifier
            .then(if (selected) Modifier.background(palette.focus, shape) else Modifier)
            .border(1.dp, if (selected) palette.focus else palette.hairline, shape),
        selected = selected,
        shape = shape,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = if (selected) palette.onFocus else palette.textSecondary,
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
        Modifier.fillMaxWidth().padding(start = if (tvLayout) 46.dp else 28.dp, end = if (tvLayout) 40.dp else 28.dp, top = 24.dp, bottom = 16.dp),
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
    /** Tv: pil höger eller OK i listan går hit — till det man senast stod på i innehållet. */
    onEnterContent: (() -> Unit)? = null,
) {
    if (tvLayout) {
        TvGroupPanel(allLabel, total, groups, counts, selected, onSelect, title, pinned, onEnterContent)
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

/** En rad i tv-listan: "Alla", en egen kategori eller en vanlig. */
private class GroupEntry(val key: String?, val name: String, val count: Int, val indent: Boolean) {
    val id: String get() = key ?: ALL_ID
}

/** Raden markeringen står på, i väntan på att den ska väljas. */
private class GroupPick(val key: String?)

private const val ALL_ID = "\u0000alla"

/**
 * Tv: kategorierna. De syns bara när man är i dem: pil vänster vid
 * innehållets kant låter dem glida in över innehållet, och de glider ut
 * igen när man går vidare. Står minispelaren i menyn är de i stället en
 * smal kant som fälls ut när man går dit.
 *
 * - In i listan hamnar markeringen alltid på den valda kategorin.
 * - Kategorin byts medan man bläddrar, när markeringen stannat en kort stund,
 *   så att innehållet bredvid visar den direkt.
 * - Pil höger eller OK går in i innehållet ([onEnterContent]); pil vänster till menyn.
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
    onEnterContent: (() -> Unit)?,
) {
    val palette = LocalPalette.current
    // Menyn räknar med kategorierna när man väljer en flik: finns de, går markeringen hit.
    DisposableEffect(Unit) {
        TvNav.categories++
        onDispose {
            TvNav.categories--
            if (TvNav.categories <= 0 && TvNav.drawer == TvDrawer.CATEGORIES) TvNav.drawer = TvDrawer.NONE
        }
    }
    // Tappas markeringen i innehållet (det man stod på stängdes) hittar den
    // tillbaka dit man senast stod, samma väg som från kategorierna.
    if (onEnterContent != null) {
        TvFocusFallback {
            onEnterContent()
            true
        }
    }
    if (LocalTvDocked.current) {
        var open by remember { mutableStateOf(false) }
        val width by animateDpAsState(
            targetValue = if (open) TV_GROUPS_OPEN else TV_GROUPS_CLOSED,
            animationSpec = tween(240, easing = FastOutSlowInEasing),
            label = "kategorier",
        )
        val textAlpha = animateFloatAsState(if (open) 1f else 0f, tween(if (open) 260 else 120), label = "kategoritext")
        val shown = remember(textAlpha) { { textAlpha.value } }
        // Platsen i raden är kantens bredd; utfälld lägger sig listan över innehållet.
        Box(Modifier.width(TV_GROUPS_CLOSED).fillMaxHeight().zIndex(1f)) {
            TvGroupList(
                allLabel, total, groups, counts, selected, onSelect, title, pinned,
                shown = shown,
                takeFocus = false,
                onLeft = null,
                onEnterContent = onEnterContent,
                onFocus = { open = it },
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .width(width)
                    .fillMaxHeight()
                    .shadow(if (open) 24.dp else 0.dp)
                    .background(if (open) palette.background else Color.Transparent)
                    .drawBehind { drawerBackdrop(palette) },
            )
            if (!open) {
                Icon(
                    Glyphs.ChevronDown,
                    contentDescription = null,
                    tint = palette.textTertiary,
                    modifier = Modifier.align(Alignment.Center).rotate(-90f).size(20.dp),
                )
            }
        }
        return
    }
    // Ingen plats i raden: listan glider in över innehållet, med skugga mot det.
    Box(Modifier.width(0.dp).fillMaxHeight().zIndex(3f)) {
        AnimatedVisibility(
            visible = TvNav.drawer == TvDrawer.CATEGORIES,
            enter = slideInHorizontally(tween(DRAWER_MS, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(DRAWER_MS)),
            exit = slideOutHorizontally(tween(DRAWER_MS, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(DRAWER_MS / 2)),
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).fillMaxHeight(),
        ) {
            Row(Modifier.fillMaxHeight()) {
                TvGroupList(
                    allLabel, total, groups, counts, selected, onSelect, title, pinned,
                    shown = { 1f },
                    takeFocus = true,
                    onLeft = { TvNav.openMenu() },
                    onEnterContent = onEnterContent,
                    // Markeringen gick vidare till innehållet: listan glider undan.
                    onFocus = { has -> if (!has && TvNav.drawer == TvDrawer.CATEGORIES) TvNav.drawer = TvDrawer.NONE },
                    modifier = Modifier
                        .width(TV_GROUPS_OPEN)
                        .fillMaxHeight()
                        .shadow(24.dp)
                        .background(palette.background)
                        .drawBehind { drawerBackdrop(palette) },
                )
                Box(
                    Modifier
                        .width(DRAWER_SCRIM)
                        .fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))),
                )
            }
        }
    }
}

/** Själva listan: rubriken och raderna, med markeringens regler (se [TvGroupPanel]). */
@Composable
private fun TvGroupList(
    allLabel: String,
    total: Int,
    groups: List<String>,
    counts: Map<String, Int>,
    selected: String?,
    onSelect: (String?) -> Unit,
    title: String,
    pinned: List<PinnedGroup>,
    shown: () -> Float,
    /** Tar emot markeringen när listan visas och när menyn lämnar över den. */
    takeFocus: Boolean,
    onLeft: (() -> Unit)?,
    onEnterContent: (() -> Unit)?,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    val listState = rememberLazyListState()
    val selectedRow = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val entries = remember(allLabel, total, groups, counts, pinned) {
        buildList {
            add(GroupEntry(null, allLabel, total, indent = false))
            pinned.forEach { add(GroupEntry(it.key, it.title, it.count, it.indent)) }
            groups.forEach { add(GroupEntry(it, it, counts[it] ?: 0, indent = false)) }
        }
    }
    val selectedIndex = entries.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    val currentSelected by rememberUpdatedState(selected)
    val currentIndex by rememberUpdatedState(selectedIndex)
    val select by rememberUpdatedState(onSelect)
    val enter by rememberUpdatedState(onEnterContent)
    var pending by remember { mutableStateOf<GroupPick?>(null) }
    var focusedIndex by remember { mutableIntStateOf(-1) }

    // Kategorin markeringen stannat på blir vald, så att innehållet bredvid följer med.
    LaunchedEffect(pending) {
        val pick = pending ?: return@LaunchedEffect
        delay(GROUP_PICK_MS)
        if (pick.key != currentSelected) select(pick.key)
    }
    // In i innehållet. Kategorin man stod på gäller direkt, utan att vänta ut pausen.
    fun goRight(): Boolean {
        pending?.let { pick -> if (pick.key != currentSelected) select(pick.key) }
        pending = null
        val target = enter ?: return false
        target()
        return true
    }
    if (takeFocus) {
        LaunchedEffect(TvNav.categoryCalls) { focusRow(listState, currentIndex, selectedRow) }
        // Tillbaka-knappen gör samma sak som pil höger medan listan syns.
        DisposableEffect(Unit) {
            TvNav.leaveCategories = { if (!goRight()) focusManager.moveFocus(FocusDirection.Right) }
            onDispose { TvNav.leaveCategories = null }
        }
    }

    Column(
        modifier
            .onFocusChanged {
                if (it.hasFocus != focused) {
                    // In i listan: till den valda kategorin, inte raden man råkade komma in vid.
                    if (it.hasFocus) scope.launch { focusRow(listState, currentIndex, selectedRow) }
                    focused = it.hasFocus
                    onFocus(it.hasFocus)
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> goRight()
                    Key.DirectionLeft -> {
                        val left = onLeft ?: return@onPreviewKeyEvent false
                        left()
                        true
                    }
                    // Listan tar slut: markeringen stannar i stället för att hoppa ut i innehållet.
                    Key.DirectionUp -> focusedIndex == 0
                    Key.DirectionDown -> focusedIndex == entries.lastIndex
                    else -> false
                }
            }
            .focusGroup(),
    ) {
        // Rubriken med ett kort streck i markeringens färg under.
        Column(Modifier.padding(start = 22.dp, top = 30.dp, bottom = 14.dp).graphicsLayer { alpha = shown() }) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                color = palette.textSecondary,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.height(7.dp))
            Box(Modifier.size(width = 26.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(menuFill(palette)))
        }
        TvScrolling(lead = 0.2f, trail = 0.2f) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                    TvGroupRow(
                        name = entry.name,
                        count = entry.count,
                        selected = index == selectedIndex,
                        shown = shown,
                        focus = if (index == selectedIndex) selectedRow else null,
                        indent = entry.indent,
                        gapAbove = pinned.isNotEmpty() && index == pinned.size + 1,
                        onFocused = {
                            focusedIndex = index
                            pending = GroupPick(entry.key)
                        },
                        onClick = {
                            pending = null
                            if (entry.key != currentSelected) select(entry.key)
                            if (!goRight()) focusManager.moveFocus(FocusDirection.Right)
                        },
                    )
                }
            }
        }
    }
}

/** Markeringen till raden [index]: rullar fram den först om den inte syns. */
private suspend fun focusRow(listState: LazyListState, index: Int, requester: FocusRequester) {
    if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
        listState.scrollToItem((index - 3).coerceAtLeast(0))
    }
    repeat(2) { withFrameNanos { } }
    runCatching { requester.requestFocus() }
}

/**
 * En kategori i tv-listan. Texten syns bara utfälld. Markerad fylls raden
 * med samma färg som menyns markerade rad, som tonar in och ut — ingen ram.
 * Den valda kategorin har ett streck i kanten. Det som står inom hakparentes
 * eller sist i namnet om bildkvalitet och språk ("4K", "DV", "MULTI-SUB")
 * står som små märken efter namnet.
 */
@Composable
private fun TvGroupRow(
    name: String,
    count: Int,
    selected: Boolean,
    shown: () -> Float,
    focus: FocusRequester?,
    indent: Boolean,
    gapAbove: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val active = focused || hovered
    val fill = menuFill(palette)
    val ink = menuInk(palette)
    // Läses först när raden ritas: bara ritningen görs om medan den tonar.
    val lit = animateFloatAsState(if (active) 1f else 0f, tween(ROW_FADE_MS), label = "kategorirad")
    val label = remember(name) { categoryLabel(name) }
    Box(
        Modifier
            .padding(top = if (gapAbove) 10.dp else 0.dp)
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clip(RoundedCornerShape(12.dp))
            .drawBehind {
                val alpha = lit.value
                if (alpha > 0.001f) {
                    drawRect(fill, alpha = alpha)
                    drawRect(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent)),
                        alpha = alpha,
                    )
                }
            }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        if (selected && !active) {
            // Kategorin man är i: ett streck i märkets färger.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(width = 3.dp, height = 20.dp)
                    .clip(CircleShape)
                    .background(Brand.stripe),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = if (indent) 30.dp else 14.dp, end = 14.dp, top = 11.dp, bottom = 11.dp)
                .graphicsLayer { alpha = shown() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Namnet och märkena direkt efter det; antalet längst till höger.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label.name,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected || active) FontWeight.SemiBold else FontWeight.Medium,
                    color = when {
                        active -> ink
                        selected -> palette.textPrimary
                        else -> palette.textSecondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                label.marks.forEach { mark ->
                    Spacer(Modifier.width(6.dp))
                    CategoryMark(mark, active, ink)
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                count.grouped(),
                style = MaterialTheme.typography.labelMedium,
                color = if (active) ink.copy(alpha = 0.78f) else palette.textTertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** En kategoris namn utan märkena, och märkena för sig. */
internal class CategoryLabel(val name: String, val marks: List<String>)

/**
 * "Filmer [4K]" → "Filmer" med märket 4K; "Action (Multi-Sub)" → "Action"
 * med MULTI-SUB; "Dokumentär UHD" → "Dokumentär" med UHD. Högst två märken,
 * och namnet blir aldrig tomt.
 */
internal fun categoryLabel(raw: String): CategoryLabel {
    val marks = ArrayList<String>()
    var name = BRACKETS.replace(raw) { match ->
        val inner = match.groupValues[1].trim()
        if (inner.isNotEmpty() && inner.length <= 12) {
            marks += inner.uppercase()
            " "
        } else {
            match.value
        }
    }
    // Bildkvaliteten sist i namnet: "Filmer 4K", "Dokumentär UHD HDR".
    while (true) {
        val words = name.trim().split(' ').filter { it.isNotEmpty() }
        val last = words.lastOrNull() ?: break
        if (words.size < 2 || last.uppercase() !in QUALITY_WORDS) break
        marks.add(0, last.uppercase())
        name = words.dropLast(1).joinToString(" ")
    }
    name = name.replace(SPACES, " ").trim().trimEnd('-', '|', ':', '/').trim()
    if (name.isEmpty()) return CategoryLabel(raw.trim(), emptyList())
    return CategoryLabel(name, marks.distinct().take(2))
}

private val BRACKETS = Regex("""[\[(]([^\[\]()]*)[\])]""")
private val SPACES = Regex("""\s+""")
private val QUALITY_WORDS = setOf("4K", "UHD", "8K", "HDR", "HDR10", "DV", "FHD", "HEVC", "H265", "3D")

/** Ett litet märke efter kategorins namn: guld för 4K, violett för HDR och Dolby Vision. */
@Composable
private fun CategoryMark(mark: String, active: Boolean, ink: Color) {
    val color = when {
        mark in setOf("4K", "UHD", "8K") -> MARK_GOLD
        mark in setOf("DV", "HDR", "HDR10") || "VISION" in mark -> MARK_VIOLET
        "SUB" in mark || "TEXT" in mark -> MARK_TEAL
        else -> null
    }
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier
            .clip(shape)
            .background(
                when {
                    active -> ink.copy(alpha = 0.18f)
                    color != null -> color.copy(alpha = 0.18f)
                    else -> Color.White.copy(alpha = 0.08f)
                },
            )
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            mark,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
            color = when {
                active -> ink
                color != null -> color
                else -> LocalPalette.current.textTertiary
            },
            maxLines = 1,
            softWrap = false,
        )
    }
}

private val MARK_GOLD = Color(0xFFE8B84A)
private val MARK_VIOLET = Color(0xFFB79CFF)
private val MARK_TEAL = Color(0xFF52D6C8)
private const val ROW_FADE_MS = 130

private val TV_GROUPS_CLOSED = 36.dp
private val TV_GROUPS_OPEN = 340.dp
/** Så länge markeringen ska stå still på en kategori innan den väljs. */
private const val GROUP_PICK_MS = 300L

/** Ett uträknat värde, och vad användaren själv valt när det räknades (kategori, sökning, sortering). */
internal class Computed<T>(val inputs: Any?, val value: T)

/**
 * Tv: listan man bläddrar i byts inte ut under en. Nya betyg, år och
 * versioner som kommer i bakgrunden ändrar ordningen, och då hoppar
 * omslagen; det väntar tills markeringen lämnat rutnätet. Det man själv
 * ändrar — kategori, sökning, sortering — gäller direkt.
 */
@Composable
internal fun <T> rememberSteady(computed: Computed<T>?, browsing: Boolean): T? {
    val shown = remember { SteadyHolder<T>() }
    val current = shown.value
    if (computed != null && (current == null || !browsing || computed.inputs != current.inputs)) {
        shown.value = computed
    }
    return shown.value?.value
}

private class SteadyHolder<T> {
    var value: Computed<T>? = null
}

/**
 * Räknar fram ett värde i bakgrunden när [keys] ändras och behåller det förra
 * under tiden, så att tunga listor — tiotusentals filmer som sorteras — aldrig
 * låser menyerna. Null tills det första värdet är klart.
 */
@Composable
internal fun <T> rememberInBackground(vararg keys: Any?, compute: () -> T): T? {
    val result = remember { mutableStateOf<T?>(null) }
    val latest by rememberUpdatedState(compute)
    LaunchedEffect(*keys) {
        result.value = withContext(Dispatchers.Default) { latest() }
    }
    return result.value
}

/**
 * Tv: var markeringen landar när man går in i en lista eller ett rutnät från
 * kategorierna — det man senast stod på om det fortfarande syns, annars det
 * första som syns. Raden eller omslaget märks med [mark].
 */
@Stable
internal class TvEntry {
    val requester = FocusRequester()
    var target by mutableStateOf<Any?>(null)
        private set
    /** Det som senast var markerat i innehållet. */
    var last: Any? = null

    fun mark(key: Any): Modifier = if (key == target) Modifier.focusRequester(requester) else Modifier

    /** [visible]: nycklarna som syns just nu, i ordning. */
    suspend fun enter(visible: () -> List<Any>) {
        // Byttes kategorin nyss: vänta in den nya listan.
        repeat(2) { withFrameNanos { } }
        val keys = visible()
        val chosen = last?.takeIf { it in keys } ?: keys.firstOrNull() ?: return
        target = chosen
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
    }
}

/**
 * Tv: hur listor rullar när markeringen flyttas. Det markerade hålls innanför
 * [lead] från början och [trail] från slutet, i andelar av höjden, så att man
 * alltid ser vad som kommer. [snap]: hamnar det utanför läggs det vid [lead]
 * — rutnät rullar då en hel rad i taget, och raden man står på hamnar överst.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvScrolling(lead: Float, trail: Float, snap: Boolean = false, content: @Composable () -> Unit) {
    if (!tvLayout) {
        content()
        return
    }
    val spec = remember(lead, trail, snap) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val top = containerSize * lead
                val bottom = containerSize * (1f - trail)
                return when {
                    // Större än utrymmet: början överst.
                    size > bottom - top -> offset - top
                    offset >= top && offset + size <= bottom -> 0f
                    snap -> offset - top
                    offset < top -> offset - top
                    else -> offset + size - bottom
                }
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * Tv: markeringen stannar i en panel eller lista i stället för att hoppa ut
 * till det som ligger bakom den. Kanterna som anges är stängda. På datorn
 * ingenting.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.tvEdges(
    blockUp: Boolean = false,
    blockDown: Boolean = false,
    blockLeft: Boolean = false,
    blockRight: Boolean = false,
): Modifier =
    if (!tvLayout || !(blockUp || blockDown || blockLeft || blockRight)) {
        this
    } else {
        focusProperties {
            if (blockUp) up = FocusRequester.Cancel
            if (blockDown) down = FocusRequester.Cancel
            if (blockLeft) left = FocusRequester.Cancel
            if (blockRight) right = FocusRequester.Cancel
        }
    }

/**
 * Tv: en ruta som markeringen kan stanna på, så att en sida går att bläddra
 * med pilarna också där inget går att välja, som en lång text. På datorn
 * bara innehållet.
 */
@Composable
internal fun TvFocusBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp), content: @Composable BoxScope.() -> Unit) {
    if (!tvLayout) {
        Box(modifier, content = content)
        return
    }
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .clip(shape)
            .then(
                if (focused) {
                    Modifier.background(palette.focus.copy(alpha = 0.08f)).focusRing(palette, 2.dp, shape, 0.8f)
                } else {
                    Modifier
                },
            )
            .focusable(interactionSource = interaction),
        content = content,
    )
}

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
