package com.pokedaisy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Linking several ROMs folders ([RomFolder.coveredBy], [RomFolder.withFolder]): each file is looked through once. */
class RomFolderTest {
    private val gba = "/storage/emulated/0/ROMs/gba"
    private val nds = "/storage/emulated/0/ROMs/nds"

    @Test fun aFolderBesideTheOthersIsAdded() {
        assertNull(RomFolder.coveredBy(listOf(gba), nds))
        assertEquals(listOf(gba, nds), RomFolder.withFolder(listOf(gba), nds))
    }

    @Test fun theSameFolderOrOneInsideIsAlreadyLinked() {
        assertEquals(gba, RomFolder.coveredBy(listOf(gba, nds), gba))
        assertEquals(gba, RomFolder.coveredBy(listOf(gba, nds), "$gba/Retail"))
        assertEquals(gba, RomFolder.coveredBy(listOf(gba), "$gba/"))
    }

    @Test fun aNameThatOnlyStartsTheSameIsNotInside() {
        assertNull(RomFolder.coveredBy(listOf(gba), "${gba}c"))
    }

    @Test fun aFolderHoldingLinkedOnesTakesTheirPlace() {
        val roms = "/storage/emulated/0/ROMs"
        val sd = "/storage/XXXX-XXXX/gb"
        assertEquals(listOf(sd, roms), RomFolder.withFolder(listOf(gba, sd, nds), roms))
    }
}
