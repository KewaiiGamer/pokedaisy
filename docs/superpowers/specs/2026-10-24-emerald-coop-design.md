# Emerald co-op: design

Date: 2026-10-24. Status: design, approved for planning once reviewed.

## Intent

Two friends play through Pokémon Emerald together, each on their own save, on two devices linked over the
internet or the same Wi-Fi. They see each other walking around Hoenn, and when one of them starts a battle
with the other nearby, the other is asked to join and the battle becomes a double battle: one player's
Pokémon on the left, the other's on the right, against the same foes. The point is to **finish the whole
game together**: battles won together count in both saves (beaten trainers, badges), while story events
stay each player's own. An optional lock makes a pair of saves playable only together, so neither player
can get ahead.

Everything here is decided by the user unless marked **(builder's call)** or **(my call)**; "my call" items
were proposed during design and not objected to, so treat them as defaults the user may still change.

## Goals

1. Pair two devices once, then reconnect without setup.
2. Each player sees the other's character walking on the same map.
3. A battle started near the partner offers the partner to join as a double battle with three Pokémon each.
4. Wins together count for both saves; EXP is full for each side.
5. An optional "play together only" lock that holds the game and story events for the partner.
6. Nothing the partner sends can damage a save.

## Non-goals (first version)

- Any game but **English retail Emerald (BPEE)**. Other Emerald languages, hacks, FireRed and the rest come
  later, one engine at a time (my call). Both players must run the same ROM (SHA-1 match).
- More than two players.
- Story progress syncing. A story event finished by one player is never finished in the other's save.
- Trading, chat, gifts.
- A solid partner (collision, talking to them).
- Each player seeing the battle from their own side.
- Firebase or Supabase for co-op. Existing Firebase uses (Crashlytics, BEST EFFORT reports, the website's
  feedback form) stay untouched.

## Anti-goals (failure even if it "works")

- A save corrupted, or progress silently lost on one side.
- Partner data written into game memory without checks.
- The relay able to read game traffic.
- One player stuck with no way out (a hung battle, a lock that never releases, a prompt that never clears).

## Constraints from the project

- Public repo: no ROMs, saves, keys or relay addresses committed. ROM data stays read from the player's ROM.
- Save files are sacred (CLAUDE.md): no new path writes the save without a backup (`SaveBackups`) first. A
  state used for undo must not carry save data (see `pk_rewind.c`, which takes states without
  `SAVESTATE_SAVEDATA` for the same reason).
- The game, its save and states never hold injected visuals (the FOLLOWER rule).
- Every player-facing feature gets a NEW tag (`NewFeatures`), a manual page in `docs/manual/`, settings keywords
  (`SETTING_KEYWORDS`), and translations in all app languages.
- Commits carry no Claude / Anthropic attribution (a hook rejects them).
- UI follows the OPTION look (`GbaMenu.kt`), pixel shapes and icons, `soundClickable`.

## 1. Connection and pairing

> Superseded for the transport (the relay, the handshake, the ticket) by the 2026-10-25 addendum, `2026-10-25-emerald-coop-ws-relay.md`.

**Decided:**

- A first-time co-op setup pairs two players. Three ways to connect:
  - **Invite**: one player creates an invite, shared as a **link** (through any chat app) or a **QR code**;
    the other opens it. No short typed codes.
  - **Same Wi-Fi**: nearby devices are listed, no invite needed.
  - **By IP**: an invite / connection can carry a direct address.
- The first connection asks **"accept this player?"**. After that both devices remember each other's key,
  reconnect without a new invite, and refuse any other device.
- Built on **iroh** (n0-computer): devices dial by public key, connections are QUIC/TLS 1.3 end to end,
  direct when hole punching works, otherwise through a **self-hosted iroh relay** the user runs. The relay
  forwards encrypted packets only and keeps nothing.
- A **relay only** option hides each player's IP from the other.
- **Which relay**: the user's self-hosted relay is **baked into release builds** from an untracked file /
  CI secret (never committed, like `SCREENSCRAPER_DEV_ID`), changeable in settings. If it's unreachable, the
  app **falls back to iroh's public relays** (run by n0; traffic stays encrypted, they see metadata). Builds
  without the secret go straight to the public relays. Same-Wi-Fi and IP need no relay.
- **Pairing is per save** (my call): a pairing belongs to one save file, so one device can co-op with friend A
  on one Emerald save and friend B on another. A save is identified by something kept in it or beside it that
  survives moving the file (builder's call); unpairing is a settings action.

**Builder's call:**

- The Android binding. Iroh's own Kotlin package needs a from-source build for Android and one third-party
  project reports its async calls never complete there, so the plan was a **thin JNI layer of our own over
  iroh's Rust crate** (cargo-ndk, a Rust toolchain next to the existing CMake build). Confirm or pick another
  route after a spike.
- Whether to self-host iroh's DNS discovery server too, or rely on tickets carrying the relay URL and
  addresses only.
- Protocol framing and versioning (both ends must refuse an incompatible app version cleanly).
- New code goes into NOTICE and the LICENSES page.

## 2. Seeing each other

**Decided:**

- On the **same map**, the partner's character walks on your screen as they really move: walking,
  running, biking, surfing, in their own look (their PLAYER LOOK if worn, else their Brendan / May).
- You **walk through each other**: purely visual, no collision, no interaction.

**My call on looks:** only the look's *id* is sent. Each device draws the partner from art it has itself (its
own `rom-art/look/`); if it lacks that look, the partner shows as plain Brendan / May. No ROM bytes move
between players. During a co-op battle the host's code-changing patches (TEXT LOOK, and any option patch the
partner's replica can't rebuild from its own Emerald ROM) are switched off, so the replica runs the same code;
option patches that are pure Emerald-code tweaks (REUSABLE TMS) are sent as a list of option ids and rebuilt
on the replica.

