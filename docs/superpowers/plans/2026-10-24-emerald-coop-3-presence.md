# Emerald Co-op, Plan 3: Seeing Each Other Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On retail English Emerald, two linked players on the same map see each other's character walk, run, bike and surf on their own screen (and in EXPAND's margins), drawn from each device's own copy of the hero art, with nothing ever written into the game's RAM, save or states.

**Architecture:** The local side reads the player's fine position, facing and movement natively each frame (`pk_partner_self` in `pk_wide.c`, beside FOLLOWER, using the same field table) and the app sends a 20-byte presence update about every 100 ms through a small `CoopPresenceLink` interface that Plan 2 implements. The remote side decodes, checks and buffers updates (`PresenceSmoother`, interpolating ~150 ms behind real time), and every frame hands the native side one placement: the partner is one or two extra hardware sprites put in at scanline 0 and taken out after line 159 (`partnerBegin` / `partnerEnd`, FOLLOWER's way, its own OAM entries, OBJ tiles 976-1007 and palette slot), plus a pass in WIDE's `drawRegion` for the margins. Art is Brendan / May (or a PLAYER LOOK hero the device keeps) from `rom-art/look/`, packed by `PartnerArt` into 42 frames of 32x32.

**Tech Stack:** C (mGBA 0.10.5, pk_wide.c's scanline hook), Kotlin + JUnit + kotlinx-coroutines (Flow), Python 3 generator (Docker), mgba_dump headless scripts.

**Spec:** `docs/superpowers/specs/2026-10-24-emerald-coop-design.md` (section 2 and "My call on looks"). This is plan 3 of 5: 1 battle engine, 2 connection and pairing, 3 seeing each other, 4 joined battles end to end, 5 the lock and story events.

**Depends on:** Plan 1's `scripts/gen_coop_emerald.py` and its pokeemerald build (Tasks 3 and 6 add symbols to it), Plan 1's `scripts/coop_headless_check.sh` runner and its mgba_dump commands `expect8/16/32` and `waittask` (Plan 1 Task 2; this plan's headless tests are `native-capture/coop/partner_*.txt` so the same runner picks them up). Plan 2 supplies a `CoopPresenceLink` and the co-op manual page; until it lands, Task 8 is tested with an in-memory link and Task 9's manual step waits.

## Global Constraints

- Game: retail English Emerald only, BPEE, SHA-1 `f3ae088181bf583e55daf962a92bb46f4f1d07b7`, the same ROM on both devices. Anything else: no presence sent, none drawn.
- Purely visual: you walk through each other, no collision, no interaction. Nothing is written into the game's RAM, save or states; VRAM / OAM / palette RAM written at line 0 are put back after line 159 (a halfword the game wrote meanwhile keeps the game's), exactly as FOLLOWER does.
- Coexists with FOLLOWER: the partner never takes FOLLOWER's OAM entry, palette slot or tiles (FOLLOWER: tiles 1008-1023; partner: tiles 976-1007).
- Looks: only a look id crosses the network, never ROM bytes. Each device draws the partner from art it has itself (`LookArt`, `rom-art/look/`); a look the device lacks shows as plain Brendan / May by the partner's gender.
- On the same map only. A partner on another map (or a connected map shown in the margins) is not drawn (my call for v1). A map change on either side hides the partner until an update puts both on one map.
- The network seam is `CoopPresenceLink`; this plan never touches Plan 2's transport.
- Python runs in the `pokedaisy-capture` Docker image (no Python on PATH here). Link mgba_dump with `-L/usr/lib/x86_64-linux-gnu` on this x86_64 host (the runner already does).
- Headless runs need `EMERALD_ROM=D:/Code/pokedaisy/roms/emerald.gba` and `EMERALD_SAVE=D:/Code/pokedaisy/roms/emerald.sav` (never committed); boot is `native-capture/boot/emerald_coop.txt` (Route 101, controls free). ROM-backed JVM tests take `-DromDir=D:/Code/pokedaisy/roms/` and skip without it.
- JVM: `./gradlew --no-daemon` with Android Studio's JBR. Baseline: 6 tests already fail on this Windows checkout (PerformanceGuardsTest, SiteDataExportTest, RomAddedTest.keptAcrossLaunches, SaveFileEditsTest.aTradeWritesBothOrNeither, BestEffortTest x2); "all pass" means no failures beyond those.
- Commits carry no Claude / Anthropic attribution (the `commit-msg` hook rejects it). Plain sentence subjects, `Co-op:` prefix like Plan 1.
- No settings row in this plan (decision 7: none needed); the manual still gets a section, since players notice it.

## Review Focus

- The sender fast-forwarding (4x) or slowed (0.25x): updates are timed in wall-clock milliseconds, not game frames, so the partner moves at their real pace without racing or drifting further behind. Test in Task 2.
- Updates late, out of order, duplicated, or in a burst after a 2 s stall: the partner never steps backwards, catches up without a visible jump over more than a tile, and disappears after 3 s of silence. Test in Task 2.
- A partner far away on the same map, or just past the screen edge: never put in OAM where the hardware's 9-bit x / 8-bit y wrap would show it on the opposite side; only EXPAND's margins draw it. Test in Task 5 (`partner_offscreen`) and Task 7.
- A malformed or hostile update (wrong version or length, coordinates past any map, unknown look id, facing / mode out of range) and a frame index past the art: refused in Kotlin, and refused again in C (`why` = bad frame), never an out-of-bounds read. Tests in Task 1 and Task 5.
- A savestate or the resume state taken while the partner is on screen: holds none of it (RAM, VRAM, OAM, palette identical to the same frames run without the partner). Test in Task 5 (`partner_clean`).

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopPresenceLink.kt` (create) | The seam to Plan 2: `send(bytes)`, `received: Flow<ByteArray>`. |
| `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/Presence.kt` (create) | `PresenceUpdate`, `PresenceCodec` (20-byte wire format, checks), `PresenceLook` (stable look ids). |
| `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/PresenceSmoother.kt` (create) | Buffer + interpolation → `PartnerPlacement` per frame; `PartnerFrames.index`. |
| `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/PartnerArt.kt` (create) | Packs the partner's 42 frames + palette from `LookArt` and the ROM's surf blob. |
| `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopEmeraldGen.kt` (create, generated) | ROM addresses Kotlin needs (surf blob pic), written by `gen_coop_emerald.py`. |
| `app/src/main/kotlin/com/pokedaisy/app/CoopPresence.kt` (create) | Glue on the emu thread: reads self, sends, receives, places, builds art. |
| `app/src/main/cpp/pk_wide.c` / `pk_wide.h` (modify) | `pk_partner_self`, `pk_partner_set`, `pk_partner_place`, `pk_partner_info`; `partnerBegin` / `partnerEnd` in the scanline hook; `drawPartner` in `drawRegion`. |
| `scripts/gen_coop_emerald.py`, `app/src/main/cpp/pk_coop_emerald.h` (modify, regenerate) | `gPaletteFade` fields (C), surf blob address (Kotlin). |
| `app/src/main/cpp/pokedaisy_jni.c`, `app/src/main/kotlin/com/pokedaisy/app/MgbaCore.kt` (modify) | `pkPartnerSelf`, `pkPartnerSet`, `pkPartnerPlace`. |
| `app/src/main/kotlin/com/pokedaisy/app/EmulatorEngine.kt` (modify, next to `stepFollower` ~line 1045) | Calls `CoopPresence.onFrame` on GBA frames. |
| `native-capture/mgba_dump.c` (modify, next to `follower` ~line 1390) | `partnerself`, `partnerset`, `partnerpos`, `partnernear`, `partnerinfo`, `memhash`, `expectsame`. |
| `native-capture/coop/partner_*.txt` (create) | Headless tests, run by Plan 1's `scripts/coop_headless_check.sh`. |
| `app/src/test/kotlin/com/pokedaisy/app/companion/data/coop/{PresenceCodecTest,PresenceSmootherTest,PartnerArtTest}.kt`, `app/src/test/kotlin/com/pokedaisy/app/CoopPresenceTest.kt` (create) | JVM tests. |
| `native-capture/README.md`, `docs/DEVELOPMENT.md`, the co-op page in `docs/manual/` (modify) | Commands, a "Co-op: seeing each other" section, a manual section. |

## Shared interfaces (every task uses these names)

```kotlin
// CoopPresenceLink.kt
interface CoopPresenceLink { fun send(bytes: ByteArray); val received: kotlinx.coroutines.flow.Flow<ByteArray> }

// Presence.kt
object PresenceMode { const val WALK = 0; const val RUN = 1; const val MACH = 2; const val ACRO = 3; const val SURF = 4 }
data class PresenceUpdate(
    val timeMs: Long,        // sender's SystemClock.elapsedRealtime() & 0xFFFFFFFF
    val valid: Boolean,      // the sender is on a map (not title / intro)
    val female: Boolean,
    val mapGroup: Int, val mapNum: Int,   // 0..255 each
    val fineX: Int, val fineY: Int,       // object-event coords (MAP_OFFSET included) x 16 + sub-tile pixels, 0..32767
    val facing: Int,         // the game's DIR_SOUTH 1 / NORTH 2 / WEST 3 / EAST 4
    val mode: Int,           // PresenceMode.*
    val step: Int,           // 0..255, +1 each tile the sender steps onto (which foot: step & 1)
    val lookId: Int,         // PresenceLook id, 0 = the gender's own hero
)
```

Wire format (`PresenceCodec`, little-endian, `SIZE = 20`): 0 u8 version (`VERSION = 1`); 1 u8 flags (bit 0 valid, bit 1 female, other bits 0); 2 u8 mapGroup; 3 u8 mapNum; 4 u16 fineX; 6 u16 fineY; 8 u8 facing; 9 u8 mode; 10 u8 step; 11 u8 lookId; 12 u32 timeMs; 16 u32 reserved (0).

`PresenceLook` ids (pinned; never renumber): 0 own hero, 1 RED, 2 LEAF, 3 BRENDAN, 4 MAY, 5 RS_BRENDAN, 6 RS_MAY, 7 GOLD, 8 KRIS. A PLAYER LOOK Pokémon (`MON:`) sends 0 (my call).

Partner art (`PartnerArt.FRAMES = 42`, `ART_BYTES = 42 * 512 + 32 = 21536`): 32x32 4bpp frames in reading order, then the 16-colour palette. Frames 0-8 walking, 9-17 running, 18-26 Mach Bike, 27-35 Acro Bike (each sheet's first nine: stand S / N / W, then S step A, S step B, N A, N B, W A, W B, the order of Emerald's hero sheets), 36-38 surfing (S / N / W), 39-41 the surf blob (S / N / W). 16x32 frames are centred with `PlayerLook.widen`. East = west flipped.

```c
// pk_wide.h
#define PK_PARTNER_FRAMES 42
#define PK_PARTNER_ART_BYTES (PK_PARTNER_FRAMES * 512 + 32)
enum { PK_PARTNER_SELF_VALID, PK_PARTNER_SELF_GROUP, PK_PARTNER_SELF_NUM, PK_PARTNER_SELF_FX, PK_PARTNER_SELF_FY,
       PK_PARTNER_SELF_FACING, PK_PARTNER_SELF_MODE, PK_PARTNER_SELF_STEP, PK_PARTNER_SELF_N };
// why the last frame drew no partner (pk_partner_info):
enum { PK_PARTNER_DRAWN, PK_PARTNER_OFF, PK_PARTNER_NO_ART, PK_PARTNER_NO_PLAYER, PK_PARTNER_OFFSCREEN,
       PK_PARTNER_NO_ENTRY, PK_PARTNER_NO_SLOT, PK_PARTNER_TILES_USED, PK_PARTNER_BAD_FRAME };

int  pk_partner_self(struct mCore* core, int32_t out[PK_PARTNER_SELF_N]);   // 1 = out filled
void pk_partner_set(const uint8_t* art);                                    // PK_PARTNER_ART_BYTES, NULL = none
void pk_partner_place(struct mCore* core, int visible, int fineX, int fineY, int frame, int hflip, int blobFrame, int bob);
void pk_partner_info(char* out, int n);
```

```kotlin
// MgbaCore
external fun pkPartnerSelf(): IntArray?          // PK_PARTNER_SELF_N ints, null = not on a known field
external fun pkPartnerSet(art: ByteArray?)       // ART_BYTES or null
external fun pkPartnerPlace(visible: Boolean, fineX: Int, fineY: Int, frame: Int, hflip: Boolean, blobFrame: Int, bob: Int)
```

---

### Task 1: The presence message

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopPresenceLink.kt`, `.../coop/Presence.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/data/coop/PresenceCodecTest.kt`

**Interfaces:**
- Produces: `CoopPresenceLink`, `PresenceMode`, `PresenceUpdate` (above); `object PresenceCodec { const val VERSION = 1; const val SIZE = 20; fun encode(u: PresenceUpdate): ByteArray; fun decode(b: ByteArray): PresenceUpdate? }`; `object PresenceLook { fun idOf(c: LookChar?): Int; fun charOf(id: Int): LookChar? }`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun roundTrips() { val u = PresenceUpdate(123456L, true, true, 0, 16, 7 * 16 + 5, 12 * 16, 4, PresenceMode.MACH, 9, 4)
    assertEquals(u, PresenceCodec.decode(PresenceCodec.encode(u))); assertEquals(20, PresenceCodec.encode(u).size) }
@Test fun refusesWrongVersionOrSize() { val b = PresenceCodec.encode(sample()); b[0] = 2
    assertNull(PresenceCodec.decode(b)); assertNull(PresenceCodec.decode(ByteArray(19))); assertNull(PresenceCodec.decode(ByteArray(21))) }
@Test fun refusesOutOfRange() { // one encoded sample, one byte changed per case, decode -> null for each:
    // facing 0, facing 5, mode 5, flags = 0x04, reserved byte 16 = 1, fineX bytes = 0x8000
}
@Test fun unknownLookIsOwnHero() { assertNull(PresenceLook.charOf(9)); assertNull(PresenceLook.charOf(0)); assertEquals(LookChar.MAY, PresenceLook.charOf(4)) }
@Test fun lookIdsArePinned() { assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8),
    listOf(LookChar.RED, LookChar.LEAF, LookChar.BRENDAN, LookChar.MAY, LookChar.RS_BRENDAN, LookChar.RS_MAY, LookChar.GOLD, LookChar.KRIS).map(PresenceLook::idOf)) }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests '*PresenceCodecTest'` → Expected: FAIL, unresolved `PresenceCodec`.

- [ ] **Step 3: Implement** `Presence.kt` and `CoopPresenceLink.kt` with the layout above; `decode` returns null for anything outside the documented ranges (no clamping on the Kotlin side: a bad update is dropped whole).

- [ ] **Step 4: Run them** → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopPresenceLink.kt app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/Presence.kt app/src/test/kotlin/com/pokedaisy/app/companion/data/coop/PresenceCodecTest.kt
git commit -m "Co-op: the presence message, checked on the way in"
```

