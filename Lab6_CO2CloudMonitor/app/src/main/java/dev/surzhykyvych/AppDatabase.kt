package dev.surzhykyvych

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "co2_records")
data class Co2Record(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val co2Level: Float,
    val type: String = "CO2_PPM",

    val deviceId: String = "",
    val deviceModel: String = "",
    val osVersion: String = "",

    val synced: Boolean = false,
    val isDuplicate: Boolean = false
)

@Dao
interface Co2Dao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: Co2Record)

    @Update
    suspend fun update(record: Co2Record)

    @Query("SELECT EXISTS(SELECT 1 FROM co2_records WHERE timestamp = :ts AND deviceId = :devId)")
    suspend fun isRecordExists(ts: Long, devId: String): Boolean

    @Query("SELECT * FROM co2_records WHERE timestamp >= :since ORDER BY timestamp ASC")
    fun getRecordsSinceFlow(since: Long): Flow<List<Co2Record>>

    @Query("SELECT * FROM co2_records WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getRecordsSinceList(since: Long): List<Co2Record>

    @Query("SELECT * FROM co2_records WHERE synced = 0")
    suspend fun getUnsyncedRecords(): List<Co2Record>

    @Query("DELETE FROM co2_records WHERE timestamp < :olderThan")
    suspend fun deleteOldRecords(olderThan: Long)

    @Query("DELETE FROM co2_records")
    suspend fun clearAll()

    @Query("SELECT MIN(co2Level) FROM co2_records")
    suspend fun getMin(): Float?

    @Query("SELECT MAX(co2Level) FROM co2_records")
    suspend fun getMax(): Float?

    @Query("SELECT AVG(co2Level) FROM co2_records")
    suspend fun getAvg(): Float?

    @Query("SELECT COUNT(*) FROM co2_records")
    suspend fun getCount(): Int
}

@Database(entities = [Co2Record::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun co2Dao(): Co2Dao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "co2_database"
                ).fallbackToDestructiveMigration(true).build()

                INSTANCE = instance
                instance
            }
        }
    }
}