**My calls:** position, facing and movement state are sent about 10 times a second (`Telemetry` already reads
`mapGroup`, `mapNum`, `x`, `y`, `facingDirection`); the sprite is drawn the way FOLLOWER is
(`pk_wide.c` `followBegin` / `followEnd`: one more hardware sprite put in at line 0 and taken out after line
159, OBJ tiles + an OAM entry + a palette slot no shown sprite uses), with its own tile range and OAM slot so
it can coexist with FOLLOWER. Hero frames come from the player's ROM as PLAYER LOOK does. It also shows in
EXPAND's margins.

**Builder's call:** movement smoothing / interpolation between updates, what happens on warps and map
connections (FOLLOWER's handling is a model), whether to show the partner on a connected map visible in the
margins.

## 3. Co-op battles

### Joining

- When either player starts a battle (wild or trainer) with the partner **nearby**, the partner gets a
  **join prompt**. Accepting makes it a double battle against the same foes; declining or no answer within
  **30 seconds** leaves it a normal single battle. The battle's starter is the **host**.
- The host's game **holds** while the prompt is up (my call: hold at the battle transition's start, before
  the battle screen; the builder picks the exact point).
- **Nearby** = same map and within a **configurable range in steps** (a setting; default 8 steps, my call).
- The partner is only prompted when free: on the field, or on the **first START menu** (the top-level list).
  Not inside the bag, PC, trainer card or any other screen, not in a battle of their own, not in a scene.

### Teams

- **Three Pokémon each.** Ordinary battles take each player's **first three that can still fight**, in party
  order, with no menu. **Story and gym battles** (the held ones, section 5) open a **picking screen on both
  devices** first.
- The host's other three sit out and come back after the battle.

### What's on screen

- Both devices show **the same picture, the host's battle**: host's Pokémon bottom left, partner's bottom
  right, the partner's character as the host's partner (as Steven appears in his battle). Messages are the
  host's game's.
- The partner's own game **holds** where it was for the whole battle.
- On the partner's turn, **their own menu** pops up over the battle: **FIGHT, POKéMON, BAG, RUN**, picked with
  their own buttons or touch. BAG uses the **partner's own bag**; what they use is taken from their save.
- RUN: a successful run ends the battle for both (my call).

**My call (architecture of the partner's view):** the partner's device runs a **replica** of the host's game
for the battle: at battle start the host sends a state (without save data) and from then on its inputs and
every injected write (partner picks, item uses), each tagged with the frame it applies on. The replica runs
them in step, so the partner sees the real battle with sound, at almost no bandwidth (works through the
relay). The host is authoritative; a periodic hash of battle RAM catches drift, and a fresh state resyncs
it. The RTC must be pinned to the host's on the replica. A second core is already a pattern in the app (the
STEADY music render core `rg`). The partner's menu is drawn by the app (the BATTLE BUTTONS lists in
`BattleLists.kt` and the BAG screen are the starting pieces), not by the game.

### Wild doubles

- A joined wild battle has **two wild Pokémon**. The second is rolled from the same map's encounter table
  the way the game rolls one (my call).
