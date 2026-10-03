package se.norrsken.tv.shield

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import se.norrsken.tv.desktop.AppState
import se.norrsken.tv.desktop.data.AppDirs
import se.norrsken.tv.desktop.ui.Images
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * En liten webbsida på Shield: spellistor och bakgrundsbilder skickas från
 * telefonens eller datorns webbläsare i stället för att skrivas med
 * fjärrkontrollen. Sidan finns bara medan inställningarna är öppna, bara på
 * det egna nätverket och bara med koden som står på tv:n.
 */
class RemoteInputServer(private val state: AppState) {
    val pin: String = Random.nextInt(1000, 10000).toString()

    var address by mutableStateOf<String?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    var lastMessage by mutableStateOf<String?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(3)
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var stopped = false
    @Volatile private var wrongPins = 0

    fun start() {
        Thread({ serve() }, "tv-fjarrinmatning").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopped = true
        runCatching { socket?.close() }
        workers.shutdownNow()
    }

    private fun serve() {
        try {
            val host = localAddress()
            if (host == null) {
                main.post { problem = "Shield verkar inte vara ansluten till något nätverk." }
                return
            }
            val server = PORTS.firstNotNullOfOrNull { port -> runCatching { ServerSocket(port, 16) }.getOrNull() }
            if (server == null) {
                main.post { problem = "Sidan gick inte att starta. Starta om appen och försök igen." }
                return
            }
            if (stopped) {
                server.close()
                return
            }
            socket = server
            main.post { address = "http://$host:${server.localPort}" }
            while (!server.isClosed && !stopped) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                try {
                    workers.execute { runCatching { client.use { handle(it) } } }
                } catch (e: Exception) {
                    runCatching { client.close() }
                }
            }
        } catch (e: Exception) {
            // Ett fel här får aldrig fälla appen.
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 20_000
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            if (headers.size > 64) return
        }
        val path = target.substringBefore('?')
        val query = parseForm(target.substringAfter('?', ""))
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        when {
            method == "GET" && (path == "/" || path == "/index.html") ->
                respond(output, 200, "text/html; charset=utf-8", PAGE.toByteArray(Charsets.UTF_8))
            method == "POST" && path == "/kalla" -> {
                if (length !in 1..MAX_FORM) return respondText(output, 413, "För mycket text.")
                val form = parseForm(String(readBody(input, length.toInt()), Charsets.UTF_8))
                val (code, text) = receiveSource(form)
                respondText(output, code, text)
            }
            method == "POST" && path == "/bild" -> {
                if (length !in 1..MAX_IMAGE) return respondText(output, 413, "Bilden är för stor. Högst 25 MB.")
                if (!pinOk(query["kod"])) return respondText(output, 403, WRONG_PIN)
                val bytes = readBody(input, length.toInt())
                val (code, text) = receiveImage(bytes, headers["content-type"])
                respondText(output, code, text)
            }
            else -> respondText(output, 404, "Finns inte.")
        }
    }

    private fun receiveSource(form: Map<String, String>): Pair<Int, String> {
        if (!pinOk(form["kod"])) return 403 to WRONG_PIN
        val name = form["namn"].orEmpty().trim()
        if (form["typ"] == "xtream") {
            val server = form["server"].orEmpty().trim()
            val username = form["anvandare"].orEmpty().trim()
            val password = form["losenord"].orEmpty()
            if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
                return 400 to "Fyll i server, användarnamn och lösenord."
            }
            main.post {
                state.addXtream(name, server, username, password)
                lastMessage = "Ett Xtream-konto togs emot. Importen syns här på tv:n."
            }
        } else {
            val url = form["lank"].orEmpty().trim()
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                return 400 to "Länken ska börja med http:// eller https://."
            }
            main.post {
                state.addM3u(name, url)
                lastMessage = "En spellista togs emot. Importen syns här på tv:n."
            }
        }
        return 200 to "Skickat! Importen syns nu på tv:n."
    }

    private fun receiveImage(bytes: ByteArray, type: String?): Pair<Int, String> {
        if (runCatching { Images.validate(bytes) }.isFailure) return 400 to "Det där gick inte att läsa som en bild."
        val extension = when {
            type?.contains("png", ignoreCase = true) == true -> "png"
            type?.contains("webp", ignoreCase = true) == true -> "webp"
            else -> "jpg"
        }
        val folder = File(AppDirs.context.cacheDir, "mottagna").apply { mkdirs() }
        val file = File(folder, "bild-${System.currentTimeMillis()}.$extension")
        file.writeBytes(bytes)
        main.post {
            state.addBackgrounds(listOf(file))
            lastMessage = "En bakgrundsbild togs emot."
        }
        return 200 to "Bilden är skickad till tv:n."
    }

    /** Fel kod gör varje nytt försök långsammare, så att koden inte går att gissa fram. */
    private fun pinOk(candidate: String?): Boolean {
        if (candidate?.trim() == pin) return true
        wrongPins++
        Thread.sleep(1_000L * wrongPins.coerceAtMost(10))
        return false
    }

    private fun respondText(output: OutputStream, code: Int, text: String) =
        respond(output, code, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))

    private fun respond(output: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
            if (bytes.size() > 8_192) return null
        }
        return bytes.toString("ISO-8859-1")
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw IOException("Avbrutet")
            read += n
        }
        return body
    }

    private fun parseForm(text: String): Map<String, String> =
        text.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            decode(key) to decode(value)
        }

    private fun decode(text: String): String = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .sortedByDescending { it.isSiteLocalAddress }
            .firstOrNull()
            ?.hostAddress
    }.getOrNull()

    private companion object {
        val PORTS = listOf(8765, 8766, 8767, 0)
        const val MAX_FORM = 64L * 1024
        const val MAX_IMAGE = 25L * 1024 * 1024
        const val WRONG_PIN = "Fel kod. Den står på tv:n under Inställningar, Lägg till från telefonen."

        // Ingen JavaScript-mall med dollartecken: sidan ligger i en Kotlin-sträng.
        val PAGE = """<!doctype html>
<html lang="sv"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Tv på Shield</title>
<style>
:root{color-scheme:dark}
body{margin:0;font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:#0b0d10;color:#e8eaed}
main{max-width:520px;margin:0 auto;padding:24px 18px 48px}
h1{font-size:24px;margin:0 0 4px}
p{color:#9aa0a6;margin:0 0 18px}
section{background:#16191e;border:1px solid #262a31;border-radius:14px;padding:18px;margin-bottom:18px}
h2{font-size:17px;margin:0 0 12px}
label{display:block;font-size:14px;color:#9aa0a6;margin:12px 0 4px}
input{box-sizing:border-box;width:100%;padding:12px;border-radius:10px;border:1px solid #333944;background:#0f1216;color:#e8eaed;font-size:16px}
.typ{display:flex;gap:8px}
.typ label{flex:1;margin:0;padding:10px;border:1px solid #333944;border-radius:10px;text-align:center;color:#e8eaed}
.typ input{display:none}
.typ label.vald{border-color:#ff8a3d;color:#ff8a3d}
button{width:100%;margin-top:16px;padding:14px;border:0;border-radius:12px;background:#ff8a3d;color:#111;font-weight:700;font-size:16px}
.svar{margin-top:12px;min-height:1.5em}
.ok{color:#7fd88f}
.fel{color:#ff7a70}
.xtream{display:none}
</style></head><body><main>
<h1>Tv på Shield</h1>
<p>Fyll i här i stället för med fjärrkontrollen. Koden står på tv:n.</p>
<section>
<label for="kod">Koden på tv:n</label>
<input id="kod" inputmode="numeric" autocomplete="off" maxlength="4">
</section>
<section>
<h2>Lägg till spellista</h2>
<form id="kalla">
<div class="typ">
<label id="val-m3u" class="vald"><input type="radio" name="typ" value="m3u" checked> M3U-länk</label>
<label id="val-xtream"><input type="radio" name="typ" value="xtream"> Xtream-konto</label>
</div>
<label for="namn">Namn (valfritt)</label>
<input id="namn" name="namn" autocomplete="off">
<div class="m3u">
<label for="lank">Länk till spellistan</label>
<input id="lank" name="lank" inputmode="url" autocomplete="off" placeholder="http://">
</div>
<div class="xtream">
<label for="server">Server</label>
<input id="server" name="server" inputmode="url" autocomplete="off" placeholder="http://">
<label for="anvandare">Användarnamn</label>
<input id="anvandare" name="anvandare" autocomplete="off">
<label for="losenord">Lösenord</label>
<input id="losenord" name="losenord" type="password" autocomplete="off">
</div>
<button type="submit">Skicka till tv:n</button>
<div class="svar" id="svar-kalla"></div>
</form>
</section>
<section>
<h2>Bakgrundsbild</h2>
<p>Högst tre bilder. De visas bakom appen, en i taget.</p>
<input id="bild" type="file" accept="image/*">
<button id="skicka-bild" type="button">Skicka bilden</button>
<div class="svar" id="svar-bild"></div>
</section>
</main>
<script>
function el(id) { return document.getElementById(id); }
function visa(ruta, ok, text) { ruta.className = 'svar ' + (ok ? 'ok' : 'fel'); ruta.textContent = text; }
var fel = 'Kom inte fram till tv:n. Är telefonen på samma wifi som Shield, och är inställningarna öppna på tv:n?';
function byt() {
  var xtream = document.querySelector('input[name=typ]:checked').value === 'xtream';
  document.querySelector('.xtream').style.display = xtream ? 'block' : 'none';
  document.querySelector('.m3u').style.display = xtream ? 'none' : 'block';
  el('val-xtream').className = xtream ? 'vald' : '';
  el('val-m3u').className = xtream ? '' : 'vald';
}
document.querySelectorAll('input[name=typ]').forEach(function (r) { r.addEventListener('change', byt); });
el('kalla').addEventListener('submit', function (e) {
  e.preventDefault();
  var data = new URLSearchParams(new FormData(e.target));
  data.set('kod', el('kod').value.trim());
  fetch('/kalla', { method: 'POST', body: data })
    .then(function (svar) { return svar.text().then(function (t) {
      visa(el('svar-kalla'), svar.ok, t);
      if (svar.ok) { el('lank').value = ''; el('losenord').value = ''; }
    }); })
    .catch(function () { visa(el('svar-kalla'), false, fel); });
});
el('skicka-bild').addEventListener('click', function () {
  var fil = el('bild').files[0];
  if (!fil) { visa(el('svar-bild'), false, 'Välj en bild först.'); return; }
  visa(el('svar-bild'), true, 'Skickar…');
  fetch('/bild?kod=' + encodeURIComponent(el('kod').value.trim()), {
    method: 'POST', headers: { 'Content-Type': fil.type || 'application/octet-stream' }, body: fil
  })
    .then(function (svar) { return svar.text().then(function (t) { visa(el('svar-bild'), svar.ok, t); }); })
    .catch(function () { visa(el('svar-bild'), false, fel); });
});
</script>
</body></html>
"""
    }
}
