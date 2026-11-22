package sefirah.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import sefirah.database.dao.DeviceDao
import sefirah.database.dao.NetworkDao
import sefirah.database.model.LocalDeviceEntity
import sefirah.database.model.NetworkEntity
import sefirah.database.model.PairedDeviceEntity

interface AppDatabase {
    fun devicesDao(): DeviceDao
    fun networkDao(): NetworkDao

    /**
     * Execute the whole database calls as an atomic operation
     */
    suspend fun <T> transaction(block: suspend () -> T): T

    companion object {
        private const val DATABASE_NAME = "sefirah.db"

        /** Persists the peer's TCP port so cached-IP reconnects do not fall back to a default. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE PairedDeviceEntity ADD COLUMN port INTEGER")
            }
        }

        fun createRoom(context: Context): AppDatabase = Room
            .databaseBuilder(context, AppRoomDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_2_3)
            .fallbackToDestructiveMigration(false)
            .build()
    }
}

@Database(
    entities = [
        PairedDeviceEntity::class,
        NetworkEntity::class,
        LocalDeviceEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
internal abstract class AppRoomDatabase : RoomDatabase(), AppDatabase {
    abstract override fun devicesDao(): DeviceDao
    abstract override fun networkDao(): NetworkDao

    override suspend fun <T> transaction(block: suspend () -> T): T = withTransaction(block)
}