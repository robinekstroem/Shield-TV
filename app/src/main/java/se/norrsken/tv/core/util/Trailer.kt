package se.norrsken.tv.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Öppnar en trailer i den app som hanterar länken — på TV och telefon
 * nästan alltid YouTube-appen.
 *
 * Xtream skickar ibland bara ett YouTube-id ("dQw4w9WgXcQ"), ibland en hel
 * adress. Båda hanteras. Trailern spelas i YouTubes egen app enligt deras
 * villkor — appen försöker inte bädda in eller ladda ner den.
 *
 * Returnerar false om ingen app kunde öppna länken.
 */
fun openTrailer(context: Context, value: String): Boolean {
    val url = if (value.startsWith("http")) value else "https://www.youtube.com/watch?v=$value"
    return runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
}
