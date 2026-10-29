# Emerald Co-op, Plan 1: Battle Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retail English Emerald can run a co-op double battle (wild or trainer): the host's three Pokémon left, a partner's three right, the partner's choices read from a mailbox the app fills, full EXP per side, the one-left Poké Ball rule, the partner's catches and evolutions kept out of the host's game, and a result the app can send back. No network, no UI: proven headless on the real ROM.

**Architecture:** A plain C module, `app/src/main/cpp/pk_coop.c`, over `struct mCore*` (no JNI), compiled into the app and into `native-capture/mgba_dump.c` like `pk_cheats.c` / `pk_wide.c`. It installs in-memory ROM patches through `pk_cheats_patch` / `pk_cheats_block` (a block of our Thumb code at the end of the ROM, plus pointer swaps in the game's own handler tables), arms a battle by writing RAM while the game's battle-start task runs, and reads results out. Addresses come from pret's pokeemerald built byte-identical to retail, generated into a C header. A thin Kotlin layer (`companion/data/coop/`) validates partner Pokémon and parses results, unit-tested on the existing `emerald_vanilla` fixture.

**Tech Stack:** C (mGBA 0.10.5), Thumb assembly (arm-none-eabi-as in the `pokedaisy-capture` Docker image), Python 3 generator, Kotlin + JUnit, Docker.

**Spec:** `docs/superpowers/specs/2026-10-24-emerald-coop-design.md` (sections 3 and 4). This is plan 1 of 5: 2 connection and pairing, 3 seeing each other, 4 joined battles end to end, 5 the lock and story events.

**Left to plan 4 (needs the partner's device):** the join prompt and the host's hold, the partner's replica and menus, the partner answering "learn a new move?" for their own Pokémon (the host forwards the partner's keys while that prompt is up for slots 3-5), writing results into the partner's save, the partner's blackout, undo on a dropped link, auto-save.

## Global Constraints

- Game: retail English Emerald only, BPEE, SHA-1 `f3ae088181bf583e55daf962a92bb46f4f1d07b7` (the `RETAIL_SHA1S` entry). Every patched function's first 32 bytes are CRC-checked before anything is written; any mismatch installs nothing.
- Save files are sacred: nothing in this plan writes the save file. States used in tests are taken without save data.
- The game, its save and states never hold co-op data beyond the battle: host party slots 3-5 and party count are put back byte for byte after every co-op battle.
- ROM patches go only through `pk_cheats_patch` / `pk_cheats_block`, so the Poller's hash and RetroAchievements' see the file's bytes.
- RAM the game never uses: Emerald's last EWRAM symbol (sRayScene) ends at `0x0203CF64`; FieldItems owns `0x0203FFF0`-`0x0203FFFF`. Co-op takes `0x0203F000`-`0x0203F3FF`.
- Python generators run in the `pokedaisy-capture` Docker image (or on the Mac); this Windows machine has no Python on PATH.
- Commits carry no Claude / Anthropic attribution (the `commit-msg` hook rejects it). Commit messages follow the repo's style (plain sentence subject).
- Headless runs need the player's own ROM and a save with at least three Pokémon: `EMERALD_ROM` and `EMERALD_SAVE` environment variables (never committed). On an x86_64 Docker host, link mgba_dump with `-L/usr/lib/x86_64-linux-gnu` (the README's line is the Mac's aarch64 path).

## Review Focus

- A host with fewer than three Pokémon (one or two): the partner still fills slots 3-5 and the battle starts; host slots 1-2 stay empty. Test in Task 4.
- A partner Pokémon struct that fails its checksum, is an egg, or names an out-of-range species: refused before any RAM write. Test in Task 10.
- The battle ending by the host running away or being defeated while the partner still has Pokémon: party restored, result still produced. Test in Task 9.
- A pick arriving that the game can't take (a move with 0 PP, a switch to a fainted or out-of-range slot): refused by the mailbox code, the game keeps waiting. Test in Task 3.
- Uninstall in the middle of a battle (the app closing the game): every patch and the mailbox go away, no partial state. Test in Task 2.

---

## File Structure

| File | Responsibility |
|---|---|
| `scripts/gen_coop_emerald.py` (create) | Reads the pokeemerald ELF / ROM built byte-identical to retail; writes `pk_coop_emerald.h` (addresses, code CRCs, struct offsets) and assembles `coop_emerald.s` into a byte array in that header. |
| `scripts/coop_emerald.s` (create) | Our Thumb code: the partner's controller handlers, the EXP split hook, the ball check, the catch redirect. Assembled at a fixed ROM address. |
| `app/src/main/cpp/pk_coop_emerald.h` (create, generated) | The table above; committed like `pk_wide_games.h`. |
| `app/src/main/cpp/pk_coop.h` / `pk_coop.c` (create) | install / uninstall, arm, pick, per-frame tick, result. |
| `app/src/main/cpp/CMakeLists.txt` (modify:96) | Add `pk_coop.c`. |
| `app/src/main/cpp/pokedaisy_jni.c` (modify) | JNI wrappers; hands `pk_call` to pk_coop and ticks it after each frame. |
| `app/src/main/kotlin/com/pokedaisy/app/MgbaCore.kt` (modify) | `external fun pkCoop*` declarations. |
| `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopBattle.kt` (create) | Partner party validation, arm arguments, result parsing. |
| `app/src/test/kotlin/com/pokedaisy/app/companion/data/coop/CoopBattleTest.kt` (create) | JVM tests on the `emerald_vanilla` fixture. |
| `native-capture/mgba_dump.c` (modify) | Includes `pk_coop.c`; new commands `expect8/16/32`, `waittask`, `coop*`. |
| `native-capture/coop/*.txt` (create) | Headless test scripts, one per behaviour. |
| `scripts/coop_headless_check.sh` (create) | Builds mgba_dump from the repo, runs every `native-capture/coop/*.txt` against `EMERALD_ROM` / `EMERALD_SAVE`, fails on any `FAIL`. |
| `native-capture/README.md`, `docs/DEVELOPMENT.md` (modify) | New commands; a "Co-op battle engine (Emerald)" section. |

## Shared interfaces (every task uses these names)

```c
// pk_coop.h
#define PK_COOP_MAILBOX 0x0203F000u   // struct pk_coop_mailbox, game-visible (our Thumb code reads it)
#define PK_COOP_RESULT  0x0203F040u   // the result (404 bytes, layout below), written by our Thumb code + pk_coop_tick
#define PK_COOP_BACKUP  0x0203F200u   // host slots 3-5 (300 bytes) + party count (1 byte)

// Waiting kinds the game reports in mailbox.waiting (0 = not waiting):
enum { PK_COOP_WAIT_NONE, PK_COOP_WAIT_ACTION, PK_COOP_WAIT_MOVE, PK_COOP_WAIT_ITEM, PK_COOP_WAIT_SWITCH };
// Pick actions (= pokeemerald B_ACTION_USE_MOVE / USE_ITEM / SWITCH / RUN):
enum { PK_COOP_ACT_MOVE = 0, PK_COOP_ACT_ITEM = 1, PK_COOP_ACT_SWITCH = 2, PK_COOP_ACT_RUN = 3 };

typedef uint32_t (*pk_coop_call_fn)(struct mCore* core, uint32_t fn, const uint32_t args[4]);

int  pk_coop_install(struct mCore* core, pk_coop_call_fn call);   // 1 = every CRC matched, all patches in; 0 = nothing changed
void pk_coop_uninstall(struct mCore* core);                        // patches out, mailbox / result / backup zeroed, host party restored if armed
int  pk_coop_battle_starting(struct mCore* core);                  // 1 while Task_BattleStart is active and not yet armed
int  pk_coop_arm(struct mCore* core, const uint8_t partner[3][100], int partnerCount,
                 const uint8_t name[8], int partnerGender, uint32_t partnerOtId);   // 1 = armed
int  pk_coop_waiting(struct mCore* core);                          // mailbox.waiting
int  pk_coop_pick(struct mCore* core, int action, int a, int b);   // MOVE: a=slot b=target; ITEM: a=item b=party slot 3..5 (ignored for balls); SWITCH: a=party slot 3..5; RUN. 1 = accepted
void pk_coop_tick(struct mCore* core);                             // after every frame
int  pk_coop_result(struct mCore* core, uint8_t* out, int cap);    // >0 = bytes of a finished battle's result, 0 = none yet
```

Result layout (`out`, little-endian, also what Kotlin parses):

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | outcome (= gBattleOutcome: 1 WON, 2 LOST, 4 RAN, 7 CAUGHT, ...) |
| 1 | 1 | partner count (1-3) |
| 2 | 300 | partner slots 3-5 after the battle (100 bytes each, as the game left them) |
| 302 | 1 | caught by: 0 nobody, 1 host, 2 partner |
| 303 | 100 | the caught Pokémon (when caught by partner; zeros otherwise) |
| 403 | 1 | partner's level-up bits (gLeveledUpInBattle bits 3-5, shifted down to 0-2) |

---

### Task 1: Address table from the decomp

**Files:**
- Create: `scripts/gen_coop_emerald.py`
- Create: `app/src/main/cpp/pk_coop_emerald.h` (generated)

**Interfaces:**
- Produces: in `pk_coop_emerald.h`, `#define`s named `COOP_EM_<SYMBOL>` for every address below, `COOP_EM_CRC_<FUNCTION>` (CRC32 of the function's first 32 ROM bytes) for every function, and struct offsets `COOP_EM_OFF_<NAME>`. Later tasks add `COOP_EM_CODE[]` / `COOP_EM_CODE_ADDR` (Task 3).

Symbols (functions unless noted): `PlayerPartnerHandleChooseAction`, `PlayerPartnerHandleChooseMove`, `PlayerPartnerHandleChooseItem`, `PlayerPartnerHandleChoosePokemon`, `PlayerPartnerBufferExecCompleted`, `BtlController_EmitTwoReturnValues`, `BtlController_EmitOneReturnValue`, `BtlController_EmitChosenMonReturnValue`, `sPlayerPartnerBufferCommands` (ROM table), `gBattleScriptingCommandsTable` (ROM table), `Cmd_getexp`, `Cmd_givecaughtmon`, `Cmd_trysetcaughtmondexflags`, `Cmd_trygivecaughtmonnick`, `ItemUseInBattle_PokeBall`, `gItems` (ROM table), `TryGenerateWildMon`, `GetCurrentMapWildMonHeaderId`, `gWildMonHeaders` (ROM), `Task_BattleStart`, `CB2_PreInitIngamePlayerPartnerBattle`, `ExecuteTableBasedItemEffect`, `GetMonData`, `DisplayItemMessage`, `CloseItemMessage`, `ScriptContext_SetupScript`, `CB2_Overworld`, `CB2_WhiteOut`, the evolution scene's CB2 (`CB2_EvolutionSceneUpdate`), `gTrainers`, `gSpeciesInfo` (ROM); RAM: `gMain`, `gTasks`, `gBattleTypeFlags`, `gPartnerTrainerId`, `gPlayerParty`, `gPlayerPartyCount`, `gEnemyParty`, `gBattleOutcome`, `gLeveledUpInBattle`, `gBattlerAttacker`, `gBattlerTarget`, `gActiveBattler`, `gBattleBufferA`, `gBattleMons`, `gBattlerPartyIndexes`, `gBattlersCount`, `gAbsentBattlerFlags`, `gBattleStruct`, `gExpShareExp`, `gPlayerAvatar`. Offsets: `struct Task` size and `.func` / `.isActive`; `struct BattleStruct.expGetterMonId`; `struct ChooseMoveStruct.moves`; `struct Trainer.trainerName` and size; `struct Item.battleUseFunc` and size; `TRAINER_STEVEN_PARTNER`; the two `TRAINER_BACK_PIC_STEVEN` immediates' addresses in `battle_controller_player_partner.c` (around its lines 1303 and 1796); the `gPartnerTrainerId == TRAINER_STEVEN_PARTNER` compare in the faint check (`battle_script_commands.c` ~3543).

- [ ] **Step 1: Build pokeemerald byte-identical to retail in Docker**

Clone `https://github.com/pret/pokeemerald` and build with agbcc in a container (pret's INSTALL.md Linux steps). Run: `sha1sum pokeemerald.gba` → Expected: `f3ae088181bf583e55daf962a92bb46f4f1d07b7`.

- [ ] **Step 2: Write `gen_coop_emerald.py <pokeemerald dir>`**

Symbols from `arm-none-eabi-nm pokeemerald.elf` (local `t` symbols included: several are static), CRCs from `pokeemerald.gba`, offsets by compiling `offsetof` probes with agbcc against the decomp's headers (the method NativeReader's Emerald comments describe), the immediates' addresses found by disassembling the named functions. Refuse to write the header if the ROM's SHA-1 isn't retail's.

- [ ] **Step 3: Generate and inspect**

Run: `python3 scripts/gen_coop_emerald.py $DECOMPS/pokeemerald` → Expected: `app/src/main/cpp/pk_coop_emerald.h` with every symbol above; spot-check three against NativeReader.kt (`gPlayerParty` `0x020244EC`, `gBattleTypeFlags` `0x02022FEC`, `gEnemyParty` `0x02024744`).

- [ ] **Step 4: Commit**

```bash
git add scripts/gen_coop_emerald.py app/src/main/cpp/pk_coop_emerald.h
git commit -m "Co-op: Emerald battle addresses from the retail-identical decomp build"
```

### Task 2: pk_coop skeleton, headless harness, install / uninstall

**Files:**
- Create: `app/src/main/cpp/pk_coop.h`, `app/src/main/cpp/pk_coop.c`
- Modify: `app/src/main/cpp/CMakeLists.txt:96`, `native-capture/mgba_dump.c` (include block ~line 128, command dispatch ~line 950)
- Create: `native-capture/coop/install.txt`, `scripts/coop_headless_check.sh`

**Interfaces:**
- Consumes: Task 1's header; `pk_cheats_patch`, `pk_cheats_block`, `pk_cheats_unpatch`, `pk_cheats_unblock` (pk_cheats.h).
- Produces: `pk_coop_install`, `pk_coop_uninstall` (shared interfaces); mgba_dump commands `expect8 ADDR VAL`, `expect16`, `expect32` (print `PASS <addr>` or `FAIL <addr> got X want Y`, exit status 1 at end of input if any failed), `waittask FUNC MAX` (run frames until a `gTasks` entry is active with that func, `FAIL` after MAX), `coopinstall` (prints `coop: installed` / `coop: refused <first mismatching function>`), `coopuninstall`.

- [ ] **Step 1: Write the failing headless test `native-capture/coop/install.txt`**

```
# boot to the field from EMERALD_SAVE (boot/default.txt's presses), then:
coopinstall
expect32 0x0203F000 0
coopuninstall
romcheck $EMERALD_ROM
```
`coop_headless_check.sh` greps for `coop: installed` and for `romcheck`'s "raw bus == file" line after uninstall.

- [ ] **Step 2: Run it to make sure it fails**

Run: `scripts/coop_headless_check.sh install` → Expected: FAIL, `unknown command coopinstall`.

- [ ] **Step 3: Implement install / uninstall and the harness commands**

`pk_coop_install` checks every `COOP_EM_CRC_*` against the bus first, then refuses if any existing `pk_cheats` block overlaps `[COOP_EM_CODE_ADDR, +size)` (PLAYER LOOK / TEXT LOOK grow upward from the ROM's tail), then puts the patches in (none yet: the list grows in later tasks; keep it a static table of `{expectAddr, expect, addr, value}`), zeroes `0x0203F000`-`0x0203F3FF`. `pk_coop_uninstall` restores the host party if armed (Task 4 fills that in), unpatches only its own entries (pk_cheats has one option list: add `pk_cheats_unpatch_range(addr, len)` to pk_cheats.c rather than dropping REUSABLE TMS), unblocks only its block, zeroes the RAM range. `pk_coop.c` gets `pk_call` through the `pk_coop_call_fn` passed to install (mgba_dump passes its own `pk_call`).

- [ ] **Step 4: Run it to verify it passes**

Run: `scripts/coop_headless_check.sh install` → Expected: `coop: installed`, all `PASS`, romcheck matches the file.

- [ ] **Step 5: Add `native-capture/coop/uninstall_midbattle.txt` (Review Focus 5)**

Start a scripted wild battle (setwildbattle / dowildbattle through ScriptContext_SetupScript, as CLAUDE.md's headless notes describe), `coopinstall`, `wait 300`, `coopuninstall`, `romcheck`: every patch gone, `expect8` zeros across `0x0203F000`-`0x0203F3FF`. Run → PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/cpp/pk_coop.h app/src/main/cpp/pk_coop.c app/src/main/cpp/pk_cheats.c app/src/main/cpp/pk_cheats.h app/src/main/cpp/CMakeLists.txt native-capture/mgba_dump.c native-capture/coop scripts/coop_headless_check.sh
git commit -m "Co-op: pk_coop install / uninstall and the headless harness"
```

### Task 3: The mailbox: the partner's choices come from the app

**Files:**
- Create: `scripts/coop_emerald.s`
- Modify: `scripts/gen_coop_emerald.py` (assemble it), `app/src/main/cpp/pk_coop_emerald.h` (regenerated), `app/src/main/cpp/pk_coop.c`, `native-capture/mgba_dump.c`
- Create: `native-capture/coop/mailbox.txt`, `native-capture/coop/mailbox_refuse.txt`

**Interfaces:**
- Consumes: Task 2's install table.
- Produces: `COOP_EM_CODE[]`, `COOP_EM_CODE_ADDR` (= `0x08FFF000`; the file must hold 0xFF there: the block's expect CRC) and one `COOP_EM_ENTRY_<NAME>` per routine; `pk_coop_waiting`, `pk_coop_pick`; mailbox layout:

```c
struct pk_coop_mailbox {      // at PK_COOP_MAILBOX
    uint8_t armed;            // 1 while a co-op battle runs (Task 4 sets it)
    uint8_t waiting;          // PK_COOP_WAIT_*, written by our handlers when they find no pick
    uint8_t action;           // PK_COOP_ACT_*, 0xFF = no pick
    uint8_t moveSlot, target;
    uint8_t switchSlot;       // party slot 3..5
    uint16_t item;
    uint32_t partnerOtId;     // Task 6
    uint8_t backPic, gender;  // Task 4
    uint8_t seq;              // +1 per pick consumed (for the app's logs and replica checks)
    uint16_t expHost, expPartner, shareHost, sharePartner;   // Task 6: per-side EXP, written by our getexp hook
};
```
- mgba_dump commands: `coopwaiting` (prints `waiting N`), `cooppick ACTION A B`.

- [ ] **Step 1: Write the failing test `native-capture/coop/mailbox.txt`**

Arm a scripted double battle by hand for this task only (`poke32` gBattleTypeFlags = DOUBLE|MULTI|INGAME_PARTNER, `poke16` gPartnerTrainerId = TRAINER_STEVEN_PARTNER, copy the save's own slots 0-2 to 3-5 with `memcopy`, party count 6) during `waittask Task_BattleStart`, then:
```
wait 900
coopwaiting            # expect: waiting 1  (partner's action)
expect8 <slot3 move1 PP addr via peek> <unchanged>
wait 600
coopwaiting            # still waiting 1: the game waits, nothing times out
cooppick 0 0 0
cooppick 0 1 1         # FIGHT, move slot 1 at target 1
wait 900
# partner's move 2 PP dropped by one (read through GetMonData via `call COOP_EM_GetMonData slot3 MON_DATA_PP2`)
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `scripts/coop_headless_check.sh mailbox` → Expected: FAIL: the AI picks on its own (PP drops before any `cooppick`).

- [ ] **Step 3: Write the handlers in `coop_emerald.s` and swap them into `sPlayerPartnerBufferCommands`**

Each of CHOOSEACTION / CHOOSEMOVE / CHOOSEITEM / CHOOSEPOKEMON's table entries (4-byte pointer patches) points at our routine. A routine with no pick (`action == 0xFF`) sets `waiting` and returns without `PlayerPartnerBufferExecCompleted`, so the controller calls it again next frame (that's how the game's own handlers wait). With a pick it clears `waiting`, sets `action = 0xFF`, bumps `seq`, emits what the AI version would (`BtlController_EmitTwoReturnValues(B_COMM_TO_ENGINE, action, 0)` for an action; `B_ACTION_EXEC_SCRIPT, slot | target << 8` for a move; `EmitOneReturnValue(item)`; `EmitChosenMonReturnValue(slot, NULL)` for a switch) and calls `PlayerPartnerBufferExecCompleted`. With `armed == 0` each routine tail-calls the original AI handler, so Steven's battle is untouched.

- [ ] **Step 4: Implement `pk_coop_pick` with refusal (Review Focus 4)**

Refuse (return 0, write nothing) when: `waiting` doesn't match the action's stage; a move slot with species move 0 or 0 PP (read from gBattleMons[2]); a target not an alive opponent or the partner itself where the move allows; a switch slot outside 3..5, fainted, an egg, or already in battle.

- [ ] **Step 5: Write `mailbox_refuse.txt`**: `cooppick` a 0-PP move (poke PP to 0 first) → prints `coop: pick refused`, `coopwaiting` still `2`. Run both tests → PASS.

- [ ] **Step 6: Commit**

```bash
git add scripts/coop_emerald.s scripts/gen_coop_emerald.py app/src/main/cpp/pk_coop_emerald.h app/src/main/cpp/pk_coop.c native-capture/mgba_dump.c native-capture/coop
git commit -m "Co-op: the partner's choices come from a mailbox, the game waits for them"
```

### Task 4: Arming a battle: partner party, flags, name, picture, loss rule

**Files:**
- Modify: `scripts/coop_emerald.s`, `app/src/main/cpp/pk_coop.c`, `native-capture/mgba_dump.c`
- Create: `native-capture/coop/arm_wild.txt`, `native-capture/coop/arm_trainer.txt`, `native-capture/coop/loss_rule.txt`, `native-capture/coop/small_host.txt`

**Interfaces:**
- Consumes: Task 3's mailbox.
- Produces: `pk_coop_battle_starting`, `pk_coop_arm`; mgba_dump `coopstarting` (prints `starting 0|1`) and `cooparm FILE NAME GENDER OTID` (FILE = 300 bytes, 3 x struct Pokemon; NAME in Emerald's charset as hex).

- [ ] **Step 1: Write the failing test `arm_wild.txt`**

Scripted wild battle; `waittask Task_BattleStart`; `expect` `pk_coop_battle_starting` via `coopstarting` → `1`; `cooparm partner3.bin <hex name> 1 0x12345678`; `wait 1200`; then: `expect8 gBattlersCount 4`; gBattleMons[2].species == the file's first mon's species; host slots 3-5 hold the file; backup area holds the host's old slots 3-5 and count; `coopwaiting` prints `waiting 1` without any showcase screen (no input needed between the transition and the first choice).

- [ ] **Step 2: Run it to make sure it fails**

Run: `scripts/coop_headless_check.sh arm_wild` → Expected: FAIL `unknown command coopstarting`.

- [ ] **Step 3: Implement `pk_coop_arm`**

While `Task_BattleStart` runs (flags already set by DoStandardWildBattle / BattleSetup_StartTrainerBattle): back up slots 3-5 + count to `PK_COOP_BACKUP`; write partner structs to slots 3..3+n-1 (zeros after), count = 6 (empty host slots stay zero: Review Focus 1); `gBattleTypeFlags |= DOUBLE | MULTI | INGAME_PARTNER` (plus `TWO_OPPONENTS` when the trainer battle already had two trainers); `gPartnerTrainerId = TRAINER_STEVEN_PARTNER` (keeps Steven's window layout); mailbox `armed = 1`, back pic = `TRAINER_BACK_PIC_BRENDAN` / `_MAY` by `partnerGender`; patch the two `TRAINER_BACK_PIC_STEVEN` immediates to read it, `gTrainers[TRAINER_STEVEN_PARTNER].trainerName` (a block patch per battle) to `name`; patch the faint check's Steven compare so all six count; skip `CB2_PreInitIngamePlayerPartnerBattle`'s party showcase (point its caller at the step after it). Return 0 and write nothing unless `pk_coop_battle_starting` is 1.

- [ ] **Step 4: Run `arm_wild` → PASS. Write and run `arm_trainer.txt`** (a single-trainer battle through `trainerbattle`'s script bytes): two opponent battlers from one trainer's party, PASS. **If a one-Pokémon trainer can't start a double** (gBattlersCount stays 2 or the game hangs), `pk_coop_arm` returns 0 for trainers with fewer than two Pokémon and the test asserts that refusal instead (the spec's fallback).

- [ ] **Step 5: `loss_rule.txt`**: poke host slots 0-2 HP to 0 after the first turn, partner picks a move each turn: the battle continues (`gBattleOutcome` 0) until partner slots faint too, then outcome 2. **`small_host.txt`** (Review Focus 1): a save trimmed to one host Pokémon (`poke8` count 1, zero slots 1-2) arms and reaches `waiting 1`. Run → PASS.

- [ ] **Step 6: Commit**

```bash
git add scripts/coop_emerald.s app/src/main/cpp/pk_coop_emerald.h app/src/main/cpp/pk_coop.c native-capture/mgba_dump.c native-capture/coop
git commit -m "Co-op: arm a battle with the partner's party, name and picture; both sides must fall to lose"
```

### Task 5: The second wild Pokémon

**Files:**
- Modify: `app/src/main/cpp/pk_coop.c`
- Create: `native-capture/coop/second_wild_land.txt`, `native-capture/coop/second_wild_water.txt`

**Interfaces:**
- Consumes: `pk_coop_arm` (calls this when the battle has no TRAINER flag).
- Produces: `static int coop_second_wild(struct mCore*)` inside pk_coop.c.

- [ ] **Step 1: Write the failing test `second_wild_land.txt`**: warp to Route 101 grass (a `warp` script, see CLAUDE.md's WILD ENCOUNTERS notes), walk until `waittask Task_BattleStart`, `cooparm`, `wait 1200`: gEnemyParty[1] species non-zero and in Route 101's land table (the test lists the table's species from GuideTables), gBattleMons[3].species == gEnemyParty[1] species.

- [ ] **Step 2: Run it** → Expected: FAIL (gEnemyParty[1] empty, battler 3 absent).

- [ ] **Step 3: Implement**: save gEnemyParty[0] (100 bytes), `TryGenerateWildMon(&gWildMonHeaders[GetCurrentMapWildMonHeaderId()].<area>MonsInfo, area, 0)` through `pk_call` (it writes gEnemyParty[0]), copy that to gEnemyParty[1], put [0] back. Area: water while gPlayerAvatar's flags have SURFING, land otherwise. Fishing and Rock Smash rolls use their own tables: detect them from the encounter path the game took (builder finds the cleanest signal: the fishing task / rock smash script state) and roll the same table; with no table for the area, no second Pokémon and `pk_coop_arm` returns 0.

- [ ] **Step 4: Run both tests** (water: warp onto a surfable tile with SURFING set) → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/pk_coop.c native-capture/coop
git commit -m "Co-op: a joined wild battle gets a second wild Pokemon from the same table"
```

### Task 6: Full EXP for each side

**Files:**
- Modify: `scripts/coop_emerald.s`, `app/src/main/cpp/pk_coop.c`
- Create: `native-capture/coop/exp_split.txt`

**Interfaces:**
- Consumes: mailbox `partnerOtId` (set by `pk_coop_arm`).

- [ ] **Step 1: Write the failing test**: wild double, host battler 0 alone and partner battler 2 alone have been sent in, both foes Lv 5 of known species; KO foe 1 with the partner. Each side's sent-in Pokémon gains `expYield * level / 7` (no division by the other side's count; Lucky Egg 1.5x applies by item, the traded 1.5x applies to a partner Pokémon whose OT ID differs from `partnerOtId`). The test computes both numbers from gSpeciesInfo (read off the ROM with `peek32`) and `expect32`s each Pokémon's experience.

- [ ] **Step 2: Run it** → Expected: FAIL (each gets half).

- [ ] **Step 3: Implement**: a hook at the end of `Cmd_getexp`'s case 1 (BL into our routine) counts sent-in Pokémon per side (slots 0-2 vs 3-5) and stores both per-side shares (and Exp Share shares) in the mailbox's `expHost` / `expPartner` / `shareHost` / `sharePartner`; a hook where case 2 loads `*exp` picks the share by `expGetterMonId >= 3`; the traded-boost branch for slots 3-5 compares the mon's OT ID with `partnerOtId` instead of skipping it. Not armed = the game's own path.

- [ ] **Step 4: Run** → PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/coop_emerald.s app/src/main/cpp/pk_coop_emerald.h app/src/main/cpp/pk_coop.c native-capture/coop
git commit -m "Co-op: each side's Pokemon get the full EXP of a single battle"
```

### Task 7: A Poké Ball only works with one wild Pokémon left

**Files:**
- Modify: `scripts/coop_emerald.s`, `app/src/main/cpp/pk_coop.c`
- Create: `native-capture/coop/ball_rule.txt`

- [ ] **Step 1: Write the failing test**: armed wild double, both foes up; host opens BAG > POKé BALLS > a Poké Ball > USE (presses). Expect: the Poké Ball count unchanged, the screen's text box shows the refusal (`shot` + a pixel check, or the text printer's buffer `expect`ed), and the battle stays on the action menu. Then poke foe 2's HP to 0, let it faint, throw again: the count drops by one and the throw animation starts (gBattleCommunication / battle script position as in the game's own catch).

- [ ] **Step 2: Run it** → Expected: FAIL (the first ball is thrown).

- [ ] **Step 3: Implement**: the 12 Poké Ball entries' `battleUseFunc` pointers in `gItems` point at our wrapper: armed, no TRAINER flag, and both battlers 1 and 3 present (gAbsentBattlerFlags) and with HP > 0 → `DisplayItemMessage(taskId, FONT_NORMAL, sText, CloseItemMessage)`; else tail-call `ItemUseInBattle_PokeBall`. Text, in Emerald's charset in the code block: `"There are two wild POKéMON!\nYou can't aim a BALL!"`.

- [ ] **Step 4: Run** → PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/coop_emerald.s app/src/main/cpp/pk_coop_emerald.h app/src/main/cpp/pk_coop.c native-capture/coop
git commit -m "Co-op: no Poke Ball while two wild Pokemon are up"
```

### Task 8: The partner's items and catches

**Files:**
- Modify: `scripts/coop_emerald.s`, `app/src/main/cpp/pk_coop.c`
- Create: `native-capture/coop/partner_item.txt`, `native-capture/coop/partner_catch.txt`

**Interfaces:**
- Produces: `pk_coop_pick(core, PK_COOP_ACT_ITEM, item, targetSlot)` applies a medicine's effect itself before answering (the player side's battle script only prints "used"); result `caught by` + caught mon.

- [ ] **Step 1: Write the failing tests**: `partner_item.txt`: partner slot 3 at half HP, `cooppick 1 <POTION> 3` → its HP +20 and the "used POTION" message prints (the battle script's buffer). `partner_catch.txt`: one wild left, `cooppick 1 <POKE_BALL> 0` with the catch forced (poke the foe's catch odds: Master Ball id 1) → host's party / PC unchanged (party count, PC box 1 first free slot), host's Pokédex caught flag for the species unchanged, no nickname prompt, result `caught by = 2` with the foe's 100 bytes.

- [ ] **Step 2: Run them** → Expected: FAIL (no HP change; the catch lands in the host's party).

- [ ] **Step 3: Implement**: medicine through `ExecuteTableBasedItemEffect(&gPlayerParty[slot], item, slot, 0)` via `pk_call` before the pick is written (balls are thrown by the battle script; X items too go through the effect call, which sets stat stages in battle); `gBattleScriptingCommandsTable` entries for `givecaughtmon`, `trysetcaughtmondexflags`, `trygivecaughtmonnick` point at wrappers that, when armed and `gBattlerAttacker` is battler 2, copy `gEnemyParty[gBattlerPartyIndexes[target]]` to the result's caught slot, set `caught by = 2`, and skip to the next command; otherwise the originals (and a host catch sets `caught by = 1`).

- [ ] **Step 4: Run** → PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/coop_emerald.s app/src/main/cpp/pk_coop_emerald.h app/src/main/cpp/pk_coop.c native-capture/coop
git commit -m "Co-op: the partner's items act on their Pokemon, their catches stay out of the host's game"
```

### Task 9: End of battle: no partner evolution in the host, party back, result out

**Files:**
- Modify: `app/src/main/cpp/pk_coop.c`, `native-capture/mgba_dump.c`
- Create: `native-capture/coop/result_won.txt`, `native-capture/coop/result_ran.txt`, `native-capture/coop/no_partner_evolution.txt`

**Interfaces:**
- Produces: `pk_coop_tick`, `pk_coop_result` (layout above); mgba_dump `coopresult FILE` (writes the bytes, prints `result N bytes outcome X`).

- [ ] **Step 1: Write the failing tests**: `no_partner_evolution.txt`: partner slot 3 is a species one level short of evolving by level (its EXP poked to one point under), the battle ends with it levelled: no evolution scene runs in the host (gMain.callback2 never reaches the evolution scene's CB2 within 600 frames after the battle), result level-up bits = 1. `result_won.txt`: after a win, host slots 3-5 and count equal the backup byte for byte, `coopresult` outcome 1 and partner slots match what slots 3-5 held at battle end. `result_ran.txt` (Review Focus 3): the host runs; same restore, outcome 4.

- [ ] **Step 2: Run them** → Expected: FAIL.

- [ ] **Step 3: Implement `pk_coop_tick`**: armed and `gBattleOutcome` non-zero → copy slots 3-5, outcome, level-up bits 3-5 into the result area, clear `gLeveledUpInBattle` bits 3-5 (before `TryEvolvePokemon` reads them; if a test shows it can read them the same frame, patch its loop bound to 3 while armed instead), mark the result ready. Once `gMain.callback2` is back on the field's (CB2_Overworld, from Task 1's table) or the white-out's: restore slots 3-5 + count from the backup, `armed = 0`, pull the ROM patches that are per battle (name, back pic) back to the file's bytes.

- [ ] **Step 4: Run** → PASS. Also rerun every `native-capture/coop/*.txt`: `scripts/coop_headless_check.sh` → all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/pk_coop.c native-capture/mgba_dump.c native-capture/coop
git commit -m "Co-op: no partner evolution in the host, the host's party back, a result for the partner"
```

### Task 10: Kotlin bridge: validation, arming, results

**Files:**
- Modify: `app/src/main/cpp/pokedaisy_jni.c`, `app/src/main/kotlin/com/pokedaisy/app/MgbaCore.kt`
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopBattle.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/data/coop/CoopBattleTest.kt`
- Modify: `native-capture/README.md`, `docs/DEVELOPMENT.md`

**Interfaces:**
- Consumes: the C interfaces above.
- Produces:
```kotlin
// MgbaCore
external fun pkCoopInstall(): Boolean
external fun pkCoopUninstall()
external fun pkCoopBattleStarting(): Boolean
external fun pkCoopArm(partner: ByteArray, partnerCount: Int, name: ByteArray, gender: Int, otId: Int): Boolean
external fun pkCoopWaiting(): Int
external fun pkCoopPick(action: Int, a: Int, b: Int): Boolean
external fun pkCoopResult(): ByteArray?   // null until a battle has finished

// CoopBattle.kt
sealed interface CoopMonCheck { object Ok : CoopMonCheck; data class Refused(val reason: String) : CoopMonCheck }
object CoopBattle {
    const val MON_SIZE = 100
    fun check(mon: ByteArray): CoopMonCheck            // checksum, not an egg, species 1..411, level 1..100, HP <= max HP
    fun firstThreeAble(party: ByteArray, count: Int): ByteArray   // the first three that can fight, packed (spec: "first three that can still fight")
    fun parseResult(bytes: ByteArray): CoopResult
}
data class CoopResult(val outcome: Int, val partner: List<ByteArray>, val caughtBy: Int, val caught: ByteArray?, val levelUps: Int)
```

- [ ] **Step 1: Write the failing tests** (`emerald_vanilla` fixture's ewram.bin, gPlayerParty at EWRAM offset `0x244EC`):

```kotlin
@Test fun fixturePartyMonsPass() { party().forEach { assertEquals(CoopMonCheck.Ok, CoopBattle.check(it)) } }
@Test fun flippedByteFailsChecksum() { val m = party()[0].copyOf(); m[40] = (m[40] + 1).toByte(); assertTrue(CoopBattle.check(m) is CoopMonCheck.Refused) }
@Test fun eggIsRefused() { /* set the egg bit in the decrypted Misc substruct, re-encrypt, fix checksum */ }
@Test fun firstThreeSkipsFainted() { /* slot 0 HP 0 -> packed starts with slot 1 */ }
@Test fun parsesResultLayout() { /* 404 bytes: outcome 1, count 3, caughtBy 2 -> fields */ }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*CoopBattleTest'` → Expected: FAIL, unresolved `CoopBattle`.

- [ ] **Step 3: Implement `CoopBattle` on the existing Gen 3 decoding** (`decodePartyMon` and its substruct helpers in NativeReader.kt; reuse, don't copy) **and the JNI wrappers** (pokedaisy_jni.c calls `pk_coop_tick` right after each `pkRunFrame` on the main core only, never `rg`; `pk_coop_install` gets the file's static `pk_call`).

- [ ] **Step 4: Run** → PASS; then `./gradlew :app:testDebugUnitTest` → all pass; `./gradlew :app:assembleDebug` builds.

- [ ] **Step 5: Document** the new mgba_dump commands in `native-capture/README.md`'s table and add "Co-op battle engine (Emerald)" to `docs/DEVELOPMENT.md`: what's patched and why (the four handler pointers, the faint check, back pic, name, showcase skip, EXP hooks, ball wrapper, catch wrappers), the RAM map, and how to run `scripts/coop_headless_check.sh`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/cpp/pokedaisy_jni.c app/src/main/kotlin/com/pokedaisy/app/MgbaCore.kt app/src/main/kotlin/com/pokedaisy/app/companion/data/coop app/src/test/kotlin/com/pokedaisy/app/companion/data/coop native-capture/README.md docs/DEVELOPMENT.md
git commit -m "Co-op: Kotlin bridge for the battle engine, partner Pokemon checked before they go in"
```