### Task 2: Smoothing the partner's movement

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/PresenceSmoother.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/data/coop/PresenceSmootherTest.kt`

**Interfaces:**
- Consumes: `PresenceUpdate`.
- Produces:
```kotlin
data class PartnerPlacement(val fineX: Int, val fineY: Int, val frame: Int, val hflip: Boolean, val blobFrame: Int, val bob: Int, val female: Boolean, val lookId: Int)
class PresenceSmoother(val delayMs: Long = 150, val staleMs: Long = 3000) {
    fun push(u: PresenceUpdate, arrivedMs: Long)
    fun at(nowMs: Long, localGroup: Int, localNum: Int): PartnerPlacement?   // null = hidden
    fun reset()
}
object PartnerFrames { fun index(mode: Int, facing: Int, stepping: Boolean, foot: Int): Int; fun blob(mode: Int, facing: Int): Int }   // -1 unless mode is SURF
```

Rules: the clock offset is the smallest `arrivedMs - u.timeMs` (mod 2^32) over the last 64 updates; render time `T = nowMs - offset - delayMs`. Updates older than the newest pushed are dropped; equal `timeMs` is a duplicate. Between two buffered updates `a.t <= T <= b.t` on the same map: position lerped; a jump over 48 px (two tiles plus) or a map change snaps to `b` once `T` reaches it. Past the newest: held there. Hidden when the newest is older than `staleMs` (by arrival), `!valid`, or its map isn't `(localGroup, localNum)`. Facing / mode / step / look from `b`. `stepping` = moving and the position along the movement axis is in the first half of its tile (`fine % 16` in 1..8 going east / south, 8..15 going west / north); `foot = b.step & 1`. `index`: walk / run / mach / acro base `mode * 9`, direction S 0 / N 1 / W 2 / E 2 (hflip); standing `base + dir`, stepping `base + 3 + dir * 2 + foot`; SURF `36 + dir`, no stepping frames. `blob(SURF, f) = 39 + dir`, any other mode -1. `bob` while surfing: `intArrayOf(0, 1, 2, 1)[(nowMs / 266 % 4).toInt()]` px up, else 0 (my call: close to the game's, not matched).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun interpolatesBetweenUpdates() { /* updates t=0 fineX=160, t=100 fineX=176 (arrived +40 ms each); at now = 40+150+50 -> fineX 168 */ }
@Test fun senderAtFourTimesSpeedKeepsWallClockPace() { /* sender steps a tile per 66 ms of its timeMs (running at 4x); placements over 1 s of local time advance at most one tile per 66 ms, never more than 2 tiles ahead of the lerp (Review Focus 1) */ }
@Test fun lateAndDuplicateUpdatesNeverStepBack() { /* push t=200, then t=100, then t=200 again: fineX at every later now is non-decreasing (Review Focus 2) */ }
@Test fun burstAfterStallCatchesUpWithoutBigJumps() { /* 2 s gap, then 20 updates arriving in one ms: frame-to-frame (16 ms steps) fineX deltas <= 16 except one snap if > 48 px apart */ }
@Test fun hiddenWhenStale() { /* newest arrived 3001 ms before now -> null; 2999 -> not null */ }
@Test fun hiddenOnOtherMapAndSnapsOnReturn() { /* b on (0,17) while local (0,16) -> null; next update back on (0,16) at another place -> placement there, no lerp from the old map */ }
@Test fun frameIndices() { assertEquals(2, PartnerFrames.index(PresenceMode.WALK, 4, false, 0)); assertEquals(9 + 3 + 2 * 2 + 1, PartnerFrames.index(PresenceMode.RUN, 3, true, 1))
    assertEquals(37, PartnerFrames.index(PresenceMode.SURF, 2, true, 0)); assertEquals(41, PartnerFrames.blob(PresenceMode.SURF, 4)); assertEquals(-1, PartnerFrames.blob(PresenceMode.WALK, 1)) }
```
(Write the commented bodies out with exact numbers: every test above names its inputs and the expected value.)

