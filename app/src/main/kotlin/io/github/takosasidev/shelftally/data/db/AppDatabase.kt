package io.github.takosasidev.shelftally.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

/**
 * FR-28: 版を上げるときは、必ず [MIGRATIONS] に Migration を足し、`app/schemas/` にできた JSON を commit する。
 * fallbackToDestructiveMigration() は使わない(更新で記録が黙って消えるため)。
 * 版 1 から始めているので、今は Migration が 1 つも無い。
 */
@Database(
    entities = [
        ItemEntity::class,
        CategoryEntity::class,
        ConsumptionLogEntity::class,
        ShoppingListEntryEntity::class,
        AppSettingsEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun categoryDao(): CategoryDao
    abstract fun consumptionLogDao(): ConsumptionLogDao
    abstract fun shoppingListDao(): ShoppingListDao
    abstract fun appSettingsDao(): AppSettingsDao

    companion object {
        const val VERSION = 1
        const val NAME = "shelftally.db"

        val MIGRATIONS: Array<Migration> = arrayOf()

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
                .also { instance = it }
        }
    }
}
