package com.evolto.parktick.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.evolto.parktick.domain.CapturedPhoto
import com.evolto.parktick.domain.ClockSnapshot
import com.evolto.parktick.domain.FinishResult
import com.evolto.parktick.domain.ParkingRecord
import com.evolto.parktick.domain.RegisterResult
import com.evolto.parktick.media.PhotoStore
import com.evolto.parktick.time.DurationMath
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ParkingRepository(private val database: ParkingDatabase, private val photos: PhotoStore) {
    private val dao = database.parkingDao()

    fun observeActive(query: String = ""): Flow<List<ParkingRecord>> =
        dao.observeActive(PlateText.searchPattern(query)).map { rows -> rows.map(ActiveParking::record) }

    fun observeHistory(query: String = ""): Flow<List<ParkingRecord>> =
        dao.observeHistory(PlateText.searchPattern(query)).map { rows -> rows.map(ParkingHistory::record) }

    fun observeRecord(id: String): Flow<ParkingRecord?> = combine(
        dao.observeActiveRecord(id), dao.observeHistoricalRecord(id),
    ) { active, history -> active?.record() ?: history?.record() }

    suspend fun register(photo: CapturedPhoto, plate: String): RegisterResult = withContext(Dispatchers.IO) {
        val normalized = PlateText.normalize(plate)
        if (normalized.isEmpty()) return@withContext RegisterResult.Failure("请输入车牌号码")
        photos.operations.withLock {
            currentCoroutineContext().ensureActive()
            // A final confirmation is a bounded commit operation. Complete the adoption before
            // allowing a concurrent UI cancellation to dispose of this photograph.
            withContext(NonCancellable) {
                try {
                    val existingId = dao.activeById(photo.id)
                    if (existingId != null) return@withContext RegisterResult.Created(existingId.record())
                    val existingPlate = dao.activeByPlate(normalized)
                    if (existingPlate != null) return@withContext duplicate(photo, existingPlate)
                    if (!photos.isDraft(photo.photoName) || !photos.file(photo.photoName).isFile || photos.file(photo.photoName).length() == 0L) {
                        return@withContext RegisterResult.Failure("照片已失效，请重新拍照")
                    }
                    val row = ActiveParking(photo.id, normalized, photo.capturedAt.wallTimeMs, photo.capturedAt.elapsedRealtimeMs, photo.capturedAt.bootCount, photo.photoName)
                    try {
                        database.withTransaction { dao.insertActive(row) }
                    } catch (conflict: SQLiteConstraintException) {
                        val original = dao.activeByPlate(normalized) ?: throw conflict
                        return@withContext duplicate(photo, original)
                    }
                    photos.adopt(photo.photoName)
                    RegisterResult.Created(row.record())
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    RegisterResult.Failure("登记保存失败，请重试；当前照片仍保留", error)
                }
            }
        }
    }

    private fun duplicate(photo: CapturedPhoto, original: ActiveParking): RegisterResult {
        if (photo.photoName != original.photoName) {
            try {
                photos.deleteOwned(photo.photoName)
            } catch (error: IOException) {
                photos.adopt(photo.photoName)
                return RegisterResult.AlreadyActive(original.record(), "车辆已在停；本次临时照片清理未完成，重新打开应用时继续处理")
            }
            photos.adopt(photo.photoName)
        }
        return RegisterResult.AlreadyActive(original.record())
    }

    suspend fun finish(id: String, end: ClockSnapshot): FinishResult = withContext(Dispatchers.IO) {
        photos.operations.withLock {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val history: ParkingHistory
                try {
                    history = database.withTransaction {
                        val active = dao.activeById(id)
                        if (active == null) {
                            return@withTransaction dao.historyById(id) ?: throw IllegalStateException("停车记录不存在")
                        }
                        val duration = DurationMath.between(active.record().startedAt, end)
                        val finished = ParkingHistory(
                            active.id, active.plate, active.startWallMs, active.startElapsedMs, active.startBootCount,
                            end.wallTimeMs, duration.milliseconds, duration.basis.name, duration.clockChanged,
                        )
                        dao.insertHistory(finished)
                        dao.enqueueDeletion(PendingPhotoDeletion(id, active.photoName))
                        check(dao.deleteActive(id) == 1)
                        finished
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    return@withContext FinishResult.Failure("结束记录保存失败，原停车记录和照片均保留", error)
                }
                try {
                    dao.pendingDeletion(id)?.let { removeQueuedPhoto(it) }
                    FinishResult.Finished(history.record())
                } catch (error: IOException) {
                    FinishResult.CleanupPending(history.record(), "停车已结束，照片清理未完成；重新打开应用时继续清理")
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    FinishResult.CleanupPending(history.record(), "停车已结束，照片清理状态尚未确认；重新打开应用时继续清理")
                }
            }
        }
    }

    private suspend fun removeQueuedPhoto(task: PendingPhotoDeletion) {
        if (task.photoName in dao.activePhotos()) throw IOException("照片仍被在停记录引用，未删除")
        photos.deleteOwned(task.photoName)
        dao.clearDeletion(task.recordId)
    }

    suspend fun recover() = withContext(Dispatchers.IO) {
        photos.operations.withLock {
            var failure: Exception? = null
            for (task in dao.pendingDeletions()) {
                currentCoroutineContext().ensureActive()
                try {
                    removeQueuedPhoto(task)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failure = error
                }
            }
            val referenced = dao.activePhotos().toSet() + dao.pendingDeletions().map { it.photoName }
            try {
                photos.pruneUnreferenced(referenced)
            } catch (error: IOException) {
                failure = error
            }
            failure?.let { throw IOException("照片清理未完成，下次打开应用时继续处理", it) }
        }
    }
}
