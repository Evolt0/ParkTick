package com.evolto.parktick

import com.evolto.parktick.data.PlateText
import com.evolto.parktick.domain.ClockSnapshot
import com.evolto.parktick.domain.DurationBasis
import com.evolto.parktick.time.DurationMath
import org.junit.Assert.*
import org.junit.Test

class ParkingRulesTest {
    @Test fun sameBootUsesMonotonicClockDespiteWallClockRollback() {
        val duration = DurationMath.between(ClockSnapshot(50_000, 1_000, 3), ClockSnapshot(10_000, 11_000, 3))
        assertEquals(10_000L, duration.milliseconds)
        assertEquals(DurationBasis.MONOTONIC, duration.basis)
        assertTrue(duration.clockChanged)
    }

    @Test fun rebootUsesWallClockEstimateNotResetElapsedClock() {
        val duration = DurationMath.between(ClockSnapshot(50_000, 90_000, 3), ClockSnapshot(80_000, 2_000, 4))
        assertEquals(30_000L, duration.milliseconds)
        assertEquals(DurationBasis.WALL_CLOCK_ESTIMATE, duration.basis)
    }

    @Test fun unknownBootNeverPretendsToBeMonotonic() {
        val duration = DurationMath.between(ClockSnapshot(50_000, 1_000, null), ClockSnapshot(55_000, 90_000, null))
        assertEquals(5_000L, duration.milliseconds)
        assertEquals(DurationBasis.WALL_CLOCK_ESTIMATE, duration.basis)
    }

    @Test fun backwardsWallClockAcrossBootIsUnknownRatherThanZero() {
        val duration = DurationMath.between(ClockSnapshot(50_000, 90_000, 3), ClockSnapshot(10_000, 2_000, 4))
        assertNull(duration.milliseconds)
        assertEquals(DurationBasis.UNKNOWN, duration.basis)
        assertTrue(duration.clockChanged)
    }

    @Test fun durationRetainsMoreThanOneDay() {
        val duration = DurationMath.between(ClockSnapshot(1_000, 1_000, 3), ClockSnapshot(172_802_000, 172_802_000, 3))
        assertEquals(172_801_000L, duration.milliseconds)
    }

    @Test fun overflowingWallClockStillKeepsValidSameBootElapsedDuration() {
        val duration = DurationMath.between(
            ClockSnapshot(Long.MIN_VALUE, 1_000, 3),
            ClockSnapshot(Long.MAX_VALUE, 11_000, 3),
        )
        assertEquals(10_000L, duration.milliseconds)
        assertEquals(DurationBasis.MONOTONIC, duration.basis)
        assertTrue(duration.clockChanged)
    }

    @Test fun overflowingWallClockAcrossRebootDoesNotInventADuration() {
        val duration = DurationMath.between(
            ClockSnapshot(Long.MIN_VALUE, 90_000, 3),
            ClockSnapshot(Long.MAX_VALUE, 2_000, 4),
        )
        assertNull(duration.milliseconds)
        assertEquals(DurationBasis.UNKNOWN, duration.basis)
        assertTrue(duration.clockChanged)
    }

    @Test fun normalizationOnlyNormalizesPresentationNotAmbiguousPlateCharacters() {
        assertEquals("京AO012I", PlateText.normalize(" 京 ａＯ０１２ｉ \n"))
        assertNotEquals(PlateText.normalize("京AO1234"), PlateText.normalize("京A01234"))
    }

}
