package com.pokedaisy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fast-forward on the refresh (GitHub #64: 2x ran at 8x on a 120 Hz screen). */
class FfPacingTest {
    @Test fun sixtyHzRunsSpeedFramesARefresh() {
        assertEquals(2, ffFramesPerRefresh(2f, 1))
        assertEquals(3, ffFramesPerRefresh(3f, 1))
        assertEquals(4, ffFramesPerRefresh(4f, 1))
    }

    @Test fun oneTwentyHzRunsHalfAsManyARefresh() {
        assertEquals(1, ffFramesPerRefresh(2f, 2))
        assertEquals(2, ffFramesPerRefresh(4f, 2))
    }

    @Test fun framesASecondMatchTheSpeed() {
        for ((speed, k) in listOf(2f to 1, 3f to 1, 4f to 1, 2f to 2, 4f to 2)) {
            val refreshHz = 60.0 * k
            assertEquals(60.0 * speed, ffFramesPerRefresh(speed, k) * refreshHz, 0.001)
        }
    }

    @Test fun fractionsGoToTheTimer() {
        assertEquals(0, ffFramesPerRefresh(1.5f, 1))
        assertEquals(0, ffFramesPerRefresh(1.5f, 2))
        assertEquals(0, ffFramesPerRefresh(3f, 2))
    }

    @Test fun noVsyncOrNoFfGoesToTheTimer() {
        assertEquals(0, ffFramesPerRefresh(2f, 0))
        assertEquals(0, ffFramesPerRefresh(1f, 1))
        assertEquals(0, ffFramesPerRefresh(0.5f, 1))
        assertEquals(0, ffFramesPerRefresh(Float.POSITIVE_INFINITY, 1))
    }

    // GitHub #216, the RP6's numbers (HeartGold, 2026-10-21): standing, the drawn frame ~8 ms and each skipped one ~3.2,
    // so 4 a refresh (17.6 ms) overran a 16.7 ms refresh and 3 (14.4 ms) didn't; walking, the drawn frame ~12 ms.
    private val period = 16_700_000L

    /** [groups] groups at the fit's size: the drawn frame [drawn] ns, the skipped ones [skipped] ns each. */
    private fun FfGroupFit.run(groups: Int, drawn: Long, skipped: Long) = repeat(groups) {
        groupDone(drawn + skipped * (size - 1), period)
    }

    @Test fun anUnevenFourBecomesASteadyThree() {
        val fit = FfGroupFit().apply { reset(4) }
        fit.run(600, 8_000_000L, 3_200_000L)
        assertEquals(3, fit.size)
    }

    @Test fun oneSlowGroupIsNotEnough() {
        val fit = FfGroupFit().apply { reset(4) }
        fit.run(30, 3_000_000L, 2_000_000L)
        fit.groupDone(40_000_000L, period)   // a hiccup
        fit.run(30, 3_000_000L, 2_000_000L)
        assertEquals(4, fit.size)
    }

    /** FF turned on again (or a stalled first group, a map loading): one long group must not take it down to 1x. */
    @Test fun aStalledFirstGroupChangesNothing() {
        val fit = FfGroupFit().apply { reset(2) }
        fit.groupDone(90_000_000_000L, period)   // FF off for a minute and a half, timed as one group
        assertEquals(2, fit.size)
        fit.run(30, 3_000_000L, 2_000_000L)
        assertEquals(2, fit.size)
    }

    /** Walking: 2 frames (12 + 3 ms) slightly over or near a refresh still make ~2 a refresh: not halved to 1. */
    @Test fun walkingKeepsTwo() {
        val fit = FfGroupFit().apply { reset(4) }
        fit.run(2000, 12_000_000L, 3_500_000L)
        assertEquals(2, fit.size)
        val slow = FfGroupFit().apply { reset(2) }
        slow.run(2000, 13_000_000L, 4_000_000L)   // 17 ms: over, but 1 would make half as many
        assertEquals(2, slow.size)
    }

    /** A heavy start (the game booting) took it down: lighter frames bring it back up to what was asked. */
    @Test fun aHeavyStartIsntForever() {
        val fit = FfGroupFit().apply { reset(4) }
        fit.run(200, 40_000_000L, 30_000_000L)
        assertEquals(1, fit.size)
        fit.run(2000, 6_000_000L, 2_000_000L)   // 6 + 3 x 2 = 12 ms: 4 fits
        assertEquals(4, fit.size)
    }

    /** A try that doesn't fit goes back, and the next one waits twice as long. */
    @Test fun aFailedTryBacksOff() {
        val fit = FfGroupFit().apply { reset(2) }
        // A group of 1 takes 9 ms (roomy) but 2 really take 35 (each frame drawn dear): fewer than 1 a refresh, 1 is kept.
        var tries = 0
        repeat(3000) {
            val before = fit.size
            fit.groupDone(if (fit.size == 1) 9_000_000L else 35_000_000L, period)
            if (fit.size > before) tries++
        }
        assertEquals(1, fit.size)
        assertTrue("tried $tries times", tries in 3..6)   // backing off: not once a second (~50 in 3000 groups)
    }

    @Test fun neverBelowOne() {
        val fit = FfGroupFit().apply { reset(2) }
        repeat(300) { fit.groupDone(80_000_000L, period) }
        assertEquals(1, fit.size)
    }
}
