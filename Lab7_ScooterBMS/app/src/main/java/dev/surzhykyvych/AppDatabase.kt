package dev.surzhykyvych

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "battery_records", indices = [Index(value = ["timestamp"])]
)
data class BatteryRecord(
    @PrimaryKey val timestamp: Long,
    val voltage: Float,
    val current: Float,
    val temperature: Float,
    val batteryHealth: Int = 100,
    val isSynced: Boolean = true
)

@Dao
interface BatteryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: BatteryRecord)

    @Query("SELECT * FROM battery_records WHERE timestamp >= :since ORDER BY timestamp ASC")
    fun getRecordsSinceFlow(since: Long): Flow<List<BatteryRecord>>

    @Query("SELECT * FROM battery_records WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getRecordsSinceList(since: Long): List<BatteryRecord>

    @Query("DELETE FROM battery_records WHERE timestamp < :olderThan")
    suspend fun deleteOldRecords(olderThan: Long)

    @Query("DELETE FROM battery_records")
    suspend fun clearAll()
}

@Database(entities = [BatteryRecord::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun batteryDao(): BatteryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "scooter_bms_database"
                ).fallbackToDestructiveMigration(false).build()
                INSTANCE = instance
                instance
            }
        }
    }
}