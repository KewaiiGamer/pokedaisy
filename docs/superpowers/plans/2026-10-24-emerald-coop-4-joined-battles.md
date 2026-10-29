# Emerald Co-op, Plan 4: Joined Battles End to End Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two linked players on retail English Emerald: when the host starts an ordinary wild battle or a two-trainer battle with the partner nearby and free, the partner is asked to join; on JOIN the host's battle becomes the co-op double battle of plan 1, the partner watches it live on their own device (a replica of the host's game) and fights their three Pokémon from their own menu, and when it ends each game gets what the battle gave it (party, catch, items used, beaten trainers, prize money, blackout, evolutions), then saves itself.

**Architecture:** The host runs the battle (plan 1's `pk_coop.c`) and streams a state taken right after arming (no save data) plus every frame's keys and every RAM write the app makes, each tagged with a battle-relative frame number. The partner's **own core** sets its game aside (a state without save data, kept in memory) and loads the host's state as the replica, with the same ROM patches and a pinned RTC, so it plays the real battle with sound at almost no bandwidth; a RAM CRC every 60 frames catches drift and a fresh state resyncs it. Pure Kotlin (`companion/data/coop/`) holds the protocol, the lockstep, the join rules, the result checks and the two drivers, all JVM-tested with fakes; native helpers (`pk_coop_field.c`, `pk_coop_after.c`, `pk_coop.c`) give the field signals, the replica arm and the game-side writes, tested headless; a thin glue (`CoopBattles.kt`) ties them to `EmulatorEngine`, plan 2's link and plan 3's presence.

**Tech Stack:** C (mGBA 0.10.5), Python 3 generator (Docker), mgba_dump headless scripts, Kotlin + JUnit + kotlinx-coroutines, Compose (OPTION look), ui-preview.

**Spec:** `docs/superpowers/specs/2026-10-24-emerald-coop-design.md` (section 3 and section 6; section 4's engine facts are plan 1's). This is plan 4 of 5: 1 battle engine (done), 2 connection and pairing (done), 3 seeing each other (done), 5 the lock and story events.

**Depends on:** plan 1 (this branch's base: `pk_coop.h`, `CoopBattle.kt`, `MgbaCore.pkCoop*`, the headless runner). Plan 2 (`CoopSession.link: CoopLink`, `CoopMessage`, `FeatureFlags.coop`, the CO-OP settings group, `TrCoop.kt`, `docs/manual-drafts/coop.md`) and plan 3 (`PokeDaisyActivity.attachCoopLink`, `CoopPresence`, `PresenceUpdate`) are on their own branches: only Tasks 11 and 12 compile against them, on an integration branch the parent session provides. Everything else is written against this plan's own seams (`CoopBattleChannel`, `CoopCore`).

**Left to plan 5:** story battles (rival, gym leaders, Elite Four, Aqua / Magma bosses) with the picking screen and the partner's own scene. They are all single-trainer battles, which the engine can't join (ruling R13: they hang as multi battles), so **badges never come from a plan 4 battle**; plan 4 joins only ordinary wild battles and two trainers who saw the player at once.

## Global Constraints

- Game: retail English Emerald only, BPEE, SHA-1 `f3ae088181bf583e55daf962a92bb46f4f1d07b7` (the `RETAIL_SHA1S` entry; a `RetailDumps` match counts). The app gates co-op battles on that hash, never on `pkCoopInstall` alone (its 32-byte CRCs also pass on Kaizo / Glazed). Both devices run that ROM (plan 2's HELLO already refuses another).
- Joinable: ordinary wild battles (`DoStandardWildBattle`'s path) and two-trainer battles only. Never: single-trainer battles (R13), scripted / static wild battles (legendaries, Kecleon, Electrode, Sudowoodo, the Route 101 rescue - even when the species is in the map's table), Safari, link, Frontier, Steven's own. No join prompt is ever shown for those.
- Nearby = same map (group and number) and Manhattan distance in tiles at most NEARBY RANGE (`Prefs.coopNearbyRange`, default 8, choices 2 / 4 / 8 / 16 / 32). Free = on the field with controls free, or on the top-level START menu; not in a battle, a menu screen, a script, a fade. The join prompt lasts 30 seconds; no answer = declined.
- Teams: each side's first three that can still fight, in party order (`CoopBattle.firstThreeAble`). Everything received is untrusted: partner Pokémon go through `CoopBattle.check` before `pkCoopArm`; a returned Pokémon must keep the personality, OT ID and species it was sent with and pass `check`; a caught one must pass `check` and carry the partner's own OT ID.
- Engine contract (plan 1, `pk_coop.h`): `pkCoopInstall` again before every battle (idempotent; `pkRomUnpatch` / `pkRomBlocks` / `pkRomUnblock` / `pkTeardown` uninstall co-op); `pkCoopArm` while `pkCoopBattleStarting()` is true, retried on later frames until true or until starting goes false (= refused); `pkCoopPick` 1 accepted, 0 refused, -1 retry on a later frame; `pkCoopResult` 404 bytes per `pk_coop.h`, then `pkCoopResultClear`.
- While a co-op battle runs on a device (host: hold to result saved; partner: accept to own game back and results saved; also whenever `pk_coop_busy()`): PLAYER LOOK and REUSABLE TMS toggles, RESTART, LOAD STATE, SAVE STATE, REWIND, cheats and HOTKEYS' state slots are refused with a notice, RetroAchievements idles (as with cheats), and no resume / suspend state is written from the replica (the partner's own game is put back first).
- PLAYER LOOK and TEXT LOOK block `pkCoopInstall` (their ROM blocks sit in co-op's code area): a host or partner wearing either is not ready for co-op battles (STATUS ready = 0); the manual says so.
- The replica: same ROM patches as the host (co-op's install + `pk_coop_replica_arm`, REUSABLE TMS when the host has it on), RTC pinned to the host's (`pk_coop_rtc_pin`, mGBA's fake epoch, on both sides from arm to the battle's end), the partner's own keys never reach it (they drive the partner's menu, or are forwarded to the host while the learn-a-move prompt is up for a partner Pokémon).
- Partner's potion prints no "used" line in the host's battle (R16): both devices show a notice "<NAME> used <ITEM>!" when the host accepts a partner item pick.
- Link drop in a battle: both sides hold up to 60 s; back in time = the replica resyncs from a fresh state and both carry on; not back = undone for both: the host loads its pre-battle state (taken before arming, no save data) and `pk_coop_abort`s, the partner puts its own game back untouched.
- Save files are sacred: every game-side write after a battle happens in game RAM on a free field frame, and the in-game auto-save makes a `SaveBackups` copy first. States here never carry save data.
- Feature flag: everything player-facing stays behind plan 2's `FeatureFlags.coop` (off by default). Settings keywords go to `FLAGGED_SETTING_KEYWORDS`; the NEW tag is plan 2's `NewFeatures.COOP` (still out of `ALL`); the manual text goes into plan 2's draft `docs/manual-drafts/coop.md` (no em / en dashes, no real Pokémon / move / item names). Every new string is translated (JA kana only, FR, DE, IT, ES, BR, PT) in `i18n/TrCoopBattle.kt`.
- UI only from the OPTION pieces (`GbaMenu.kt`, `PixelShapes.kt`, `PixelIcons`), the battle lists' looks (`BattleLists.kt`), `soundClickable`, colours through `OptionColors` (dark mode).
- Message types `0x0200`-`0x02FF` only (plan 2's range for battles). Payload at most 65,534 bytes.
- Machine: Windows, Git Bash. No Python on PATH: generators run in Docker (`MSYS_NO_PATHCONV=1 docker run --rm -v "D:/Code/pokedaisy/decomps:/decomps" -v "<worktree>:/repo" pokedaisy-decomp -c "python3 /repo/scripts/gen_coop_emerald.py /decomps/pokeemerald"`). Headless: `EMERALD_ROM=/d/Code/pokedaisy/roms/emerald.gba EMERALD_SAVE=/d/Code/pokedaisy/roms/emerald.sav COOP_WORK=/d/Code/pokedaisy/roms/work/coop-p4-<task> bash scripts/coop_headless_check.sh <names>` (a work dir of the task's own). JVM: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew --no-daemon ...`; at most two Gradle runs at once on this machine (`bash /d/Code/pokedaisy/pokedaisy/.claude/worktrees/emerald-coop-joined-battles-6a7a42/.superpowers/sdd/2026-10-24-emerald-coop-4-joined-battles/gradle-slot.sh <gradle args>` from the task's worktree; `gradle-slot.sh` below means that script takes a slot). Baseline: 6 JVM tests already fail on this checkout (PerformanceGuardsTest, SiteDataExportTest, RomAddedTest.keptAcrossLaunches, SaveFileEditsTest.aTradeWritesBothOrNeither, BestEffortTest x2); "all pass" means no failures beyond those. ROM-backed JVM tests take env `POKEDAISY_ROM_DIR=D:/Code/pokedaisy/roms/`.
- Never run `find /` or any root-wide search; never run an interpreter reading stdin with nothing piped (`python -`). Never commit `third_party/melonds` (a build marks it modified).
- Commits carry no Claude / Anthropic attribution (the `commit-msg` hook rejects it; never bypass). Plain-sentence subjects with a `Co-op:` prefix.

## Review Focus

- The partner's app closed, killed or put to sleep mid-battle: the partner's own game comes back before any resume / suspend state is written (never the host's battle saved as the partner's game), and the host sees a link drop and undoes after 60 s. Test in Task 10 (`partnerStopRestoresOwnGameFirst`) and Task 11 (`stopWhileReplicaRestoresFirst`).
- A host whose app writes RAM during the battle (the one-press picks' cursor writes, BATTLE BUTTONS): the write reaches the replica on the same frame, so the CRCs keep matching. Test in Task 9 (`replica_writes`) and Task 10 (`hostWritesAreReplayed`).
- A hostile or broken partner (a returned Pokémon with another personality, a species that changed, a caught Pokémon with the host's OT ID, a trainer id out of range, a result for a battle id it never offered, a state chunk past the declared size): refused before anything touches game RAM. Tests in Task 2 (decode) and Task 5 (after-battle plan).
- Both players start a battle at the same moment (each the other's host): each side, while it is itself holding a battle start, declines the incoming offer at once (BUSY), so both battles go on single and neither game waits on the other. Test in Task 4 (`crossedOffersBothDecline`).
- The replica falling behind (a slow device, the relay): it plays catch-up at up to 4 frames per tick and never runs a frame it has no keys for; a CRC mismatch resyncs instead of carrying on wrong. Test in Task 3 (`starvedReplicaWaits`, `catchUpBounded`) and Task 10 (`hashMismatchResyncs`).

---

## File Structure

| File | Responsibility |
|---|---|
| `scripts/gen_coop_emerald.py`, `app/src/main/cpp/pk_coop_emerald.h` (modify, regenerate) | Task 1's new symbols. |
| `app/src/main/cpp/pk_coop_field.h` / `.c` (create) | Field-side signals: joinable kind and trainer ids, partner free, the learn-a-move prompt. |
| `app/src/main/cpp/pk_coop_after.h` / `.c` (create) | Game-side writes after a battle, through the game's own functions: give a Pokémon, remove an item, trainer flag, prize, money, party slot, white-out, evolution, in-game save. |
| `app/src/main/cpp/pk_coop.c` / `.h` (modify) | Replica arm (ROM patches only), abort, RTC pin, app-write log, RAM CRC. |
| `app/src/main/cpp/pokedaisy_jni.c`, `app/src/main/cpp/CMakeLists.txt` (modify) | JNI wrappers at named anchors; in-memory states without save data. |
| `app/src/main/kotlin/com/pokedaisy/app/MgbaCore.kt` (modify) | `external fun`s at named anchors. |
| `native-capture/mgba_dump.c`, `native-capture/README.md` (modify) | Commands at named anchors. |
| `native-capture/coop/*.txt` (create) | Headless tests (`join_*`, `free_*`, `learn_*`, `after_*`, `replica_*`). |
| `.../companion/data/coop/CoopBattleChannel.kt` (create) | The seam to plan 2: `send(type, payload)`, `received`. |
| `.../companion/data/coop/CoopBattleWire.kt` (create) | Message types and codecs, checked on the way in. |
| `.../companion/data/coop/CoopLockstep.kt` (create) | Host recorder, replica feed. |
| `.../companion/data/coop/CoopJoin.kt` (create) | Nearby / free / joinable rules; the host's and partner's join state machines. |
| `.../companion/data/coop/CoopAfter.kt` (create) | The partner's and host's after-battle plans (validated operations). |
| `.../companion/data/coop/CoopCore.kt` (create) | The native calls the drivers use, as an interface. |
| `.../companion/data/coop/CoopBattleHost.kt`, `CoopBattlePartner.kt` (create) | The two drivers. |
| `app/src/main/kotlin/com/pokedaisy/app/CoopBattles.kt` (create) | Glue: `MgbaCoopCore`, engine hooks, link / presence adapters, overlays' state. |
| `app/src/main/kotlin/com/pokedaisy/app/EmulatorEngine.kt`, `PokeDaisyActivity.kt` (modify) | Frame hook, hold, key routing, blocks, RA idle, stop order. |
| `.../companion/ui/CoopBattleUi.kt` (create) | Join prompt, host waiting, link-drop wait, partner menu, notices. |
| `.../companion/i18n/TrCoopBattle.kt` (create), `i18n/Tables.kt` (modify) | Translations. |
| `ui-preview/src/render/Main.kt` (modify) | `coop-battle-*` shots. |
| `Prefs.kt`, `SettingsActivity.kt`, `companion/CompanionSettings.kt`, `companion/ui/CompanionSettingsScreen.kt`, `companion/ui/SettingRows.kt` (modify) | NEARBY RANGE row. |
| `docs/manual-drafts/coop.md`, `docs/DEVELOPMENT.md`, `CLAUDE.md` (modify) | Manual draft, developer section, project note. |
| `app/src/test/kotlin/com/pokedaisy/app/companion/data/coop/*Test.kt`, `app/src/test/kotlin/com/pokedaisy/app/CoopBattlesTest.kt` (create) | JVM tests. |

## Shared interfaces (every task uses these names)

```kotlin
// CoopBattleChannel.kt - plan 2's CoopLink adapted in Task 11
interface CoopBattleChannel {
    val connected: Boolean
    fun send(type: Int, payload: ByteArray): Boolean        // false while not connected
    val received: kotlinx.coroutines.flow.Flow<Pair<Int, ByteArray>>   // (type, payload), types 0x0200..0x02FF only
}

// CoopCore.kt - one per device, emu thread only; MgbaCoopCore (Task 11) forwards to MgbaCore
interface CoopCore {
    // plan 1
    fun install(): Boolean; fun battleStarting(): Boolean
    fun arm(partner: ByteArray, count: Int, name: ByteArray, gender: Int, otId: Int): Boolean
    fun waiting(): Int; fun pick(action: Int, a: Int, b: Int): Int
    fun result(): ByteArray?; fun resultClear()
    // Task 7
    fun joinKind(): Int                     // 0 not joinable, 1 wild, 2 two trainers (call while battleStarting())
    fun joinTrainers(): IntArray            // [trainerA, trainerB], 0 = none
    fun partnerFree(): Boolean
    fun learnPrompt(): Boolean              // the learn-a-move prompt for a partner Pokémon is up
    // Task 8 (1 done, 0 refused, -1 retry on a later frame unless noted)
    fun giveMon(mon: ByteArray): Int        // 0 party, 1 PC, 2 no room, -1 retry
    fun removeItem(item: Int, count: Int): Int
    fun setTrainerFlag(trainerId: Int): Int
    fun prize(trainerId: Int): Int          // base prize money from the ROM's tables, -1 bad id
    fun addMoney(amount: Int): Int
    fun writePartyMon(slot: Int, mon: ByteArray): Int
    fun whiteOut(): Int
    fun evolve(slot: Int): Int              // 1 scene started, 0 nothing to evolve into, -1 retry
    fun saveGame(): Int
    fun onFreeField(): Boolean              // = partnerFree() without the START menu case: safe for the writes above
    // Task 9
    fun replicaArm(kind: Int, name: ByteArray, gender: Int): Boolean
    fun abort()
    fun rtcPin(epochSeconds: Long); fun rtcUnpin()
    fun stateSave(): ByteArray?             // no save data
    fun stateLoad(state: ByteArray): Boolean
    fun recordWrites(on: Boolean); fun takeWrites(): ByteArray?   // packed [u32 addr][u16 len][bytes]...
    fun writeBytes(addr: Int, bytes: ByteArray): Boolean
    fun ramCrc(): Int                       // CRC32 of EWRAM then IWRAM
    fun setKeys(keys: Int)
    // the app's other ROM changes, for the partner's swap
    fun romChangesOut(); fun romChangesBack()   // PLAYER LOOK / TEXT LOOK / option patches out, and the player's own back
    fun reusableTms(on: Boolean)
}
```

```c
// pk_coop_field.h
int  pk_coop_join_kind(struct mCore* core);              // 0 / 1 wild / 2 two trainers; 0 unless pk_coop_battle_starting()
void pk_coop_join_trainers(struct mCore* core, int out[2]);
int  pk_coop_partner_free(struct mCore* core);
int  pk_coop_on_free_field(struct mCore* core);
int  pk_coop_learn_prompt(struct mCore* core);
// pk_coop_after.h: 1 / 0 / -1 as CoopCore above; every one runs only when pk_coop_on_free_field() (else -1)
int pk_coop_give_mon(struct mCore* core, const uint8_t mon[100]);
int pk_coop_remove_item(struct mCore* core, int item, int count);
int pk_coop_set_trainer_flag(struct mCore* core, int trainerId);
int pk_coop_prize(struct mCore* core, int trainerId);     // ROM reads only, any frame
int pk_coop_add_money(struct mCore* core, int amount);
int pk_coop_write_party_mon(struct mCore* core, int slot, const uint8_t mon[100]);
int pk_coop_white_out(struct mCore* core);
int pk_coop_evolve(struct mCore* core, int slot);
int pk_coop_save_game(struct mCore* core);
// pk_coop.h additions
int  pk_coop_replica_arm(struct mCore* core, int kind, const uint8_t name[8], int partnerGender);
void pk_coop_abort(struct mCore* core);
void pk_coop_rtc_pin(struct mCore* core, int64_t epochSeconds);
void pk_coop_rtc_unpin(struct mCore* core);
void pk_coop_record_writes(int on);
int  pk_coop_take_writes(uint8_t* out, int cap);          // bytes, 0 none
void pk_coop_note_write(uint32_t addr, const uint8_t* bytes, int len);   // pkWriteBytes calls it
uint32_t pk_coop_ram_crc(struct mCore* core);
```

Wire (`CoopBattleWire`, little-endian; every message starts with `u32 battleId`, a host-chosen random non-zero id):

| Type | Name | Direction | Payload after battleId |
|---|---|---|---|
| 0x0200 | STATUS | both, on change + every 1 s | u8 flags: bit 0 free, bit 1 ready (co-op installs, no PLAYER LOOK / TEXT LOOK, not in a co-op battle). battleId 0. |
| 0x0201 | OFFER | host | u8 kind (1 / 2), u16 trainerA, u16 trainerB, u16 species of the first foe |
| 0x0202 | ANSWER | partner | u8 accept; if 1: u8 count (1-3), count x 100 mon bytes, 8 name bytes, u8 gender, u32 otId, u8 partySlots[count] (the partner's own party indexes) |
| 0x0203 | CANCEL | both | u8 reason (`CoopCancel`: 0 TIMEOUT, 1 DECLINED, 2 ENGINE, 3 LINK, 4 BUSY, 5 CLOSED) |
| 0x0204 | START | host | u8 kind, 8 name, u8 gender, i64 rtcEpoch, u8 options (bit 0 REUSABLE TMS), u32 stateSize, u32 stateCrc, u32 startFrame (0 at arm, later on a resync) |
| 0x0205 | STATE_CHUNK | host | u32 offset, bytes (at most 60,000) |
| 0x0206 | FRAMES | host | u32 firstFrame, u8 n (1-60), n x u16 keys |
| 0x0207 | PICK_APPLIED | host | u32 frame, u8 action, u16 a, u8 b |
| 0x0208 | WRITES | host | u32 frame, packed writes (each len 1-256, addr in EWRAM / IWRAM) |
| 0x0209 | HASH | host | u32 frame, u32 crc |
| 0x020A | RESYNC | partner | u32 frame |
| 0x020B | PICK | partner | u8 seq, u8 action, u16 a, u8 b |
| 0x020C | PICK_ANSWER | host | u8 seq, u8 accepted |
| 0x020D | KEYS | partner | u16 keys |
| 0x020E | RESULT | host | 404 result bytes, u8 hostAllOut |
| 0x020F | RESULT_ACK | partner | (none) |

Frame numbers are battle-relative: frame 0 is the first frame run after the state in START was taken. `PICK_APPLIED` / `WRITES` for frame N are applied before frame N runs; `HASH` for frame N is checked after it.

---

### Task 1: New addresses and the anchors the native tasks share

**Files:**
- Modify: `scripts/gen_coop_emerald.py`, `app/src/main/cpp/pk_coop_emerald.h` (regenerated), `app/src/main/cpp/CMakeLists.txt`, `native-capture/mgba_dump.c`, `app/src/main/cpp/pokedaisy_jni.c`, `app/src/main/kotlin/com/pokedaisy/app/MgbaCore.kt`
- Create: `app/src/main/cpp/pk_coop_field.h` / `.c`, `app/src/main/cpp/pk_coop_after.h` / `.c` (headers with Task 7 / 8's prototypes; `.c` files with an include line only)

**Interfaces:**
- Produces: `COOP_EM_<SYMBOL>` for `CB2_EndWildBattle`, `CB2_EndTrainerBattle`, `CB2_EndScriptedWildBattle`, `gTrainerBattleOpponent_A`, `gTrainerBattleOpponent_B`, `gMain`'s `savedCallback` offset (`COOP_EM_OFF_MAIN_SAVED_CALLBACK`), `sStartMenuCallback`, `HandleStartMenuInput`, `ArePlayerFieldControlsLocked` (or the lock byte it reads), `gPaletteFade`, `gBattlescriptCurrInstr`, the learn-a-move battle scripts' address range (`BattleScript_AskToLearnMove` .. the end of `BattleScript_LearnMoveReturn`, builder confirms the bounds), `CB2_InitBattle` already present, `GiveMonToPlayer`, `GetSetPokedexFlag`, `SpeciesToNationalPokedexNum`, `RemoveBagItem`, `CheckBagHasItem`, `FlagSet`, `AddMoney`, `gSaveBlock1Ptr`, `COOP_EM_OFF_SB1_MONEY`, `gTrainerMoneyTable`, `gTrainers` offsets (`partySize`, `party`, `trainerClass`, `partyFlags` / party type), `GetEvolutionTargetSpecies`, `BeginEvolutionScene`, `gCB2_AfterEvolution`, `CB2_ReturnToField`, `SetMainCallback2`, the START menu save path's functions (`SaveDoSaveCallback`'s callees: `IncrementGameStat`, `TrySavingData`, and what the builder finds it calls before), `gPlayerPartyCount`. Anchors (single comment lines, nothing else changes there): `// coop-field (plan 4 Task 7)`, `// coop-after (plan 4 Task 8)`, `// coop-replica (plan 4 Task 9)` in `mgba_dump.c`'s include block and its command dispatch, in `pokedaisy_jni.c`'s JNI section, and in `MgbaCore.kt` after `pkCoopResultClear`.

- [ ] **Step 1: Add the symbols to the generator's lists** (additive, as plan 3 did: new list entries, no reordering).
- [ ] **Step 2: Regenerate** with the Docker command in Global Constraints → Expected: the header gains every symbol above, nothing existing changes (`git diff app/src/main/cpp/pk_coop_emerald.h` shows only added lines).
- [ ] **Step 3: Add the two new `.c` files to `CMakeLists.txt` and include them in `mgba_dump.c`**, and the anchors.
- [ ] **Step 4: Verify both builds:** `bash scripts/coop_headless_check.sh install` → PASS (mgba_dump compiles with the new files); `gradle-slot.sh :app:externalNativeBuildDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — `git add scripts/gen_coop_emerald.py app/src/main/cpp native-capture/mgba_dump.c app/src/main/kotlin/com/pokedaisy/app/MgbaCore.kt && git commit -m "Co-op: addresses for joined battles, and room for the field, after-battle and replica helpers"`

### Task 2: The battle protocol

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopBattleChannel.kt`, `.../coop/CoopBattleWire.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/data/coop/CoopBattleWireTest.kt`

**Interfaces:**
- Produces: `CoopBattleChannel` (above); `object CoopBattleWire { const val STATUS = 0x0200 ... RESULT_ACK = 0x020F; const val MAX_CHUNK = 60_000; fun encode(m: CoopBattleMsg): Pair<Int, ByteArray>; fun decode(type: Int, payload: ByteArray): CoopBattleMsg? }`; `sealed interface CoopBattleMsg { val battleId: Int }` with one data class per table row (`Status(free, ready)`, `Offer(kind, trainerA, trainerB, species)`, `Answer(accept, team: ByteArray, count, name: ByteArray, gender, otId, partySlots: IntArray)`, `Cancel(reason: CoopCancel)`, `Start(kind, name, gender, rtcEpoch: Long, reusableTms: Boolean, stateSize, stateCrc, startFrame)`, `StateChunk(offset, bytes)`, `Frames(firstFrame, keys: IntArray)`, `PickApplied(frame, action, a, b)`, `Writes(frame, packed: ByteArray)`, `Hash(frame, crc)`, `Resync(frame)`, `Pick(seq, action, a, b)`, `PickAnswer(seq, accepted)`, `Keys(keys)`, `Result(result: ByteArray, hostAllOut)`, `ResultAck`); `enum class CoopCancel { TIMEOUT, DECLINED, ENGINE, LINK, BUSY, CLOSED }`; the partner menu's choices (Task 6 draws them, Task 10 sends them): `sealed interface CoopMenuChoice { data class Move(val slot: Int, val target: Int); data class Switch(val slot: Int); data class Item(val item: Int, val slot: Int); object Run }` with `fun CoopMenuChoice.toPick(seq: Int): CoopBattleMsg.Pick`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun everyMessageRoundTrips()        // one instance of each class: decode(encode(m)) == m (arrays by content)
@Test fun wrongLengthIsNull()             // each type: one byte short, one byte long -> null
@Test fun unknownTypeIsNull()             // 0x0210, 0x01FF -> null
@Test fun answerChecksEveryMon()          // a team whose 2nd mon fails CoopBattle.check (flip a byte) -> null; count 0 or 4 -> null; partySlots outside 0..5 or repeated -> null
@Test fun offerKindAndTrainers()          // kind 0 / 3 -> null; trainer id >= 855 -> null; kind 1 with a trainer id != 0 -> null
@Test fun framesBounds()                  // n 0 or 61 -> null; keys with bits above 0x3FF -> null
@Test fun writesOnlyGameRam()             // a record at 0x08000000, 0x03008000+, len 0 or 257, or running past the payload -> null
@Test fun chunkBounds()                   // 60,001 bytes -> null
@Test fun resultChecks()                  // 404 bytes whose partner count is 0 -> null (CoopBattle.parseResult's rule)
@Test fun zeroBattleIdOnlyForStatus()     // any other type with battleId 0 -> null
```
Use the `emerald_vanilla` fixture's party for real mons (as `CoopBattleTest` does).

- [ ] **Step 2: Run** `gradle-slot.sh :app:testDebugUnitTest --tests '*CoopBattleWireTest'` → FAIL (unresolved).
- [ ] **Step 3: Implement** (a `ByteBuffer` little-endian reader that returns null on any underflow or leftover byte).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `git commit -m "Co-op: the battle messages, checked on the way in"`

### Task 3: Lockstep: the host's recorder and the replica's feed

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopLockstep.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/data/coop/CoopLockstepTest.kt`

**Interfaces:**
- Consumes: Task 2's message classes.
- Produces:
```kotlin
class CoopRecorder(private val battleId: Int, private val out: (CoopBattleMsg) -> Unit) {
    var frame: Int                                   // the next frame to run, starts at the START's startFrame
    fun beforeFrame(pick: CoopBattleMsg.PickApplied?, writes: ByteArray?)   // sends PickApplied / Writes for `frame` (flushing pending Frames first)
    fun afterFrame(keys: Int, crc: () -> Int)        // queues keys; sends Frames every 3 frames; Hash every 60th frame (frame % 60 == 59)
    fun flush()
}
class CoopFeed {
    fun reset(startFrame: Int)
    fun accept(m: CoopBattleMsg): Boolean            // Frames / PickApplied / Writes / Hash; false = out of order (a gap or a frame already run): resync
    class Step(val frame: Int, val keys: Int, val pick: CoopBattleMsg.PickApplied?, val writes: ByteArray?, val hash: Int?)
    fun next(): Step?                                // null = no keys for the next frame yet
    val behind: Int                                  // frames with keys received but not run
    fun framesThisTick(): Int                        // 1 normally; behind > 6 -> 2, > 30 -> 4; 0 when next() would be null
}
```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun recorderBatchesThreeFrames()    // 7 afterFrame calls -> Frames(0,[k0,k1,k2]), Frames(3,[..]) sent, k6 pending until flush()
@Test fun pickGoesOutBeforeItsFrame()     // keys for 0..1 pending, beforeFrame(pick) at frame 2 -> Frames(0,[k0,k1]) then PickApplied(frame=2)
@Test fun hashEverySixtyFrames()          // 120 frames -> Hash(59, ..), Hash(119, ..), crc lambda called twice only
@Test fun feedReplaysInOrder()            // recorder -> feed: every Step's frame / keys / pick / writes / hash equal what went in
@Test fun starvedReplicaWaits()           // keys only up to frame 4 -> next() for frame 5 is null, framesThisTick() == 0
@Test fun catchUpBounded()                // 40 frames buffered -> 4, after running 10 -> 2, at 3 behind -> 1
@Test fun gapAsksResync()                 // Frames(0..2) then Frames(6..) -> accept false
@Test fun pickForARunFrameAsksResync()    // PickApplied for a frame already run -> false
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `git commit -m "Co-op: the host records the battle's keys and writes, the replica plays them back in step"`

### Task 4: Who may join, and the join flow on both sides

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopJoin.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/data/coop/CoopJoinTest.kt`

**Interfaces:**
- Produces:
```kotlin
object CoopJoinRules {
    const val DEFAULT_RANGE = 8
    val RANGES = listOf(2, 4, 8, 16, 32)
    const val PROMPT_MS = 30_000L
    const val LINK_GRACE_MS = 60_000L
    data class Spot(val mapGroup: Int, val mapNum: Int, val tileX: Int, val tileY: Int)   // tiles = PresenceUpdate.fine / 16
    fun nearby(me: Spot?, partner: Spot?, range: Int): Boolean
    fun mayOffer(kind: Int, connected: Boolean, partnerReady: Boolean, partnerFree: Boolean, nearby: Boolean, selfReady: Boolean): Boolean
}
// The host's side: a battle is starting; decides offer / wait / arm / single.
class CoopHostJoin(private val now: () -> Long) {
    sealed interface State { object Idle; data class Asking(val battleId: Int, val since: Long); data class Accepted(val answer: CoopBattleMsg.Answer); object Single }
    val state: State
    fun battleStarting(battleId: Int, offer: Boolean)            // offer false -> Single at once
    fun onAnswer(a: CoopBattleMsg.Answer)                        // the asking battle's only; accept -> Accepted, else Single
    fun onCancel(battleId: Int); fun tick(); fun linkLost()      // tick: PROMPT_MS passed -> Single (sends nothing: the caller sends CANCEL TIMEOUT)
    val holdGame: Boolean                                        // true while Asking
    fun incomingOffer(): Boolean                                 // a partner's OFFER arrived: true = decline it (we're Asking / Accepted)
}
// The partner's side.
class CoopPartnerJoin(private val now: () -> Long) {
    sealed interface State { object Idle; data class Prompt(val offer: CoopBattleMsg.Offer, val since: Long); data class Joining(val battleId: Int); object Declined }
    val state: State
    fun onOffer(o: CoopBattleMsg.Offer, free: Boolean, ready: Boolean): CoopBattleMsg?   // busy -> Cancel(BUSY) to send; else Prompt and null
    fun accept(): Int?; fun decline(): Int?                      // the battle id to answer, null if no prompt
    fun onCancel(battleId: Int); fun tick()                      // PROMPT_MS -> Declined (the host times out on its own)
    val secondsLeft: Int
}
```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun nearbyByManhattanTiles()        // same map, dx 5 dy 3, range 8 -> true; dx 5 dy 4 -> false; other mapNum -> false; null -> false
@Test fun offerNeedsEverything()          // mayOffer false when any of kind 0, not connected, not ready, not free, not nearby, self not ready
@Test fun hostTimesOutAfterThirtySeconds()// Asking at t; tick at t+29,999 -> Asking; t+30,000 -> Single, holdGame false
@Test fun hostIgnoresOtherBattlesAnswer() // Answer for another battleId -> still Asking
@Test fun declineMeansSingle()            // Answer(accept=false) -> Single
@Test fun partnerBusyIsCancelled()        // onOffer(free=false) -> Cancel(BUSY), state Idle
@Test fun partnerPromptCountsDown()       // secondsLeft 30 at t, 1 at t+29,001, Declined at t+30,000
@Test fun crossedOffersBothDecline()      // host Asking; incomingOffer() -> true (the partner side answers BUSY), and the other device's host gets BUSY -> Single
@Test fun linkLostWhileAskingIsSingle()
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `git commit -m "Co-op: who may join a battle, and the 30 second join prompt on both sides"`

### Task 5: After the battle: what each game gets, checked

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopAfter.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/data/coop/CoopAfterTest.kt`

**Interfaces:**
- Consumes: `CoopBattle.check`, `CoopBattle.parseResult`, `CoopResult`, `decodePartyMon`, `Gen3BoxMon`.
- Produces:
```kotlin
sealed interface CoopOp {
    data class WriteMon(val slot: Int, val mon: ByteArray) : CoopOp
    data class GiveMon(val mon: ByteArray) : CoopOp
    data class RemoveItem(val item: Int, val count: Int) : CoopOp
    data class TrainerFlag(val trainerId: Int) : CoopOp
    data class Prize(val trainerId: Int) : CoopOp          // the money comes from CoopCore.prize on the receiving game
    object WhiteOut : CoopOp
    data class Evolve(val slot: Int) : CoopOp
    object Save : CoopOp
}
object CoopAfter {
    const val WON = 1; const val LOST = 2; const val RAN = 4; const val CAUGHT = 7
    /** The partner's own game after the host's [result]: sent = the team as answered (100 bytes each), slots = their party indexes. */
    fun forPartner(result: CoopResult, sent: List<ByteArray>, slots: IntArray, ownOtId: Int, ownParty: List<ByteArray>,
                   itemsUsed: Map<Int, Int>, kind: Int, trainers: IntArray, refused: (String) -> Unit): List<CoopOp>
    /** The host's game: whiteout when the team won but no host Pokémon can fight; save when trainers were beaten. */
    fun forHost(outcome: Int, hostParty: List<ByteArray>, kind: Int): List<CoopOp>
    fun ableCount(party: List<ByteArray>): Int
}
```
Order of `forPartner`'s list: `WriteMon`s, `GiveMon` (caught by partner), `RemoveItem`s, (WON and kind 2) `TrainerFlag` + `Prize` per non-zero trainer, then either `WhiteOut` (no able Pokémon in the party after the writes) or one `Evolve` per level-up bit whose slot's Pokémon didn't faint, then `Save` when anything but `RemoveItem` / `WriteMon` changed progress (a trainer flag) or after a white-out. Ruling for the spec's "whose Pokémon all faint": a player blacks out only when **no** Pokémon in their whole party can fight (the three who sat out count), as the game itself does.

- [ ] **Step 1: Write the failing tests** (fixture party as in `CoopBattleTest`; a "returned" mon = the sent one with HP / EXP changed and re-checksummed by a test helper)

```kotlin
@Test fun wonAgainstTwoTrainers()         // ops == [WriteMon x3, TrainerFlag(a), Prize(a), TrainerFlag(b), Prize(b), Save]
@Test fun returnedMonWithOtherPersonalityRefused() // WriteMon for that slot left out, refused("...") called
@Test fun returnedSpeciesChangedRefused()
@Test fun caughtWithHostOtIdRefused()     // no GiveMon
@Test fun caughtByPartnerGiven()          // CAUGHT, caughtBy 2, own OT -> GiveMon after the WriteMons
@Test fun trainerIdOutOfRangeDropped()    // 855 -> no TrainerFlag / Prize
@Test fun lostMeansWhiteOutAndNoFlags()   // LOST -> WriteMons, WhiteOut, Save; no TrainerFlag
@Test fun allThreeFaintedButBenchAbleNoWhiteOut()  // the party's other three can fight -> no WhiteOut
@Test fun levelUpEvolvesLivingOnly()      // bits 0b101, slot of bit 2 fainted -> Evolve(slots[0]) only
@Test fun ranGivesPartyBackOnly()         // RAN -> WriteMons only (+ RemoveItem for items used)
@Test fun hostWhiteOutWhenWonWithNoneAble()
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `git commit -m "Co-op: what each game gets from a joined battle, every returned Pokemon checked"`

### Task 6: The co-op battle screens

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/ui/CoopBattleUi.kt`, `.../companion/i18n/TrCoopBattle.kt`
- Modify: `.../companion/i18n/Tables.kt` (register the table), `ui-preview/src/render/Main.kt` (shots)

**Interfaces:**
- Consumes: `BattleMoveList` / `BattlePartyList` (`BattleLists.kt`), OPTION pieces, `MonView` / `MoveView`.
- Produces (state classes are plain Compose state holders the glue fills; no driver logic here):
```kotlin
class CoopJoinPromptState { var hostName by mutableStateOf(""); var kind by mutableStateOf(1); var secondsLeft by mutableStateOf(30); var cursorJoin by mutableStateOf(true) }
@Composable fun CoopJoinPrompt(state: CoopJoinPromptState, onJoin: () -> Unit, onDecline: () -> Unit)        // "<HOST> STARTED A BATTLE NEARBY." / WILD or TRAINERS, JOIN / NOT NOW, "30"
@Composable fun CoopHostWaiting(partnerName: String, secondsLeft: Int, onCancel: () -> Unit)                // "ASKING <NAME> TO JOIN..."
@Composable fun CoopLinkWait(partnerName: String, secondsLeft: Int)                                         // "WAITING FOR <NAME>..." (link drop)
enum class CoopMenuPage { ACTION, MOVES, PARTY, BAG, WAITING }
class CoopPartnerMenuState {   // the glue fills these from the replica's RAM and the partner's own bag
    var page by mutableStateOf(CoopMenuPage.WAITING); var cursor by mutableStateOf(0)
    var moves by mutableStateOf<List<MoveView?>>(emptyList()); var party by mutableStateOf<List<MonView>>(emptyList())
    var bag by mutableStateOf<List<Pair<Int, Int>>>(emptyList())   // item id, count (battle-usable only)
    var canRun by mutableStateOf(true); var message by mutableStateOf<String?>(null)
    fun key(k: MenuKey): CoopMenuChoice?   // D-pad moves the cursor, A picks, B goes back a page; null = nothing chosen yet
}
// CoopMenuChoice comes from Task 2 (CoopBattleWire.kt)
@Composable fun CoopPartnerMenu(state: CoopPartnerMenuState, onChoice: (CoopMenuChoice) -> Unit)
@Composable fun CoopNotice(text: String)   // "<NAME> USED <ITEM>!", "THERE ARE TWO WILD POKéMON! YOU CAN'T AIM A BALL!", results
```
FIGHT's move list and POKéMON's party list are `BattleMoveList` / `BattlePartyList` as BATTLE BUTTONS draws them; ACTION is FIGHT / POKéMON / BAG / RUN (RUN only `canRun`); BAG lists the partner's battle items (medicine, X items, balls) by name with "× N", a medicine then asks which of the partner's slots. A move with more than one possible target (a double) asks the target with the two foes' names. MenuKey is the companion window's.

- [ ] **Step 1: Write a failing state test** `app/src/test/kotlin/com/pokedaisy/app/companion/ui/CoopPartnerMenuStateTest.kt`: from ACTION, DOWN + A -> PARTY; A on a party slot -> `Switch(slot)`; B -> ACTION; FIGHT + A on move 1 with two foes up -> target page, A -> `Move(1, target)`; RUN hidden when `canRun` false (cursor skips it); BAG medicine -> slot page -> `Item(id, slot)`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement the state and the composables; add the strings with all seven translations** (localized games' words, about as short as the English; Japanese kana only). **Step 4: Run** → PASS; `scripts/check_translations.py` runs as `TranslationsTest` → PASS.
- [ ] **Step 5: Render and look at every screen:** add `coop-battle-prompt`, `coop-battle-waiting`, `coop-battle-link`, `coop-battle-menu-action`, `-moves`, `-target`, `-party`, `-bag`, `-notice` shots (light and dark) to ui-preview; `cd ui-preview && <cached gradle> render -Ponly=coop-battle` and `-Pdark=true -Pout=build/shots-dark`; read every PNG: nothing clipped, OPTION look, pixel corners.
- [ ] **Step 6: Commit** — `git commit -m "Co-op: the join prompt, the waits and the partner's battle menu"`

### Task 7: Field signals: joinable, free, the learn-a-move prompt

**Files:**
- Modify: `app/src/main/cpp/pk_coop_field.c`, `pokedaisy_jni.c` / `MgbaCore.kt` / `mgba_dump.c` (at the `coop-field` anchors)
- Create: `native-capture/coop/join_wild.txt`, `join_scripted.txt`, `join_rescue.txt`, `join_single_trainer.txt`, `join_two_trainers.txt`, `free_field.txt`, `free_start_menu.txt`, `free_busy.txt`, `learn_prompt.txt`

**Interfaces:**
- Consumes: Task 1's addresses.
- Produces: the `pk_coop_field.h` functions; `MgbaCore.pkCoopJoinKind(): Int`, `pkCoopJoinTrainers(): IntArray`, `pkCoopPartnerFree(): Boolean`, `pkCoopOnFreeField(): Boolean`, `pkCoopLearnPrompt(): Boolean`; mgba_dump `coopjoin` (prints `join K A B`), `coopfree` (prints `free F field G`), `cooplearn` (prints `learn L`).

Rules: joinable wild = no `BATTLE_TYPE_TRAINER`, `gMain.savedCallback == CB2_EndWildBattle`, none of FIRST_BATTLE / LEGENDARY / SAFARI / PYRAMID / ROAMER flags; two trainers = TRAINER and TWO_OPPONENTS, `savedCallback == CB2_EndTrainerBattle`, no INGAME_PARTNER / MULTI / FRONTIER / LINK flags, both opponent ids non-zero; everything else 0, and 0 unless `pk_coop_battle_starting`. Free = `pk_coop_on_free_field` (callback2 `CB2_Overworld`, controls free, no palette fade, not in battle) or the START menu's top level (`sStartMenuCallback == HandleStartMenuInput`). Learn prompt = armed and (`gBattlescriptCurrInstr` inside the learn-a-move scripts' range, or the summary screen's forget-a-move page open) and `expGetterMonId >= 3`.

- [ ] **Step 1: Write the failing headless tests.** `join_wild.txt`: walk Route 101 grass until `waittask Task_BattleStart`, `coopjoin` → `# want: join 1 0 0`. `join_scripted.txt`: setwildbattle + dowildbattle through ScriptContext_SetupScript (CLAUDE.md's recipe) with a Route 101 species → `join 0`. `join_rescue.txt`: start the first battle the way `special ChooseStarter`'s path does (BATTLE_TYPE_FIRST_BATTLE) → `join 0`. `join_single_trainer.txt` / `join_two_trainers.txt`: trainerbattle script bytes for one trainer / two at once → `join 0` / `join 2 A B`. `free_field.txt` → `free 1 field 1`; `free_start_menu.txt` (press START, wait) → `free 1 field 0`; `free_busy.txt` (START, then open POKéMON; and separately a sign's text box) → `free 0`. `learn_prompt.txt`: armed wild double, partner slot 3 one EXP point short of a level whose learnset adds a move, four moves known; KO with the partner → `learn 1` while the "learn a new move?" box is up, `learn 0` after B / A answer it.
- [ ] **Step 2: Run** `bash scripts/coop_headless_check.sh join_wild join_scripted ...` → FAIL (`unknown command coopjoin`).
- [ ] **Step 3: Implement** the functions, JNI wrappers and mgba_dump commands.
- [ ] **Step 4: Run** every new test → PASS; `gradle-slot.sh :app:externalNativeBuildDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — `git commit -m "Co-op: which battles can be joined, when a player is free, and the partner's learn-a-move prompt"`

### Task 8: Writing a battle's results into a game

**Files:**
- Modify: `app/src/main/cpp/pk_coop_after.c`, `pokedaisy_jni.c` / `MgbaCore.kt` / `mgba_dump.c` (at the `coop-after` anchors)
- Create: `native-capture/coop/after_give_party.txt`, `after_give_pc.txt`, `after_items.txt`, `after_flags_money.txt`, `after_whiteout.txt`, `after_evolve.txt`, `after_save.txt`, `after_not_free.txt`

**Interfaces:**
- Consumes: Task 1's addresses; `pk_call` as pk_coop gets it.
- Produces: the `pk_coop_after.h` functions; `MgbaCore.pkCoopGiveMon(mon: ByteArray): Int`, `pkCoopRemoveItem(item: Int, count: Int): Int`, `pkCoopSetTrainerFlag(id: Int): Int`, `pkCoopPrize(id: Int): Int`, `pkCoopAddMoney(amount: Int): Int`, `pkCoopWritePartyMon(slot: Int, mon: ByteArray): Int`, `pkCoopWhiteOut(): Int`, `pkCoopEvolve(slot: Int): Int`, `pkCoopSaveGame(): Int`; mgba_dump `coopgive FILE`, `coopremove ITEM N`, `coopflag ID`, `coopprize ID`, `coopmoney N`, `coopwritemon SLOT FILE`, `coopwhiteout`, `coopevolve SLOT`, `coopsave` (each prints `<name> R`).

Each runs through the game's own function (`GiveMonToPlayer` - party, else PC; then `GetSetPokedexFlag` SEEN and CAUGHT by `SpeciesToNationalPokedexNum`; `RemoveBagItem`; `FlagSet(TRAINER_FLAGS_START + id)`; `AddMoney(&gSaveBlock1Ptr->money, n)`; `SetMainCallback2(CB2_WhiteOut)` with `gMain.state = 0`; `gCB2_AfterEvolution = CB2_ReturnToField` + `BeginEvolutionScene(mon, GetEvolutionTargetSpecies(mon, EVO_MODE_NORMAL, 0), TRUE, slot)`; the START menu's save steps), only while `pk_coop_on_free_field` (else -1). `pk_coop_prize` ports `GetTrainerMoneyToGive` over ROM reads (last party member's level x 4 x the class's rate, x 2 for a trainer whose party is a double's, as the game computes it), no Amulet Coin (ruling: the partner's lead item isn't looked at). Ids: trainer 1..854, slot below `gPlayerPartyCount`, item 1..376, count 1..99, money 1..999,999; out of range = 0, nothing written.

- [ ] **Step 1: Write the failing headless tests**: give with a free party slot → `give 0`, party count +1, the dex caught flag set; with a full party → `give 1`, PC box 1's first free slot holds it; remove a POTION → its count -1, removing more than held → `remove 0`; flag + prize + money: flag bit set, money increased by exactly the amount; whiteout: within 400 frames on the last heal spot's map, party healed, money halved; evolve: a Lv 16 TORCHIC (`writemon` a crafted one) → `evolve 1`, after the scene species is its next stage and the game is back on a free field; save: `coopsave` → `save 1`, then a second boot from the written `.sav` (copy it out) holds the changes; `after_not_free.txt`: each call inside a text box → `-1`, RAM unchanged (`memhash` before / after).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS; `externalNativeBuildDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — `git commit -m "Co-op: a battle's results written through the game's own functions on a free field"`

### Task 9: The replica: arm without RAM, abort, pinned clock, app writes, RAM CRC

**Files:**
- Modify: `app/src/main/cpp/pk_coop.c` / `.h`, `pokedaisy_jni.c` (`pkWriteBytes` calls `pk_coop_note_write`; new state calls) / `MgbaCore.kt` / `mgba_dump.c` (at the `coop-replica` anchors)
- Create: `native-capture/coop/replica_record.txt`, `replica_play.txt`, `replica_writes.txt`, `replica_abort.txt`, `replica_rtc.txt`; `scripts/coop_headless_check.sh` (pairs: a `replica_play*` test runs after its `replica_record*` and gets its printed `crc` lines as `# want:`)

**Interfaces:**
- Produces: the `pk_coop.h` additions; `MgbaCore.pkCoopReplicaArm(kind: Int, name: ByteArray, gender: Int): Boolean`, `pkCoopAbort()`, `pkCoopRtcPin(epochSeconds: Long)`, `pkCoopRtcUnpin()`, `pkStateSaveBytes(): ByteArray?`, `pkStateLoadBytes(state: ByteArray): Boolean` (mGBA state in memory, flags without `SAVESTATE_SAVEDATA`), `pkCoopRecordWrites(on: Boolean)`, `pkCoopTakeWrites(): ByteArray?`, `pkCoopRamCrc(): Int`; mgba_dump `statesave FILE`, `stateload FILE`, `coopreplicaarm KIND NAMEHEX GENDER`, `coopabort`, `rtcpin EPOCH`, `ramcrc` (prints `crc FRAME X`), `pokelog` (a `poke*` that goes through the write log).

`pk_coop_arm` is split into its RAM part and its ROM part; `pk_coop_replica_arm` runs the ROM part (and the internal armed state the tick needs) only. `pk_coop_abort` takes the battle's ROM patches out and forgets the battle without touching RAM. `pk_coop_rtc_pin` sets the core's RTC to mGBA's fake epoch (time = epoch + emulated time), `unpin` back to the wall clock.

- [ ] **Step 1: Write the failing headless tests.** `replica_record.txt`: `rtcpin 1800000000`, armed wild double (as `arm_wild.txt`), `statesave /work/rep.state` right after `cooparm` says armed, then fixed presses and `cooppick`s at fixed frames, `ramcrc` at +300, +900, +1500. `replica_play.txt`: boot, `stateload /work/rep.state`, `coopinstall`, `coopreplicaarm 1 <name> 1`, `rtcpin 1800000000`, the same presses / picks at the same frame offsets → the same three `crc` lines. `replica_writes.txt`: the record run `pokelog`s a byte into gActionSelectionCursor at a frame; the play run without that write → a different crc at +900 (proves the CRC sees it), with it (applied before the same frame) → equal. `replica_abort.txt`: armed, `coopabort`, `romcheck` → the file's bytes except the install's; then `stateload` of a pre-arm state, walk into another battle, `cooparm` → armed again. `replica_rtc.txt`: pinned, read the game's RTC through `RtcGetInfo` (`call`) twice 600 frames apart → 10 s apart exactly.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS, and every existing `native-capture/coop/*.txt` still PASS (the arm split changed `pk_coop_arm`); `externalNativeBuildDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — `git commit -m "Co-op: the partner's replica plays the host's battle in step, the clock pinned, the app's writes replayed"`

### Task 10: The two drivers, end to end on the JVM

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopCore.kt`, `CoopBattleHost.kt`, `CoopBattlePartner.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/data/coop/CoopBattleDriversTest.kt` (a `FakeCoopCore` per side: RAM as a byte array, frames advance a counter, `stateSave` / `stateLoad` copy RAM, waiting / pick / result scripted; an in-memory channel pair with a switch to drop the link)

**Interfaces:**
- Consumes: Tasks 2-5; `CoopCore` (above).
- Produces:
```kotlin
class CoopBattleHost(core: CoopCore, channel: CoopBattleChannel, now: () -> Long, private val ui: CoopHostUi) {
    var range: Int; var partnerSpot: CoopJoinRules.Spot?; var mySpot: CoopJoinRules.Spot?   // the glue keeps these current
    val busy: Boolean               // hold .. result saved
    val holdGame: Boolean           // the engine runs no frames while true (asking, link drop)
    fun beforeFrame(keys: Int): Int // emu thread, before each frame: returns the keys to run (the partner's during the learn prompt)
    fun afterFrame(keys: Int)
    fun onMessage(m: CoopBattleMsg) // emu thread (the glue hops messages there)
    fun linkChanged(connected: Boolean)
}
interface CoopHostUi { fun asking(partner: String, secondsLeft: Int); fun linkWait(secondsLeft: Int); fun notice(text: String); fun clear() }
class CoopBattlePartner(core: CoopCore, channel: CoopBattleChannel, now: () -> Long, private val ui: CoopPartnerUi) {
    val busy: Boolean               // prompt answered JOIN .. own game back and results saved
    val replicaRunning: Boolean
    fun beforeFrame(localKeys: Int): Int?   // null = run no frame now; else the keys for the replica frame (or the own game's when idle)
    fun framesThisTick(): Int
    fun afterFrame()
    fun onMessage(m: CoopBattleMsg)
    fun choose(c: CoopMenuChoice)    // from the menu
    fun answerPrompt(join: Boolean)
    fun stopNow()                    // the app is leaving: own game back at once (before any suspend state), CANCEL(CLOSED) sent
    fun linkChanged(connected: Boolean)
}
interface CoopPartnerUi { fun prompt(offer: CoopBattleMsg.Offer, secondsLeft: Int); fun menu(waiting: Int); fun linkWait(secondsLeft: Int); fun notice(text: String); fun clear() }
```
Host order: `battleStarting` + `joinKind` + `mayOffer` → OFFER, hold → ANSWER accept → `CoopBattle.check` each mon again → unhold, `pkCoopInstall`, keep calling `arm` each frame until true (starting false → CANCEL ENGINE) → `rtcPin(now/1000)`, `stateSave` (the undo state was taken before the first `arm` try) → START + chunks → recorder on; PICK: when `waiting()` matches, `pick` (-1 → retry next frame, keep the seq), answer PICK_ANSWER, `PickApplied` for the next frame; host writes via `recordWrites(true)` / `takeWrites()` each frame; `learnPrompt()` → use the partner's last KEYS; result → RESULT (re-sent every 2 s until RESULT_ACK, at most 30 s), `rtcUnpin`, `CoopAfter.forHost` ops run on free field frames. Partner order: OFFER → prompt → ANSWER(team = `CoopBattle.armArguments` of the own party, with the slots) → START → `romChangesOut`, `stateSave` of the own game (kept), `install`, `reusableTms` if asked, `replicaArm`, `rtcPin`, `stateLoad` → feed; each tick `framesThisTick()` steps; menu from `waiting()`; HASH mismatch / feed gap → RESYNC (host answers with START at its current frame + chunks); RESULT → `stateLoad` own game, `rtcUnpin`, `abort`, `romChangesBack`, RESULT_ACK, then `CoopAfter.forPartner` ops one per free-field frame (each -1 retried); item uses = own accepted picks. The team's party indexes come from a new `CoopBattle.ableSlots(party, count): IntArray` (the indexes `firstThreeAble` packs; this task adds it to `CoopBattle.kt`).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun wildBattleEndToEnd()            // host offer -> partner joins -> replica runs every host frame with equal keys, picks reach the host, result -> partner ops run, both saved
@Test fun declinedStaysSingle()           // partner declines: host never arms, holdGame false right away
@Test fun noAnswerInThirtySeconds()
@Test fun armRefusedCancels()             // fake arm false until battleStarting false -> CANCEL ENGINE, partner back to its game, nothing changed
@Test fun hostWritesAreReplayed()         // a host writeBytes during the battle shows in the replica's RAM before the same frame
@Test fun hashMismatchResyncs()           // corrupt the replica RAM -> RESYNC -> fresh START -> CRCs match again
@Test fun linkDropUnder60sCarriesOn()
@Test fun linkDropOver60sUndoes()         // host RAM == the pre-arm state, abort called; partner RAM == its own game, no ops run
@Test fun partnerStopRestoresOwnGameFirst() // stopNow(): the own state loaded before stopNow returns
@Test fun refusedPickShowsMenuAgain()     // pick 0 -> PICK_ANSWER false -> ui.menu again with a message
@Test fun retryPickKeepsTrying()          // pick -1 twice then 1 -> one PICK_ANSWER true, one PickApplied
@Test fun learnPromptForwardsPartnerKeys()
@Test fun partnerItemNoticeOnBoth()       // an accepted item pick -> notice "<NAME> USED <ITEM>!" on both uis
@Test fun resultResentUntilAcked()
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS, and `*Coop*` tests all PASS.
- [ ] **Step 5: Commit** — `git commit -m "Co-op: the host and partner drivers, a whole joined battle proven on the JVM"`

### Task 11: The game: engine hooks, link, presence, screens

Needs the integration branch with plans 2 and 3 (ask the parent session; Tasks 1-10 don't).

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/CoopBattles.kt`
- Modify: `app/src/main/kotlin/com/pokedaisy/app/EmulatorEngine.kt`, `app/src/main/kotlin/com/pokedaisy/app/PokeDaisyActivity.kt`, the RetroAchievements idle path (`achievements/RetroAchievements.kt`)
- Test: `app/src/test/kotlin/com/pokedaisy/app/CoopBattlesTest.kt`

**Interfaces:**
- Consumes: Task 10's drivers, plan 2's `CoopSession.link` (`CoopLink.state` / `messages` / `send`), plan 3's received `PresenceUpdate` and `pkPartnerSelf`, Task 6's UI.
- Produces: `class MgbaCoopCore : CoopCore` (each call to its `MgbaCore` external; `romChangesOut` / `romChangesBack` through the activity's PLAYER LOOK / TEXT LOOK / option-patch runners); `class CoopLinkChannel(link: CoopLink) : CoopBattleChannel`; `object CoopBattles { val busy: Boolean; fun attach(activity: PokeDaisyActivity, link: CoopLink?); fun beforeFrame(keys: Int): Int?; fun afterFrame(); fun stopNow() }`; `EmulatorEngine.coopBattles: CoopFrameHook?` (`interface CoopFrameHook { fun hold(): Boolean; fun framesThisTick(): Int; fun beforeFrame(keys: Int): Int?; fun afterFrame() }`).

Wiring: the engine asks the hook before each `pkSetKeys` / `pkRunFrame` (held = no frame, audio paused); messages from `link.messages` hop to the emu thread through `postToEmu`; STATUS sent every 1 s and on change from `pkCoopPartnerFree` + readiness; spots from plan 3's last received update and `pkPartnerSelf`; the overlays in the HUD's ComposeView (`GameStageLayout.overGame`, shown even with the HUD off), the partner menu taking game keys like QUICK MENU does (`quickMenuTakes`); while `CoopBattles.busy`: RESTART / LOAD STATE / SAVE STATE / REWIND / PLAYER LOOK / REUSABLE TMS / CHEATS refused with a notice, RA idles; `onPause` / sleep / `onDestroy` call `CoopBattles.stopNow()` before `stopWithSuspend` / `sleep` writes a state; a game whose SHA-1 isn't retail Emerald gets no hook.

- [ ] **Step 1: Write the failing tests** `CoopBattlesTest`: `stopWhileReplicaRestoresFirst` (a fake engine records the order: own state loaded, then the suspend state written); `busyBlocksLoadState` (`loadStateBlocked()` true while busy); `notRetailEmeraldNoHook`; `messagesHopToEmuThread` (a received message is handled only in the posted job).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS; `./gradlew :app:testDebugUnitTest` → all pass; `./gradlew :app:assembleDebug -Ppokedaisy.coop=true` → builds.
- [ ] **Step 5: Look at it:** ui-preview renders of the overlays over a game frame (`coop-battle-*-over-game`); read the PNGs.
- [ ] **Step 6: Commit** — `git commit -m "Co-op: joined battles in the game, over the link and the partner's presence"`

### Task 12: NEARBY RANGE, the manual and the notes

Needs the integration branch (plan 2's CO-OP group and manual draft). Runs beside Task 11 (disjoint files: Task 11 reads `Prefs.coopNearbyRange` only).

**Files:**
- Modify: `Prefs.kt` (`coopNearbyRange`, default 8), `SettingsActivity.kt` and `companion/CompanionSettings.kt` + `companion/ui/CompanionSettingsScreen.kt` (NEARBY RANGE in the CO-OP group on both screens, an `OptionSelector` of 2 / 4 / 8 / 16 / 32 STEPS, `labelBadge` with `NewFeatures.COOP`; `CompanionSettings.coopNearbyRange` with a default getter / setter on the interface so the activity needs no change), `companion/ui/SettingRows.kt` (`FLAGGED_SETTING_KEYWORDS`: "range", "distance", "join", "co-op battle"), `TrCoopBattle.kt` (the row's words), `docs/manual-drafts/coop.md` ("Battling together": the join prompt, who can join, three each, the menu, balls with two wild, what each save gets, blackout, link drops, what's blocked, PLAYER LOOK / TEXT LOOK off), `docs/DEVELOPMENT.md` ("Co-op: joined battles": the protocol table, the replica swap and its stop order, the lockstep, the headless tests, the rulings), `native-capture/README.md` (Tasks 7-9's commands), `CLAUDE.md` (a short note under the co-op notes)
- Test: ui-preview `settings-coop*` shots

- [ ] **Step 1:** the row on both screens; render `-Ponly=settings` and the companion's SETTINGS, read the PNGs.
- [ ] **Step 2:** the manual draft section and the docs; `make docs-check` (or its `docs.test.js` with the website's Node) → PASS on the draft (no dashes, no real names).
- [ ] **Step 3:** `TranslationsTest` → PASS.
- [ ] **Step 4: Commit** — `git commit -m "Co-op: NEARBY RANGE, and joined battles in the manual draft and the developer notes"`

---

## Execution order (subagent-driven, parallel)

- Wave 1 (at once, each in its own worktree): Tasks 1, 2, 3, 4, 5, 6. Merge in that order.
- Wave 2 (after Task 1 is merged; Task 10 after 2-5): Tasks 7, 8, 9, 10. Merge 7, 8, 9 (anchors keep their JNI / mgba_dump blocks apart), then 10.
- Wave 3 (on the integration branch): Tasks 11 and 12. Merge 11, then 12.
