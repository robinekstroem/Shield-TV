package se.norrsken.tv.desktop.ui

import se.norrsken.tv.shield.R
import se.norrsken.tv.shield.RemoteInputHost
import se.norrsken.tv.shield.TvResolution
import se.norrsken.tv.shield.TvDisplay
import se.norrsken.tv.shield.RemoteInputServer
import se.norrsken.tv.desktop.AppState
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import android.content.Intent
import android.net.Uri
import android.view.SurfaceView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import se.norrsken.tv.desktop.data.AppDirs
import se.norrsken.tv.desktop.player.VideoPlayer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

// Det som skiljer Shield från Windows i gränssnittet. Samma namn och samma
// funktioner som Windows-filen, skrivna för Android.

/** Ingen rullningslist med fjärrkontroll: markeringen visar var man är. */
@Composable
fun ProvideScrollbars(palette: Palette, content: @Composable () -> Unit) {
    content()
}

@Composable
fun Scrollbar(state: LazyListState, modifier: Modifier = Modifier) = Unit

@Composable
fun Scrollbar(state: LazyGridState, modifier: Modifier = Modifier) = Unit

@Composable
fun Scrollbar(state: ScrollState, modifier: Modifier = Modifier) = Unit

@Composable
fun HorizontalBar(state: ScrollState, modifier: Modifier = Modifier) = Unit

/**
 * Ingen glöd på tv:n. Suddningen bakom varje markerat omslag, knapp och rad
 * räknades om för varje bildruta och gjorde bläddrandet tungt, och skenet
 * gjorde kanterna otydliga på tv:n. Markeringen syns i stället med fyllning,
 * ram och storlek. På datorn finns glöden kvar.
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.glow(color: Color, shape: Shape, radius: Dp = 18.dp): Modifier = this

/** Öppnar en adress, om det finns något på Shield som kan öppna den. */
internal fun openInBrowser(url: String) {
    runCatching {
        AppDirs.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Musik: Spotifys tv-app (eller den vanliga), och finns ingen av dem Spotify
 * i Play Butik. Musiken spelar vidare när man kommer tillbaka hit. Falskt om
 * inget gick att öppna. Paketen står i manifestets queries, annars syns de inte.
 */
internal fun openSpotify(): Boolean {
    val context = AppDirs.context
    val packages = context.packageManager
    for (name in listOf("com.spotify.tv.android", "com.spotify.music")) {
        val intent = packages.getLeanbackLaunchIntentForPackage(name) ?: packages.getLaunchIntentForPackage(name) ?: continue
        if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
    }
    return runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.spotify.tv.android")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
}

/** Ingen fildialog på en tv-box. Egna bakgrundsbilder läggs in från Windows-appen. */
internal fun pickImages(): List<File> = emptyList()

/** Ingen muspekare att gömma; Shield styrs med fjärrkontrollen. */
internal val hiddenCursor: PointerIcon = PointerIcon.Default

/**
 * Bilden. ExoPlayer ritar direkt i en SurfaceView med hårdvarans hjälp —
 * även HDR. Ytan får bildens format; undertexterna ritas ovanpå.
 */
@Composable
internal fun VideoSurface(player: VideoPlayer, sampleAspect: Float, sharp: Boolean, modifier: Modifier) {
    // Bara bildens storlek: tiden ändras fyra gånger i sekunden och ska inte rita om ytan.
    val size by remember(player) { player.status.map { it.videoWidth to it.videoHeight }.distinctUntilChanged() }
        .collectAsState(initial = 0 to 0)
    val cues by player.cues.collectAsState()
    Box(modifier, contentAlignment = Alignment.Center) {
        val (videoWidth, videoHeight) = size
        val ratio = if (videoWidth > 0 && videoHeight > 0) {
            videoWidth * sampleAspect / videoHeight
        } else {
            16f / 9f
        }
        AndroidView(
            factory = { context -> SurfaceView(context).also { player.attachSurface(it) } },
            modifier = Modifier.aspectRatio(ratio),
            onRelease = { player.detachSurface(it) },
        )
        if (cues.isNotEmpty()) {
            Text(
                cues.joinToString("\n"),
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 64.dp, end = 64.dp, bottom = 56.dp),
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = Color.White,
                    fontFamily = FontFamily.Default,
                    fontStyle = FontStyle.Normal,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 8f),
                ),
            )
        }
    }
}

/** Shield: källor och bilder skickas från telefonen i stället för med fjärrkontrollen. */
internal const val remoteInputAvailable = true
internal const val canPickFiles = false

/**
 * Adressen och koden för webbsidan där källor och bakgrundsbilder skickas
 * från telefonen. Sidan finns bara så länge den här panelen visas.
 */
@Composable
internal fun RemoteInputPanel(state: AppState) {
    val palette = LocalPalette.current
    val server = rememberRemoteInput(state)
    val big = MaterialTheme.typography.headlineSmall.copy(
        fontFamily = FontFamily.Monospace,
        fontStyle = FontStyle.Normal,
        fontWeight = FontWeight.SemiBold,
        shadow = null,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Slipp skriva långa adresser med fjärrkontrollen. Öppna adressen i webbläsaren på telefonen eller " +
                "datorn, på samma wifi som Shield, och klistra in spellistan där. Bakgrundsbilder skickas samma väg.",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.textSecondary,
        )
        val address = server.address
        val problem = server.problem
        when {
            address != null -> {
                Text(address, style = big, color = palette.focus)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Kod", style = MaterialTheme.typography.titleMedium, color = palette.textSecondary)
                    Text(server.pin, style = big, color = palette.textPrimary)
                }
            }
            problem != null -> Text(problem, style = MaterialTheme.typography.bodyLarge, color = palette.alert)
            else -> Text("Startar…", style = MaterialTheme.typography.bodyLarge, color = palette.textTertiary)
        }
        server.lastMessage?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = palette.signal) }
    }
}

