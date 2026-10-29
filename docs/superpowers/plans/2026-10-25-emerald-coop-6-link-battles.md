# Emerald Co-op, Plan 6: Joined Battles as Link Battles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A joined battle runs on each phone as Emerald's own link multi battle: the host's game is the link master and decides the battle, the partner's game shows it from the partner's side with its own menus, and the link blocks travel over the co-op connection instead of a cable.

**Architecture:** A native link bridge (`pk_coop_link.c` + Thumb hooks in `scripts/coop_link_emerald.s`, generated header from `gen_coop_emerald.py`) replaces `link.c`'s wired block layer in game: outgoing blocks land in an outbox the app drains, incoming blocks are written into `gBlockRecvBuffer` after a shape check. Per-battle patches (the existing `coop_battle_in` / `coop_battle_out` / `sCoopCrcs` gating) turn the link battle into a co-op battle: wild intro, EXP, BAG, catching, RUN, the normal end. Kotlin (`CoopLinkBattle`) pumps blocks between `MgbaCore` and `CoopSession`'s link, runs the 60 s drop rule and replaces the replica drivers. Headless tests run two cores in one `mgba_dump` (`pair` mode) passing blocks.

**Tech Stack:** C (mGBA 0.10.5), Thumb asm, Python 3 generator (Docker), mgba_dump headless scripts, Kotlin + JUnit, Compose (OPTION look).

**Spec:** `docs/superpowers/specs/2026-10-25-emerald-coop-link-battles-design.md` (amends `2026-10-24-emerald-coop-design.md`). Plans 1-5 and 2b are done on this branch; this plan replaces plan 1's partner controller path, plan 4's replica and the partner menu / cover UI.

## Global Constraints

