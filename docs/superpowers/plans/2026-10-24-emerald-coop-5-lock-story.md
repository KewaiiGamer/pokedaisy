# Emerald Co-op, Plan 5: The Lock and Story Events Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two paired players can turn on PLAY TOGETHER ONLY for two new Emerald saves; from then on a locked save only plays while the partner is connected, story events and story-important trainer battles wait until both players stand at the same spot and then start in both games at once, LOAD STATE / REWIND are off, and a co-op trainer win can be written into a save (beaten trainer, badge, defeated leader) and saved in game.

**Architecture:** A generator (`scripts/gen_coop_story.py`) reads pokeemerald's map data and scripts and writes the story table (coord triggers with a var condition, ON_FRAME map scripts, story trainers' and scripted wild encounters' object scripts) and the win table (trainer -> flags) into Kotlin and a C header. A native module `pk_coop_lock.c` (beside plan 1's `pk_coop.c`, never inside it) hooks `ScriptContext_SetupScript` with a small Thumb routine in its own ROM block: while the lock is active, a story script is refused and recorded in a RAM mailbox (a coord trigger also steps the player back one tile), and a per-frame tick clears the hold when the player walks away. Kotlin decides the rest: a pure protocol core (`companion/coop/lock/`) exchanges hold and lock messages (types 0x0300-0x03FF on plan 2's `CoopLink`), and when both players hold the same event it tells the native side to start it. `CoopGuards` is the one place the emulator loop asks "hold the game?" and "are state loads off?"; plan 4 uses it too.

**Tech Stack:** C (mGBA 0.10.5), Thumb assembly (arm-none-eabi-as in the `pokedaisy-capture` image), Python 3 generator (Docker), Kotlin + JUnit + kotlinx-coroutines, Compose (the OPTION look), mgba_dump headless scripts.

**Spec:** `docs/superpowers/specs/2026-10-24-emerald-coop-design.md` (section 5, section 3's "What a partner's win gives them" and "Ending", section 6's LOAD STATE / REWIND rule). This is plan 5 of 5: 1 battle engine, 2 connection and pairing, 3 seeing each other, 4 joined battles end to end, 5 the lock and story events.

**Depends on:** Plan 1 (this branch's base): `pk_cheats_block` / `pk_cheats_patch`, `scripts/coop_headless_check.sh`, mgba_dump's `expect*` / `waittask` / `call`, the RAM rule (co-op owns `0x0203F000`-`0x0203F3FF`). Plan 2 (`refs/heads/claude/emerald-coop-connection-f73ba5`): `CoopSession.link: CoopLink`, `CoopState`, `CoopSaveId`, `CoopPair`, `FeatureFlags.coop`, the CO-OP group (`CoopUi.kt`), `NewFeatures.COOP`, `FLAGGED_SETTING_KEYWORDS`, the manual draft `docs/manual-drafts/coop.md`, `TrCoop.kt`. Plan 3 (`refs/heads/claude/emerald-coop-presence-929d8d`): the per-frame co-op hook in `PokeDaisyActivity` (`attachCoopLink`, `CoopPresence.onFrame`). Tasks 1-6 need none of plans 2-4 and run on this branch; Tasks 7-8 need an integration branch holding plans 2-4 (asked of the parent session before they start).

**Assumed plan-4 interfaces (plan 4 implements them; until it does, the defaults below hold):**
- `CoopGuards.battleActive: () -> Boolean` (default `{ false }`): true from a co-op battle's start until its results are saved. Plan 5 keeps the story hook off and leaves link drops to plan 4 while it is true.
- `CoopGuards.hold(CoopGuards.Hold.BATTLE, on)` / `blockStates(CoopGuards.StateBlock.BATTLE, on)`: plan 4's own game holds (join prompt, partner's game during the battle) and its LOAD STATE / REWIND block go through the same guards, not a second mechanism.
- `CoopStoryBattle.joinable(event: StoryEvent): Boolean` (`CoopStory.storyBattle`, default `NONE` = false): plan 4 answers whether a held story battle can become a co-op battle (single-trainer battles can't yet: plan 1 refuses them). False = each player fights it in their own game after the shared hold.
- Plan 4 calls `CoopWins.apply(core, trainerA, trainerB)` then `CoopAutoSave.save(core, saveFile)` on both devices after a won co-op trainer battle (the host for its own game, the partner on receiving the result). Plan 5 provides both; it does not decide when a battle was won.

## Global Constraints

- Game: retail English Emerald only, BPEE, SHA-1 `f3ae088181bf583e55daf962a92bb46f4f1d07b7`. Every hooked function's first 32 bytes are CRC-checked before anything is written; any mismatch installs nothing.
- Save files are sacred: nothing in this plan writes the `.sav` file directly. Flags go into the game's RAM, then the game's own save routine (`TrySavingData`) writes them, after `SaveBackups.snapshot(saveFile)` returned a backup. No backup = no save.
- ROM patches only through `pk_cheats_patch` / `pk_cheats_block` (the Poller's and RetroAchievements' hashes see the file). The lock's code block is `0x08FFE000`-`0x08FFEFFF` (the file must hold 0xFF there; plan 1's is `0x08FFF000`); an overlapping block refuses the install.
- RAM: the lock owns `0x0203F400`-`0x0203F4FF` (EWRAM the game never uses; plan 1 owns `0x0203F000`-`0x0203F3FF`, FieldItems `0x0203FFF0`-`0x0203FFFF`).
- Message types `0x0300`-`0x03FF` only (plan 2's `CoopWire` range for plan 5). Every payload byte 0 is `LOCK_WIRE_VERSION = 1`; a wrong version, length or unknown story id is dropped whole.
- A held player is never stuck: stepping away (or changing map) clears a hold, closing the game always works, SAVE STATE and the companion work while held.
- Everything player-facing is behind `FeatureFlags.coop` (off by default): keyword lines go into `FLAGGED_SETTING_KEYWORDS`, the NEW tag is `NewFeatures.badge(NewFeatures.COOP)`, the manual text goes into `docs/manual-drafts/coop.md` (plan 2's draft), not `docs/manual/`. Manual writing rules hold there too: second person, no em / en dashes or spaced hyphen, no real Pokémon / move / item names.
- New UI text: wrapped in `tr()` with all seven translations (JA / FR / DE / IT / ES / BR / PT) in `i18n/TrCoopLock.kt`, OPTION look pieces, `soundClickable`, pixel shapes.
- Python runs in Docker: `MSYS_NO_PATHCONV=1 docker run --rm -v "D:/Code/pokedaisy/decomps:/decomps" -v "<worktree>:/repo" pokedaisy-decomp -c "python3 /repo/scripts/gen_coop_story.py /decomps/pokeemerald"` (the pinned pokeemerald is built: `pokeemerald.elf`, `.map`). Never run an interpreter reading stdin without input, never `find /` or any drive-wide search.
- Headless runs: `EMERALD_ROM=/d/Code/pokedaisy/roms/emerald.gba EMERALD_SAVE=/d/Code/pokedaisy/roms/emerald.sav COOP_WORK=/d/Code/pokedaisy/roms/work/coop-p5-<task> bash scripts/coop_headless_check.sh lock_*` (each task its own work dir).
- JVM: `./gradlew --no-daemon :app:testDebugUnitTest --tests '<pattern>'` (only the task's tests; never the whole suite in parallel waves). Baseline: 6 tests already fail on this Windows checkout (PerformanceGuardsTest, SiteDataExportTest, RomAddedTest.keptAcrossLaunches, SaveFileEditsTest.aTradeWritesBothOrNeither, BestEffortTest x2). C: is nearly full: check `df -h /c` (over 10 GB free) before a Gradle build.
- Never commit `third_party/melonds` (it shows modified after a build), ROMs, saves or work dirs. Commits: plain sentence subjects with a `Co-op:` prefix, no Claude / Anthropic attribution (the `commit-msg` hook rejects it; never bypass it).

## Review Focus

- Both players held at different events, or one held while the other wanders off: each can walk away, the hold clears, nothing freezes. Test in Task 5 (`lock_cancel_distance`, `lock_cancel_map`) and Task 3 (`staleHoldIsForgotten`).
- A partner message for an unknown story id, a wrong length, an old version, or a hold that arrives after the partner already cancelled: dropped, or it never starts an event alone. Tests in Task 3 (`refusesUnknownId`, `goOnlyWhenBothHoldTheSame`).
- A one-second link hiccup on a locked save: no "waiting" flash; a longer drop holds the game, the reconnect lets it go, and closing the game works meanwhile. Tests in Task 3 (`shortDropDoesNotHold`) and Task 4 (`stopWorksWhileHeld`).
- LOAD STATE from every way in (hotkey, QUICK MENU, touch button, UNDO LOAD, REWIND hold) while states are blocked: refused and reported, SAVE STATE still works, the launch's auto-resume still loads. Tests in Task 4.
- A win naming trainer 0, an id past the last trainer, or a flag outside the allowlist; an auto-save asked while a script runs or a menu is up: nothing written, the caller told to retry (auto-save) or refused (wins). Tests in Task 6.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopStory.kt` (create, Task 1) | `StoryKind`, `StoryEvent`, `TrainerWin`, `CoopStory` lookups, `CoopStoryBattle` seam. |
| `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopStoryGen.kt` (create: placeholder Task 1, generated Task 2) | `COOP_STORY_EVENTS`, `COOP_TRAINER_WINS`, ROM / RAM constants Kotlin needs. |
| `app/src/main/kotlin/com/pokedaisy/app/CoopGuards.kt` (create, Task 1) | Game holds and state-load blocks by reason; `battleActive` seam. |
| `app/src/main/kotlin/com/pokedaisy/app/companion/coop/lock/CoopLockModel.kt` (create, Task 1) | `CoopLockUi`, `LockRefusal`, `LockWait`, `CoopLockActions`. |
| `scripts/gen_coop_story.py`, `app/src/main/cpp/pk_coop_lock_emerald.h` (create, Task 2) | Generator; header with addresses, CRCs, the story table as C data. |
| `app/src/main/kotlin/com/pokedaisy/app/companion/coop/lock/{CoopLockWire,CoopStoryHold,CoopLockRules,CoopLockStore}.kt` (create, Task 3) | Wire format, hold protocol, eligibility / hold rules, the locks file. |
| `app/src/main/kotlin/com/pokedaisy/app/EmulatorEngine.kt` (modify, Task 4) | Loop hold, load / rewind refusal. |
| `scripts/coop_lock_emerald.s`, `app/src/main/cpp/pk_coop_lock.{c,h}`, `app/src/main/cpp/pk_coop_lock_jni.c`, `app/src/main/kotlin/com/pokedaisy/app/CoopLockNative.kt` (create, Task 5) | The hook, its mailbox, tick, go / cancel; JNI. |
| `app/src/main/cpp/CMakeLists.txt`, `app/src/main/cpp/pokedaisy_jni.c` (modify, Task 5: one source line, one tick call) | Build it, tick it after each main-core frame. |
| `native-capture/mgba_dump.c`, `native-capture/coop/lock_*.txt`, `native-capture/README.md` (modify / create, Tasks 5-6) | `lock*` commands, `expectsaveflag`, headless tests. |
| `app/src/main/kotlin/com/pokedaisy/app/companion/coop/lock/{CoopWins,CoopAutoSave}.kt` (create, Task 6) | Allowlisted flag writes; backup then the game's own save. |
| `app/src/main/kotlin/com/pokedaisy/app/CoopLock.kt` (create, Task 7; integration branch) | Glue: link messages, store, guards, native hold, per frame. |
| `PokeDaisyActivity.kt`, `companion/ui/CoopUi.kt`, `SettingsActivity` / `CompanionSettingsScreen.kt`, `companion/ui/CoopLockUi.kt`, `i18n/TrCoopLock.kt`, `i18n/Tables.kt`, `SettingRows.kt`, `docs/manual-drafts/coop.md` (Tasks 7-8) | Wiring, the PLAY TOGETHER ONLY row, the waiting overlay, words, manual. |
| `docs/DEVELOPMENT.md` (modify, Tasks 2, 5, 7) | A "Co-op lock and story events (Emerald)" section after "Co-op battle engine (Emerald)". |

## Shared interfaces (every task uses these names)

```kotlin
// CoopStory.kt (package com.pokedaisy.app.companion.data.coop)
enum class StoryKind { COORD, OBJECT, FRAME }
data class StoryEvent(
    val id: Int,            // 1..n, stable within one generated table (0 = none on the wire)
    val kind: StoryKind,
    val mapGroup: Int, val mapNum: Int,
    val x: Int, val y: Int, // COORD: the trigger tile; OBJECT: the object's tile; FRAME: -1, -1 (the whole map)
    val script: Long,       // ROM address of the script ScriptContext_SetupScript receives
    val label: String,      // the script's pokeemerald label, for logs / tests only (never shown)
    val trainers: List<Int>,// story trainer ids its script battles (empty for a scene)
)
data class TrainerWin(val trainerId: Int, val flags: List<Int>)  // flags beyond the trainer's own flag (badge, FLAG_DEFEATED_*)
fun interface CoopStoryBattle { fun joinable(event: StoryEvent): Boolean; companion object { val NONE = CoopStoryBattle { false } } }
object CoopStory {
    @Volatile var storyBattle: CoopStoryBattle = CoopStoryBattle.NONE   // plan 4 sets it
    fun byId(id: Int): StoryEvent?
    fun winFlags(trainerId: Int): List<Int>   // the trainer's own flag (TRAINER_FLAGS_START + id) + its TrainerWin flags; empty = not allowed
}
// CoopStoryGen.kt (generated): val COOP_STORY_EVENTS: List<StoryEvent>; val COOP_TRAINER_WINS: List<TrainerWin>;
// const val COOP_TRAINERS_COUNT: Int; const val COOP_TRAINER_FLAGS_START = 0x500; const val COOP_SAVEBLOCK1_PTR: Long (0x03005D8C);
// const val COOP_SB1_FLAGS_OFF = 0x1270; const val COOP_PLAYER_PARTY_COUNT = 0x020244E9L; const val COOP_TRY_SAVING_DATA: Long;
// const val COOP_SAVE_STATUS_OK = 1

// CoopGuards.kt (package com.pokedaisy.app)
object CoopGuards {
    enum class Hold { LOCK_START, LOCK_LINK, BATTLE }
    enum class StateBlock { LOCK, BATTLE }
    fun hold(reason: Hold, on: Boolean); val held: Boolean              // any reason on
    fun blockStates(reason: StateBlock, on: Boolean); val statesBlocked: Boolean
    @Volatile var battleActive: () -> Boolean = { false }               // plan 4 sets
    fun reset()                                                          // all off (game closed)
}

// CoopLockModel.kt (package com.pokedaisy.app.companion.coop.lock)
enum class LockRefusal { NOT_PAIRED, NOT_CONNECTED, OWN_NOT_NEW, PARTNER_NOT_NEW }
sealed interface LockWait { data object Partner : LockWait; data class AtStory(val eventId: Int) : LockWait }
data class CoopLockUi(
    val locked: Boolean = false,
    val refusal: LockRefusal? = LockRefusal.NOT_PAIRED, // why it can't be turned on now (null = it can)
    val partnerProposed: Boolean = false,               // the partner asked; show ACCEPT / NO
    val proposalSent: Boolean = false,                  // we asked; waiting for their answer
    val waiting: LockWait? = null,                      // the overlay over the game
)
interface CoopLockActions { fun propose(); fun accept(); fun decline(); fun turnOff() }
```

Wire (`CoopLockWire`, little-endian, byte 0 = version 1):
| Type | Name | Payload |
|---|---|---|
| `0x0301` | LOCK_PROPOSE | `[1, partyCount u8]` (2 bytes) |
| `0x0302` | LOCK_ANSWER | `[1, accepted 0/1, partyCount u8]` (3) |
| `0x0303` | LOCK_OFF | `[1]` (1) |
| `0x0304` | LOCK_STATUS | `[1, locked 0/1]` (2), sent on every Connected |
| `0x0310` | STORY_HOLD | `[1, id u16]` (3), 0 = holding nothing; sent on change and every 1000 ms while non-zero |

Lock RAM mailbox (`0x0203F400`, `struct pk_coop_lock_mailbox`): `+0 u8 active`, `+1 u8 heldKind` (0 COORD, 1 OBJECT, 2 FRAME), `+2 u16 heldId` (0 = none), `+4 u16 allowId`, `+6 u8 heldMapGroup`, `+7 u8 heldMapNum`, `+8 s16 heldX`, `+10 s16 heldY` (the player's tile when the hold began, after any step back), `+12 u8 heldFacing`, `+13 u8 selectedObjectEvent`, `+14 u16 lastTalked`, `+0x20` 32 bytes: the re-entry script built by `pk_coop_lock_go`.

```c
// pk_coop_lock.h
#define PK_COOP_LOCK_MAILBOX 0x0203F400u
int  pk_coop_lock_install(struct mCore* core);     // 1 ok; 0 CRC mismatch / overlapping block / not BPEE (nothing written)
void pk_coop_lock_uninstall(struct mCore* core);   // patches, block and 0x0203F400-0x0203F4FF gone
void pk_coop_lock_set_active(struct mCore* core, int on);
void pk_coop_lock_tick(struct mCore* core);        // after every main-core frame: clears a hold the player walked away from
int  pk_coop_lock_held(struct mCore* core, int* kind);   // held event id, 0 = none
int  pk_coop_lock_go(struct mCore* core, int id);  // 1 started / allowed, 0 refused (not held / not this id), -1 not now (a script runs, controls locked, no pk_call-safe frame)
void pk_coop_lock_cancel(struct mCore* core);
```
```kotlin
// CoopLockNative.kt (package com.pokedaisy.app): object CoopLockNative { external fun install(): Int; external fun uninstall();
//   external fun setActive(on: Boolean); external fun held(): IntArray /* [id, kind] */; external fun go(id: Int): Int; external fun cancel() }
```

---

### Task 1: Shared types and guards

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopStory.kt`, `.../data/coop/CoopStoryGen.kt` (placeholder: empty lists, constants as above with `COOP_TRAINERS_COUNT = 855`, `COOP_TRY_SAVING_DATA = 0x08153338L`), `app/src/main/kotlin/com/pokedaisy/app/CoopGuards.kt`, `.../companion/coop/lock/CoopLockModel.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/CoopGuardsTest.kt`, `.../companion/data/coop/CoopStoryTest.kt`

**Interfaces:** Produces everything in "Shared interfaces" above (Kotlin). Every later task consumes it.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun heldWhileAnyReason() { CoopGuards.reset(); CoopGuards.hold(Hold.LOCK_LINK, true); CoopGuards.hold(Hold.BATTLE, true)
    CoopGuards.hold(Hold.LOCK_LINK, false); assertTrue(CoopGuards.held); CoopGuards.hold(Hold.BATTLE, false); assertFalse(CoopGuards.held) }
@Test fun statesBlockedWhileAnyReason() { /* same shape with StateBlock.LOCK / BATTLE */ }
@Test fun resetClearsAll() { /* both on, reset(), both false; battleActive left as set */ }
@Test fun winFlagsAddTheTrainersOwnFlag() { // with a test-only table helper CoopStory.winFlagsFrom(listOf(TrainerWin(265, listOf(0x807, 0x4F0))), 265)
    assertEquals(listOf(0x500 + 265, 0x807, 0x4F0), CoopStory.winFlagsFrom(listOf(TrainerWin(265, listOf(0x807, 0x4F0))), 265))
    assertEquals(listOf(0x500 + 3), CoopStory.winFlagsFrom(emptyList(), 3)) }
@Test fun winFlagsRefuseOutOfRange() { assertEquals(emptyList<Int>(), CoopStory.winFlagsFrom(emptyList(), 0))
    assertEquals(emptyList<Int>(), CoopStory.winFlagsFrom(emptyList(), COOP_TRAINERS_COUNT)) }
```

- [ ] **Step 2: Run them** → FAIL (unresolved references).
- [ ] **Step 3: Implement** the four files. `CoopGuards` keeps two `EnumSet`s under one lock; `held` / `statesBlocked` are reads of `@Volatile` booleans updated inside it. `CoopStory.byId` indexes `COOP_STORY_EVENTS` by id (a map built once); `winFlags(id) = winFlagsFrom(COOP_TRAINER_WINS, id)`; valid ids are `1 until COOP_TRAINERS_COUNT`.
- [ ] **Step 4: Run them** → PASS.
- [ ] **Step 5: Commit** `Co-op: the lock's shared types, and one place for game holds and state-load blocks`.

---

### Task 2: The story and win tables, generated

**Files:**
- Create: `scripts/gen_coop_story.py`, `app/src/main/cpp/pk_coop_lock_emerald.h` (generated), overwrite `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopStoryGen.kt` (generated)
- Modify: `docs/DEVELOPMENT.md` (new section "Co-op lock and story events (Emerald)": the generator's rules, the report below)

**Interfaces:**
- Consumes: Task 1's `StoryEvent` / `TrainerWin` constructor shapes (the generator writes calls to them).
- Produces: `COOP_STORY_EVENTS`, `COOP_TRAINER_WINS` and the constants in "Shared interfaces"; in the C header: `COOP_LK_ScriptContext_SetupScript` + `COOP_LK_CRC_ScriptContext_SetupScript`, `COOP_LK_sGlobalScriptContext`, `COOP_LK_gObjectEvents`, `COOP_LK_gPlayerAvatar`, `COOP_LK_gSaveBlock1Ptr`, `COOP_LK_gSpecialVar_LastTalked`, `COOP_LK_gSelectedObjectEvent`, `COOP_LK_gSpecialVar_Facing`, `COOP_LK_ArePlayerFieldControlsLocked`, `COOP_LK_ScriptContext_IsEnabled`, struct offsets (`COOP_LK_OBJ_SIZE`, `_OBJ_CURRENT_X/Y`, `_OBJ_FACING`, `_AVATAR_OBJ_ID`, `_SB1_LOCATION_GROUP/NUM`), the script command bytes the re-entry / step-back scripts need (`applymovement`, `waitmovement`, `goto`, `releaseall`, `end`, the four `walk_*` movement bytes), and `static const struct { uint32_t script; uint16_t id; uint8_t kind, group, num; int16_t x, y; } COOP_LK_STORY[]` + `COOP_LK_STORY_COUNT`. Everything `static const` or `#define` (the header may be included twice).

Rules (each a function in the script; record each in DEVELOPMENT.md):
- COORD: every `coord_events` entry of `type: trigger` in `data/maps/*/map.json` whose `var` is not `VAR_TEMP_*` and not `0`.
- FRAME: every `map_script_2` entry of each map's `MAP_SCRIPT_ON_FRAME_TABLE` whose var is not `VAR_TEMP_*`.
- OBJECT: every object event whose script (followed through `goto` / `call` to labels in the same map's `scripts.inc`) contains `trainerbattle*` naming a trainer of class `TRAINER_CLASS_LEADER`, `ELITE_FOUR`, `CHAMPION`, `AQUA_LEADER`, `AQUA_ADMIN`, `MAGMA_LEADER`, `MAGMA_ADMIN`, or a trainer named `TRAINER_BRENDAN_*`, `TRAINER_MAY_*`, `TRAINER_WALLY_*` (from `src/data/trainers.h`); or contains `setwildbattle` followed by `dowildbattle` (scripted wild battles). A story trainer whose object has a sight range (`trainer_type` not `TRAINER_TYPE_NONE`) and whose battle starts only by being seen is left out and listed in the report (my call: a sighted start goes through the generic approach script, which the hook can't tell apart).
- Duplicates (one script pointer twice) keep the first id; ids are assigned in sorted (map name, kind, x, y) order so a rerun gives the same table.
- Script addresses from the ELF's symbol table (labels with `::` are global); a label not found fails the generator.
- Wins: each story trainer's battle script's `setflag` of `FLAG_BADGE0n_GET` and `FLAG_DEFEATED_*` after its `trainerbattle` (the leader's post-battle script, followed through `goto` / `call`). Report each gym leader whose TM `giveitem` sits behind a check of its own trainer flag only (collectable by talking to them later) vs one that needs anything else (report it as "not collectable"); the report goes to stdout and into DEVELOPMENT.md.
- The ROM block check: the generator reads the built ROM and asserts `0x00FFE000`-`0x00FFEFFF` (file offset) is all 0xFF, and prints the CRC32 of `ScriptContext_SetupScript`'s first 32 bytes.

- [ ] **Step 1: Write the generator's own checks** (asserts at the end of `main`, failing the run): at least 8 COORD and 8 FRAME events; the eight gym leaders' trainer ids each in some OBJECT event's `trainers`; `TRAINER_ROXANNE_1`'s win flags = `[FLAG_BADGE01_GET, FLAG_DEFEATED_RUSTBORO_GYM]` in some order; Route 101's Birch scene (`Route101_EventScript_StartBirchRescue` or the label the decomp uses on that map's var trigger) present as COORD; no `VAR_TEMP_` event; every id unique and 1..n.
- [ ] **Step 2: Run it** (the Docker line in Global Constraints) → it fails until written; then write the generator until the checks pass. Expected output ends with the counts and the report.
- [ ] **Step 3: Commit** the script, both generated files and the DEVELOPMENT.md section: `Co-op: the story events and trainer wins, generated from pokeemerald`.

---

### Task 3: The lock's protocol core

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/coop/lock/CoopLockWire.kt`, `CoopStoryHold.kt`, `CoopLockRules.kt`, `CoopLockStore.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/coop/lock/{CoopLockWireTest,CoopStoryHoldTest,CoopLockRulesTest,CoopLockStoreTest}.kt`

**Interfaces:**
- Consumes: Task 1 (`CoopStory.byId`, `LockRefusal`). Nothing from plan 2 (plain `Boolean connected`, save keys as `String`).
- Produces:
```kotlin
sealed interface LockMsg { data class Propose(val partyCount: Int) : LockMsg; data class Answer(val accepted: Boolean, val partyCount: Int) : LockMsg
    data object Off : LockMsg; data class Status(val locked: Boolean) : LockMsg; data class Hold(val id: Int) : LockMsg }
object CoopLockWire { const val VERSION = 1; const val PROPOSE = 0x0301; const val ANSWER = 0x0302; const val OFF = 0x0303
    const val STATUS = 0x0304; const val HOLD = 0x0310
    fun encode(m: LockMsg): Pair<Int, ByteArray>; fun decode(type: Int, payload: ByteArray, knownId: (Int) -> Boolean = { CoopStory.byId(it) != null }): LockMsg? }
class CoopStoryHold(private val clock: () -> Long) {   // not thread-safe: one owner thread
    fun onPartner(id: Int)                  // a received Hold
    fun step(ownHeld: Int, connected: Boolean): Step   // once per frame
    data class Step(val send: Int?, val go: Int?)      // send: a Hold to send now (null = nothing); go: an id to start now
}
object CoopLockRules {
    const val LINK_GRACE_MS = 3000L
    fun refusal(paired: Boolean, connected: Boolean, ownParty: Int, partnerParty: Int?): LockRefusal?
    fun holdForLink(locked: Boolean, connected: Boolean, battleActive: Boolean, lostForMs: Long): Boolean
}
class CoopLockStore(private val file: File) { fun isLocked(own: String, partner: String): Boolean
    fun lock(own: String, partner: String); fun unlock(own: String) }   // file = filesDir/coop/locks.json
```

Rules: `CoopStoryHold` sends `Hold(own)` when `ownHeld` changes and every 1000 ms while it is non-zero; a partner hold older than 3000 ms (by `clock`) counts as 0; `go = ownHeld` once per hold instance when `connected && ownHeld != 0 && ownHeld == partner` (not again until `ownHeld` has been 0 or another id). `refusal`: NOT_PAIRED, NOT_CONNECTED, OWN_NOT_NEW (`ownParty != 0`), PARTNER_NOT_NEW (`partnerParty != 0`, null = unknown counts as not new), in that order. `holdForLink` = `locked && !battleActive && !connected && lostForMs >= LINK_GRACE_MS`. `CoopLockStore` = a JSON array of `{own, partner, since}` written to a temp file and renamed; an unreadable file is moved aside to `locks.json.bad` and reads as empty, like plan 2's `CoopPairs`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// CoopLockWireTest
@Test fun roundTripsEach() { listOf(LockMsg.Propose(0), LockMsg.Answer(true, 0), LockMsg.Off, LockMsg.Status(true), LockMsg.Hold(7))
    .forEach { m -> val (t, b) = CoopLockWire.encode(m); assertEquals(m, CoopLockWire.decode(t, b) { true }) } }
@Test fun refusesWrongVersionLengthOrType() { /* version 2, one byte short / long, type 0x0305: each null */ }
@Test fun refusesUnknownId() { val (t, b) = CoopLockWire.encode(LockMsg.Hold(9)); assertNull(CoopLockWire.decode(t, b) { it == 7 })
    assertEquals(LockMsg.Hold(0), CoopLockWire.decode(t, CoopLockWire.encode(LockMsg.Hold(0)).second) { false }) }
@Test fun refusesFlagsOutOfRange() { /* Answer accepted = 2, Status locked = 2: null */ }
// CoopStoryHoldTest (fake clock)
@Test fun goOnlyWhenBothHoldTheSame() { h.onPartner(5); assertNull(h.step(4, true).go); assertEquals(5, h.step(5, true).go); assertNull(h.step(5, true).go) }
@Test fun noGoWhileDisconnected() { h.onPartner(5); assertNull(h.step(5, false).go) }
@Test fun staleHoldIsForgotten() { h.onPartner(5); now += 3001; assertNull(h.step(5, true).go) }
@Test fun sendsOnChangeAndEverySecond() { assertEquals(5, h.step(5, true).send); assertNull(h.step(5, true).send); now += 1000
    assertEquals(5, h.step(5, true).send); assertEquals(0, h.step(0, true).send); now += 5000; assertNull(h.step(0, true).send) }
@Test fun goAgainAfterTheHoldClearedAndCameBack() { /* 5 -> go, 0, 5 again with partner 5 -> go again */ }
// CoopLockRulesTest
@Test fun refusalOrder() { assertEquals(NOT_PAIRED, refusal(false, false, 3, 3)); assertEquals(NOT_CONNECTED, refusal(true, false, 0, 0))
    assertEquals(OWN_NOT_NEW, refusal(true, true, 1, 0)); assertEquals(PARTNER_NOT_NEW, refusal(true, true, 0, null)); assertNull(refusal(true, true, 0, 0)) }
@Test fun shortDropDoesNotHold() { assertFalse(holdForLink(true, false, false, 2999)); assertTrue(holdForLink(true, false, false, 3000))
    assertFalse(holdForLink(true, false, true, 60_000)); assertFalse(holdForLink(false, false, false, 60_000)); assertFalse(holdForLink(true, true, false, 60_000)) }
// CoopLockStoreTest (TemporaryFolder)
@Test fun locksAPairAndSurvivesReload(); @Test fun unlockRemoves(); @Test fun unreadableFileIsMovedAsideAndReadsEmpty()
```

- [ ] **Step 2: Run them** → FAIL. **Step 3: Implement.** **Step 4: Run them** → PASS.
- [ ] **Step 5: Commit** `Co-op: the lock's messages, hold protocol, rules and store`.

---

### Task 4: Holds and state blocks in the emulator loop

**Files:**
- Modify: `app/src/main/kotlin/com/pokedaisy/app/EmulatorEngine.kt` (the loop's frame step near `wantPaused` ~line 1122, `requestLoadState` ~634, the UNDO LOAD path ~1540, `setRewindHeld` ~319)
- Test: `app/src/test/kotlin/com/pokedaisy/app/CoopGuardsEngineTest.kt`

**Interfaces:**
- Consumes: `CoopGuards` (Task 1).
- Produces: `EmulatorEngine.loadAllowed(): Boolean` (= `!CoopGuards.statesBlocked`), and these behaviours: while `CoopGuards.held`, the loop runs no game frame (audio paused as `wantPaused` does, the last picture stays), still serves pending SAVE STATE, suspend, sleep and stop; while `CoopGuards.statesBlocked`, `requestLoadState` and UNDO LOAD answer `onStateResult(action, slot, false)` at once without loading, and `setRewindHeld(true)` is ignored (a rewind already held is let go). The launch's auto-resume (the engine's resume-state load, not a slot load) is never blocked (my call: continuing where you stopped never goes back in time).

Pull the decisions into pure functions so they test without a core: `internal fun EmulatorEngine.Companion.shouldRunFrame(held: Boolean): Boolean`, `internal fun refuseLoad(statesBlocked: Boolean, isResume: Boolean): Boolean`, `internal fun rewindAllowed(statesBlocked: Boolean): Boolean`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun noFrameWhileHeld() { assertFalse(EmulatorEngine.shouldRunFrame(held = true)); assertTrue(EmulatorEngine.shouldRunFrame(false)) }
@Test fun slotLoadRefusedWhileBlocked() { assertTrue(EmulatorEngine.refuseLoad(statesBlocked = true, isResume = false)) }
@Test fun resumeLoadNeverRefused() { assertFalse(EmulatorEngine.refuseLoad(true, isResume = true)) }
@Test fun rewindOffWhileBlocked() { assertFalse(EmulatorEngine.rewindAllowed(true)); assertTrue(EmulatorEngine.rewindAllowed(false)) }
@Test fun stopWorksWhileHeld() { /* read the loop: a stop / suspend request is checked before the held branch. Assert through a small
   extracted `internal fun EmulatorEngine.Companion.loopAction(held, stopRequested, saveRequested): LoopAction` that STOP and SAVE_STATE win over HOLD */ }
```

- [ ] **Step 2: Run them** → FAIL. **Step 3: Implement** the functions and wire them into the loop at the named places (one `if` each; no restructuring of the loop).
- [ ] **Step 4: Run them** → PASS; then `./gradlew --no-daemon :app:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `Co-op: the emulator holds the game and refuses state loads when co-op says so`.

---

### Task 5: The story hook (native)

**Files:**
- Create: `scripts/coop_lock_emerald.s`, `app/src/main/cpp/pk_coop_lock.h`, `app/src/main/cpp/pk_coop_lock.c`, `app/src/main/cpp/pk_coop_lock_jni.c`, `app/src/main/kotlin/com/pokedaisy/app/CoopLockNative.kt`, `native-capture/coop/lock_{install,refused,hold_object,hold_frame,hold_coord_stepback,cancel_distance,cancel_map,go_object,go_coord,inactive,uninstall}.txt`
- Modify: `scripts/gen_coop_story.py` (assemble `coop_lock_emerald.s` into `COOP_LK_CODE[]` at `COOP_LK_CODE_ADDR = 0x08FFE000`, the story table appended after the code, as plan 1's generator assembles its own), `app/src/main/cpp/pk_coop_lock_emerald.h` (regenerated), `app/src/main/cpp/CMakeLists.txt` (add the two .c files), `app/src/main/cpp/pokedaisy_jni.c` (call `pk_coop_lock_tick` where `pk_coop_tick` is called; `pk_coop_lock_uninstall` where co-op is reset on teardown / all-out unpatch), `native-capture/mgba_dump.c` (include `pk_coop_lock.c`; commands below), `native-capture/README.md`, `docs/DEVELOPMENT.md`

**Interfaces:**
- Consumes: Task 2's header; `pk_cheats_block` / `pk_cheats_patch` / `pk_cheats_unpatch_range` / `pk_cheats_unblock`; `pk_call` (passed in like plan 1's `pk_coop_call_fn`).
- Produces: the C API and `CoopLockNative` in "Shared interfaces"; mgba_dump commands `lockinstall`, `lockuninstall`, `lockactive 0|1`, `lockheld` (prints `lock: held <id> kind <k>`), `lockgo <id>` (prints `lock: go <r>`), `lockcancel`.

Behaviour:
- The hook replaces `ScriptContext_SetupScript`'s first instructions with a jump into the block (a `pk_cheats_patch` of its head; the displaced instructions run in our stub before returning into the function). In the stub: not `active`, or `ptr` not in the table, or `id == allowId` (then `allowId = 0`) → the original function. Otherwise record the hold (id, kind, map, the player's tile and facing, `gSpecialVar_LastTalked`, `gSelectedObjectEvent`) and: OBJECT / FRAME → return without setting anything up (the game goes on as if nothing was triggered); COORD → set up instead one of four ROM scripts `applymovement OBJ_EVENT_ID_PLAYER, <one step opposite the facing>; waitmovement 0; releaseall; end` (the step back), and record the tile after that step.
- `pk_coop_lock_tick`: with a hold, clear it when the map changes, or for COORD when the player is more than 1 tile (Chebyshev) from the recorded tile, for OBJECT when the player's tile or facing changed. FRAME clears only on a map change.
- `pk_coop_lock_go(id)`: `heldId != id` → 0. FRAME → `allowId = id`, clear the hold, 1 (the game's own ON_FRAME check runs it next frame). Not a free field (a script running, `ArePlayerFieldControlsLocked`, no pk_call-safe frame) → -1, nothing changed. OBJECT → restore `gSpecialVar_LastTalked` / `gSelectedObjectEvent`, `allowId = id`, `pk_call(ScriptContext_SetupScript, script)`, clear, 1. COORD → write at `+0x20` `applymovement OBJ_EVENT_ID_PLAYER, <one step in the hold's facing>; waitmovement 0; goto <script>` and `pk_call(ScriptContext_SetupScript, mailbox+0x20)`, clear, 1.
- `pk_coop_lock_set_active(0)` also clears any hold.

- [ ] **Step 1: Write the headless tests** (each with `# want:` lines). Story scripts are started with `call <COOP_LK_ScriptContext_SetupScript> 1 <script>` (address printed by the generator) on the test save's Route 101 field, and the event used is one whose script is safe there. Pick, from the generated table, one OBJECT, one FRAME and one COORD event; use their scripts directly (the hook only looks at the pointer) and note the labels in each test's header comment.
  - `lock_install`: `lockinstall` → `lock: installed`; `romcheck $EMERALD_ROM` passes (the Poller's view is the file).
  - `lock_refused`: `rompatch` one byte of `ScriptContext_SetupScript` first → `lockinstall` prints `lock: refused` and `romblocks` shows no block at `0x08FFE000`.
  - `lock_inactive`: installed, not active: the OBJECT script runs (`expect8` on `sGlobalScriptContext.mode` running) and `lockheld` → `held 0`.
  - `lock_hold_object` / `lock_hold_frame`: active: the call returns, nothing runs (context mode stays stopped, controls not locked), `lockheld` → `held <id> kind 1` / `kind 2`.
  - `lock_hold_coord_stepback`: player facing north (`hold UP 1`), COORD script called → after 40 frames the player is one tile south (`expect16` on the player object's y), `lockheld` → `held <id> kind 0`, controls free.
  - `lock_cancel_distance`: after the step back, `hold DOWN 40` (two tiles) → `held 0`. `lock_cancel_map`: FRAME hold, then a `warp` to another map → `held 0`.
  - `lock_go_object`: held OBJECT, `lockgo <id>` → `go 1` and the script runs (`expect8` context running); `lockheld` → `held 0`; a second `call` of the same script is held again (allow is single-use).
  - `lock_go_coord`: held COORD after the step back, `lockgo <id>` → `go 1`, after 40 frames the player is back on the trigger tile and the context runs the story script (`expect32` scriptPtr inside the script, within 64 bytes of it).
  - `lock_uninstall`: active + held, `lockuninstall` → `0x0203F400` 256 bytes zero (`expectfile` against a zero file), `romcheck` passes, the OBJECT script runs normally again.
- [ ] **Step 2: Run them** → FAIL (`unknown command`).
- [ ] **Step 3: Implement** the assembly, `pk_coop_lock.c`, the mgba_dump commands, the JNI file (`Java_com_pokedaisy_app_CoopLockNative_*`, each taking the main core under the same lock `pokedaisy_jni.c`'s plan-1 wrappers take; export what it needs from `pokedaisy_jni.c` through a small header rather than duplicating) and `CoopLockNative.kt`. Syntax-check the JNI file in the `pokedaisy-capture` image (`gcc -fsyntax-only` with a JNI header if the image has one; else say so in the report).
- [ ] **Step 4: Run them** → all PASS; then rerun plan 1's whole suite (`coop_headless_check.sh` with no names) → every test PASS (the two blocks coexist).
- [ ] **Step 5: Docs**: the commands in `native-capture/README.md`; the hook, mailbox and contract in DEVELOPMENT.md's section.
- [ ] **Step 6: Commit** `Co-op: story scripts wait in a hook while the lock is on, and start on the app's word`.

---

### Task 6: A win in the save, through the game

> **Dropped during execution (ruling, agreed with plan 4):** plan 4's `pk_coop_after` owns every game-side write after a battle (trainer flag, prize, the in-game save with a `SaveBackups` copy first), and no plan-4 battle gives a badge (story battles aren't joinable). Plan 5 keeps only Task 2's win table (`CoopStory.winFlags`) for when they become joinable. The text below is kept for the record.

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/coop/lock/CoopWins.kt`, `CoopAutoSave.kt`, `native-capture/coop/lock_win_save.txt`, `native-capture/coop/lock_save_busy.txt`
- Modify: `native-capture/mgba_dump.c` (command `expectsaveflag <flag> <0|1>`: parses the core's savedata buffer, picks the newer slot by save counter, checks each SaveBlock1 sector's signature `0x08012025` and checksum, prints `PASS` / `FAIL`), `native-capture/README.md`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/coop/lock/{CoopWinsTest,CoopAutoSaveTest}.kt`

**Interfaces:**
- Consumes: Task 1 (`CoopStory.winFlags`, the constants in `CoopStoryGen.kt`); `SaveBackups.snapshot(save: File): File?`.
- Produces:
```kotlin
interface CoopCore { fun read(addr: Long, n: Int): ByteArray?; fun write(addr: Long, bytes: ByteArray): Boolean
    fun call(fn: Long, vararg args: Int): Int?   /* null = no pk_call-safe frame */; fun fieldFree(): Boolean }
object CoopWins { fun apply(core: CoopCore, trainerA: Int, trainerB: Int = 0): Boolean }   // false = refused, nothing written
object CoopAutoSave { enum class Result { SAVED, NOT_NOW, NO_BACKUP, FAILED }
    fun save(core: CoopCore, saveFile: File, backup: (File) -> File? = SaveBackups::snapshot): Result }
// plus `MgbaCoopCore : CoopCore` over MgbaCore.pkReadBytes / pkWriteBytes / pkCall and the field-free read FieldItems uses
```
`apply`: both ids (B 0 = none) must give a non-empty `winFlags`, else false; reads `gSaveBlock1Ptr`, must land in EWRAM (`0x02000000`-`0x0203FFFF`); sets each flag's bit in `flags[]` (read-modify-write of single bytes), reads them back; any failure → false (bits already set stay: flags only ever go from 0 to 1). `save`: `!core.fieldFree()` → NOT_NOW (no backup taken); `backup(saveFile) == null` → NO_BACKUP; `core.call(COOP_TRY_SAVING_DATA, 0 /* SAVE_NORMAL */)`: null → NOT_NOW, `COOP_SAVE_STATUS_OK` → SAVED, anything else → FAILED.

- [ ] **Step 1: Write the failing tests** (a fake `CoopCore` over a 256 KB EWRAM array + IWRAM pointer)

```kotlin
@Test fun setsTrainerBadgeAndDefeatedFlags() { /* COOP_TRAINER_WINS has Roxanne's entry: apply(core, ROXANNE) sets 0x500+id, FLAG_BADGE01_GET (0x867), 0x4F0; no other byte changed */ }
@Test fun twoTrainersBoth() { /* apply(a, b) sets both trainer flags */ }
@Test fun refusesTrainerZeroOrPastTheLast() { assertFalse(CoopWins.apply(core, 0)); assertFalse(CoopWins.apply(core, COOP_TRAINERS_COUNT)); /* RAM unchanged */ }
@Test fun refusesABadSaveBlockPointer() { /* pointer 0x08000000: false, nothing written */ }
@Test fun saveWaitsForAFreeField() { assertEquals(NOT_NOW, CoopAutoSave.save(busy, f) { error("no backup while busy") }) }
@Test fun noBackupNoSave() { assertEquals(NO_BACKUP, CoopAutoSave.save(free, f) { null }); /* call never made */ }
@Test fun savedOnStatusOk() { /* call returns 1 -> SAVED; 0xFF -> FAILED; null -> NOT_NOW */ }
```
Headless: `lock_win_save.txt` sets Roxanne's three flags with `poke8` read-modify-writes at `gSaveBlock1Ptr + 0x1270 + flag/8` (pointer read with `peek32`), `call <TrySavingData> 1 0` → `r0 = 1`, then `expectsaveflag 0x867 1`, `expectsaveflag 0x4F0 1`, `expectsaveflag <0x500+Roxanne> 1` and an untouched flag `expectsaveflag 0x868 0`. `lock_save_busy.txt` documents what NOT_NOW guards: it opens the START menu (`press START`, `wait 30`) and asserts `ArePlayerFieldControlsLocked` returns 1 through `call` (so `fieldFree` is false there).

- [ ] **Step 2: Run them** → FAIL. **Step 3: Implement.** **Step 4: Run them** → PASS (JVM and `coop_headless_check.sh lock_win_save lock_save_busy`).
- [ ] **Step 5: Commit** `Co-op: a won trainer's flags and badge go into the game, and the game saves them after a backup`.

---

### Task 7: The lock in the running game (integration branch)

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/CoopLock.kt`
- Modify: `app/src/main/kotlin/com/pokedaisy/app/PokeDaisyActivity.kt` (where plan 2 starts co-op for a game and plan 3 attaches the link and runs its per-frame hook), `docs/DEVELOPMENT.md`
- Test: `app/src/test/kotlin/com/pokedaisy/app/CoopLockTest.kt`

**Interfaces:**
- Consumes: plan 2's `CoopLink` (`state`, `messages`, `send`), `CoopState`, `CoopSaveId.key`, `CoopPair`; Tasks 1, 3, 5 (`CoopLockNative` behind a small `LockNative` interface so tests fake it), 4 (through `CoopGuards`); plan 4's `CoopGuards.battleActive`.
- Produces: `class CoopLock(link: CoopLink, store: CoopLockStore, own: String, partner: String, native: LockNative, partyCount: () -> Int, clock: () -> Long) : CoopLockActions { val ui: StateFlow<CoopLockUi>; fun onFrame(); fun close() }`; `PokeDaisyActivity` keeps one per game while a pairing exists and hands its `ui` / actions to the companion and the overlay (Task 8).

Behaviour: on every `Connected`, send `Status(locked)`; a partner `Status(false)` while we are locked turns ours off (store + notice). `propose()` only when `refusal == null`: sends `Propose(partyCount())`; a received `Propose` sets `partnerProposed`; `accept()` re-checks the refusal with the partner's count, stores the lock, sends `Answer(true, count)`; a received `Answer(true, …)` with our own still `refusal == null` stores it; `decline()` sends `Answer(false, count)`; `turnOff()` stores off and sends `Off`; a received `Off` turns ours off. While locked: `CoopGuards.blockStates(LOCK, true)`; `CoopGuards.hold(LOCK_START, true)` from launch until the first `Connected`; `hold(LOCK_LINK, CoopLockRules.holdForLink(...))` each frame; `native.setActive(connected && !battleActive)`; each frame `CoopStoryHold.step(native.held()[0], connected)` → send / `native.go(id)` (a -1 is retried next frame by keeping the go pending until 1 or the hold clears). `ui.waiting` = Partner while either hold is on, else `AtStory(id)` while the native side holds. `close()` (game closed): every guard reason of the lock off, native inactive. Unlocked: none of this runs but the proposal messages.

- [ ] **Step 1: Write the failing tests** (a fake `CoopLink` with a `MutableStateFlow<CoopState>` and recorded sends, a fake `LockNative`, fake clock): `proposeAndAcceptLocksBoth`, `acceptRefusedWhenPartnerNotNew`, `partnerOffUnlocks`, `statusMismatchUnlocks`, `launchHeldUntilConnected`, `dropHoldsAfterGraceOnly`, `noLinkHoldDuringCoopBattle`, `storyGoWhenBothHold`, `goRetriedWhileNotNow`, `closeReleasesEveryGuard`, `unlockedSaveTouchesNoGuard`.
- [ ] **Step 2: Run them** → FAIL. **Step 3: Implement** `CoopLock.kt`, then wire it in `PokeDaisyActivity` (created after the game's pairing is known, `onFrame` from the emu-thread co-op hook, `close` on game close; `CoopLockNative.install()` after the ROM loads on retail Emerald with a pairing, `uninstall()` on close).
- [ ] **Step 4: Run them** → PASS; `./gradlew --no-daemon :app:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 5: Docs** (DEVELOPMENT.md: who owns which guard reason, the message flow) and **commit** `Co-op: PLAY TOGETHER ONLY holds the game for the partner and lets story events start together`.

---

### Task 8: PLAY TOGETHER ONLY on screen (integration branch)

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/ui/CoopLockUi.kt` (the row's pick-list / confirm, the partner's proposal confirm, `CoopWaitOverlay`), `app/src/main/kotlin/com/pokedaisy/app/companion/i18n/TrCoopLock.kt`
- Modify: `companion/ui/CoopUi.kt` (the CO-OP group: the row on both screens), `companion/i18n/Tables.kt` (register the table), `companion/ui/SettingRows.kt` (`FLAGGED_SETTING_KEYWORDS`: `"PLAY TOGETHER ONLY" to listOf("lock", "together", "story", "wait", "partner")`), `PokeDaisyActivity.kt` (the overlay in `GameStageLayout.overGame`), `docs/manual-drafts/coop.md`, the CO-OP screenshot tests / ui-preview shots plan 2 added
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/ui/CoopLockUiTest.kt` (texts and states), the existing `TranslationsTest` / `check_translations.py`

**Interfaces:**
- Consumes: `CoopLockUi`, `CoopLockActions`, `LockRefusal`, `LockWait` (Task 1); `CoopStory.byId` (for nothing shown: the overlay never names the event).
- Produces: `@Composable fun CoopLockRow(ui: CoopLockUi, actions: CoopLockActions)`, `@Composable fun CoopWaitOverlay(wait: LockWait, partnerName: String, onCloseGame: () -> Unit)`.

Copy (exact English; translations in `TrCoopLock.kt`): row label `PLAY TOGETHER ONLY`, values `ON` / `OFF`, `labelBadge = NewFeatures.badge(NewFeatures.COOP)`, subtitle by state: refusal NOT_PAIRED `Pair with a partner first`, NOT_CONNECTED `Your partner must be connected`, OWN_NOT_NEW `Only for new games: you already have a POKéMON`, PARTNER_NOT_NEW `Only for new games: your partner already has a POKéMON`; `proposalSent` `Waiting for your partner to accept`. Turning on asks `Turn on PLAY TOGETHER ONLY for both games? You can only play while your partner is connected, and story events wait for both of you.` YES / NO. The partner's proposal: `{0} wants to PLAY TOGETHER ONLY.` ACCEPT / NO. Turning off: `Turn off PLAY TOGETHER ONLY? It can't be turned on again for these games.` YES / NO. Overlay: Partner `Waiting for your partner...` with CLOSE GAME; AtStory `Waiting for {0} to get here.` and `Step away to cancel.` (no button: walking away is the cancel). The overlay never blocks the game's own input in AtStory (the player must be able to walk away); in Partner the game is held anyway.

- [ ] **Step 1: Write the failing tests**: `subtitleFollowsRefusal` (each `LockRefusal` → its text), `noSubtitleWhenItCanTurnOn`, `overlayTextsByWait`.
- [ ] **Step 2: Run them** → FAIL. **Step 3: Implement**; add every new string's seven translations; run `TranslationsTest`.
- [ ] **Step 4: Look at it** (CLAUDE.md "UI work"): render the CO-OP group with the row in each state and the overlay in both waits (ui-preview `gradle render -Ponly=coop` or the Paparazzi CO-OP test plan 2 uses, plus dark mode) and read the PNGs; fix anything clipped.
- [ ] **Step 5: Manual**: a "Playing together only" section in `docs/manual-drafts/coop.md` (what it does, when it can be turned on, what waits, walking away, LOAD STATE and REWIND off, that it can't come back on); `make docs-check` style rules by eye (no dashes, second person).
- [ ] **Step 6: Commit** `Co-op: the PLAY TOGETHER ONLY row and the waiting screen`.

---

### Task 9: Wiring the row and the overlay (added during execution)

Tasks 7 and 8 run in parallel, so neither puts the other's pieces into place: Task 7 makes `CoopLock` and its lifecycle in `PokeDaisyActivity` but draws nothing, and Task 8 makes the composables and the row but never touches `PokeDaisyActivity`.

**Files:** Modify: `PokeDaisyActivity.kt` (`CoopWaitOverlay` in `GameStageLayout.overGame` while `CoopLock.ui.waiting != null`, CLOSE GAME = the activity's close), the companion settings provider (`coopLockUi` / `coopLockActions` beside plan 2's `coopState`), `SettingsActivity` (the top screen's row: state and actions come from the running game's `CoopLock` when one exists, else the row shows the refusal NOT_CONNECTED), and the refused-load notice ("Not while you play together only") where `onStateResult(false)` comes back while `CoopGuards.statesBlocked`.

- [ ] **Step 1:** wire it; `./gradlew --no-daemon :app:compileDebugKotlin -x licensesAsset` → BUILD SUCCESSFUL.
- [ ] **Step 2:** render the companion's CO-OP group with the row and the game screen with the overlay (ui-preview or Paparazzi), and read the PNGs.
- [ ] **Step 3: Commit** `Co-op: the lock's row and waiting screen wired to the running game`.

## Task order and parallel waves

- Wave 0: Task 1 alone (everything else compiles against it).
- Wave 1, in parallel: Task 2 (Docker only, this worktree), Task 3 (Gradle, its own worktree), Task 4 (Gradle, its own worktree). Merge order 2, 3, 4 (disjoint files).
- Wave 2, in parallel: Task 5 (Docker + C, this worktree; needs Task 2), Task 6 (Gradle + Docker, its own worktree; needs Task 1's constants, Task 2's real values). Merge order 5, 6 (both touch `native-capture/mgba_dump.c` and `README.md` in different places: 6 rebases on 5).
- Wave 3 (integration branch from the parent session): Task 7, then Task 8 (8 needs 7's wiring in `PokeDaisyActivity`; the row itself can start beside 7 against Task 1's types if the files stay apart).

## Self-review notes

- Spec section 5: the lock only for two new saves (Task 3 refusal, Task 7 handshake), only starts with the partner (Task 7 LOCK_START), pauses on a drop outside battles with the companion / states / close working (Tasks 4, 7), story events and story battles wait for both at the same spot (Tasks 2, 5, 7), ordinary trainers don't (Task 2 rules), cancel by walking away (Task 5), both turn it on together (Task 7), the generated list (Task 2), a held story battle hosted by whoever triggered it as a co-op battle: through `CoopStoryBattle` (plan 4), each in their own game until then.
- Section 3 wins: trainer flag, badge, defeated flag (Tasks 2, 6), auto-save after a backup (Task 6); when to call them is plan 4's. The gym TM report (Task 2).
- Section 6: LOAD STATE / REWIND off whenever a locked save is played (Tasks 4, 7); only allowlisted flags are written (Task 6).
