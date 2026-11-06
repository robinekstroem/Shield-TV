package se.norrsken.tv.ipad

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hämtade loggor, bara i minnet. Används bara från huvudtråden. */
private object LogoCache {
    val images = LinkedHashMap<String, ImageBitmap>(64, 0.75f, true)
    val failed = HashSet<String>()

    fun store(url: String, image: ImageBitmap) {
        images[url] = image
        while (images.size > 400) {
            val oldest = images.keys.first()
            images.remove(oldest)
        }
    }
}

/** En kanallogga från nätet. Visar [placeholder] tills den finns, och för alltid om den inte gick att hämta. */
@Composable
internal fun Logo(url: String?, modifier: Modifier, placeholder: @Composable () -> Unit = {}) {
    var image by remember(url) { mutableStateOf(url?.let { LogoCache.images[it] }) }
    if (url != null && image == null && url !in LogoCache.failed) {
        LaunchedEffect(url) {
            val loaded = runCatching {
                withContext(Dispatchers.Default) {
                    org.jetbrains.skia.Image.makeFromEncoded(fetchBytes(url)).toComposeImageBitmap()
                }
            }.getOrNull()
            if (loaded != null) {
                LogoCache.store(url, loaded)
                image = loaded
            } else {
                LogoCache.failed += url
            }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val current = image
        if (current != null) {
            Image(current, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            placeholder()
        }
    }
}
