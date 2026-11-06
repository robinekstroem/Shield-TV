package se.norrsken.tv.ipad

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import se.norrsken.tv.desktop.ui.AppTheme

internal enum class IpadScreen { LIVE, SETTINGS }

/** Sparas i iPadens egna inställningar för appen. */
private object Prefs {
    private val defaults get() = NSUserDefaults.standardUserDefaults

    fun get(key: String): String? = defaults.stringForKey(key)

    fun set(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }
}

/** Allt iPad-appen vet och gör. Ändras bara från huvudtråden. */
internal class IpadState {
    // Ett fel i bakgrunden ska aldrig stänga appen: det visas som ett meddelande.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, error ->
            message = "Något gick fel: ${error.message ?: error::class.simpleName}"
        },
    )

    var screen by mutableStateOf(IpadScreen.LIVE)
    var theme by mutableStateOf(AppTheme.entries.firstOrNull { it.name == Prefs.get("tema") } ?: AppTheme.entries.first())
        private set
    var sources by mutableStateOf(loadSources())
        private set
    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var group by mutableStateOf<String?>(null)
    var query by mutableStateOf("")

    init {
        refresh()
    }

    fun selectTheme(choice: AppTheme) {
        theme = choice
        Prefs.set("tema", choice.name)
    }

    fun refresh() {
        if (sources.isEmpty()) {
            channels = emptyList()
            return
        }
        scope.launch {
            loading = true
            message = null
            val all = ArrayList<Channel>()
            for (source in sources) {
                runCatching { withContext(Dispatchers.Default) { load(source) } }
                    .onSuccess { all += it }
                    .onFailure { message = "${source.name}: ${it.message ?: "gick inte att hämta"}" }
            }
            channels = all
            if (group != null && all.none { it.group == group }) group = null
            loading = false
        }
    }

    private suspend fun load(source: Source): List<Channel> =
        if (source.xtream) Xtream.live(source) else M3u.parse(fetchText(source.url.trim()), source.id)

    fun addM3u(name: String, url: String): String? {
        val link = url.trim()
        if (!link.startsWith("http://", ignoreCase = true) && !link.startsWith("https://", ignoreCase = true)) {
            return "Länken ska börja med http:// eller https://."
        }
        add(Source(NSUUID().UUIDString, name.trim().ifEmpty { link.substringAfter("://").substringBefore('/') }, xtream = false, url = link))
        return null
    }

    fun addXtream(name: String, server: String, username: String, password: String): String? {
        if (server.isBlank() || username.isBlank() || password.isBlank()) return "Fyll i server, användarnamn och lösenord."
        add(
            Source(
                NSUUID().UUIDString,
                name.trim().ifEmpty { server.trim().substringAfter("://").substringBefore('/') },
                xtream = true,
                server = server.trim(),
                username = username.trim(),
                password = password,
            ),
        )
        return null
    }

    private fun add(source: Source) {
        sources = sources + source
        saveSources()
        refresh()
    }

    fun remove(source: Source) {
        sources = sources.filterNot { it.id == source.id }
        saveSources()
        refresh()
    }

    fun play(channel: Channel) = playStream(channel.url)

    private fun saveSources() {
        val json = sources.joinToString(",", "[", "]") { s ->
            listOf(
                "id" to s.id, "name" to s.name, "xtream" to s.xtream.toString(), "url" to s.url,
                "server" to s.server, "username" to s.username, "password" to s.password,
            ).joinToString(",", "{", "}") { (key, value) -> Json.quote(key) + ":" + Json.quote(value) }
        }
        Prefs.set("kallor", json)
    }

    private fun loadSources(): List<Source> {
        val text = Prefs.get("kallor") ?: return emptyList()
        val list = runCatching { Json.parse(text) as? List<*> }.getOrNull() ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            Source(
                id = map["id"].asText() ?: return@mapNotNull null,
                name = map["name"].asText().orEmpty(),
                xtream = map["xtream"].asText() == "true",
                url = map["url"].asText().orEmpty(),
                server = map["server"].asText().orEmpty(),
                username = map["username"].asText().orEmpty(),
                password = map["password"].asText().orEmpty(),
            )
        }
    }
}
