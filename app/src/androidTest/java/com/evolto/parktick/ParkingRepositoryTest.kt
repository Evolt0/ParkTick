package com.evolto.parktick

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.evolto.parktick.data.ParkingDatabase
import com.evolto.parktick.data.ParkingRepository
import com.evolto.parktick.domain.CapturedPhoto
import com.evolto.parktick.domain.ClockSnapshot
import com.evolto.parktick.domain.FinishResult
import com.evolto.parktick.domain.RegisterResult
import com.evolto.parktick.media.PhotoStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParkingRepositoryTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var database: ParkingDatabase
    private lateinit var photos: PhotoStore
    private lateinit var repository: ParkingRepository

    @Before fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "parking-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir(): File = root
        }
        database = Room.inMemoryDatabaseBuilder(base, ParkingDatabase::class.java).build()
        photos = PhotoStore(context)
        repository = ParkingRepository(database, photos)
    }

    @After fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    private fun capture(start: Long): CapturedPhoto {
        val output = photos.newFile()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        output.file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return CapturedPhoto(output.id, output.photoName, ClockSnapshot(start, start, 5))
    }

    @Test fun finalEditedPlateIsUniqueAndOriginalPhotoAndStartArePreserved() = runBlocking {
        val first = capture(1_000)
        repository.register(first, "京A12345")
        val second = capture(9_000)
        val result = repository.register(second, " 京ａ１２３４５ ")
        assertTrue(result is RegisterResult.AlreadyActive)
        val records = repository.observeActive().first()
        assertEquals(listOf(first.id), records.map { it.id })
        assertEquals(1_000L, records.single().startedAt.wallTimeMs)
        assertTrue(photos.file(first.photoName).exists())
        assertFalse(photos.file(second.photoName).exists())
    }

    @Test fun uiCancellationAfterAdoptionCannotDeleteAnActivePhoto() = runBlocking {
        val capture = capture(1_000)
        repository.register(capture, "京A12345")
        photos.discard(capture.photoName)
        repository.recover()
        assertTrue(photos.file(capture.photoName).exists())
        assertEquals(capture.id, repository.observeActive().first().single().id)
    }

    @Test fun interruptedDeletionRecoversWithoutDeletingNextVisitOfSamePlate() = runBlocking {
        val first = capture(1_000)
        repository.register(first, "京A12345")
        val blocked = photos.file(first.photoName)
        assertTrue(blocked.delete())
        assertTrue(blocked.mkdir())
        val obstruction = File(blocked, "obstruction").apply { writeText("simulate a filesystem deletion failure") }
        val ended = repository.finish(first.id, ClockSnapshot(5_000, 5_000, 5))
        assertTrue(ended is FinishResult.CleanupPending)
        assertTrue(repository.observeActive().first().isEmpty())
        assertEquals(4_000L, repository.observeHistory().first().single().duration?.milliseconds)
        val second = capture(8_000)
        repository.register(second, "京A12345")
        assertTrue(obstruction.delete())
        val resumed = ParkingRepository(database, PhotoStore(context))
        resumed.recover()
        assertFalse(blocked.exists())
        assertTrue(photos.file(second.photoName).exists())
        assertEquals(second.id, resumed.observeActive().first().single().id)
        assertEquals(1, resumed.observeHistory().first().size)
    }

    @Test fun failedHistoryWriteRollsBackEndWithoutDeletingPhoto() = runBlocking {
        val capture = capture(1_000)
        repository.register(capture, "京A12345")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_end BEFORE INSERT ON parking_history BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END",
        )
        val result = repository.finish(capture.id, ClockSnapshot(5_000, 5_000, 5))
        assertTrue(result is FinishResult.Failure)
        assertEquals(capture.id, repository.observeActive().first().single().id)
        assertTrue(repository.observeHistory().first().isEmpty())
        assertTrue(database.parkingDao().pendingDeletions().isEmpty())
        assertTrue(photos.file(capture.photoName).exists())
    }

    @Test fun searchTreatsWildcardsLiterallyAndNormalizesHumanInput() = runBlocking {
        val capture = capture(1_000)
        repository.register(capture, "京A12345")
        assertTrue(repository.observeActive("%").first().isEmpty())
        assertTrue(repository.observeActive("_").first().isEmpty())
        assertEquals(capture.id, repository.observeActive("京ａ１２３４５").first().single().id)
        assertEquals(capture.id, repository.observeActive("123").first().single().id)
    }

    @Test fun repeatedFinishKeepsOriginalConfirmationTimeAndDuration() = runBlocking {
        val capture = capture(1_000)
        repository.register(capture, "京A12345")
        repository.finish(capture.id, ClockSnapshot(5_000, 5_000, 5))
        repository.finish(capture.id, ClockSnapshot(99_000, 99_000, 5))
        val history = repository.observeHistory().first().single()
        assertEquals(5_000L, history.endedAtWallMs)
        assertEquals(4_000L, history.duration?.milliseconds)
        assertFalse(photos.file(capture.photoName).exists())
    }

    @Test fun simultaneousFinalPlateConfirmationsCreateOnlyOneParkingRecord() = runBlocking {
        val first = capture(1_000)
        val second = capture(2_000)
        val start = CompletableDeferred<Unit>()
        val confirmations = listOf(first to "京A12345", second to " 京ａ１２３４５ ").map { (photo, plate) ->
            async(Dispatchers.Default) {
                start.await()
                repository.register(photo, plate)
            }
        }
        start.complete(Unit)
        val results = confirmations.awaitAll()
        assertEquals(1, results.count { it is RegisterResult.Created })
        assertEquals(1, results.count { it is RegisterResult.AlreadyActive })
        val active = repository.observeActive().first().single()
        val winner = listOf(first, second).single { it.id == active.id }
        val duplicate = listOf(first, second).single { it.id != active.id }
        assertEquals(winner.capturedAt, active.startedAt)
        assertEquals(winner.photoName, active.photoName)
        assertTrue(photos.file(winner.photoName).exists())
        assertFalse(photos.file(duplicate.photoName).exists())
        assertTrue(repository.observeHistory().first().isEmpty())
    }

    @Test fun cancelledDraftCannotBeRegisteredLater() = runBlocking {
        val capture = capture(1_000)
        photos.discard(capture.photoName)
        val result = repository.register(capture, "京A12345")
        assertTrue(result is RegisterResult.Failure)
        assertTrue(repository.observeActive().first().isEmpty())
        assertTrue(repository.observeHistory().first().isEmpty())
        assertFalse(photos.file(capture.photoName).exists())
    }

    @Test fun foregroundRecoveryPreservesLiveDraftAndPrunesAbandonedPhoto() = runBlocking {
        val live = capture(1_000)
        val abandonedStore = PhotoStore(context)
        val abandoned = abandonedStore.newFile()
        abandoned.file.writeText("unconfirmed photo from an earlier process")
        repository.recover()
        assertTrue(photos.file(live.photoName).exists())
        assertFalse(abandoned.file.exists())
        assertTrue(repository.register(live, "京A12345") is RegisterResult.Created)
    }

    @Test fun failedRegistrationKeepsOriginalDraftAndCaptureTimeForRetry() = runBlocking {
        val capture = capture(1_000)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_registration BEFORE INSERT ON active_parking BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END",
        )
        assertTrue(repository.register(capture, "京A12345") is RegisterResult.Failure)
        assertTrue(repository.observeActive().first().isEmpty())
        assertTrue(photos.file(capture.photoName).exists())
        repository.recover()
        assertTrue(photos.file(capture.photoName).exists())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_registration")
        val result = repository.register(capture, "京A12345")
        assertTrue(result is RegisterResult.Created)
        assertEquals(capture.capturedAt, repository.observeActive().first().single().startedAt)
    }

    @Test fun failedDeletionQueueWriteRollsBackHistoryAndPreservesActivePhoto() = runBlocking {
        val capture = capture(1_000)
        repository.register(capture, "京A12345")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_deletion BEFORE INSERT ON pending_photo_deletions BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END",
        )
        assertTrue(repository.finish(capture.id, ClockSnapshot(5_000, 5_000, 5)) is FinishResult.Failure)
        assertEquals(capture.id, repository.observeActive().first().single().id)
        assertTrue(repository.observeHistory().first().isEmpty())
        assertTrue(database.parkingDao().pendingDeletions().isEmpty())
        assertTrue(photos.file(capture.photoName).exists())
    }

    @Test fun failedDuplicatePhotoCleanupRecoversWithoutChangingOriginalParking() = runBlocking {
        val original = capture(1_000)
        repository.register(original, "京A12345")
        val duplicate = capture(9_000)
        val blocked = photos.file(duplicate.photoName)
        assertTrue(blocked.delete())
        assertTrue(blocked.mkdir())
        val obstruction = File(blocked, "obstruction").apply { writeText("simulate a filesystem deletion failure") }
        val result = repository.register(duplicate, "京A12345")
        assertTrue(result is RegisterResult.AlreadyActive)
        assertNotNull((result as RegisterResult.AlreadyActive).cleanupWarning)
        assertTrue(obstruction.delete())
        repository.recover()
        assertFalse(blocked.exists())
        val active = repository.observeActive().first().single()
        assertEquals(original.id, active.id)
        assertEquals(original.capturedAt, active.startedAt)
        assertEquals(original.photoName, active.photoName)
        assertTrue(photos.file(original.photoName).exists())
    }

    @Test fun simultaneousEndConfirmationsKeepOneHistoryAndTheFirstCommittedTime() = runBlocking {
        val capture = capture(1_000)
        repository.register(capture, "京A12345")
        val start = CompletableDeferred<Unit>()
        val confirmations = listOf(5_000L, 9_000L).map { time ->
            async(Dispatchers.Default) {
                start.await()
                repository.finish(capture.id, ClockSnapshot(time, time, 5))
            }
        }
        start.complete(Unit)
        val results = confirmations.awaitAll()
        assertTrue(results.all { it is FinishResult.Finished })
        val history = repository.observeHistory().first().single()
        assertTrue(history.endedAtWallMs in listOf(5_000L, 9_000L))
        assertEquals(history.endedAtWallMs!! - capture.capturedAt.wallTimeMs, history.duration?.milliseconds)
        for (result in results) assertEquals(history, (result as FinishResult.Finished).record)
        assertTrue(repository.observeActive().first().isEmpty())
        assertTrue(database.parkingDao().pendingDeletions().isEmpty())
        assertFalse(photos.file(capture.photoName).exists())
    }

}
