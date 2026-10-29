package com.pokedaisy.app

import java.io.File
import java.security.MessageDigest

/**
 * Plain, emulator-free ways to fingerprint a ROM file — a 4-byte header read
 * and a whole-file SHA1, both cheap enough to run once at import/backfill
 * time (never inside a Compose recomposition; see [SteamGridDbGames]'s doc
 * comment for why the SHA1 in particular must stay a one-shot cost). An
 * archive (`.zip` / `.7z`) answers for the ROM inside it ([RomArchive]).
 */
object RomIdentity {
    /** The 4-char ASCII game code at ROM header offset 0xAC (`BPRE`, `BPEE`,
     * …) — identifies the *base* game only. Most FireRed-based ROM hacks
     * (Unbound, Gaia, Radical Red, Odyssey, Amethyst, …) keep this exact same
     * code, so it can't tell them apart from vanilla FireRed or from each
     * other — see [sha1] for that. */
    fun gameCode(rom: File): String? =
        RomArchive.readAt(rom, 0xACL, 4)?.takeIf { it.size == 4 }?.let { String(it, Charsets.US_ASCII) }

    /** A Game Boy / Color cart: its header's Nintendo logo starts CE ED 66 66 at 0x104
     * (a GBA ROM has ARM code there). */
    fun isGameBoy(rom: File): Boolean =
        RomArchive.readAt(rom, 0x104L, 4)?.contentEquals(GB_LOGO_START) == true

    /** A Game Boy Color-only cart (header byte 0x143 = 0xC0: Crystal). A cart that also runs on the original Game Boy
     * (0x80: Yellow, Gold, Silver) isn't (the library's GB / GBC views: GameTitles.gameBoyPlatform). */
    fun isGameBoyColor(rom: File): Boolean =
        RomArchive.readAt(rom, 0x143L, 1)?.firstOrNull()?.let { it.toInt() and 0xFF == 0xC0 } == true

    /** A Nintendo DS cart: the header's CRC16 of its Nintendo logo at 0x15C is CF56 on every one (pk_nds_is_rom). */
    fun isNds(rom: File): Boolean =
        RomArchive.readAt(rom, 0x15CL, 2)?.let { it.size == 2 && it[0] == 0x56.toByte() && it[1] == 0xCF.toByte() } == true

    /** A DS cart's 4-char game code at 0x0C (`CPUE`: Platinum) - [gameCode]'s place on a DS header. */
    fun ndsGameCode(rom: File): String? =
        RomArchive.readAt(rom, 0x0CL, 4)?.takeIf { it.size == 4 }?.let { String(it, Charsets.US_ASCII) }

    /** A DS cart's revision byte (0x1E: Platinum USA is 1). */
    fun ndsRevision(rom: File): Int? =
        RomArchive.readAt(rom, 0x1EL, 1)?.firstOrNull()?.let { it.toInt() and 0xFF }

    /** A GBA cart header (the fixed 0x96 at 0xB2, which the BIOS checks) or, with Game Boy / DS on, a GB / DS
     * one - for an archive, the ROM inside. What the frontend entry point insists on. */
    fun looksLikeRom(rom: File): Boolean =
        RomArchive.readAt(rom, 0xB2L, 1)?.firstOrNull() == 0x96.toByte() ||
            (com.pokedaisy.app.companion.data.TelemetrySampler.GAME_BOY_SUPPORT && isGameBoy(rom)) ||
            (com.pokedaisy.app.companion.data.TelemetrySampler.NDS_SUPPORT && isNds(rom))

    private val GB_LOGO_START = byteArrayOf(0xCE.toByte(), 0xED.toByte(), 0x66, 0x66)

    /** The header's 12-character internal title at 0x0A0 (`POKEMON FIRE`, …). */
    fun headerTitle(rom: File): String? =
        RomArchive.readAt(rom, 0xA0L, 12)?.takeIf { it.size == 12 }
            ?.let { String(it, Charsets.US_ASCII).trimEnd('\u0000', ' ').takeIf { s -> s.all { c -> c in ' '..'~' } } }

    /** The header's revision byte (0x0BC): Ruby / Sapphire / LeafGreen
     * addresses differ between revisions. */
    fun revision(rom: File): Int? =
        RomArchive.readAt(rom, 0xBCL, 1)?.firstOrNull()?.let { it.toInt() and 0xFF }

    /**
     * Whole-file SHA1, lowercase hex — equals a plain `shasum -a 1` on the
     * file. This is deliberately the *same* value the companion's
     * `Poller.kt` uses for live ROM-hack detection (its comments confirm
     * each of its hash constants equals a plain file hash now that its
     * chunked live-bus read masks the RTC/GPIO hole) — computing it here
     * from the file directly needs no running emulator core at all.
     */
    fun sha1(rom: File): String? = digest(rom, "SHA-1")

    /** The file's MD5 - for a GBA ROM, the hash RetroAchievements identifies it by. */
    fun md5(rom: File): String? = digest(rom, "MD5")

    /** The hash RetroAchievements identifies [rom] by: a DS cart's own ([ndsRaHash]), else the file's MD5. */
    fun raHash(rom: File): String? = if (isNds(rom)) ndsRaHash(rom) else md5(rom)

    /** RetroAchievements' console id for [rom] (rc_consoles.h): DS 18, Game Boy 4, a Color-only cart 6, else the GBA's 5. */
    fun raConsole(rom: File): Int = when {
        isNds(rom) -> 18
        isGameBoy(rom) -> if (isGameBoyColor(rom)) 6 else 4
        else -> 5
    }

    /**
     * A DS cart's RetroAchievements hash (rcheevos' rc_hash_nintendo_ds): the MD5 of the header's first 0x160 bytes,
     * the ARM9 and ARM7 code and the 0xA00-byte icon / title block (zero-padded) - not the whole file. A SuperCard
     * header in front is skipped, as rcheevos does.
     */
    fun ndsRaHash(rom: File): String? = try {
        java.io.RandomAccessFile(rom, "r").use { f ->
            fun read(at: Long, n: Int): ByteArray {
                val b = ByteArray(n)
                if (at < f.length()) {
                    f.seek(at)
                    var got = 0
                    while (got < n) {
                        val r = f.read(b, got, n - got)
                        if (r < 0) break
                        got += r
                    }
                }
                return b
            }
            var base = 0L
            var h = read(0, 512)
            fun u8(i: Int) = h[i].toInt() and 0xFF
            if (u8(0) == 0x2E && u8(1) == 0 && u8(2) == 0 && u8(3) == 0xEA && u8(0xB0) == 0x44 && u8(0xB1) == 0x46 && u8(0xB2) == 0x96 && u8(0xB3) == 0) {
                base = 512
                h = read(base, 512)
            }
            fun u32(i: Int) = (u8(i).toLong() or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24))
            val arm9Size = u32(0x2C)
            val arm7Size = u32(0x3C)
            if (arm9Size + arm7Size > 16L shl 20) return null
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(h, 0, 0x160)
            md5.update(read(base + u32(0x20), arm9Size.toInt()))
            md5.update(read(base + u32(0x30), arm7Size.toInt()))
            md5.update(read(base + u32(0x68), 0xA00))
            md5.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (_: Throwable) {
        null
    }

    private fun digest(rom: File, algorithm: String): String? = try {
        val digest = MessageDigest.getInstance(algorithm)
        RomArchive.open(rom).buffered(1 shl 16).use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        null
    }
}
