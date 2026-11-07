package se.norrsken.tv.desktop.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Fliken Fotboll: matcher, tabeller och startelvor från ESPN:s öppna data.
 * Ingen nyckel behövs. Allt läses tolerant: saknas ett fält blir det tomt i
 * stället för att något går sönder.
 */
enum class League(val slug: String, val title: String, val short: String, val accent: Long) {
    CHAMPIONS_LEAGUE("uefa.champions", "Champions League", "CL", 0xFF3D6BFF),
    PREMIER_LEAGUE("eng.1", "Premier League", "PL", 0xFFA24DFF),
    LA_LIGA("esp.1", "LaLiga", "LaLiga", 0xFFFF5A4E),
    ALLSVENSKAN("swe.1", "Allsvenskan", "Allsvenskan", 0xFF2E9BFF),
}

class FootballTeam(
    val id: String,
    val name: String,
    val short: String,
    val abbreviation: String,
    val logo: String?,
    /** Formen i de senaste matcherna som ESPN skriver den: W, D, L. */
    val form: String?,
    /** Lagets färger som 0xFFRRGGBB, när ESPN skickar dem. */
    val color: Long? = null,
    val alternateColor: Long? = null,
)

enum class MatchState { UPCOMING, LIVE, FINISHED, POSTPONED }

class MatchGoal(val minute: String, val home: Boolean, val player: String, val penalty: Boolean, val ownGoal: Boolean)

class FootballMatch(
    val id: String,
    val league: League,
    val kickoff: Long,
    val state: MatchState,
    /** "67'", "Halvtid", "Slut", "Uppskjuten". Tomt före avspark. */
    val status: String,
    val home: FootballTeam,
    val away: FootballTeam,
    val homeScore: Int?,
    val awayScore: Int?,
    val venue: String?,
    val goals: List<MatchGoal>,
)

class TableRow(
    val rank: Int,
    val team: FootballTeam,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val goalDiff: Int,
    val points: Int,
    /** Vad placeringen ger, som ESPN skriver det: "Champions League", "Relegation". */
    val zone: String?,
    /** Zonens färg som 0xFFRRGGBB. */
    val zoneColor: Long?,
)

class LineupPlayer(
    val number: String,
    val name: String,
    val shortName: String,
    /** ESPN:s position: "G", "CD-L", "RB", "DM", "AM", "LW", "F" … */
    val position: String,
    val place: Int,
    val starter: Boolean,
    val subbedIn: Boolean,
    val subbedOut: Boolean,
)

class Lineup(val teamId: String, val home: Boolean, val formation: String?, val players: List<LineupPlayer>) {
    val starters: List<LineupPlayer> get() = players.filter { it.starter }
    val bench: List<LineupPlayer> get() = players.filter { !it.starter }
}

enum class EventKind { GOAL, PENALTY, OWN_GOAL, YELLOW, RED, SUB }

class MatchEvent(
    val minute: String,
    val kind: EventKind,
    /** Hemmalaget, bortalaget, eller null när det inte framgår. */
    val home: Boolean?,
    val player: String,
    /** Passningsläggaren vid mål, spelaren som går ut vid byte. */
    val other: String?,
)

class MatchStat(val label: String, val home: String, val away: String, val homeShare: Float)

class MatchDetail(
    val lineups: List<Lineup>,
    val events: List<MatchEvent>,
    val stats: List<MatchStat>,
    val venue: String?,
    val referee: String?,
    val attendance: Int?,
) {
    val lineupsReleased: Boolean get() = lineups.any { it.starters.isNotEmpty() }
}

class FootballClient {