- [ ] **Step 2: Run them** → Expected: FAIL, unresolved `PresenceSmoother`.

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests '*PresenceSmootherTest'`

- [ ] **Step 3: Implement `PresenceSmoother` and `PartnerFrames`** (a ring of the last 8 updates, sorted by `timeMs`; 32-bit wrap handled by comparing `(a - b).toInt()`).

- [ ] **Step 4: Run them** → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/PresenceSmoother.kt app/src/test/kotlin/com/pokedaisy/app/companion/data/coop/PresenceSmootherTest.kt
git commit -m "Co-op: the partner's movement smoothed between updates"
```

### Task 3: The partner's art from the player's own ROM

**Files:**
- Modify: `scripts/gen_coop_emerald.py` (a Kotlin output), create `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopEmeraldGen.kt` (generated)
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/PartnerArt.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/data/coop/PartnerArtTest.kt`

**Interfaces:**
- Consumes: `LookArt.load(filesDir, c)`, `PlayerLook.scan(rom)`, `PlayerLook.sheet(part, me, look, art, own)`, `PlayerLook.wear(me, look, art, own)`, `PlayerLook.remap(frame, map)`, `PlayerLook.widen(frame)` (all `internal`, same module).
- Produces: `object CoopEmeraldGen { const val SURF_BLOB_PIC: Int; const val SURF_BLOB_CRC: Long }` (ROM offset of `gFieldEffectObjectPic_SurfBlob`, 3 frames x 512 bytes, and their CRC32); `object PartnerArt { const val FRAMES = 42; const val ART_BYTES = 21536; fun pack(own: Map<LookPart, ByteArray>, worn: Map<LookPart, ByteArray>?, me: LookChar, look: LookChar?, rom: ByteArray): ByteArray?; fun build(filesDir: File, rom: ByteArray, female: Boolean, look: LookChar?): ByteArray? }`. `build` = `me` BRENDAN / MAY by `female`; own art from `LookArt.load`, else `PlayerLook.scan(rom)` + `LookArt.keep` first; `look` honoured only when `LookArt.load(filesDir, look)` is whole (else plain hero); blob read from `rom` at `SURF_BLOB_PIC`, refused (null) if its CRC differs.

- [ ] **Step 1: Generate the Kotlin constants**

Add to `gen_coop_emerald.py` a second output, `CoopEmeraldGen.kt` (header comment "generated by scripts/gen_coop_emerald.py, do not edit"), with `gFieldEffectObjectPic_SurfBlob`'s ROM offset (`nm` address - 0x08000000) and the CRC32 of its 1536 bytes in the retail ROM. Run in Docker: `docker run --rm -v "$(cygpath -m "$PWD"):/repo" -w /repo pokedaisy-capture -c "python3 scripts/gen_coop_emerald.py \$DECOMPS/pokeemerald"` (the same invocation Plan 1 Task 1 used) → Expected: `pk_coop_emerald.h` byte-identical to before (`git diff --exit-code app/src/main/cpp/pk_coop_emerald.h`) and the new Kotlin file.

- [ ] **Step 2: Write the failing tests**

```kotlin
@Test fun packHasFixedSize() { /* synthetic own art (each LookPart filled with its index), no look -> size 21536, palette = own OW_PAL */ }
@Test fun walkingFramesAreWidenedInOrder() { /* frame 4 == PlayerLook.widen(EM_WALKING frame 4) */ }
@Test fun machAndAcroTakeFirstNine() { /* frame 18 == EM_MACH_BIKE frame 0, frame 35 == EM_ACRO_BIKE frame 8 */ }
@Test fun crossGameLookIsRemappedIntoOwnSlot() { /* look RED with synthetic FR art: palette == PlayerLook.wear(BRENDAN, RED, ...).pal, frame 0 == remap(widen(sheet(EM_WALKING, BRENDAN, RED, ...)[0]), wear.map) */ }
@Test fun missingLookFallsBackToOwnHero() { /* build(tmpDir, rom, female = true, look = LEAF) with no LEAF kept == build(..., look = null) */ }
@Test fun writesHeadlessArtFromRealRom() { // skips without $romDir/emerald.gba
    // build for BRENDAN and MAY, assert size, then write app/build/partner/brendan.bin and may.bin (for the headless tests)
}
```

- [ ] **Step 3: Run them** → Expected: FAIL, unresolved `PartnerArt`.

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests '*PartnerArtTest' -DromDir=D:/Code/pokedaisy/roms/`