- Game: retail English Emerald only (BPEE, SHA-1 `f3ae088181bf583e55daf962a92bb46f4f1d07b7`); every ROM patch is CRC-gated through `sCoopCrcs` and refused as a whole on a mismatch.
- The host (who started the battle) is the link master, multiplayer id 0; the partner is id 1 and battler 2 (`B_POSITION_PLAYER_RIGHT`) with its own `Player` controller.
- Teams: each player's first three able Pokémon (`CoopBattle.firstThreeAble`, `CoopBattle.check` on every packed mon); story battles use the link multi party screen to pick.
- Rules: both players throw balls; a ball works only when one wild Pokémon is left; whoever catches keeps it in their own save (party, else PC) with their own OT; full single-battle EXP on each side; level-ups / move learning / evolutions in the owner's game; each BAG is its owner's; RUN rolls the normal escape in wild battles, never from trainers; trainer win pays what a two-trainer battle pays (a single trainer with 2+ Pokémon: that trainer's own prize); a player blacks out only when no Pokémon in their whole party can fight once the battle ends.
- Link drop: both pause with "Waiting for <NAME>... <s>" (existing `CoopLinkWait`); back within 60 s = carry on; after 60 s: a story battle (every save, also a drop on its team-pick screen) = a loss, blackout for both; anything else = the battle ends for both, earned EXP / levels / catches / used items stay, HP / PP / status as they are, no prize, trainer not beaten.
- A player who doesn't answer never times out while the link is up.
- Story battles joinable in every save: join prompt only when the partner's game would run the same `trainerbattle` (at the same spot, trainer not beaten); both games enter the link battle at their own `trainerbattle`, each script sets its own flags.
- PLAYER LOOK and TEXT LOOK off on both games; REUSABLE TMS allowed; cheats keep co-op off.
- Nothing from the network reaches game memory unchecked (`CoopSafetyTest`): every received block is checked against its kind's size / shape before `pk_coop_link_give` writes it.
- Pace budget: a typical turn at 100 ms round trip at most 3 s longer than on the same Wi-Fi.
- Free EWRAM for co-op: 0x0203CF64-0x0203FFFF only; the link bridge's area is declared in `pk_coop.h` beside `PK_COOP_RAM`, not overlapping it or plan 5's lock RAM.
- Everything stays behind `FeatureFlags.coop`; strings in `TrCoopBattle.kt` in all 8 languages; manual text in `docs/manual-drafts/coop.md` (no em / en dashes, no real Pokémon / move / item names).
- Machine: Windows + Git Bash; generators and headless runs in Docker as in plan 4's constraints; at most two Gradle runs at once; baseline JVM failures are the known Windows ones. Never `find /`; never an interpreter reading empty stdin; never commit `third_party/melonds`, ROMs, saves, `local.properties`.
- Commits: no Claude / Anthropic attribution (hook enforced), `Co-op:` prefix, plain sentences.

## Review Focus

- A block that arrives when its game is not in a link battle (late after the end, early before the start, from a previous battle id): dropped, never written. Test in Task 1 (`link_late_block.txt`) and Task 8 (`staleBlocksAreDropped`).
- One game reaches the battle's end while the other is still in a text box: both reach the field, no hang, no extra blocks sent after the end. Test in Task 5 (`link_end_uneven.txt`).
- The host's app goes to HOME / is killed mid-battle: the partner gets the drop rule; the host's game on return does not resume inside a dead link battle. Test in Task 7 (`link_host_gone.txt`) and Task 8 (`hostStopEndsCleanly`).
- A team with fewer than three able Pokémon (1 or 2), or eggs in the first slots: what's able goes in, eggs never. Test in Task 2 (`link_two_able.txt`).
- A partner whose own game is in a menu or text box when the host's battle starts: no join prompt (existing free rule), and a story join waits for the partner's own trigger. Test in Task 6 (`storyNotOfferedWhenBusy`).

## Parallel execution (the user's request: up to 8 Sonnet low subagents at once)

Task 1 is the gate: it proves a link multi battle runs between two cores over the bridge. Stop and report if it can't. After it lands, Tasks 2-7 each own their own C file and their own section of `scripts/coop_link_emerald.s` (marked `@ task N` blocks, append-only) and run in parallel worktrees; Task 8 (Kotlin) runs in parallel with Tasks 2-7 against Task 1's interface. The controller merges them in order 2, 3, 4, 5, 6, 7, 8 (regenerating `pk_coop_emerald.h` once per merge), then Task 9 (remove the old path, docs) and Task 10 (pace, devices).

---

### Task 1: The link bridge and a two-core link multi battle (gate)

**Files:**
- Create: `app/src/main/cpp/pk_coop_link.c`, `scripts/coop_link_emerald.s`, `native-capture/coop/link_*.txt`
- Modify: `app/src/main/cpp/pk_coop.h`, `scripts/gen_coop_emerald.py` (+ regenerated `pk_coop_emerald.h`), `native-capture/mgba_dump.c`, `native-capture/README.md`, `scripts/coop_headless_check.sh`, `app/src/main/cpp/CMakeLists.txt` (if sources are listed)

**Interfaces:**
- Produces (C, `pk_coop.h`):
  ```c
  struct pk_coop_link_setup { uint8_t master; uint8_t kind; uint16_t trainerA, trainerB;   // kind: PK_COOP_LINK_WILD / TRAINERS / SINGLE / STORY
                              uint8_t partnerName[8]; uint8_t partnerGender; uint32_t partnerOtId; };
  enum { PK_COOP_LINK_WILD = 1, PK_COOP_LINK_TRAINERS, PK_COOP_LINK_SINGLE, PK_COOP_LINK_STORY };
  enum { PK_COOP_LINK_IDLE, PK_COOP_LINK_SETUP, PK_COOP_LINK_BATTLE, PK_COOP_LINK_ENDED };
  int  pk_coop_link_begin(struct mCore*, const struct pk_coop_link_setup*);  // 1 = the game is entering the link battle
  int  pk_coop_link_take(struct mCore*, uint8_t* out, int cap);              // next outgoing block, its size; 0 = none
  int  pk_coop_link_give(struct mCore*, const uint8_t* block, int size);     // 1 written; 0 refused (shape, state, full)
  int  pk_coop_link_state(struct mCore*);                                    // PK_COOP_LINK_*
  void pk_coop_link_reset(struct mCore*);                                    // hooks out, area zeroed
  ```
  A block on the wire = `[u8 kind][u8 seq][u16 size][bytes]`; `kind` is the bridge's own (header, party, seed, battle buffer, ...), assigned in Task 1 and listed in `pk_coop.h`.
- Produces (mgba_dump): `pair <save2>` (second core, same ROM), `on a|b` (which core the next commands drive), `linkrun N` (N frames on both cores, blocks moved each frame), `linkdelay F` (blocks delivered F frames late), `coopbegin master kind trainerA trainerB`.

- [ ] **Step 1:** Write `native-capture/coop/link_trainers.txt`: pair the co-op test saves (host `emerald.sav`, partner `emerald_partner.sav`), `coopbegin` on both (host master, kind TRAINERS, two Route trainers' ids), `linkrun` until both report battle state, each core picks a move with its own keys (`on a` / `on b` + key presses), `linkrun` to the next action prompt. `# want:` lines: both cores at `PK_COOP_LINK_BATTLE`; the partner core's battler 2 controller is the player controller (`expect32` on `gBattlerControllerFuncs[2]` = `PlayerHandleChooseAction`'s loop address from the generator); both cores' `gBattleMons` HP bytes equal after the turn; the partner core's own `gPlayerParty[0]` species is MUDKIP (its own team); the host's is TORCHIC.
- [ ] **Step 2:** Run it; expect FAIL (`unknown command pair`).
- [ ] **Step 3:** Implement the bridge: hook `SendBlock`, `IsLinkTaskFinished`, `GetBlockReceivedStatus`, `ResetBlockReceivedFlag(s)`, `GetMultiplayerId`, `GetLinkPlayerCount`, `BitmaskAllOtherLinkPlayers`, `OpenLink` / `CloseLink` and the link error checks with Thumb stubs reading / writing the bridge area; `pk_coop_link_begin` sets the link players (`gLinkPlayers`, names, genders, version signature so `FindLinkBattleMaster` picks the host) and starts the link multi battle from the field (builder's call: tower link multi or plain multi + two AI trainers, whichever undoes fewer facility rules; record the choice in DEVELOPMENT.md). Add mgba_dump's pair mode.
- [ ] **Step 4:** Run `link_trainers` until PASS. Also `link_late_block.txt`: a block given while IDLE and one with a stale seq after ENDED are refused (`pk_coop_link_give` = 0, `gBlockReceivedStatus` unchanged).
- [ ] **Step 5:** Run every existing headless test (`coop_headless_check.sh`) to confirm nothing else broke; commit `Co-op: link bridge, two games in one link multi battle headless`.

### Task 2: Starting a joined trainer battle from the field

**Files:** Create `app/src/main/cpp/pk_coop_link_start.c`, `native-capture/coop/link_start_*.txt`; modify `scripts/coop_link_emerald.s` (`@ task 2`), `gen_coop_emerald.py`.

**Interfaces:** Consumes Task 1's API. Produces `int pk_coop_link_host_hold(struct mCore*)` (1 while the host's `Task_BattleStart` is held for the join answer, the existing `pk_coop_battle_starting` moment) and the team rule inside `pk_coop_link_begin` (party slots 0-2 = first three able, rest set aside and restored at the end, plan 1's backup at `PK_COOP_BACKUP`).

- [ ] **Step 1:** Tests: `link_start_trainers.txt` (host walks into two Route trainers who see the player at once, partner joins: both in the link battle with the two trainers' parties, prize later per Task 5); `link_start_single.txt` (a single trainer with 2+ Pokémon split across the two foe slots, as `pk_coop_single_trainer_prepare` does now); `link_two_able.txt` (partner with an egg first and one fainted: two able go in, no egg).
- [ ] **Step 2:** Run; expect FAIL.
- [ ] **Step 3:** Implement: host held at battle start, `pk_coop_link_begin` on both from a pk_call-safe frame; the partner started from its field.
- [ ] **Step 4:** Run until PASS; commit `Co-op: a joined trainer battle starts as a link battle on both games`.

### Task 3: Wild battles as link battles

**Files:** Create `app/src/main/cpp/pk_coop_link_wild.c`, `native-capture/coop/link_wild_*.txt`; modify `coop_link_emerald.s` (`@ task 3`), generator.

**Interfaces:** Consumes Tasks 1-2. Produces kind `PK_COOP_LINK_WILD` behaviour: the host's game rolls the second wild Pokémon from the map's table (plan 1's `TryGenerateWildMon` path); the enemy party reaches the partner as the link battle's own enemy-party block.

- [ ] **Step 1:** Tests: `link_wild_intro.txt` ("Wild X and Y appeared!" text pointer on both cores, no trainer pic sprites, wild battle music id, both foes' species equal on both cores); `link_wild_run.txt` (host RUN with speed poked high: outcome 4 on both; poked low with runTries 0: "Can't escape!", next prompt arrives); `link_wild_run_partner.txt`.
- [ ] **Step 2:** Run; FAIL.
- [ ] **Step 3:** Implement the wild per-battle patches (TRAINER forced by link: intro, pics, music, the escape roll instead of link's forced success, no trainer slide / VS screen).
- [ ] **Step 4:** PASS; commit `Co-op: a joined wild battle is a link battle that looks and runs like a wild one`.

### Task 4: EXP, the BAG and catching inside a link battle

**Files:** Create `app/src/main/cpp/pk_coop_link_rewards.c`, `native-capture/coop/link_exp*.txt`, `link_bag*.txt`, `link_catch*.txt`; modify `coop_link_emerald.s` (`@ task 4`), generator.

**Interfaces:** Consumes Tasks 1-3. Produces nothing new for others beyond the patches; the caught Pokémon lands in the catcher's own game (on the partner's core through its own give-mon path, never written by the host).

- [ ] **Step 1:** Tests: `link_exp.txt` (a foe KO: each side's battler gets the full single-battle yield, EXP bytes checked on each core's own party; a level-up message and the learn-move prompt on the owner's core only, answered by its keys); `link_bag.txt` (partner uses a Potion from its own bag: its own bag count -1, the host's bag unchanged; host likewise); `link_catch_two_up.txt` (ball while two foes: refused message, ball not used); `link_catch_partner.txt` (one foe left, partner's ball catches: the Pokémon in the partner core's party / PC with the partner's OT ID, name, gender bit; nothing added on the host core); `link_catch_host.txt`.
- [ ] **Step 2:** Run; FAIL.
- [ ] **Step 3:** Implement: `Cmd_getexp` link skip lifted with a per-side yield, BAG allowed in the link battle with the item taken from its user's own bag, `Cmd_handleballthrow` allowed with the one-left rule, the catch handed to the catcher's game.
- [ ] **Step 4:** PASS; commit `Co-op: EXP, the BAG and catching work in a joined link battle`.

### Task 5: The end of a joined battle

**Files:** Create `app/src/main/cpp/pk_coop_link_end.c`, `native-capture/coop/link_end_*.txt`; modify `coop_link_emerald.s` (`@ task 5`), generator, `pk_coop_after.c` (reuse for ordinary trainers' flags on the partner).

**Interfaces:** Consumes Tasks 1-4. Produces `int pk_coop_link_result(struct mCore*, uint8_t* out, int cap)` (outcome, prize paid, beaten trainer ids, blackout flag; layout in `pk_coop.h`) and `void pk_coop_link_result_clear(struct mCore*)`.

- [ ] **Step 1:** Tests: `link_end_won.txt` (two trainers beaten: both cores back on the field via the normal end, no VS / record screen, each paid the two-trainer prize in its own money, both trainers' flags set on both cores, set-aside slots 3-5 restored byte for byte); `link_end_single_prize.txt`; `link_end_blackout.txt` (partner's three out, host wins: partner core not blacked out if its slots 3-5 can fight, blacked out if none can); `link_end_lost.txt` (both out: both black out, trainers unbeaten); `link_end_evolve.txt` (a level-up into evolution on the partner: the evolution scene runs on the partner core after the battle); `link_end_uneven.txt` (one core held in a text box 300 frames longer: both reach the field, no block sent after ENDED).
- [ ] **Step 2:** Run; FAIL.
- [ ] **Step 3:** Implement the normal end flow replacing the link end (`CB2_InitEndLinkBattle`, the record prompt, link teardown) and the result.
- [ ] **Step 4:** PASS; commit `Co-op: a joined link battle ends like a normal battle in each game`.

### Task 6: Story battles

**Files:** Create `app/src/main/cpp/pk_coop_link_story.c`, `native-capture/coop/link_story_*.txt`; modify `coop_link_emerald.s` (`@ task 6`), `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopJoin.kt` (+ test), `CoopStoryGen.kt` / `scripts/gen_coop_story.py` if the story list needs the trainer ids.

**Interfaces:** Consumes Tasks 1-5. Produces `fun CoopJoin.storyOffer(host: StoryAt, partner: StoryAt, partnerFree: Boolean): Boolean` (offer only when the partner stands at the same story spot, its trainer flag unset, and free) and kind `PK_COOP_LINK_STORY` (each game enters at its own `trainerbattle`, team picked on the link multi party screen).

- [ ] **Step 1:** Tests: `CoopJoinTest.storyOfferedOnlyAtSameSpot`, `storyNotOfferedWhenBeaten`, `storyNotOfferedWhenBusy`; headless `link_story_gym.txt` (both saves poked to stand before Roxanne with her flag unset: both enter at their own `trainerbattle`, pick three on the party screen, win: badge 1 flag and Roxanne's flag set on both cores by each game's own script, her after-battle text runs on both).
- [ ] **Step 2:** Run; FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** PASS; commit `Co-op: story battles can be joined in every save`.

### Task 7: A dropped link

**Files:** Create `app/src/main/cpp/pk_coop_link_drop.c`, `native-capture/coop/link_drop_*.txt`; modify `coop_link_emerald.s` (`@ task 7`).

**Interfaces:** Consumes Tasks 1-5. Produces `void pk_coop_link_leave(struct mCore*, int blackout)`: ends this game's side of the battle now (blackout = 1: the game's whiteout; 0: back to the field, earned EXP / catches / used items kept, no prize, trainer flags untouched), from any battle frame.

- [ ] **Step 1:** Tests: `link_drop_back.txt` (blocks held 1800 frames then delivered: both carry on, the turn completes); `link_drop_leave.txt` (blocks stopped; `pk_coop_link_leave 0` on both after 3600 frames: both on the field, EXP earned before the drop kept, trainer flags unset, money unchanged); `link_drop_blackout.txt` (`pk_coop_link_leave 1`: whiteout on that core); `link_host_gone.txt` (the host core reset mid-battle: the partner core leaves cleanly).
- [ ] **Step 2:** Run; FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** PASS; commit `Co-op: a joined battle survives a short drop and ends cleanly after a long one`.

### Task 8: The Kotlin driver

**Files:** Create `app/src/main/kotlin/com/pokedaisy/app/companion/data/coop/CoopLinkBattle.kt` (+ `CoopLinkBattleTest.kt`); modify `CoopBattleWire.kt` (+ test), `MgbaCore.kt`, `app/src/main/cpp/pokedaisy_jni.c`, `CoopBattles.kt`, `CoopSafetyTest`.

**Interfaces:**
- Consumes Task 1's C API (JNI: `pkCoopLinkBegin(master: Boolean, kind: Int, trainerA: Int, trainerB: Int, name: ByteArray, gender: Int, otId: Int): Boolean`, `pkCoopLinkTake(): ByteArray?`, `pkCoopLinkGive(block: ByteArray): Boolean`, `pkCoopLinkState(): Int`, `pkCoopLinkLeave(blackout: Boolean)`, `pkCoopLinkResult(): ByteArray?`).
- Produces `class CoopLinkBattle(engine: CoopLinkEngine, channel: CoopBattleChannel, ui: CoopBattleUiSink, clock: () -> Long)` with `fun afterFrame()` (emu thread: take / send all outgoing, give all received in order), `fun linkDown()` / `fun linkUp()`, `companion object { const val DROP_MS = 60_000L }`; `interface CoopLinkEngine` mirroring the JNI calls; wire message `CoopBattleMsg.LinkBlock(battleId: Int, seq: Int, bytes: ByteArray)`.

- [ ] **Step 1:** Tests: `blocksFlowBothWaysInOrder`, `staleBlocksAreDropped` (another battleId / a seq already seen), `malformedBlockNeverGiven` (`CoopSafetyTest`), `dropWaitsThenLeaves` (59 s: waiting UI; 60 s: `leave(false)`), `storyLockDropBlacksOut` (`leave(true)`), `slowPlayerNeverTimesOut` (link up, no blocks for 10 minutes: no leave), `hostStopEndsCleanly`.
- [ ] **Step 2:** Run (`./gradlew :app:testDebugUnitTest --tests '*CoopLinkBattle*' --tests '*CoopBattleWire*' --tests '*CoopSafety*'`); FAIL.
- [ ] **Step 3:** Implement; wire `CoopBattles.kt`'s glue to it (join prompt, team, begin on both, result reading as plan 4 does).
- [ ] **Step 4:** PASS; `assembleDebug -Ppokedaisy.coop=true` builds; commit `Co-op: the app carries link battle blocks between the two games`.

### Task 9: Remove the replica path; docs

**Files:** Delete or strip the replica (`CoopBattlePartner` replica paths, `CoopLockstep`, `CoopFrameHook.replicaRunning`, `BattleInputController.locked` for replicas, `Start` / `StateChunk` / `Frames` / `PickApplied` / `Writes` messages), the partner menu and cover (`CoopBattleUi.kt`'s menu, `CoopGameBox.kt`, their tests and ui-preview shots), the mailbox pick path and INGAME_PARTNER patches in `pk_coop.c` / `coop_emerald.s` that the link path no longer uses, the old headless tests they back, the auto-scroll text patch if link battles already advance text; update `docs/manual-drafts/coop.md` ("Battling together"), `docs/DEVELOPMENT.md` (co-op battle sections), `CLAUDE.md`'s co-op joined battles paragraph.

- [ ] **Step 1:** Delete; run the unit suite, `assembleDebug -Ppokedaisy.coop=true`, every headless test: all pass (baseline failures only).
- [ ] **Step 2:** Commit `Co-op: the replica and the app-drawn battle menu are gone`.

### Task 10: Pace and the phones

- [ ] **Step 1:** Headless: `link_pace.txt` with `linkdelay 6` (100 ms): count blocks and frames for a typical turn (both use a move, one effect text) vs `linkdelay 0`. Over 3 s (180 frames) longer: make the bridge send controller-done early where the master doesn't need the partner's animation finished, rerun, and report both numbers.
- [ ] **Step 2:** Build, install the same APK on both phones (the user approves the install), run a joined wild battle, a two-trainer battle and a catch through the relay; read `pokedaisy/coop` logs; report seconds per turn.
- [ ] **Step 3:** Commit any pace change `Co-op: joined battles keep pace over the relay`.