    /**
     * Ligans matcher mellan två dagar, båda medräknade. Sedan september 2026
     * svarar ESPN 400 på datumintervall ("20261030-20261120"), så perioden
     * hämtas som hela månader ("202610", "202611") och sållas här.
     */
    fun matches(league: League, from: LocalDate, to: LocalDate): List<FootballMatch> {
        val last = YearMonth.from(to)
        val months = generateSequence(YearMonth.from(from)) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(last) }
            .map { it.format(MONTH) }
            .toList()
        return scoreboard(league, months).filter { localDay(it.kickoff) in from..to }
    }

    /** Ligans matcher de här dagarna, en fråga per dag: ställningen medan matcher pågår. */
    fun matchesOn(league: League, days: List<LocalDate>): List<FootballMatch> =
        scoreboard(league, days.distinct().map { it.format(DateTimeFormatter.BASIC_ISO_DATE) })

    /** En fråga per datum, "20261106" eller "202611". Samma match i två svar räknas en gång. */
    private fun scoreboard(league: League, dates: List<String>): List<FootballMatch> {
        val found = LinkedHashMap<String, FootballMatch>()
        for (value in dates) {
            val root = get("$SITE/${league.slug}/scoreboard?dates=$value&limit=1000")
            val events = root.optJSONArray("events") ?: continue
            for (event in objects(events)) {
                val match = runCatching { parseMatch(league, event) }.getOrNull() ?: continue
                found[match.id] = match
            }
        }
        return found.values.toList()
    }

    private fun localDay(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    /** Ligatabellen, bäst först. ESPN har den under två sökvägar; den andra är reserv. */
    fun table(league: League): List<TableRow> {
        val first = runCatching { parseTable(get("$STANDINGS/${league.slug}/standings")) }
        first.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val second = runCatching { parseTable(get("$SITE/${league.slug}/standings")) }
        second.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        // Inget att visa: felet från den första vägen säger mest.
        first.exceptionOrNull()?.let { throw it }
        second.exceptionOrNull()?.let { throw it }
        return emptyList()
    }

    private fun parseTable(root: JSONObject): List<TableRow> {
        val entries = ArrayList<JSONObject>()
        val groups = root.optJSONArray("children")
        if (groups != null) {
            for (group in objects(groups)) {
                group.optJSONObject("standings")?.optJSONArray("entries")?.let { entries += objects(it) }
            }
        } else {
            root.optJSONObject("standings")?.optJSONArray("entries")?.let { entries += objects(it) }
        }
        return entries.mapIndexedNotNull { index, entry -> runCatching { parseRow(entry, index + 1) }.getOrNull() }
            .sortedBy { it.rank }
    }

    /** Startelvor, händelser och statistik för en match. */
    fun detail(league: League, matchId: String, homeTeamId: String): MatchDetail {
        val root = get("$SITE/${league.slug}/summary?event=$matchId")

        val lineups = ArrayList<Lineup>()
        root.optJSONArray("rosters")?.let { rosters ->
            for (roster in objects(rosters)) {
                val players = ArrayList<LineupPlayer>()
                roster.optJSONArray("roster")?.let { list ->
                    for (p in objects(list)) {
                        val athlete = p.optJSONObject("athlete") ?: continue
                        val name = athlete.text("displayName") ?: continue
                        players += LineupPlayer(
                            number = p.text("jersey") ?: "",
                            name = name,
                            shortName = athlete.text("shortName") ?: name,
                            position = p.optJSONObject("position")?.text("abbreviation") ?: "",
                            place = p.text("formationPlace")?.toIntOrNull() ?: 0,
                            starter = p.optBoolean("starter", false),
                            subbedIn = p.optBoolean("subbedIn", false),
                            subbedOut = p.optBoolean("subbedOut", false),
                        )
                    }
                }
                lineups += Lineup(
                    teamId = roster.optJSONObject("team")?.text("id") ?: "",
                    home = roster.text("homeAway") == "home",
                    formation = roster.text("formation"),
                    players = players,
                )
            }
        }

        val events = ArrayList<MatchEvent>()
        root.optJSONArray("keyEvents")?.let { list ->
            for (k in objects(list)) {
                val type = k.optJSONObject("type")
                val what = ((type?.text("type") ?: "") + " " + (type?.text("text") ?: "")).lowercase()
                val words = what.split(' ', '-', '_', '/')
                val kind = when {
                    // VAR-beslut och bortdömda mål är inga mål.
                    "var" in words || "disallowed" in what || "cancelled" in what || "canceled" in what -> continue
                    "own" in what && "goal" in what -> EventKind.OWN_GOAL
                    "penalty" in what && ("scored" in what || "goal" in what) -> EventKind.PENALTY
                    "penalty" in what -> continue
                    "goal" in what -> EventKind.GOAL
                    "red" in what -> EventKind.RED
                    "yellow" in what -> EventKind.YELLOW
                    "substitution" in what -> EventKind.SUB
                    else -> continue
                }
                val people = k.optJSONArray("participants")?.let { array ->
                    objects(array).mapNotNull { it.optJSONObject("athlete")?.text("displayName") }
                }.orEmpty()
                var player = people.getOrNull(0) ?: ""
                var other = people.getOrNull(1)
                if (kind == EventKind.SUB) {
                    // "Substitution, Fulham. Raúl Jiménez replaces Rodrigo Muniz."
                    val text = k.text("text") ?: ""
                    val sentence = text.substringAfter(". ", text)
                    if (" replaces " in sentence) {
                        player = sentence.substringBefore(" replaces ").trim()
                        other = sentence.substringAfter(" replaces ").trim().trimEnd('.')
                    }
                }
                if (player.isEmpty()) player = k.text("shortText") ?: continue
                val teamId = k.optJSONObject("team")?.text("id")
                events += MatchEvent(
                    minute = k.optJSONObject("clock")?.text("displayValue") ?: "",
                    kind = kind,
                    home = teamId?.let { it == homeTeamId },
                    player = player,
                    other = other,
                )
            }
        }

        val byTeam = HashMap<String, Map<String, String>>()
        root.optJSONObject("boxscore")?.optJSONArray("teams")?.let { teams ->
            for (t in objects(teams)) {
                val id = t.optJSONObject("team")?.text("id") ?: continue
                val values = HashMap<String, String>()
                t.optJSONArray("statistics")?.let { list ->
                    for (s in objects(list)) {
                        val name = s.text("name") ?: continue
                        values[name] = s.text("displayValue") ?: ""
                    }
                }
                byTeam[id] = values
            }
        }
        val homeStats = byTeam[homeTeamId]
        val awayStats = byTeam.entries.firstOrNull { it.key != homeTeamId }?.value
        val stats = if (homeStats == null || awayStats == null) {
            emptyList()
        } else {
            STAT_LABELS.mapNotNull { (name, label) ->
                val h = homeStats[name]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val a = awayStats[name]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val hv = h.toFloatOrNull() ?: 0f
                val av = a.toFloatOrNull() ?: 0f
                val share = if (hv + av > 0f) hv / (hv + av) else 0.5f
                if (name == "possessionPct") {
                    MatchStat(label, "${hv.roundToInt()} %", "${av.roundToInt()} %", share)
                } else {
                    MatchStat(label, h, a, share)
                }
            }
        }

        val info = root.optJSONObject("gameInfo")
        return MatchDetail(
            lineups = lineups,
            events = events,
            stats = stats,
            venue = info?.optJSONObject("venue")?.text("fullName"),
            referee = info?.optJSONArray("officials")?.optJSONObject(0)?.text("displayName"),
            attendance = info?.optInt("attendance", 0)?.takeIf { it > 0 },
        )
    }

    private fun parseMatch(league: League, e: JSONObject): FootballMatch? {
        val competition = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val competitors = competition.optJSONArray("competitors") ?: return null
        var homeJson: JSONObject? = null
        var awayJson: JSONObject? = null
        for (c in objects(competitors)) {
            if (c.text("homeAway") == "home") homeJson = c else awayJson = c
        }
        val homeSide = homeJson ?: return null
        val awaySide = awayJson ?: return null
        val home = parseTeam(homeSide.optJSONObject("team") ?: return null, homeSide.text("form"))
        val away = parseTeam(awaySide.optJSONObject("team") ?: return null, awaySide.text("form"))

        val status = e.optJSONObject("status") ?: competition.optJSONObject("status")
        val type = status?.optJSONObject("type")
        val name = (type?.text("name") ?: "").uppercase()
        val detail = (type?.text("shortDetail") ?: "").uppercase()
        val state = when {
            "POSTPON" in name || "CANCEL" in name || "ABANDON" in name || "SUSPEND" in name -> MatchState.POSTPONED
            type?.text("state") == "in" -> MatchState.LIVE
            type?.text("state") == "post" -> MatchState.FINISHED
            else -> MatchState.UPCOMING
        }
        val statusText = when (state) {
            MatchState.LIVE -> when {
                "HALFTIME" in name || detail == "HT" -> "Halvtid"
                "SHOOTOUT" in name -> "Straffar"
                else -> status?.text("displayClock") ?: type?.text("shortDetail") ?: ""
            }
            MatchState.FINISHED -> when {
                "PEN" in detail || "PENALT" in name -> "Slut, straffar"
                "AET" in detail || "EXTRA" in name -> "Slut, förlängning"
                else -> "Slut"
            }
            MatchState.POSTPONED -> if ("CANCEL" in name) "Inställd" else "Uppskjuten"
            MatchState.UPCOMING -> ""
        }
        val kickoff = parseDate(e.text("date")) ?: parseDate(competition.text("date")) ?: return null

        val goals = ArrayList<MatchGoal>()
        competition.optJSONArray("details")?.let { details ->
            for (d in objects(details)) {
                if (!d.optBoolean("scoringPlay", false)) continue
                val scorer = d.optJSONArray("athletesInvolved")?.optJSONObject(0)
                goals += MatchGoal(
                    minute = d.optJSONObject("clock")?.text("displayValue") ?: "",
                    home = d.optJSONObject("team")?.text("id") == home.id,
                    player = scorer?.text("shortName") ?: scorer?.text("displayName") ?: "",
                    penalty = d.optBoolean("penaltyKick", false),
                    ownGoal = d.optBoolean("ownGoal", false),
                )
            }
        }
        val played = state == MatchState.LIVE || state == MatchState.FINISHED
        return FootballMatch(
            id = e.text("id") ?: competition.text("id") ?: return null,
            league = league,
            kickoff = kickoff,
            state = state,
            status = statusText,
            home = home,
            away = away,
            homeScore = if (played) score(homeSide) else null,
            awayScore = if (played) score(awaySide) else null,
            venue = competition.optJSONObject("venue")?.text("fullName"),
            goals = goals,
        )
    }

    private fun parseRow(e: JSONObject, position: Int): TableRow? {
        val team = parseTeam(e.optJSONObject("team") ?: return null, null)
        val values = HashMap<String, Double>()
        e.optJSONArray("stats")?.let { list ->
            for (s in objects(list)) {
                val name = s.text("name") ?: continue
                val value = s.optDouble("value", Double.NaN)
                if (!value.isNaN()) values[name] = value
            }
        }
        fun stat(name: String): Int = values[name]?.toInt() ?: 0
        val note = e.optJSONObject("note")
        return TableRow(
            rank = stat("rank").takeIf { it > 0 } ?: position,
            team = team,
            played = stat("gamesPlayed"),
            won = stat("wins"),
            drawn = stat("ties"),
            lost = stat("losses"),
            goalDiff = stat("pointDifferential"),
            points = stat("points"),
            zone = note?.text("description"),
            zoneColor = note?.text("color")?.let { parseColor(it) },
        )
    }

    private fun parseTeam(t: JSONObject, form: String?): FootballTeam {
        val name = t.text("displayName") ?: t.text("name") ?: "?"
        return FootballTeam(
            id = t.text("id") ?: name,
            name = name,
            short = t.text("shortDisplayName") ?: name,
            abbreviation = t.text("abbreviation") ?: name.take(3).uppercase(),
            logo = t.text("logo") ?: t.optJSONArray("logos")?.optJSONObject(0)?.text("href"),
            form = form,
            color = t.text("color")?.let { parseColor(it) },
            alternateColor = t.text("alternateColor")?.let { parseColor(it) },
        )
    }

    private fun score(side: JSONObject): Int? = when (val raw = side.opt("score")) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull()
        is JSONObject -> raw.text("displayValue")?.toIntOrNull() ?: raw.optDouble("value", Double.NaN).takeIf { !it.isNaN() }?.toInt()
        else -> null
    }

    private fun parseDate(value: String?): Long? =
        value?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    private fun parseColor(value: String): Long? {
        val hex = value.trim().removePrefix("#")
        if (hex.length != 6) return null
        return hex.toLongOrNull(16)?.let { 0xFF000000L or it }
    }

    /**
     * Sedan augusti 2026 spärrar ESPN ofta site.api.espn.com ("You don't have
     * permission"), särskilt för program som utger sig för att vara en
     * webbläsare. site.web.api.espn.com svarar på samma frågor. Adressen som
     * svarade senast provas först, den andra är reserv.
     */
    private fun get(path: String): JSONObject {
        val first = preferredHost
        var failure: IOException? = null
        for (host in listOf(first) + HOSTS.filter { it != first }) {
            try {
                val result = fetch("https://$host$path")
                preferredHost = host
                return result
            } catch (error: IOException) {
                if (failure == null) failure = error
            }
        }
        throw failure ?: IOException("ESPN svarade inte")
    }

    private fun fetch(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 15_000
        // Inte "Mozilla": ESPN spärrar program som säger sig vara en webbläsare utan att vara det.
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("ESPN svarade $code")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            // En spärrsida i stället för data räknas som ett fel, så att reservadressen provas.
            if (!text.trimStart().startsWith("{")) throw IOException("ESPN svarade utan data")
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    /** Adressen som svarade senast. Läses och skrivs från flera hämtningar samtidigt. */
    @Volatile
    private var preferredHost: String = HOSTS[0]

    private companion object {
        const val SITE = "/apis/site/v2/sports/soccer"
        const val STANDINGS = "/apis/v2/sports/soccer"
        val HOSTS = listOf("site.web.api.espn.com", "site.api.espn.com")
        const val USER_AGENT = "Tv/1.0 (fotboll)"
        val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")

        val STAT_LABELS = listOf(
            "possessionPct" to "Bollinnehav",
            "totalShots" to "Skott",
            "shotsOnTarget" to "Skott på mål",
            "wonCorners" to "Hörnor",
            "foulsCommitted" to "Regelbrott",
            "offsides" to "Offside",
            "yellowCards" to "Gula kort",
            "redCards" to "Röda kort",
            "saves" to "Räddningar",
        )
    }
}

