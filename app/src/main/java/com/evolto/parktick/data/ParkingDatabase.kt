package com.evolto.parktick.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.evolto.parktick.domain.ClockSnapshot
import com.evolto.parktick.domain.DurationBasis
import com.evolto.parktick.domain.ParkingDuration
import com.evolto.parktick.domain.ParkingRecord
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "active_parking", indices = [Index(value = ["plate"], unique = true), Index(value = ["photoName"], unique = true)])
data class ActiveParking(
    @PrimaryKey val id: String,
    val plate: String,
    val startWallMs: Long,
    val startElapsedMs: Long,
    val startBootCount: Int?,
    val photoName: String,
) {
    fun record() = ParkingRecord(id, plate, ClockSnapshot(startWallMs, startElapsedMs, startBootCount), photoName = photoName)
}

@Entity(tableName = "parking_history", indices = [Index(value = ["plate"]), Index(value = ["endWallMs"])])
data class ParkingHistory(
    @PrimaryKey val id: String,
    val plate: String,
    val startWallMs: Long,
    val startElapsedMs: Long,
    val startBootCount: Int?,
    val endWallMs: Long,
    val durationMs: Long?,
    val durationBasis: String,
    val clockChanged: Boolean,
) {
    fun record() = ParkingRecord(
        id, plate, ClockSnapshot(startWallMs, startElapsedMs, startBootCount), endWallMs,
        ParkingDuration(durationMs, DurationBasis.valueOf(durationBasis), clockChanged),
    )
}

@Entity(
    tableName = "pending_photo_deletions",
    foreignKeys = [ForeignKey(entity = ParkingHistory::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["photoName"], unique = true)],
)
data class PendingPhotoDeletion(@PrimaryKey val recordId: String, val photoName: String)

@Dao
interface ParkingDao {
    @Query("SELECT * FROM active_parking WHERE plate LIKE :pattern ESCAPE '\\' ORDER BY startWallMs ASC, id ASC")
    fun observeActive(pattern: String): Flow<List<ActiveParking>>

    @Query("SELECT * FROM parking_history WHERE plate LIKE :pattern ESCAPE '\\' ORDER BY endWallMs DESC, id ASC")
    fun observeHistory(pattern: String): Flow<List<ParkingHistory>>

    @Query("SELECT * FROM active_parking WHERE id = :id")
    fun observeActiveRecord(id: String): Flow<ActiveParking?>

    @Query("SELECT * FROM parking_history WHERE id = :id")
    fun observeHistoricalRecord(id: String): Flow<ParkingHistory?>

    @Query("SELECT * FROM active_parking WHERE plate = :plate LIMIT 1")
    suspend fun activeByPlate(plate: String): ActiveParking?

    @Query("SELECT * FROM active_parking WHERE id = :id")
    suspend fun activeById(id: String): ActiveParking?

    @Query("SELECT * FROM parking_history WHERE id = :id")
    suspend fun historyById(id: String): ParkingHistory?

    @Query("SELECT photoName FROM active_parking")
    suspend fun activePhotos(): List<String>

    @Insert
    suspend fun insertActive(record: ActiveParking)

    @Insert
    suspend fun insertHistory(record: ParkingHistory)

    @Insert
    suspend fun enqueueDeletion(record: PendingPhotoDeletion)

    @Query("DELETE FROM active_parking WHERE id = :id")
    suspend fun deleteActive(id: String): Int

    @Query("SELECT * FROM pending_photo_deletions ORDER BY recordId")
    suspend fun pendingDeletions(): List<PendingPhotoDeletion>

    @Query("SELECT * FROM pending_photo_deletions WHERE recordId = :id")
    suspend fun pendingDeletion(id: String): PendingPhotoDeletion?

    @Query("DELETE FROM pending_photo_deletions WHERE recordId = :id")
    suspend fun clearDeletion(id: String)
}

@Database(entities = [ActiveParking::class, ParkingHistory::class, PendingPhotoDeletion::class], version = 1, exportSchema = true)
abstract class ParkingDatabase : RoomDatabase() {
    abstract fun parkingDao(): ParkingDao

    companion object {
        fun open(context: Context): ParkingDatabase = Room.databaseBuilder(
            context.applicationContext, ParkingDatabase::class.java, "parking.db",
        ).build()
    }
}
