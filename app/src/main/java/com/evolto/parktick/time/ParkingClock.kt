package com.evolto.parktick.time

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.evolto.parktick.domain.ClockSnapshot
import com.evolto.parktick.domain.DurationBasis
import com.evolto.parktick.domain.ParkingDuration
import kotlin.math.abs

class ParkingClock(context: Context) {
    // A reboot terminates this process, so this identifier is constant for its lifetime.
    private val bootCount = try {
        Settings.Global.getInt(context.applicationContext.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Settings.SettingNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }

    fun snapshot(): ClockSnapshot = ClockSnapshot(
        wallTimeMs = System.currentTimeMillis(),
        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        bootCount = bootCount,
    )

    fun duration(start: ClockSnapshot, now: ClockSnapshot = snapshot()): ParkingDuration =
        DurationMath.between(start, now)
}

object DurationMath {
    fun between(start: ClockSnapshot, now: ClockSnapshot): ParkingDuration {
        val wall = difference(now.wallTimeMs, start.wallTimeMs)
        if (start.bootCount != null && start.bootCount == now.bootCount &&
            start.elapsedRealtimeMs >= 0 && now.elapsedRealtimeMs >= start.elapsedRealtimeMs
        ) {
            val elapsed = now.elapsedRealtimeMs - start.elapsedRealtimeMs
            val drift = wall?.let { difference(it, elapsed) }
            return ParkingDuration(
                elapsed,
                DurationBasis.MONOTONIC,
                drift == null || drift == Long.MIN_VALUE || abs(drift) > 2_000,
            )
        }
        return if (wall != null && wall >= 0) {
            ParkingDuration(wall, DurationBasis.WALL_CLOCK_ESTIMATE)
        } else {
            ParkingDuration(null, DurationBasis.UNKNOWN, clockChanged = true)
        }
    }

    private fun difference(a: Long, b: Long): Long? = try {
        Math.subtractExact(a, b)
    } catch (_: ArithmeticException) {
        null
    }
}