/** Objekten i en JSON-lista; annat hoppas över. */
private fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).mapNotNull { array.optJSONObject(it) }

/** Texten under nyckeln, eller null om den saknas, är tom eller JSON-null (Android skriver "null"). */
private fun JSONObject.text(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
}

// --- Visas på: matchen i tablån -------------------------------------------------------

/**
 * Kanalerna som enligt tablån visar matchen: ett program som pågår vid
 * avspark och nämner båda lagen, eller ett av dem och ligan. Bäst först.
 *
 * Kan gå igenom tusentals kanaler, så det mesta görs en gång: lagens namn
 * per match, programmen per kanal och avspark, och programmens text per
 * program (kanaler i flera kvaliteter delar samma tablå).
 */
fun findBroadcasts(matches: List<FootballMatch>, channels: List<LiveChannel>, guide: GuideIndex): Map<String, List<LiveChannel>> {
    if (matches.isEmpty()) return emptyMap()
    val withGuide = channels.filter { guide.hasSchedule(it) }
    if (withGuide.isEmpty()) return emptyMap()
    // Ett ord som flera lag har i namnet säger inte vilket lag det är: "madrid", "manchester".
    val teams = matches.flatMap { listOf(it.home, it.away) }.distinctBy { it.id }
    val shared = teams
        .flatMap { team -> nameWords(team).map { it to team.id } }
        .distinct()
        .groupingBy { it.first }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    val names = HashMap<String, Pair<List<String>, List<String>>>()
    for (match in matches) {
        // Med mellanslag runt om: "inter" ska inte träffa "winterstudion".
        val home = teamKeys(match.home, shared).map { " $it " }
        val away = teamKeys(match.away, shared).map { " $it " }
        if (home.isNotEmpty() && away.isNotEmpty()) names[match.id] = home to away
    }
    val byKickoff = matches.filter { it.id in names }.groupBy { it.kickoff }
    val leagues = League.entries.associateWith { " " + normalizeName(it.title) + " " }
    val texts = IdentityHashMap<Programme, String>()
    val found = HashMap<String, MutableList<Pair<Int, LiveChannel>>>()
    for (channel in withGuide) {
        val schedule = guide.schedule(channel)
        for ((kickoff, group) in byKickoff) {
            val around = programmesAround(schedule, kickoff)
            if (around.isEmpty()) continue
            val programmeTexts = around.map { p ->
                texts.getOrPut(p) {
                    " " + normalizeName(p.title + " " + (p.subtitle ?: "") + " " + (p.description ?: "").take(400)) + " "
                }
            }
            for (match in group) {
                val (home, away) = names[match.id] ?: continue
                val league = leagues.getValue(match.league)
                var best = 0
                for (text in programmeTexts) {
                    val h = home.any { it in text }
                    val a = away.any { it in text }
                    val score = when {
                        h && a -> 3
                        (h || a) && league in text -> 2
                        else -> 0
                    }
                    if (score > best) best = score
                }
                if (best >= 2) found.getOrPut(match.id) { ArrayList() }.add(best to channel)
            }
        }
    }
    return found.mapValues { (_, list) -> list.sortedByDescending { it.first }.map { it.second }.distinctBy { it.name }.take(4) }
}

