package se.norrsken.tv.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MappingSource { TVG_ID, EXACT_NAME, NORMALIZED_NAME, ALIAS, MANUAL }

/**
 * Ett program i tablån. Tiderna är UTC-millisekunder — offset läggs på vid
 * visning, aldrig i databasen, annars måste allt skrivas om när användaren
 * ändrar offset.
 *
 * Indexen är inte valfria: med tiotusen kanaler och en vecka framåt ligger
 * den här tabellen på miljontals rader, och tablån gör intervallfrågor
 * på varje scroll.
 */
@Entity(
    tableName = "epg_programmes",
    indices = [
        Index("epgChannelId", "startUtc"),
        Index("endUtc"),
    ],
)
data class EpgProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** XMLTV:s kanal-id, inte vårt interna kanal-id. */
    val epgChannelId: String,
    val title: String,
    val description: String? = null,
    val startUtc: Long,
    val endUtc: Long,
    val category: String? = null,
    val iconUrl: String? = null,
    val episodeNumber: String? = null,
)

/**
 * Kopplingen kanal → EPG-kanal. Egen tabell i stället för en kolumn på
 * kanalen, eftersom vi måste veta *hur* matchningen gjordes: en manuell
 * koppling får aldrig skrivas över av nästa automatiska synk.
 */
@Entity(
    tableName = "epg_mappings",
    indices = [Index("channelId", unique = true), Index("epgChannelId")],
)
data class EpgMappingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val epgChannelId: String,
    val source: MappingSource,
    /** 0–100. Under tröskeln visas ingen tablå alls hellre än fel tablå. */
    val confidence: Int,
    /** Offset för just den här kanalen. Läggs ovanpå källans offset. */
    val offsetMinutes: Int = 0,
)
