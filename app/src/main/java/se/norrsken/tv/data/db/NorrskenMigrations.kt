package se.norrsken.tv.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Databasmigrationer.
 *
 * Vid varje schemaandring galler:
 *
 * 1. Hoj `version` i [NorrskenDatabase].
 * 2. Skriv en Migration harunder.
 * 3. Lagg den i [ALL].
 *
 * Hoppar man over steg 2 kraschar appen vid start i stallet for att tyst
 * radera anvandarens data. Det ar avsiktligt — det ar det billigare felet.
 */
object NorrskenMigrations {

    /**
     * Lagger till popularitet pa filmer och serier.
     *
     * ALTER TABLE ADD COLUMN behaller alla befintliga rader, sa favoriter,
     * historik, spellistor och EPG-kopplingar overlever. Kolumnen ar
     * nullbar och behover darfor inget defaultvarde.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE movies ADD COLUMN popularity REAL")
            db.execSQL("ALTER TABLE series ADD COLUMN popularity REAL")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