/** Programmen som pågår vid avspark: börjar högst två timmar före och slutar en bit in i matchen. */
private fun programmesAround(schedule: List<Programme>, kickoff: Long): List<Programme> {
    // Programmen sorteras efter start: börja två timmar före avspark.
    var low = 0
    var high = schedule.size
    val from = kickoff - 2 * HOUR_MS
    while (low < high) {
        val mid = (low + high) ushr 1
        if (schedule[mid].start < from) low = mid + 1 else high = mid
    }
    if (low >= schedule.size || schedule[low].start > kickoff + 20 * MINUTE_MS) return emptyList()
    val result = ArrayList<Programme>(3)
    var index = low
    while (index < schedule.size) {
        val p = schedule[index]
        if (p.start > kickoff + 20 * MINUTE_MS) break
        if (p.stop > kickoff + 10 * MINUTE_MS) result.add(p)
        index++
    }
    return result
}

/** Orden i lagets namn, så som de står i tablån. */
private fun nameWords(team: FootballTeam): Set<String> =
    listOf(team.name, team.short).flatMap { normalizeName(it).split(' ') }.filter { it.length >= 5 }.toSet()

/**
 * Namnen laget kan stå under i tablån: "manchester united", "man utd",
 * "arsenal". Ett ord som flera av lagen har ([shared]) räknas bara i hela namnet.
 */