- **A Poké Ball only works when one wild Pokémon is left.** With two, using a ball is refused with a
  message, on both players' menus.
- Whoever throws the ball that catches it gets the Pokémon **in their own save** (party, else PC).
- **Scripted wild battles are not joinable** (my call): legendaries (Sky Pillar, the weather trio, the regis),
  static encounters (Kecleon, Electrode, Sudowoodo), and the Route 101 rescue. With the lock on they are story
  events, each player plays them in their own game at the same spot.

### EXP and levels

- **Each player's Pokémon get the full EXP of a normal single battle.** No splitting across the four.
- The partner's level-ups show **in the battle** ("grew to level 12"), on both screens since it's the same
  picture. When a partner's Pokémon wants to learn a move, **the partner answers** that prompt with their own
  buttons.
- Evolutions don't run in the host's game; the partner's play **in their own game the moment it resumes**
  (my call).

### Ending

- **Team wins a trainer battle**: the trainer counts as beaten in **both** saves; a gym leader's **badge goes
  to both**. Each player gets the prize money in their own game.
- A player whose Pokémon all faint **stays in the battle watching** while the other fights on. If the team
  wins, that player **still gets the win and the badge**, then **blacks out once the whole battle has
  ended** (normal blackout: last Pokémon Center, money lost).
- **Both run out**: a normal loss for both, both black out, the trainer stays unbeaten in both (my guess,
  not contradicted).
- The partner's game resumes with their Pokémon's HP, PP, status, EXP, levels and moves as the battle left
  them, plus any catch, and their used items gone.
- **Auto-save**: after any co-op battle that changed progress (a trainer win, a badge), **both games save in
  game by themselves**, with a backup made first.

**What a partner's win gives them** depends on the battle:

- **Ordinary trainers** (and gym leaders' plain win marks): what the game itself records for winning: the
  trainer's flag, and for a gym leader the badge flag and the leader's defeated flag (my call). Anything a
  leader hands over after the battle (TMs) is collected by talking to them in your own game, if their script
  allows it; the builder checks each gym leader's script and reports any that don't.
- **Story battles** (rival, Wally, Team Aqua / Magma, gym leaders, Elite Four, champion: the held list in
  section 5): **the partner's own game plays the same scene at the same time**, and when its script reaches
  the battle, that battle counts as won (or lost) from the co-op battle, so their story moves on exactly as if
  they had fought it (user's decision). This needs both players at the same spot. Without the lock, a story
  battle only offers the join prompt when the partner is at the same spot, and then works the same way (my
  call); otherwise it's an ordinary single battle for the host.

### Link drops in a battle

- Both games **hold up to about 60 seconds** for a reconnect and carry on if it comes back.
- If it doesn't, the battle is **undone for both**: the host goes back to **just before the battle
  started** (a state taken then, without save data; the encounter may come out differently), the partner's
  save stays untouched.

## 4. Verified engine facts (pret/pokeemerald, read 2026-10-24)

These are what the battle design leans on; the builder should confirm each on the real ROM headless
(`mgba_dump`) before building on it.

- Emerald has an in-game partner battle: `BATTLE_TYPE_DOUBLE | BATTLE_TYPE_MULTI | BATTLE_TYPE_INGAME_PARTNER`
  (+ `TWO_OPPONENTS` for two trainers), used for Steven at the Space Center. The partner's party sits in
  `gPlayerParty[3..5]`; the player's slots 3-5 are set aside (`SavePlayerParty` / `LoadPlayerParty` in that
  script). Constants: `include/constants/battle.h` (INGAME_PARTNER = `1 << 22`).
- The partner's choices are made by AI in `battle_controller_player_partner.c`:
  `PlayerPartnerHandleChooseAction` (AI_TrySwitchOrUseItem) and `PlayerPartnerHandleChooseMove`
  (BattleAI_ChooseMoveOrAction, then emits `chosenMoveId | (gBattlerTarget << 8)`). The plan: an in-memory ROM
  patch (overlaid like REUSABLE TMS in `pk_cheats.c`'s game-option patches, so the Poller / RA hashes see the
  file) making these read the partner's pick from a mailbox in EWRAM and return without completing while it's
  empty, so the game simply waits.
- Obedience: `battle_util.c` `IsMonDisobedient` returns early for the partner (position 2) in INGAME_PARTNER
  battles, so the partner's Pokémon never disobey the host's badges.
- Loss check (`battle_script_commands.c`, the faint check near line 3543): with `gPartnerTrainerId ==
  TRAINER_STEVEN_PARTNER` only the player's three count, so the battle is lost when they're gone; with any
  other partner id **all six count**, so the battle goes on until both sides are out, which is the rule the
  user wants. But a non-Steven id also switches the partner's trainer picture to a Frontier front sprite and
  a different window layout (`battle_main.c` ~636, partner controller ~1301 / ~1794). Pick one id and patch
  the other behaviour; the partner's back picture must be the partner's hero (Brendan / May back pic, or their
  PLAYER LOOK).
- EXP: `Cmd_getexp` already gives EXP to the partner's slots (3-5) and suppresses the traded boost for them,
  but splits the yield across all sent-in Pokémon of both players (`viaSentIn`), which must change to a
  per-side split.
- Wild doubles: the engine has the text (`sText_TwoWildPkmnAppeared`). `Cmd_handleballthrow` targets
  `BATTLE_OPPOSITE(attacker)` with no check for two wild Pokémon, hence the one-left rule must be added.
- **Unproven**: INGAME_PARTNER has only ever been used in a trainer battle. A wild battle with a partner, and
  a single trainer (not TWO_OPPONENTS) against a partner, are untried. A trainer with one Pokémon against two
  players is the riskiest; if it can't work, the fallback is no join prompt for such a trainer (my call).

## 5. The "play together only" lock

- A toggle that can **only be turned on for two brand-new saves started together** (user's decision), so story
  progress always matches.
- With it on, **the save only starts when the partner is connected**.
- If the link drops outside a battle, the game **pauses with "waiting for your partner"** until it's back;
  the companion, save states and closing the game still work.
- **Story events wait for both**: walking up to one holds your game there until **your partner stands at the
  same spot in their own game**; then both play it at once, each in their own save.
- **Story-important trainer battles** wait the same way: gym leaders, rival battles, Team Aqua / Magma
  bosses, the Elite Four and champion. **Ordinary route trainers don't.**
- Without the lock everything else works; either player can play alone.

**My calls:** a held player can always **cancel and walk away** (a step back from the trigger releases the
hold; nothing freezes), which also clears the case of both players held at different events. Both players turn
the lock on together and it applies to that pair of saves. The list of story
events and story battles is generated from pokeemerald's scripts (`gen_*.py` style, like the guide's area
data), not hand-guessed. A held story battle becomes a co-op battle with the picking screen, hosted by
whoever triggered it.

**Builder's call:** how a story trigger is detected and held (coord events, object scripts, map scripts on
entry), and what "same spot" means per trigger (the same trigger area / within a tile or two).