- [ ] **Step 4: Implement `PartnerArt`.** Within one engine `PlayerLook.wear` returns the look's palette as is; a cross-engine look keeps Brendan's / May's shared colours, so the blob (drawn with the hero's palette in Emerald) stays right. Surfing frames 36-38 = `EM_SURFING` frames 0-2.

- [ ] **Step 5: Run them** → PASS; `app/build/partner/brendan.bin` and `may.bin` exist, 21536 bytes each.

- [ ] **Step 6: Commit**

```bash
git add scripts/gen_coop_emerald.py app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopEmeraldGen.kt app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/PartnerArt.kt app/src/test/kotlin/com/pokedaisy/app/companion/data/coop/PartnerArtTest.kt
git commit -m "Co-op: the partner drawn from the player's own Brendan / May, or a look this device keeps"
```

### Task 4: Reading where the player is, every frame

**Files:**
- Modify: `app/src/main/cpp/pk_wide.c` (after `followEnd` ~line 2297), `app/src/main/cpp/pk_wide.h`, `native-capture/mgba_dump.c`, `app/src/main/cpp/pokedaisy_jni.c`, `app/src/main/kotlin/com/pokedaisy/app/MgbaCore.kt`
- Create: `native-capture/coop/partner_self.txt`

**Interfaces:**
- Consumes: pk_wide.c's `playerObject()`, `mem()`, the field table's SaveBlock1 pointer (as `takeGhosts` reads it) and `playerAvatar()`.
- Produces: `pk_partner_self` (shared interfaces), `MgbaCore.pkPartnerSelf()`, mgba_dump `partnerself` (prints `partnerself: valid V map G.N fine X,Y facing F mode M step S`).

