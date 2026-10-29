package com.pokedaisy.app

import com.pokedaisy.app.achievements.RetroAchievements
import java.io.File

/**
 * Shared "does this ROM have a fetchable cover, and can we get it" check —
 * used by both LibraryActivity's Refresh backfill and SettingsActivity's
 * Cover Art screen (so hitting Save can show live per-ROM progress right
 * there, instead of the user having to separately open Library and tap
 * Refresh to find out whether the key actually works).
 *
 * Three sources, in this order: ScreenScraper ([GameMedia]: the whole media set, box art used as
 * the cover) with the player's own account - always first, and its box art replaces an automatic
 * cover from the others; then the game's RetroAchievements box art (found by the ROM's MD5 -
 * [RetroAchievements.gameBoxArtUrl]) wherever RA has one; then SteamGridDB's icons for the games
 * [SteamGridDbGames] knows.
 */
object CoverArtSync {
    enum class Status { FETCHED, REPLACED, KEPT, ALREADY_CACHED, MANUAL_COVER, UNKNOWN_GAME, FETCH_FAILED, NO_API_KEY }

    /** Where players get each key (Settings > Cover Art's GET KEY, setup's OPEN buttons). */
    const val STEAMGRIDDB_KEY_PAGE = "https://www.steamgriddb.com/profile/preferences/api"
    const val RA_KEY_PAGE = "https://retroachievements.org/settings?tab=applications"

    /** Where an automatic cover came from ([Prefs.romCoverSource]). */
    enum class Source { SCREENSCRAPER, STEAMGRIDDB, RETROACHIEVEMENTS }

    /**
     * One library pass's ScreenScraper state: once the account or the day's quota stops a call
     * ([ScreenScraperClient.ApiException.fatal]), the rest of the pass skips ScreenScraper
     * and [stopped] says why.
     */
    class ScreenScraperPass(val account: ScreenScraperClient.Account?, val videos: Boolean) {
        @Volatile var stopped: ScreenScraperClient.ApiException? = null

        companion object {
            fun of(prefs: Prefs) = ScreenScraperPass(prefs.screenScraperAccount, prefs.screenScraperVideos)
        }
    }

    /** Blocking (hashes the ROM, and may hit the network) — call only from a
     * background thread. [replace]: fetch even over an existing (cached or
     * manual) cover - which is only swapped once the new image has fully
     * downloaded, so a ROM no source has anything for keeps its cover ([Status.KEPT]).
     * [mediaRoot]: [GameMedia.root] (the covers folder's sibling); [label]: the library's name,
     * ScreenScraper's search when the hashes aren't in its database. */
    fun fetchOne(
        rom: File,
        coverFile: File,
        prefs: Prefs,
        replace: Boolean = false,
        ss: ScreenScraperPass = ScreenScraperPass.of(prefs),
        mediaRoot: File = File(coverFile.parentFile?.parentFile, "media"),
        label: String = GameTitles.tidy(rom.nameWithoutExtension),
    ): Status {
        val hasCover = coverFile.isFile && coverFile.length() > 0
        // A manual cover whose file is gone (GitHub #272: a player's picked covers went missing) protects nothing: the
        // game used to keep the placeholder for good, never fetched again. Its flag goes once an automatic one lands.
        val staleManual = !hasCover && prefs.romCoverManual(rom)
        val manual = hasCover && prefs.romCoverManual(rom)
        // ScreenScraper's media first, whatever the cover: the launcher's media screen shows it all.
        val ssBox = fetchMedia(rom, ss, mediaRoot, label, replace)
        if (!replace && manual) return Status.MANUAL_COVER
        // Its box art wins over an automatic cover from the other sources.
        val ssCover = hasCover && prefs.romCoverSource(rom) == Source.SCREENSCRAPER.name
        if (ssBox != null && (replace || !ssCover)) {
            if (copyCover(ssBox, coverFile)) {
                if (staleManual) prefs.setRomCoverManual(rom, false)
                prefs.setRomCoverSource(rom, Source.SCREENSCRAPER.name)
                return if (hasCover) Status.REPLACED else Status.FETCHED
            }
        }
        if (!replace && hasCover) return Status.ALREADY_CACHED
        val apiKey = prefs.steamGridDbApiKey?.takeIf { it.isNotBlank() }
        val raKey = prefs.raWebApiKey?.takeIf { it.isNotBlank() }
        // Hashes the ROM: only when RA had nothing.
        val game by lazy { apiKey?.let { SteamGridDbGames.forRom(rom) } }
        val source = when {
            raKey != null && fetchRaBoxArt(rom, coverFile, raKey) -> Source.RETROACHIEVEMENTS
            game?.let { SteamGridDbClient.fetchCover(apiKey!!, it, coverFile) } == true -> Source.STEAMGRIDDB
            else -> null
        }
        if (source != null) {
            if (staleManual) prefs.setRomCoverManual(rom, false)
            prefs.setRomCoverSource(rom, source.name)
            return if (hasCover) Status.REPLACED else Status.FETCHED
        }
        return when {
            hasCover -> Status.KEPT
            apiKey == null && raKey == null && ss.account == null -> Status.NO_API_KEY
            game == null && raKey == null -> Status.UNKNOWN_GAME
            else -> Status.FETCH_FAILED
        }
    }

    /** [rom]'s ScreenScraper media (fetched now unless it's been already), and its box art's file. */
    private fun fetchMedia(rom: File, ss: ScreenScraperPass, root: File, label: String, replace: Boolean): File? {
        val account = ss.account ?: return null
        if (ss.stopped == null) {
            try {
                GameMedia.fetch(root, rom, account, label, ss.videos, replace)
            } catch (e: ScreenScraperClient.ApiException) {
                if (e.fatal) ss.stopped = e
            } catch (_: java.io.IOException) {
                // Offline: the next pass tries again.
            }
        }
        return GameMedia.existing(root, rom, GameMedia.Kind.BOX)
    }

    /** The box art as the library's cover (a copy: RESET COVER / REPLACE COVER work on the cover alone). */
    private fun copyCover(box: File, coverFile: File): Boolean = runCatching {
        coverFile.parentFile?.mkdirs()
        val tmp = File(coverFile.parentFile, "${coverFile.name}.tmp")
        box.copyTo(tmp, overwrite = true)
        check(tmp.renameTo(coverFile))
    }.isSuccess

    /** The RA box art's URL for [rom], if RA has one (blocking: hashes, network). */
    fun raBoxArtUrl(rom: File, raKey: String): String? =
        RomIdentity.raHash(rom)?.let { RetroAchievements.gameBoxArtUrl(it, raKey) }

    private fun fetchRaBoxArt(rom: File, coverFile: File, raKey: String): Boolean {
        val url = raBoxArtUrl(rom, raKey) ?: return false
        return SteamGridDbClient.download(url, coverFile)
    }
}