private fun teamKeys(team: FootballTeam, shared: Set<String>): List<String> {
    val keys = LinkedHashSet<String>()
    for (name in listOf(team.name, team.short)) {
        val n = normalizeName(name)
        if (n.length >= 3) keys += n
        if ("united" in n) keys += n.replace("united", "utd")
        // Ett eget ord räcker när det säger vem det är: "Liverpool", "Hammarby", "Bayern".
        for (word in n.split(' ')) {
            if (word.length >= 5 && word !in COMMON_WORDS && word !in shared) {
                keys += word
                if (word.length >= 7 && word.endsWith("s")) keys += word.dropLast(1)
            }
        }
        // Namnen svenska tablåer brukar använda: "Athletic Bilbao", "Inter", "Köpenhamn".
        ALIASES[n]?.let { keys += it }
    }
    val abbreviation = normalizeName(team.abbreviation)
    if (abbreviation.length >= 3 && abbreviation !in COMMON_WORDS && abbreviation !in shared) keys += abbreviation
    return keys.toList()
}

/** ESPN:s namn → andra namn på laget i tablåer. Allt i små bokstäver utan accenter. */
private val ALIASES: Map<String, List<String>> = mapOf(
    "athletic club" to listOf("athletic bilbao", "bilbao"),
    "atletico madrid" to listOf("atletico de madrid", "atletico"),
    "internazionale" to listOf("inter", "inter milan", "inter milano"),
    "fc copenhagen" to listOf("fc kobenhavn", "kopenhamn", "fc kopenhamn"),
    "sporting cp" to listOf("sporting lissabon", "sporting lisbon"),
    "bayern munich" to listOf("bayern munchen", "bayern"),
    "paris saint germain" to listOf("psg", "paris sg", "paris saint germain"),
    "tottenham hotspur" to listOf("tottenham", "spurs"),
    "wolverhampton wanderers" to listOf("wolves", "wolverhampton"),
    "brighton hove albion" to listOf("brighton"),
    "nottingham forest" to listOf("nottingham", "nottm forest"),
    "newcastle united" to listOf("newcastle"),
    "west ham united" to listOf("west ham"),
    "manchester united" to listOf("man united", "man utd", "manchester utd"),
    "manchester city" to listOf("man city"),
    "real betis" to listOf("betis"),
    "real sociedad" to listOf("sociedad"),
    "celta vigo" to listOf("celta"),
    "rayo vallecano" to listOf("rayo"),
)

