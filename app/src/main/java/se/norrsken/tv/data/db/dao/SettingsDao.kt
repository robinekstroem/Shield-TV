package se.norrsken.tv.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import se.norrsken.tv.data.db.entity.SettingEntity

@Dao
interface SettingsDao {

    /** Alla varden pa en gang. Ett Flow per nyckel hade gett tiotals
     *  databaslyssnare for en enda installningsskarm. */
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT value FROM settings WHERE key = :key")
    fun observe(key: String): Flow<String?>

    @Query("SELECT * FROM settings")
    suspend fun observeAllOnce(): List<SettingEntity>

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun clear(key: String)
}
