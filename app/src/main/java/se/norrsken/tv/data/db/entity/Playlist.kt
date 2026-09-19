package se.norrsken.tv.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PlaylistType { M3U, XTREAM }

enum class ContentKind { LIVE, MOVIE, SERIES }

/**
 * En källa användaren lagt till. Flera kan vara aktiva samtidigt och slås
 * ihop i gränssnittet — [id] följer med varje kanal och film så vi alltid
 * vet var något kom ifrån.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: PlaylistType,
    /** M3U-URL, eller Xtream-serverns bas-URL. */
    val url: String,
    val username: String? = null,
    /**
     * Xtream-lösenord. Lagras krypterat i Fas 2 — fältet är med här för att
     * schemat inte ska behöva migreras då.
     */
    val secret: String? = null,
    /** Egen EPG-källa för just den här spellistan. */
    val epgUrl: String? = null,
    val enabled: Boolean = true,
    /** Lägre tal visas först när flera källor slås ihop. */
    val priority: Int = 0,
    /** Minuter. Gäller alla kanaler i källan om inget satts per kanal. */
    val epgOffsetMinutes: Int = 0,
    /**
     * Filändelse för Xtream-live: "m3u8" eller "ts". Panelerna stödjer oftast
     * båda. HLS återhämtar sig bättre från nätavbrott, men enstaka paneler
     * serverar bara ts — därför ett fält och inte en konstant.
     */
    val liveExtension: String = "m3u8",
    val lastSyncUtc: Long? = null,
)

@Entity(
    tableName = "categories",
    indices = [Index("playlistId", "kind")],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val kind: ContentKind,
    /** Leverantörens eget id, för att kunna matcha om vi synkar igen. */
    val externalId: String?,
    val name: String,
    val sortOrder: Int = 0,
    val hidden: Boolean = false,
)