/**
 * Menyernas upplösning. Högre får plats med mer, som en datorskärm; texten
 * växer med så att den går att läsa från soffan.
 */
@Composable
internal fun DisplayOptions() {
    val palette = LocalPalette.current
    // Vad som faktiskt ritas: fönstrets bildpunkter, och vad tv:n får.
    val metrics = LocalContext.current.resources.displayMetrics
    val mode = LocalView.current.display?.mode
    val window = "${metrics.widthPixels} × ${metrics.heightPixels}"
    val output = mode?.let { "${it.physicalWidth} × ${it.physicalHeight}" }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Storlek på menyerna", style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
        Text(
            "Ändrar hur stort allt är och hur mycket som får plats, inte skärpan.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
        )
        Text(
            if (output != null && output != window) {
                "Menyerna ritas i $window bildpunkter, som Android TV gör för alla appar, och skalas upp till tv:ns $output. Filmer och serier spelas i full upplösning."
            } else {
                "Menyerna ritas i $window bildpunkter, tv:ns fulla upplösning."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvResolution.entries.forEach { option ->
                Chip(option.label, selected = TvDisplay.resolution == option, onClick = { TvDisplay.select(option) })
            }
        }
    }
}

/** Shield styrs med fjärrkontroll: tydlig markering och titeln man står på överst. */
internal const val tvLayout = true

/**
 * De nya menyernas typsnitt (MenuStyles.kt): Anton för klockan och de stora
 * namnen, och en smal Roboto för raderna. Ligger i res/font; licenserna
 * (SIL OFL) i assets/licenses.
 */
internal val menuDisplayFont: FontFamily = FontFamily(Font(R.font.anton))

internal val menuCondensedFont: FontFamily = FontFamily(
    Font(R.font.tv_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.tv_condensed_bold, FontWeight.Bold),
)

/** Under appens namn i sidomenyn. */
internal const val platformName = "Shield"

/** Sidan för inmatning från telefonen, delad av rutorna och stängd när ingen visas. */
@Composable
private fun rememberRemoteInput(state: AppState): RemoteInputServer {
    val server = remember { RemoteInputHost.acquire(state) }
    DisposableEffect(server) {
        onDispose { RemoteInputHost.release() }
    }
    return server
}

/** TMDB-nyckeln på Shield: klistras in i telefonen, på samma sida som spellistorna. */
@Composable
internal fun TmdbPhoneHint(state: AppState) {
    val palette = LocalPalette.current
    val server = rememberRemoteInput(state)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Enklast: öppna adressen i webbläsaren på telefonen, skriv koden och klistra in nyckeln under TMDB-nyckel.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
        )
        val address = server.address
        if (address != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    address,
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, fontStyle = FontStyle.Normal, shadow = null),
                    color = palette.focus,
                )
                Text(
                    "Kod ${server.pin}",
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, fontStyle = FontStyle.Normal, shadow = null),
                    color = palette.textPrimary,
                )
            }
        } else {
            Text(server.problem ?: "Startar…", style = MaterialTheme.typography.bodyMedium, color = palette.textTertiary)
        }
    }
}

/** Egna bakgrundsbilder på Shield: skickas från telefonen eller datorn, till samma sida som spellistorna. */
@Composable
internal fun BackgroundUploadHint(state: AppState) {
    val palette = LocalPalette.current
    val server = rememberRemoteInput(state)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Lägg till bilder från telefonen eller datorn: öppna adressen i webbläsaren, skriv koden och välj bilder under Bakgrundsbild.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
        )
        val address = server.address
        if (address != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    address,
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, fontStyle = FontStyle.Normal, shadow = null),
                    color = palette.focus,
                )
                Text(
                    "Kod ${server.pin}",
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, fontStyle = FontStyle.Normal, shadow = null),
                    color = palette.textPrimary,
                )
            }
        } else {
            Text(server.problem ?: "Startar…", style = MaterialTheme.typography.bodyMedium, color = palette.textTertiary)
        }
    }
}

/**
 * Shield: spelaren täcker alltid hela tv:n, så helskärm är bara samma ruta.
 * Något eget helskärmsfönster finns inte, och skärmen under ska aldrig bli svart.
 */
internal const val separateFullscreenWindow = false

/** Återanvänds: ritningen sker bara på UI-tråden, och duken kopierar matrisen. */
private val perspectiveMatrix = android.graphics.Matrix()

/**
 * Lägger en 3×3-matris med perspektiv (radvis: x, y och perspektivraden) på
 * ritytan, ovanpå det som redan gäller. Startens filmvägg ritas med den.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.concatPerspective(m: FloatArray) {
    perspectiveMatrix.setValues(m)
    drawContext.canvas.nativeCanvas.concat(perspectiveMatrix)
}