/**
 * Gemener utan accenter, bara bokstäver och siffror med ett mellanslag
 * emellan: "Atlético Madrid" → "atletico madrid", "Malmö FF" → "malmo ff".
 * Går igenom långa programtexter, så tecknen viks ett och ett i stället
 * för med Normalizer och reguljära uttryck på hela texten.
 */
private fun normalizeName(value: String): String {
    val out = StringBuilder(value.length)
    var gap = false
    for (raw in value) {
        val c = raw.lowercaseChar()
        val piece = when {
            c in 'a'..'z' || c in '0'..'9' -> null
            c.code < 128 -> ""
            // Ett fristående accenttecken hör till bokstaven före.
            c.category == CharCategory.NON_SPACING_MARK -> continue
            else -> folded(c)
        }
        if (piece != null && piece.isEmpty()) {
            gap = true
            continue
        }
        if (gap && out.isNotEmpty()) out.append(' ')
        gap = false
        if (piece == null) out.append(c) else out.append(piece)
    }
    return out.toString()
}

private val foldedChars = ConcurrentHashMap<Char, String>()

/** Ett tecken utanför a–z: "é" → "e", "ø" → "o", "ß" → "ss". Annat blir mellanrum (""). */
private fun folded(c: Char): String = foldedChars.getOrPut(c) {
    SPECIAL_LETTERS[c] ?: Normalizer.normalize(c.toString(), Normalizer.Form.NFD).filter { it in 'a'..'z' || it in '0'..'9' }
}

private val SPECIAL_LETTERS = mapOf(
    'ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss", 'ł' to "l", 'đ' to "d", 'ð' to "d", 'þ' to "th", 'ı' to "i",
)

private val COMMON_WORDS = setOf(
    "united", "city", "town", "real", "club", "sporting", "athletic", "olympique", "borussia",
    "rovers", "wanderers", "albion", "county", "saint", "football", "calcio", "inter", "internacional",
    "deportivo", "union", "dynamo", "racing", "hotspur", "national", "fotboll", "fotbollsforening",
)

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
