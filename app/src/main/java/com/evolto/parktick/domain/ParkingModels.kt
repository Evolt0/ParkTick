package com.evolto.parktick.domain

import java.io.File

/** Paired clocks sampled for a single capture or final end confirmation. */
data class ClockSnapshot(
    val wallTimeMs: Long,
    val elapsedRealtimeMs: Long,
    val bootCount: Int?,
)

enum class DurationBasis { MONOTONIC, WALL_CLOCK_ESTIMATE, UNKNOWN }

data class ParkingDuration(
    val milliseconds: Long?,
    val basis: DurationBasis,
    val clockChanged: Boolean = false,
)

data class PhotoFile(val id: String, val photoName: String, val file: File)

data class CapturedPhoto(
    val id: String,
    val photoName: String,
    val capturedAt: ClockSnapshot,
)

data class ParkingRecord(
    val id: String,
    val plate: String,
    val startedAt: ClockSnapshot,
    val endedAtWallMs: Long? = null,
    val duration: ParkingDuration? = null,
    val photoName: String? = null,
) {
    val isActive: Boolean get() = endedAtWallMs == null
}

sealed interface RegisterResult {
    data class Created(val record: ParkingRecord) : RegisterResult
    data class AlreadyActive(val record: ParkingRecord, val cleanupWarning: String? = null) : RegisterResult
    data class Failure(val message: String, val cause: Throwable? = null) : RegisterResult
}

sealed interface FinishResult {
    data class Finished(val record: ParkingRecord) : FinishResult
    data class CleanupPending(val record: ParkingRecord, val message: String) : FinishResult
    data class Failure(val message: String, val cause: Throwable? = null) : FinishResult
}
