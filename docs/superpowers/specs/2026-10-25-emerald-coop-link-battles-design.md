# Emerald co-op: joined battles as link battles (design)

Date: 2026-10-25. Status: design, for review.

Replaces, in `2026-10-24-emerald-coop-design.md` section 3, "What's on screen", the "architecture of the partner's view"
(the replica), "Link drops in a battle", and section 4's plan to drive the partner through `BATTLE_TYPE_INGAME_PARTNER`.
Everything else in that spec (joining, nearby, teams, wild doubles, EXP, ending, what a win gives, the lock) stands unless
changed below. Sections 1 and 2 (connection, seeing each other) and the WebSocket relay addendum are untouched.

Everything here is the user's decision unless marked **(my call)** or **(builder's call)**.

## Intent

In a joined battle **each player's own game runs the battle from their own side**: on the joining player's phone their own
character and Pokémon are theirs, the menus are their game's own ("What will MUDKIP do?", FIGHT / BAG / POKéMON / RUN, the
move box, the party and bag screens), drawn by their game at their screen's scale. Nothing is streamed between phones and
nothing is drawn over the game by the app.

The first version (a lockstep replica of the host's game on the partner's phone) put the host's prompt on the partner's
screen and needed an app-drawn menu over it; the user rejected that ("awful, goes against what I want for this project").

## The decision: reuse Emerald's own link battles

Emerald already runs a battle on two consoles, each from its own side, in its **link battles**, and has exactly our case in
the Battle Tower's **link multi battle**: two human players side by side against two computer trainers. Joined battles reuse
that machinery, with the co-op connection (`CoopChannel` over the relay / same Wi-Fi / by IP) standing in for the link cable.
The user chose this (option A) over building a new "two separate battles synced at each prompt" system, knowing that:

- **The host's game decides the battle.** In a link battle only the master (multiplayer id 0) runs the battle engine: battle
  scripts, damage, accuracy and the AI of the computer opponents. The other game runs controllers that draw what the master
  sends and answers its player's questions. The two games can't disagree, so no shared random seed or drift detection is
  needed. The host (the player who started the battle) is the master.
- **The games keep step on every message, not only at the prompts.** Each controller command (a text box, an animation, an HP
  bar) waits until every game reports it done, as in a real link battle. On the same Wi-Fi this is unnoticeable; over the
  relay each message costs a round trip, so a turn takes longer. Accepted.
- **Link battles are always trainer battles** in the game; wild battles, catching and EXP are added by us (below).

## What the players get

All carried over from the first design, confirmed by the user on 2026-10-25:

- **Teams**: each player's first three Pokémon that can still fight. For held story battles the picking screen is the link
  multi battle's own party screen (`CB2_PreInitMultiBattle`'s party menu), not one we draw (my call).
- **Positions**: the host on the left, the partner on the right; each phone shows its own player as "you". (In the tower
  link multi controller setup the non-master player is battler 2, `B_POSITION_PLAYER_RIGHT`, with its own `Player`
  controller.)
- **Wild battles**: two wild Pokémon, rolled from the map's encounter table as the game rolls one. Introduced as a wild
  battle ("Wild X and Y appeared!"), no trainer intro, slide-ins or VS screen.
- **Catching**: both players can throw balls; **a ball only works when one wild Pokémon is left** (refused with a message
  while two are up); **whoever catches it keeps it**, in their own save (party, else PC), with their own OT.
- **EXP**: each player's Pokémon get the full EXP of a normal single battle; level-ups, "learn a new move?" prompts and
  evolutions happen in the owner's own game, answered by the owner.
- **Items**: each player's BAG is their own; what they use is taken from their own save.
- **RUN**: a wild battle can be run from; a successful run ends it for both.
- **Prize**: a trainer win pays what a two-trainer battle pays, each player in their own game. A single trainer with 2+
  Pokémon pays that trainer's own prize.
- **Fainting**: a player whose Pokémon all faint stays in the battle; they blackout only if the battle ends and no Pokémon in
  their whole party can fight. Both out: a loss for both.
- **Auto-save** after a battle that changed progress, with a backup first, as before.

### Story battles: joinable in every save (changed)

The user's decision (2026-10-25): story battles (rival, Wally, Team Aqua / Magma bosses, gym leaders, Elite Four, champion)
can be joined in **every** save, not only with the "play together only" lock. The join prompt appears when the partner is
standing at the same spot in their own game; the partner's game then plays the same scene, and the battle counts as won (or
lost) in both stories, badge included. If the partner isn't there, it's a normal solo battle for the host.

