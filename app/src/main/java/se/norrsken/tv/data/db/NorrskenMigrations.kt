package se.norrsken.tv.data.db

import androidx.room.migration.Migration

/**
 * Databasmigrationer.
 *
 * Tom just nu, och det ar meningen: version 1 ar utgangslaget. Fran och med
 * nu galler foljande vid varje schemaandring:
 *
 * 1. Hoj `version` i [NorrskenDatabase].
 * 2. Lagg till en Migration harunder som gor motsvarande ALTER/CREATE.
 * 3. Lagg den i [ALL].
 *
 * Hoppar man over steg 2 kraschar appen vid start i stallet for att tyst
 * radera anvandarens data. Det ar avsiktligt — det ar det billigare felet.
 *
 * Schemat exporteras till app/schemas, sa nasta migration kan skrivas mot
 * det faktiska JSON-schemat och testas med MigrationTestHelper.
 *
 * Exempel for nasta gang:
 *
 *     private val MIGRATION_1_2 = object : Migration(1, 2) {
 *         override fun migrate(db: SupportSQLiteDatabase) {
 *             db.execSQL("ALTER TABLE channels ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
 *         }
 *     }
 */
object NorrskenMigrations {
    val ALL: Array<Migration> = emptyArray()
}
