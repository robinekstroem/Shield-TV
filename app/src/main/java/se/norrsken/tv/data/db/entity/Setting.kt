package se.norrsken.tv.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Nyckel/värde för appens egna inställningar.
 *
 * Ligger i Room i stället för DataStore eller SharedPreferences för att
 * ändringar då kommer ut som Flow gratis, utan ytterligare ett beroende.
 */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