My call on how: both games reach the same `trainerbattle` in their own scripts, and that is the moment both enter the link
battle, so each game's own script sets its own flags and runs its own after-battle text. The first design's after-battle
flag writing (plan 4's `pk_coop_after.c`) stays for ordinary trainers, where the partner's game has no such script running.

My calls on timing: the host's game holds at its trigger while the join prompt is up (the existing 30 s); the partner joins
by triggering the same event in their own game within that time (walking up to the same trainer / spot), and both games
enter the link battle when each reaches that `trainerbattle`. With the lock on, the existing story hold (both wait at the
spot) does the same job. A partner whose game would not run that `trainerbattle` (the trainer already beaten, the story
elsewhere) gets no join prompt. A trainer's single party (the single-trainer rule: 2+ Pokémon, split between the two foe
slots, as the current `pk_coop_single_trainer_prepare` does) is set up the same way in a link battle; if the link battle
can't run it, the builder reports back before dropping single-trainer joins.

Scripted wild battles (legendaries, static encounters, the Route 101 rescue) stay not joinable.

### Game patches on either side (my call, as before)

PLAYER LOOK and TEXT LOOK must be off on **both** games for a joined battle (they change battle code / art the other game
doesn't have). Option patches that are plain Emerald code tweaks (REUSABLE TMS) are fine. Cheats keep co-op off, as today.

### Link drops in a battle (changed)

- Both games pause the battle and show the wait ("Waiting for MAYA... 60"); if the link is back within **60 seconds** the
  battle carries on where it was.
- After 60 seconds without it:
  - a **story battle, in every save** (not only "play together only" ones; the user's decision, 2026-10-26): **counts as a
    loss for both: blackout**, the trainer not beaten, no prize; what was earned before the drop stays, as a normal blackout
    keeps it. Never "goes on as a solo battle". The same for a drop while both players are on a story battle's team-pick
    screen (armed, before the battle starts): a loss and a blackout for both;
  - **every other battle** (wild, route trainers): the battle **ends for both** and each player is back on the field. My
    call: what was earned stays (EXP and levels so far, a Pokémon already caught), HP / PP / status as they were, items used
    stay used, the trainer is **not** beaten (can be fought again), no prize.
- The old rule (undo the battle from a state taken before it) is dropped.
- **A player who doesn't answer never times out** (the user's decision): while the link is up, a prompt waits as long as
  the player takes, and the other game waits with it, as in a real link battle.

## How it fits the game (the builder's map, from pret/pokeemerald)

Facts read from the decomp on 2026-10-25 (file:line from `pret/pokeemerald`); confirm each headless on the retail ROM before
building on it.

- **Engine on the master only.** `InitLinkBtlControllers` (`battle_controllers.c:390`) gives only the master
  `gBattleMainFunc = BeginBattleIntro`; others keep a no-op. `BattleMainCB1` runs every battler's controller on every game.
- **Messages.** `Emit*` -> `PrepareBufferDataTransferLink` (`battle_controllers.c:733`) queues a message (`LINK_BUFF_*`
  header: buffer id, battler, attacker, target, size, absent flags, effect battler; data); `Task_HandleSendLinkBuffersData`
  (`:774`) sends with `SendBlock`; `TryReceiveLinkBattleData` (`:870`, from `main.c:363`) and
  `Task_HandleCopyReceivedLinkBuffersData` (`:908`) deliver `B_COMM_TO_CONTROLLER` / `TO_ENGINE` / `CONTROLLER_IS_DONE`.
  `gBattleControllerExecFlags` holds 4 bits per player; the master's script waits until every player's done arrives.
- **The narrowest place to carry the link**: the wired block layer in `link.c`: `SendBlock` (`:1038`), `IsLinkTaskFinished`
  (`:1058`), `GetBlockReceivedStatus` (`:1068`), `ResetBlockReceivedFlag(s)` (`:1084`, `:1100`), `gBlockRecvBuffer`
  (`:110`, `BLOCK_BUFFER_SIZE` 0x100; larger blocks land in `gDecompressionBuffer`). Outgoing blocks are taken at `SendBlock`,
  incoming ones written into `gBlockRecvBuffer[player]` with `gBlockReceivedStatus[player]` set, as the cable's serial
  interrupt would. **No serial (SIO) emulation**: a frame-timed link over the internet can't keep time.
- **Setup** (`CB2_InitBattle` -> `HandleLinkBattleSetup`, `CB2_PreInitMultiBattle`, `CB2_InitBattleInternal` ->
  `CB2_HandleStartMultiPartnerBattle` for the tower link multi): link player headers (`gLinkPlayers`, names, genders, version
  signature; `FindLinkBattleMaster` picks the master), party blocks, the RNG seed block, then `BattleMainCB2`.
  `CB2_HandleStartMultiPartnerBattle` (`battle_main.c:1196`) forces link ids 0, 2, 1, 3 and puts the AI trainers into
  `gLinkPlayers[2..3]` from `gTrainerBattleOpponent_A` / `_B`.
- **End**: `ReturnFromBattleToOverworld` (`:5217`) has link branches; `CB2_InitEndLinkBattle` (`:2177`, the VS result screen),
  `CB2_EndLinkBattle` / `EndLinkBattleInSteps` (`:2264`), the "record this battle?" prompt.
- **What a link battle switches off that we need back** (each a patch, per battle, only while a joined battle runs, using
  the existing per-battle patch / CRC gating of `pk_coop.c`: `coop_battle_in` / `coop_battle_out`, `sCoopCrcs`):
  - EXP (`Cmd_getexp` skips link battles), level-ups, move learning, evolutions after the battle;
  - the BAG in battle, and items taken from the user's own bag;
  - catching (`Cmd_handleballthrow`), plus the one-wild-left rule, and the catch going into the catcher's own save;
  - a wild battle under `BATTLE_TYPE_TRAINER` (link forces it): wild intro text, no trainer pictures, wild music, the
    enemy party = the two wild Pokémon;
  - RUN (link forces run success; a wild run should roll the normal escape) and no running from trainers;
  - Battle Frontier rules the tower mode brings (level 50 scaling, the facility's rules, its own end flow);
  - the link end flow (VS screen, record prompt, link teardown) replaced by the normal end: back to the field, blackout
    rules, evolutions, the prize.
- **The partner's own party**: on the partner's game its battler is its own `Player` controller, so its HP / PP / status /
  EXP updates arrive as controller commands on its own `gPlayerParty`. Confirm that every change the battle makes to the
  partner's Pokémon (including EXP, level, moves, a catch) ends up in the partner's own save, and nothing from the host's
  party is written there.

## What goes and what stays in the code (my call)

- **Goes**: the replica (the host's state sent to the partner, `CoopFrameHook.replicaRunning`, the second run of the host's
  game), the pick mailbox (0x0203F000) and q-tagged picks, `CoopBattlePartner` / `CoopBattleHost`'s replica paths, the
  app-drawn partner menu and the cover (`CoopBattleUi.kt`, `CoopGameBox.kt`, commit d90ae43), the INGAME_PARTNER controller
  patches. The auto-scrolling text patch (f1d6199) goes too if link battles already advance text by themselves.
- **Stays**: pairing, the relay, presence, the join prompt and its 30 s, nearby range, CoopGuards' holds, the "play together
  only" lock, the after-battle save writing for ordinary trainers (with its backup), the single-trainer party split, the
  safety rule that nothing from the network reaches game memory unchecked (`CoopSafetyTest`): link blocks from the partner are
  now game data, so each block type gets a size / shape check before it's written, and party blocks are checked like
  `CoopBattle.check` does today.

## Goals

1. Each player sees and plays the battle in their own game, from their own side, at their own screen's scale.
2. The two battles never disagree (one engine decides).
3. Wild, ordinary trainer, single trainer (2+ Pokémon) and story battles are all joinable.
4. Every rule in "What the players get" holds.

## Non-goals

- Battles that keep step only at the prompts (option B).
- Serial-port (SIO) link emulation, the wireless adapter path (`link_rfu*.c`).
- Recording or replaying joined battles.
- Any game but English retail Emerald (the other base games follow, one engine at a time, in their own sessions).

## Anti-goals

- A save written with the other player's data, or progress lost on one side.
- Partner bytes in game memory without a check.
- A hung battle: every wait on the link (a block that never comes) ends in the 60 s rule, never forever. A player who is
  simply slow to answer, with the link up, is not a hang.
- The host's menus or text on the partner's screen.

## Builder's calls

- Where exactly blocks are taken and given (patching `SendBlock` & co. in the ROM, or doing it from the core between frames),
  and the wire format of a block over `CoopChannel` (size, sequence, version).
- How the partner's game is put into the link battle at the same moment as the host's (the join prompt's accept is the cue;
  the partner's game must be at a safe point, as the join rules already require).
- Which link battle type to start from: the tower link multi, or a plain multi link with the AI trainers set up another way,
  whichever needs fewer patches to undo.
- How a wild battle's two foes are given to the master, and how the partner's game learns them (a block).
- How to make the pace fit the budget below (e.g. sending done messages early where that's safe, batching blocks).

## Pace budget (my call, the user left it to me)

Measured on a typical turn (both players use a move, damage, one effect text) with 100 ms of round trip between the phones:
a joined turn may take **at most 3 seconds longer** than the same turn on the same Wi-Fi. Over that, the speed-up is part of
the first build, not a later one. The builder measures headless (two cores, an added 100 ms delay on the blocks) and on the
phones through the real relay, and reports round trips per turn and seconds per turn.
- Headless testing: two cores in one `mgba_dump` (or two processes) passing blocks, so a whole joined battle can run in CI
  shape like `scripts/coop_headless_check.sh`.
- Updating the player docs (`docs/manual-drafts/coop.md`, "Battling together") and DEVELOPMENT.md's co-op sections.
