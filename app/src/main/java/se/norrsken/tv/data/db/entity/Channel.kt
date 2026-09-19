package se.norrsken.tv.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * En TV-kanal. Fälten speglar M3U-attributen rakt av så inget tappas vid
 * import — det vi inte använder än kan behövas för EPG-matchning senare.
 */
@Entity(
    tableName = "channels",
    indices = [
        Index("playlistId"),
        Index("categoryId"),
        Index("tvgId"),
        Index("name"),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val categoryId: Long?,
    val name: String,
    /** Normaliserat namn (gemener, utan skräptecken) — används vid EPG-matchning. */
    val normalizedName: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val groupTitle: String? = null,
    val channelNumber: Int? = null,
    /** Antal dagar bakåt leverantören uppger att catch-up finns. */
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
    val sortOrder: Int = 0,
    val hidden: Boolean = false,
)
