package se.norrsken.tv.shield

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import se.norrsken.tv.desktop.ui.LocalPalette
import se.norrsken.tv.desktop.ui.PrimaryButton
import java.io.File

/**
 * Felrapporten. Stängs appen av ett fel sparas felet, och nästa gång appen
 * startar visas det på skärmen, så att det går att fotografera och skicka.
 * Ingen dator eller kabel behövs för att se vad som hände.
 */
object CrashReport {
    private const val FILE = "senaste-krasch.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = buildString {
                    append("Tv för Shield · Android ").append(Build.VERSION.RELEASE)
                    append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    append(describe(error))
                }
                File(context.filesDir, FILE).writeText(text.take(20_000), Charsets.UTF_8)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Rapporten från förra gången, om appen stängdes av ett fel. Läses en gång. */
    fun take(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        file.delete()
        return text?.ifBlank { null }
    }

    /** Felet och dess orsaker, med de översta raderna ur varje: det som behövs för att hitta felet. */
    private fun describe(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            if (depth > 0) append("\n\nOrsak: ")
            append(current.javaClass.name).append(": ").append(current.message.orEmpty())
            current.stackTrace.take(8).forEach { append("\n    at ").append(it.toString()) }
            val cause = current.cause
            current = if (cause === current) null else cause
            depth++
        }
    }
}

@Composable
fun CrashScreen(report: String, onContinue: () -> Unit) {
    val palette = LocalPalette.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(palette.background)) {
        Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Appen stängdes av ett fel förra gången", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Ta ett foto av rutan nedan och skicka det i chatten, så kan felet rättas. Tryck sedan på Fortsätt.",
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
            )
            PrimaryButton("Fortsätt", onClick = onContinue, modifier = Modifier.focusRequester(focus))
            SelectionContainer(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.surface)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontStyle = FontStyle.Normal),
                    color = palette.textPrimary,
                )
            }
        }
    }
}
