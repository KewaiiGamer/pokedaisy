package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SoulGold's save progress (its vars aren't right after its flags) and NEXT BOSS. */
class SoulGoldGuideTest {
    @Test fun `the fixture's flags and starter var read`() {
        for ((fixture, cfg, tables) in listOf(
            Triple("soulgold", NATIVE_SOULGOLD, GUIDE_TABLES_SOULGOLD),
            Triple("soulgold_v12", NATIVE_SOULGOLD_V1_2, GUIDE_TABLES_SOULGOLD_V1_2),
        )) {
            val progress = readSaveProgress(FixtureMemoryReader.load(fixture), cfg, tables)
            assertNotNull(fixture, progress)
            // Cyndaquil was the starter (VAR_STARTER_SPECIES 0x4051).
            assertEquals(fixture, 155, progress!!.variable(0x4051))
            val sysFlags = 0x98C
            assertTrue(fixture, progress.flag(sysFlags))            // FLAG_SYS_POKEMON_GET
            assertTrue(fixture, progress.flag(sysFlags + 0x60))     // FLAG_SYS_B_DASH
            assertFalse(fixture, progress.flag(sysFlags + 7))       // no Falkner badge yet
            assertEquals(fixture, "LEADER FALKNER", gameGuide(GuideId.SOULGOLD)!!.bosses.first { !it.isDone(progress) }.title)
        }
    }
}