## 6. Session rules and safety

**Decided:** everything the partner sends is checked against the game's rules before it touches memory
(values legal for Emerald, as `Gen3Legal` checks for PC BOXES), and only an allowlist of synced things (the
flags in section 3's "what a win writes", party updates from the battle, catches, item removals) can be
written. The save is backed up before each co-op session.

**My calls:**

- **LOAD STATE and REWIND**: with the lock on, off whenever that save is played. Without the lock, off from a
  co-op battle's start until its results are saved (a state load writes the state's save over the file and
  would undo a win); free the rest of the time.
- **RetroAchievements** pauses from a co-op battle's start until its results are saved (that's when co-op
  writes game memory), as it does with cheats; ordinary connected play keeps achievements.
- Co-op refuses to start with cheats loaded.
- The lock is enforced by the app alone. Playing the save elsewhere (RetroArch), LOAD SAVE or RESTORE BACKUP
  can't be prevented; the app only refuses to start a locked save without the partner.

## 7. Where it lives

**My calls:** a CO-OP group in SETTINGS on both screens (pairing / invite, relay address, relay only, nearby
range, the lock), with a NEW tag, settings keywords and a manual page. Invites (QR display, link opening,
LAN list) can live on the top screen, where a keyboard and camera are easy; the companion shows status and
the join prompt. The join prompt and the partner's battle menu show on the game screen.

## Left to the builder

Phasing, module layout, the protocol, the exact hold points in the game, addresses (from pokeemerald built
byte-identical to retail, as the rest of the app does), UI layouts within the OPTION look, the second wild
Pokémon's roll, and test fixtures. Suggested first step: a headless spike in `mgba_dump` that forces a
wild and a trainer battle with the partner flags and the mailbox patch, and confirms the game waits for the
mailbox and uses the written move.