Values: map from SaveBlock1 `location` (+4 group, +5 num, the bytes Telemetry reads); tile and facing from the player's object event (`currentCoords` +0x10, `previousCoords` +0x14, facing = +0x18 low nibble, Telemetry's `OBJECT_EVENT_FACING_OFF`); fine = previous x 16 + (current - previous) x progress, progress = BG2 scroll moved since `currentCoords` last changed, capped at 16 (FOLLOWER's measure, its own static state); mode from gPlayerAvatar flags: SURFING (bit 3) → SURF, MACH_BIKE (bit 1) → MACH, ACRO_BIKE (bit 2) → ACRO, DASH (bit 7) → RUN, else WALK; step +1 whenever `currentCoords` changes by one tile. valid = 0 unless the field table knows the ROM and the player object exists.

- [ ] **Step 1: Write the failing test `partner_self.txt`**

```
# want: partnerself: valid 1
wideon 0
partnerself
hold RIGHT 4
partnerself            # mid-step: fine x strictly between the old tile x16 and the new
wait 30
partnerself            # standing: fine x == (old tile + 1) x 16, facing 4, mode 0, step = previous + 1
hold B,RIGHT 20
partnerself            # mode 1 (running) while B is held and moving
```
Each line's values are checked with `# want:` lines written from the first run's standing values (Route 101's start tile, read once with `partnerself`, then pinned in the file).

- [ ] **Step 2: Run it** → Expected: FAIL, `unknown command partnerself`.

Run: `EMERALD_ROM=D:/Code/pokedaisy/roms/emerald.gba EMERALD_SAVE=D:/Code/pokedaisy/roms/emerald.sav scripts/coop_headless_check.sh partner_self`

- [ ] **Step 3: Implement `pk_partner_self`, the mgba_dump command and the JNI wrapper** (returns null off a GBA core or when `pk_partner_self` returns 0).

- [ ] **Step 4: Run it** → PASS. Then pin the bike / surf modes: poke gPlayerAvatar's flags to MACH_BIKE (`poke8` at the address `fieldinfo` reports bike from) and `partnerself` → mode 2; SURFING → mode 4. Add both to the file, rerun → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/pk_wide.c app/src/main/cpp/pk_wide.h app/src/main/cpp/pokedaisy_jni.c app/src/main/kotlin/com/pokedaisy/app/MgbaCore.kt native-capture/mgba_dump.c native-capture/coop/partner_self.txt
git commit -m "Co-op: the player's fine position, facing and movement read each frame"
```

### Task 5: The partner as a hardware sprite

**Files:**
- Modify: `app/src/main/cpp/pk_wide.c` (FOLLOWER block ~lines 789-870, `followWrote`, `hookDrawScanline`), `app/src/main/cpp/pk_wide.h`, `native-capture/mgba_dump.c`, `app/src/main/cpp/pokedaisy_jni.c`, `app/src/main/kotlin/com/pokedaisy/app/MgbaCore.kt`
- Create: `native-capture/coop/partner_draw.txt`, `partner_clean.txt`, `partner_follower.txt`, `partner_offscreen.txt`, `partner_surf.txt`, `partner_badframe.txt`

**Interfaces:**
- Consumes: Task 4's local fine position (the partner's screen place = the player's sprite box centre / feet, as `followBegin` finds them, + (partner fine - local fine)).
- Produces: `pk_partner_set`, `pk_partner_place`, `pk_partner_info` (prints `why W oam O blobOam B slot S at X,Y frame F`), JNI `pkPartnerSet` / `pkPartnerPlace`; mgba_dump `partnerset FILE` (21536 bytes), `partnerpos VIS FX FY FRAME FLIP BLOB BOB`, `partnernear DX DY FRAME FLIP BLOB` (each frame: the local fine position + DX, DY tiles x 16, so it stays put relative to the player), `partnerinfo`, `memhash NAME` (CRC32 of EWRAM, IWRAM, VRAM, OAM, palette RAM; prints `memhash NAME XXXXXXXX`), `expectsame A B` (`PASS` / `FAIL memhash A != B`).

`partnerBegin` runs at line 0 right after `followBegin`; `partnerEnd` after line 159 right before `followEnd`. Placement: rider at OBJ tiles 976-991, blob at 992-1007 (both 32x32, 4bpp, one palette slot); OAM entries = hidden entries after the game's last shown one, searched down from 127, skipping FOLLOWER's (rider at the lower index, so above the blob); palette slot = the highest no shown sprite uses, not FOLLOWER's; priority = the player's. Skipped for the frame (and `why` set) when: art missing, frame or blob index outside 0..41 (`BAD_FRAME`), the box isn't inside x -31..239 / y -31..159 (`OFFSCREEN`: never relies on OAM's wrap), any shown OAM entry uses a tile in 976-1007 (`TILES_USED`), no free entry (`NO_ENTRY`) or slot (`NO_SLOT`). `followWrote`'s dirty tracking covers 976-1007 too. WIDE's per-line OAM record leaves the partner's entries out, as FOLLOWER's.

- [ ] **Step 1: Write the failing tests**

`partner_draw.txt`: `wideon 0`, `partnerset /repo/app/build/partner/brendan.bin`, `savestate /work/a.ss`, `partnernear 1 0 0 0 -1`, `wait 2`, `partnerinfo` (`# want: why 0`), `shot /work/with.ppm`; `loadstate /work/a.ss`, `partnerpos 0 0 0 0 0 -1 0`, `wait 2`, `shot /work/without.ppm`; then `framediff /work/with.ppm /work/without.ppm` → prints the bounding box of differing pixels (`# want: framediff box` with a box 1 tile right of the player's sprite: x within 128..160, y within 56..88 on Route 101's centred camera).
`partner_clean.txt` (Review Focus 5): from `a.ss`, partner shown 120 frames walking (`hold RIGHT 64`), `memhash with`; `loadstate`, partner off, the same presses, `memhash without`; `expectsame with without`. Also `savestate /work/b.ss` taken mid-run with the partner on, loaded with it off, one frame run, `memhash` equal to the run that never had it.
`partner_follower.txt`: `follower` + `followon 1` with a FOLLOWER frame file in the README's 3104-byte format (`native-capture/coop/data/solid_follower.bin`, committed: 3072 bytes of 0x11, i.e. colour index 1 everywhere, then a 32-byte palette of one made-up colour; no game art), then the partner: `followinfo` and `partnerinfo` both drawn, OAM entries differ, slots differ.
`partner_offscreen.txt` (Review Focus 3): `partnernear 9 0 ...` → `# want: why 4`; `partnernear 0 7 ...` → `why 4`; and no pixel changed (`framediff` → `framediff none`).
`partner_surf.txt`: `partnernear 1 0 36 0 39` → `why 0` with `blobOam` >= 0 and != `oam`.
`partner_badframe.txt` (Review Focus 4): `partnerpos 1 <fine> 42 0 -1 0` → `why 8`; `blob 42` → `why 8`.

Add `framediff A B` to mgba_dump in this task (prints `framediff box X0,Y0-X1,Y1` or `framediff none`).

- [ ] **Step 2: Run them** → Expected: FAIL, `unknown command partnerset`.

Run: `scripts/coop_headless_check.sh partner_draw partner_clean partner_follower partner_offscreen partner_surf partner_badframe` (with the env from Task 4).

- [ ] **Step 3: Implement** `partnerBegin` / `partnerEnd` and the API in pk_wide.c (the palette written through `writePalette` tinted when `tintShown`, like FOLLOWER), `pk_partner_place` also installs the scanline hook (`hookScanlines`), the mgba_dump commands, the JNI wrappers (`pkPartnerSet` refuses any length but 21536).

- [ ] **Step 4: Run them** → PASS. Look at `with.ppm` (convert with any viewer): Brendan standing one tile right of the player, facing south.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/pk_wide.c app/src/main/cpp/pk_wide.h app/src/main/cpp/pokedaisy_jni.c app/src/main/kotlin/com/pokedaisy/app/MgbaCore.kt native-capture/mgba_dump.c native-capture/coop
git commit -m "Co-op: the partner drawn as hardware sprites beside FOLLOWER, the game's own bytes put back"
```

### Task 6: Fades and DAY AND NIGHT

**Files:**
- Modify: `scripts/gen_coop_emerald.py`, `app/src/main/cpp/pk_coop_emerald.h` (regenerated), `app/src/main/cpp/pk_wide.c`
- Create: `native-capture/coop/partner_fade.txt`

**Interfaces:**
- Produces: in `pk_coop_emerald.h`, `COOP_EM_gPaletteFade` and the masks / shifts of its `y`, `blendColor`, `active` bitfields and `multipurpose1` (selectedPalettes) offset: `COOP_EM_PALFADE_Y_OFF/_SHIFT/_MASK`, `_BLEND_*`, `_ACTIVE_*`, `COOP_EM_PALFADE_SELECTED_OFF` (bitfields have no `offsetof`: the generator compiles an agbcc probe that sets each field to all ones in a zeroed struct and prints the bytes).

While `active` and the player's OBJ palette's bit (16 + slot of the player's sprite) is in selectedPalettes, the partner's colours are blended the game's way (`c + ((blendColor - c) * y >> 4)` per channel) before `writePalette`; DAY AND NIGHT's tint applies after, as for FOLLOWER. A screen fade thus takes the partner with it instead of leaving them bright on black.

- [ ] **Step 1: Write the failing test `partner_fade.txt`**: partner one tile right, then start a fade to black through ScriptContext_SetupScript (`fadescreen 1` + `end`, the method CLAUDE.md's headless notes give), `wait 8` (mid-fade), `partnerinfo` prints the blended first colour (`pal1 XXXX`, added to `pk_partner_info`); after the fade completes `pal1 0000`. Expected values: from the art's palette index 1 and the game's formula at the `y` `peek`ed that frame (pinned in the file after one run is checked by hand against the player's own palette slot in `vdump`'s pal.bin: the partner's index 1 must equal the player's own palette's index 1 blended the same way when the partner is Brendan and the player is Brendan).

- [ ] **Step 2: Run it** → Expected: FAIL (pal1 unchanged mid-fade).

- [ ] **Step 3: Regenerate the header (Docker, as Task 3) and implement the blend.**

- [ ] **Step 4: Run it, plus `partner_draw`** → PASS. Then `tint 128 128 256` with the partner shown: `partnerinfo`'s pal1 is the tinted colour (add that line to `partner_fade.txt`) → PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/gen_coop_emerald.py app/src/main/cpp/pk_coop_emerald.h app/src/main/cpp/pk_wide.c native-capture/coop/partner_fade.txt
git commit -m "Co-op: the partner fades with the screen and takes DAY AND NIGHT's tint"
```

### Task 7: The partner in EXPAND's margins

**Files:**
- Modify: `app/src/main/cpp/pk_wide.c` (`drawRegion` ~line 1371, after `drawGhosts`)
- Create: `native-capture/coop/partner_wide.txt`

**Interfaces:**
- Consumes: the partner's screen position, frames, palette and priority as `partnerBegin` computed them for this frame (kept even when the box was `OFFSCREEN` for the hardware: store the unclipped position before the bounds check).
- Produces: `static void drawPartner(int x0, int y0, int x1, int y1, uint32_t* dst, int stride, uint32_t alpha)`.

Like `drawGhosts`: per pixel, skip `takenBuf` pixels, honour OBJ enable per line and `windowLayers`, the player's priority against `prioBuf`, `brightness` in the window's effect area; blob first, rider over it; colours through `color()` (so DAY AND NIGHT matches the middle). It draws over the real frame's area too when the region isn't skipping the frame (the middle rebuild `widecheck` compares), so the rebuild still equals the real frame, which holds the hardware partner. It doesn't draw while `followBegin`'s conditions for the field fail (no player object) or the art is missing.

- [ ] **Step 1: Write the failing test `partner_wide.txt`**

```
# want: widecheck: 0 frames differ
wideon 40
widecheck
partnerset /repo/app/build/partner/brendan.bin
partnernear 1 0 0 0 -1
wait 60
wideinfo
partnernear 9 0 0 0 -1      # past the right edge: hardware why 4, the margin shows it
wait 2
wideshot /work/wide_with.ppm
```
and the same from a `savestate` with the partner off → `wideshot /work/wide_without.ppm`; `framediff` → a box in x 240..300 of the wide frame (the margin) (`# want:` with that range), and the `widecheck` summary line exact over all 60 frames with the partner in the middle.

- [ ] **Step 2: Run it** → Expected: FAIL (`widecheck` reports differing frames: the rebuild lacks the partner; and `framediff none` past the edge).

Run: `scripts/coop_headless_check.sh partner_wide`

- [ ] **Step 3: Implement `drawPartner`** and call it from `drawRegion` after `drawGhosts`.

- [ ] **Step 4: Run it, and rerun every `partner_*`** → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/pk_wide.c native-capture/coop/partner_wide.txt
git commit -m "Co-op: the partner shows in EXPAND's margins, the middle still exact"
```

### Task 8: Wiring it into the game

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/CoopPresence.kt`
- Modify: `app/src/main/kotlin/com/pokedaisy/app/EmulatorEngine.kt` (beside `stepFollower`, ~line 1045), `app/src/main/kotlin/com/pokedaisy/app/PokeDaisyActivity.kt` (where `applyFollower` sets the engine up, ~line 856)
- Test: `app/src/test/kotlin/com/pokedaisy/app/CoopPresenceTest.kt`

**Interfaces:**
- Consumes: Tasks 1-5.
- Produces:
```kotlin
interface PartnerCore { fun self(): IntArray?; fun setArt(art: ByteArray?); fun place(p: PartnerPlacement?) }   // MgbaCore in the app, a fake in tests
class CoopPresence(private val core: PartnerCore, private val clock: () -> Long, private val art: (female: Boolean, look: LookChar?) -> ByteArray?) {
    @Volatile var link: CoopPresenceLink? = null          // set by Plan 2's session; null = no partner, nothing drawn
    @Volatile var myLook: LookChar? = null                // PlayerLook.worn for this ROM, null = own hero
    @Volatile var myFemale: Boolean = false
    fun onReceived(bytes: ByteArray)                      // decode, push to the smoother (called from the link's Flow collector)
    fun onFrame(field: Boolean)                           // emu thread, every GBA frame
    fun stop()                                            // link gone / game closed: place(null), setArt(null), smoother reset
}
```

`onFrame`: if `clock()` passed 100 ms since the last send (wall clock, Review Focus 1) or the local map changed, read `core.self()` and `link.send(PresenceCodec.encode(...))` (valid = self non-null and `field`); then `place(smoother.at(clock(), localGroup, localNum))` when `field`, else `place(null)`. When the partner's `(female, lookId)` changes, `setArt(art(female, PresenceLook.charOf(lookId)))` once (built off the emu thread in the app: the `art` lambda posts `PartnerArt.build` to a background thread and hands the result in on the next frame). The app's `PartnerCore` maps `place(null)` to `pkPartnerPlace(false, ...)`. `EmulatorEngine` calls `onFrame` with the same field condition `stepFollower` gets, minus its not-surfing clause. `PokeDaisyActivity` creates one `CoopPresence` per loaded ROM only when the ROM's SHA-1 is retail Emerald's, collects `link.received` on a coroutine scope tied to the activity, and keeps `myLook` / `myFemale` from `PlayerLookRunner` (`worn`, `female`).

- [ ] **Step 1: Write the failing tests** (two `CoopPresence` over an in-memory link pair, fake cores, a fake clock)

```kotlin
@Test fun sendsTenTimesASecond() { /* 60 onFrame calls over 1000 ms of clock -> 10 sends (+1 on the first frame) */ }
@Test fun partnerWalksOnMyScreen() { /* A's self moves fineX 160 -> 176 over 266 ms; B (same map) gets place() with fineX rising monotonically to 176 */ }
@Test fun otherMapHides() { /* A on map (0,16), B on (0,17): B's last place == null */ }
@Test fun offFieldSendsInvalidAndPartnerStillPlaced() { /* A in a battle (field false): B shows A standing at A's last place; A's own place == null */ }
@Test fun unknownLookAsksForOwnHero() { /* A sends lookId 1 (RED); B's art lambda receives (female, RED); with the lambda returning null for RED, B asks again with (female, null) */ }
@Test fun linkGoneClears() { /* stop() -> place(null) and setArt(null) */ }
```

- [ ] **Step 2: Run them** → Expected: FAIL, unresolved `CoopPresence`.

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests '*CoopPresenceTest'`

- [ ] **Step 3: Implement `CoopPresence`, the engine call and the activity wiring.**

- [ ] **Step 4: Run them** → PASS; `./gradlew --no-daemon :app:testDebugUnitTest` → no failures beyond the 6 baseline ones; `./gradlew --no-daemon :app:assembleDebug` builds.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pokedaisy/app/CoopPresence.kt app/src/main/kotlin/com/pokedaisy/app/EmulatorEngine.kt app/src/main/kotlin/com/pokedaisy/app/PokeDaisyActivity.kt app/src/test/kotlin/com/pokedaisy/app/CoopPresenceTest.kt
git commit -m "Co-op: presence sent and the partner placed on every field frame"
```

### Task 9: Documentation

**Files:**
- Modify: `native-capture/README.md` (commands table), `docs/DEVELOPMENT.md` (new "Co-op: seeing each other" section after "FOLLOWER"), the co-op page Plan 2 adds under `docs/manual/`

- [ ] **Step 1: README**: `partnerself`, `partnerset`, `partnerpos`, `partnernear`, `partnerinfo` (its `why` codes), `framediff`, `memhash`, `expectsame`, and how `app/build/partner/*.bin` are made (`PartnerArtTest` with `-DromDir`).

- [ ] **Step 2: DEVELOPMENT.md**: the wire format, the look ids, the smoothing rules (150 ms behind, wall-clock timed, 3 s stale), the tile / OAM / palette choices and why (beside FOLLOWER), fades, the margins pass and the `widecheck` rule, what isn't done (connected maps, reflections, tall grass over the feet, NPC depth order north of the partner).

- [ ] **Step 3: Manual**: a "Seeing your partner" section on the co-op page in the manual's writing rules (second person, no dashes, no real Pokémon names): on the same map you see your partner walk, run, ride and surf; you walk through each other; they look like their own hero, or the game's own boy / girl hero when your device doesn't have their look. Run `make docs-check` → Expected: passes (no dash, links fine). If Plan 2's page isn't merged, this step waits for it.

- [ ] **Step 4: Commit**

```bash
git add native-capture/README.md docs/DEVELOPMENT.md docs/manual
git commit -m "Co-op: seeing each other, documented"
```

---

## Self-review notes

- Spec section 2: same-map walking / running / biking / surfing (Tasks 2, 4, 5), facing (2, 4), walk-through (nothing in RAM: 5's `partner_clean`), own look or Brendan / May (3, 8), FOLLOWER-style sprite with its own tiles / OAM / slot coexisting with FOLLOWER (5), EXPAND margins (7), ~10 updates a second (8), smoothing and warps / connections (2: other map hides, map change snaps; connected maps not shown, stated in Global Constraints).
- "My call on looks": only an id is sent (1), each device draws from its own art (3), fallback to Brendan / May (3, 8). The battle-time patch rules in that paragraph belong to Plan 4.
- Names cross-checked: `PresenceUpdate`, `PresenceCodec.encode/decode`, `PresenceSmoother.push/at`, `PartnerFrames.index/blob`, `PartnerArt.build`, `pk_partner_self/set/place/info`, `pkPartnerSelf/Set/Place`, `PartnerCore.self/setArt/place`.
