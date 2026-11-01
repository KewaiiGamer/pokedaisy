package com.pokedaisy.app.companion.data.coop


/**
 * The game side of a link battle, mirroring the native bridge (`pkCoopLink*`, `pkCoopStory*`). Emu thread only.
 * The start calls answer like the C ones: 1 / true started, 0 / false refused, -1 the field is not free yet (ask again on a later frame).
 */
interface CoopLinkEngine {
    // --- the blocks and the end ---
    /** The next outgoing block (`[u8 kind][u8 seq][u16 size][bytes]`), null when none. */
    fun take(): ByteArray?
    /** True when the block was written; false = refused (shape, state, full). */
    fun give(block: ByteArray): Boolean
    /** [STATE_IDLE] .. [STATE_ERROR]. Also runs the bridge's per-frame end steps: call it after EVERY frame, until the result is read. */
    fun state(): Int
    /** The blocks the bridge last refused as malformed (pk_coop_link_bad_blocks' records), for the log of a STATE_ERROR; null = none or unknown. */
    fun badBlocks(): ByteArray? = null
    /** The bridge's position as one line for the log (counters, seqs, what the game holds); empty = unknown. */
    fun diag(): String = ""
    /** Ends the link battle: [blackout] = a story battle's loss for this game, else the battle just stops. */
    fun leave(blackout: Boolean)
    /** The 16-byte result, null until the game is back on its field with everything paid. */
    fun result(): ByteArray?
    /** What the battle caught: byte 0 who threw (1 host, 2 partner), then the Pokémon's 100 bytes; null = nothing. Read before [resetBridge] / [leave]. */
    fun caught(): ByteArray?
    /** The bridge goes (hooks out, area zeroed) and the result is dropped: after the result was read, or a battle that never got one. */
    fun resetBridge()
    /** The game stands free on its field (a left battle's tidy-up has run by then). */
    fun settled(): Boolean

    // --- the start ---
    /** The co-op code and the link bridge are in (idempotent); false = refused, nothing changed. */
    fun install(): Boolean
    /** Why the native side last refused (the co-op check function, the bridge reason), for the log; empty = nothing. */
    fun refused(): String = ""
    /** The host's game is held where a joined battle is offered. */
    fun hostHold(): Boolean
    /** The held battle becomes the link battle; the arguments are the PARTNER's identity. False = refused, the battle as it was. */
    fun hostGo(name: ByteArray, gender: Int, otId: Int): Boolean
    /** The partner's start from its free field; the arguments after [trainerB] are the HOST's identity. */
    fun partnerGo(kind: Int, trainerA: Int, trainerB: Int, name: ByteArray, gender: Int, otId: Int): Int
    fun storyArm(trainerId: Int): Boolean
    /** One of [STORY_IDLE] .. [STORY_MISMATCH]. */
    fun storyState(): Int
    /** Both teams are picked: the link battle begins; the arguments are the OTHER player's identity. */
    fun storyGo(master: Boolean, name: ByteArray, gender: Int, otId: Int): Boolean
    fun storyCancel(): Boolean
    fun storyDisarm()
    /** A script the story hook held is let go (1), none was (0), or the game is not ready (-1: ask again). */
    fun releaseScript(): Int

    companion object {
        const val STATE_IDLE = 0
        const val STATE_SETUP = 1
        const val STATE_BATTLE = 2
        const val STATE_ENDED = 3
        const val STATE_ERROR = 4

        const val STORY_IDLE = 0
        const val STORY_ARMED = 1
        const val STORY_PICKING = 2
        const val STORY_READY = 3
        const val STORY_CANCELLED = 4
        const val STORY_MISMATCH = 5
    }
}

interface CoopBattleUiSink {
    fun linkWait(secondsLeft: Int)
    fun notice(text: String)
    fun clear()
}

/** How a link battle ended for this game: [result] (the 16 bytes, null after a leave), the catch read before the bridge went ([caughtBy] 0 = nothing). */
class CoopLinkEnd(val result: ByteArray?, val caughtBy: Int, val caught: ByteArray?, val left: Boolean)

/**
 * Carries the bridge's link blocks between the two games over [channel]. [afterFrame] runs on the emu thread after EVERY frame
 * (the bridge's end steps ride its state call), including after the battle went inactive and until the result is read or a leave's
 * tidy-up is through; [onMessage] may be called from any thread. Block contents are opaque here: only the generic shape (size field,
 * cap, battle id, sequence order) is checked, the per-kind checks are `pk_coop_link_give`'s.
 */
class CoopLinkBattle(
    private val engine: CoopLinkEngine, private val channel: CoopBattleChannel,
    private val ui: CoopBattleUiSink, private val clock: () -> Long,
) {
    companion object {
        const val DROP_MS = 60_000L
        /** Blocks waiting to go out; never dropped: past this the battle ends loudly. */
        const val MAX_OUTBOX = 4096
        /** Messages waiting to be read on the emu thread. */
        const val MAX_INBOX = 8192
        /** ...and the bytes they hold. */
        const val MAX_INBOX_BYTES = 1 shl 20
        /** A block the game refuses (full) is retried this many frames, then the battle ends loudly (never a silent drop: the two games would desync).
         * 1800 frames = 30 s at 60 fps: covers the gap before the other game's own begin (blocks given earlier are refused and retried). */
        const val MAX_REFUSALS = 1800
        /** After the bridge says ENDED the result is waited for this many frames (a minute): an evolution scene or a save prompt may run first. */
        const val RESULT_FRAMES = 3600
        /** After a leave the bridge's tidy-up is pumped until the game stands on its field, at most this many frames. */
        const val SETTLE_FRAMES = 1800
        /** Once the result is ready, the last blocks still queued get this many frames to leave (the other game ends by them). */
        const val FLUSH_FRAMES = 300
        /** Blocks per direction kept for the log's trace. */
        const val TRACE_MAX = 40
        /** A head block refused this many frames running gets its bytes and the bridge's position in the log (once). */
        const val STUCK_LOG_FRAMES = 120
        /** A running battle whose blocks stop (none sent, given or heard) this long gets its position logged, then again every [STALL_REPEAT_MS], [STALL_LOGS] times. */
        const val STALL_MS = 5_000L
        const val STALL_REPEAT_MS = 10_000L
        const val STALL_LOGS = 6
    }

    var log: (String) -> Unit = {}
    private var cancelled = false

    // One lock for the queue and its counters (any thread adds, the emu thread drains).
    private val inboxLock = Any()
    private val inbox = ArrayDeque<CoopBattleMsg>()
    private var inboxBytes = 0
    /** The battle id whose blocks are kept although this game has not started yet (the other player began first). */
    @Volatile private var expected = 0
    private val outbox = ArrayDeque<ByteArray>()
    private val ready = ArrayDeque<ByteArray>()
    private var battleId = 0
    private var story = false
    private var lastSeq = -1
    private var refusals = 0
    private var downAt: Long? = null
    private var endedSeen = false
    private var endFrames = 0
    private var settleFrames = 0
    private var flushFrames = 0
    private var reported = false
    // the log's evidence: the first TRACE_MAX blocks each way (kind/seq/size), once per battle
    private val sentTrace = StringBuilder(); private var sentN = 0
    private val gotTrace = StringBuilder(); private var gotN = 0
    private val heardTrace = StringBuilder(); private var heardN = 0
    private var traceLogged = false
    private var stuckLogged = false
    // the stall watch: running totals and the wall time of the last block each way (a battle that stands still logs where it stands)
    private var totalSent = 0; private var totalGiven = 0
    @Volatile private var totalHeard = 0
    private var lastMoveAt = 0L; private var stallLogs = 0; private var stallLoggedAt = 0L; private var marked = 0
    private var lastSentKind = -1; private var lastSentSeq = -1; private var lastGivenKind = -1; private var lastGivenSeq = -1
    @Volatile private var lastHeardSeq = -1; @Volatile private var lastHeardKind = -1
    /** Blocks the game refused after its battle ended (the other player's trailing "done"): dropped, counted. */
    var dropped = 0
        private set
    /** Blocks refused before the game's battle was running, retried and finally given. */
    var retried = 0
        private set

    /** The battle is running (or its end is being waited for): the guards' view. False once [onEnded] has been called. */
    @Volatile var active = false
        private set
    /** The bridge still needs its state pumped every frame: from the start to the result read / a leave's tidy-up. */
    @Volatile var pumping = false
        private set
    /** Called once on the emu thread when the battle is over (the result is ready, or the battle was left). */
    var onEnded: (CoopLinkEnd) -> Unit = {}

    /**
     * Starts the link battle in the game: [start] is the engine call that does it (the host's go, the partner's go, a story go) and
     * answers 1 / 0 / -1 like them; only a 1 makes the battle run. [story]: a story battle (a drop for good is a loss: the game's blackout, in every save).
     * Returns [start]'s answer. A second start while one runs is refused (0) without calling [start].
     */
    fun start(battleId: Int, story: Boolean, start: () -> Int): Int {
        require(battleId != 0)
        if (active || pumping) return 0   // one link battle at a time: a second start is refused, the running one untouched
        if (expected == 0) synchronized(inboxLock) { heardTrace.setLength(0); heardN = 0 }
        reset()
        val r = start()
        if (r != 1) return r
        this.battleId = battleId; this.story = story; active = true; pumping = true; expected = 0
        return 1
    }

    /** A join is on its way: the other player's blocks for [battleId] are kept until [start] (it may begin first). */
    fun expect(battleId: Int) = synchronized(inboxLock) {
        if (expected == battleId) return@synchronized   // already kept for this battle (the host asks at its offer, again at its install): what came meanwhile stays
        inbox.clear(); inboxBytes = 0; expected = battleId; heardTrace.setLength(0); heardN = 0
    }

    /** The join fell through: nothing more is kept. */
    fun forget() = synchronized(inboxLock) { expected = 0; inbox.clear(); inboxBytes = 0 }

    fun onMessage(m: CoopBattleMsg) = synchronized(inboxLock) {
        if (m is CoopBattleMsg.LinkBlock) {
            totalHeard++; lastHeardSeq = m.seq; lastHeardKind = (m.bytes.getOrNull(0)?.toInt() ?: 0) and 0xFF
            if (heardN < TRACE_MAX) { heardN++; heardTrace.append(' ').append((m.bytes.getOrNull(0)?.toInt() ?: 0) and 0xFF).append('/').append(m.seq).append('/').append(m.bytes.size - 4).append(if (active) "" else "(idle)") }
        }
        if (!active && !(m is CoopBattleMsg.LinkBlock && expected != 0 && m.battleId == expected)) return@synchronized
        val size = if (m is CoopBattleMsg.LinkBlock) m.bytes.size else 0
        // a flood is dropped (by count and by bytes): the game's refusals end the battle loudly anyway
        if (inbox.size >= MAX_INBOX || inboxBytes + size > MAX_INBOX_BYTES) return@synchronized
        inboxBytes += size; inbox.addLast(m)
    }

    fun linkDown() { if (active && !endedSeen && downAt == null) downAt = clock() }

    /** A link that comes back resumes the battle, unless the other side cancelled it: then only the drop rule remains. */
    fun linkUp() {
        if (downAt != null && !cancelled) { downAt = null; ui.clear() }
    }

    /** The host's app stops (HOME, close): the game leaves at once and the partner gets the drop rule. */
    fun stop() {
        if (!active) { if (pumping) resetNow(); return }
        channel.send(CoopBattleWire.CANCEL, CoopBattleWire.encode(CoopBattleMsg.Cancel(battleId, CoopCancel.CLOSED)).second)
        finish(blackout = false)
    }

    /** The other side could not start its half (it said so): this game leaves at once, there is no drop wait and nothing is sent back. */
    fun abandon() { if (active) { exitEvidence("the other side cancelled (ENGINE)"); finish(blackout = false) } }

    fun afterFrame() {
        if (!pumping) return
        // The state call first, every frame, active or not: the bridge's end steps and a left battle's tidy-up ride it.
        val st = engine.state()
        drainInbox()
        if (!active) {
            // A left battle: the game finishes its own end; the tidy-up needs the pump until it stands on its field.
            if (++settleFrames >= SETTLE_FRAMES || engine.settled()) pumping = false
            return
        }
        if (st == CoopLinkEngine.STATE_ERROR) {
            return failLoud("the bridge reports the two games out of step")
        }
        if (st == CoopLinkEngine.STATE_ENDED && !endedSeen) { endedSeen = true; endFrames = 0; flushFrames = 0; ui.clear() }
        while (true) {
            // Everything the game made since last frame goes out, in order.
            val b = engine.take() ?: break
            if (outbox.size >= MAX_OUTBOX) return failLoud("outbox full")
            outbox.addLast(b)
        }
        flushOutbox()
        if (!giveReady()) return failLoud("the game refused a block $MAX_REFUSALS frames running")
        val d = downAt
        if (d != null && !endedSeen) {
            val left = DROP_MS - (clock() - d)
            if (left > 0) { ui.linkWait(((left + 999) / 1000).toInt()); return }
            log("link battle: the link was down ${DROP_MS / 1000} s")
            ui.notice(CoopNotices.UNDONE)
            tell(CoopCancel.CLOSED)   // best effort: the link may be back for a moment, the other side then leaves too
            finish(blackout = story)
            return
        }
        watchStall()
        if (endedSeen) waitForResult()
    }

    /** Evidence for a battle that stands still: no block sent, given or heard for [STALL_MS] while the link is up (wall time, so fast-forward doesn't change it). */
    private fun watchStall() {
        val now = clock()
        val seen = totalSent + totalGiven + totalHeard
        if (seen != marked || lastMoveAt == 0L) { marked = seen; lastMoveAt = now; stallLogs = 0; return }
        if (endedSeen || downAt != null || stallLogs >= STALL_LOGS) return
        val idle = now - lastMoveAt
        if (idle < STALL_MS || (stallLogs > 0 && now - stallLoggedAt < STALL_REPEAT_MS)) return
        stallLogs++; stallLoggedAt = now
        log("link battle: no block for ${idle / 1000} s ($stallLogs): sent $totalSent (last $lastSentKind/$lastSentSeq), given $totalGiven (last $lastGivenKind/$lastGivenSeq), " +
            "heard $totalHeard (last $lastHeardKind/$lastHeardSeq), lastSeq $lastSeq, ready ${ready.size}, outbox ${outbox.size}, connected ${channel.connected}; bridge ${engine.diag()}")
        badBlocksLog(engine.badBlocks()).forEach { log("link battle: refused block $it") }
    }

    /** The bridge said ENDED: the game's own end runs (field, prize, flags, evolutions); the result shows once it is all through. */
    private fun waitForResult() {
        val r = engine.result()
        if (r == null) {
            // The game may still be in its own end (an evolution scene, a learn-a-move prompt, a save prompt: it waits for the player
            // for as long as it takes), so the hooks stay in and nothing is counted until it stands on its field. Only a game that is
            // back on the field with no result for RESULT_FRAMES is stuck: a leave (no-op once ended) and then the bridge goes.
            if (!engine.settled()) { endFrames = 0; return }
            if (++endFrames >= RESULT_FRAMES) {
                log("link battle: on the field with no result after $RESULT_FRAMES frames")
                tell(CoopCancel.ENGINE)
                engine.leave(false)
                report(null, left = true)
            }
            return
        }
        // The game's last blocks (its end command) must reach the other game too; a link that is down doesn't hold the result back.
        if (outbox.isNotEmpty() && channel.connected && ++flushFrames < FLUSH_FRAMES) return
        report(r, left = false)
    }

    private fun report(result: ByteArray?, left: Boolean) {
        if (reported) return
        reported = true
        val c = engine.caught()
        engine.resetBridge()
        active = false; pumping = false
        ui.clear()
        onEnded(CoopLinkEnd(result, c?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0, c?.copyOfRange(1, c.size), left))
    }

    private fun drainInbox() {
        while (true) {
            val m = synchronized(inboxLock) { inbox.removeFirstOrNull()?.also { inboxBytes -= if (it is CoopBattleMsg.LinkBlock) it.bytes.size else 0 } } ?: break
            when {
                m.battleId != battleId -> {}
                m is CoopBattleMsg.Cancel -> { cancelled = true; linkDown() }   // the other side stopped: the drop rule decides, a returning link doesn't resume
                m is CoopBattleMsg.LinkBlock -> accept(m)
            }
        }
    }

    private fun accept(m: CoopBattleMsg.LinkBlock) {
        if (!CoopBattleWire.validLinkBlock(m.bytes) || (m.bytes[1].toInt() and 0xFF) != m.seq) return
        if (lastSeq >= 0 && ((m.seq - lastSeq) and 0xFF).let { it == 0 || it > 127 }) return   // seen already or older
        lastSeq = m.seq
        ready.addLast(m.bytes)
    }

    private fun flushOutbox() {
        if (downAt != null || !channel.connected) return
        while (outbox.isNotEmpty()) {
            val b = outbox.first()
            val seq = b[1].toInt() and 0xFF
            val (type, payload) = CoopBattleWire.encode(CoopBattleMsg.LinkBlock(battleId, seq, b))
            if (!channel.send(type, payload)) return
            totalSent++; lastSentKind = b[0].toInt() and 0xFF; lastSentSeq = seq
            if (sentN < TRACE_MAX) { sentN++; sentTrace.append(' ').append(b[0].toInt() and 0xFF).append('/').append(seq).append('/').append(b.size - 4) }
            outbox.removeFirst()
        }
    }

    /**
     * False = the game refused the head block too long. A block refused before the game's battle was running is retried (the other
     * player started first); one refused after its end is dropped, not retried (the other player's trailing "done" reaches a game that
     * has already ended: nothing in it waits for that block).
     */
    private fun giveReady(): Boolean {
        while (ready.isNotEmpty()) {
            if (engine.give(ready.first())) {
                totalGiven++; lastGivenKind = ready.first()[0].toInt() and 0xFF; lastGivenSeq = ready.first()[1].toInt() and 0xFF
                if (gotN < TRACE_MAX) { val h = ready.first(); gotN++; gotTrace.append(' ').append(h[0].toInt() and 0xFF).append('/').append(h[1].toInt() and 0xFF).append('/').append(h.size - 4) }
                if (refusals > 0) retried++; ready.removeFirst(); refusals = 0; stuckLogged = false
            }
            else if (endedSeen) {
                ready.removeFirst(); dropped++; refusals = 0
                log("link battle: a block came after the end and was dropped ($dropped)")
            } else {
                if (++refusals == STUCK_LOG_FRAMES && !stuckLogged) { stuckLogged = true; logStuck() }
                return refusals < MAX_REFUSALS
            }
        }
        return true
    }

    /** pk_coop_link_bad_blocks' records as log lines: the check (a source line of pk_coop_link.c), give / take, kind, size, the bytes in hex. */
    internal fun badBlocksLog(r: ByteArray?): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (r != null && i + 6 <= r.size) {
            val why = (r[i].toInt() and 0xFF) or ((r[i + 1].toInt() and 0xFF) shl 8)
            val len = (r[i + 4].toInt() and 0xFF) or ((r[i + 5].toInt() and 0xFF) shl 8)
            val end = minOf(r.size, i + 6 + len)
            val hex = (i + 6 until end).joinToString("") { "%02x".format(r[it].toInt() and 0xFF) }
            out.add("check=$why ${if (r[i + 2].toInt() == 1) "give" else "take"} kind=${r[i + 3].toInt() and 0xFF} size=$len bytes=$hex")
            i = end
        }
        return out
    }

    /** What the head block was refused as, the bridge's position and the refused blocks: the lines that say why a battle stood still. */
    private fun logStuck() {
        val h = ready.firstOrNull()
        log("link battle: head block refused $STUCK_LOG_FRAMES frames: kind=${h?.getOrNull(0)?.toInt()?.and(0xFF)} seq=${h?.getOrNull(1)?.toInt()?.and(0xFF)} size=${h?.size} bytes=${h?.take(64)?.joinToString("") { "%02x".format(it.toInt() and 0xFF) }} queued=${ready.size} out=${outbox.size}")
        log("link battle: bridge ${engine.diag()}")
        badBlocksLog(engine.badBlocks()).forEach { log("link battle: refused block $it") }
    }

    /** Every exit of a running battle logs where it stood (the bridge's position, the refused blocks, the first blocks each way), once. */
    private fun exitEvidence(why: String) {
        log("link battle exit: $why; bridge ${engine.diag()}; refusals $refusals; ready ${ready.size}; outbox ${outbox.size}")
        badBlocksLog(engine.badBlocks()).forEach { log("link battle: refused block $it") }
        if (!traceLogged) {
            traceLogged = true
            log("link battle: first $sentN blocks sent (kind/seq/size):$sentTrace")
            log("link battle: first $gotN blocks given (kind/seq/size):$gotTrace")
            log("link battle: first $heardN blocks heard from the link (kind/seq/size):$heardTrace; lastSeq $lastSeq")
        }
    }

    private fun failLoud(why: String) {
        log("link battle ended: $why")
        exitEvidence(why)
        ui.notice(CoopNotices.UNDONE)
        tell(CoopCancel.ENGINE)   // the other game must not wait for blocks of a game that left
        finish(blackout = false)
    }

    /** Tells the other game this side ended the battle by itself (the link may be up: it would wait for blocks for ever). Best effort. */
    private fun tell(reason: CoopCancel) {
        channel.send(CoopBattleWire.CANCEL, CoopBattleWire.encode(CoopBattleMsg.Cancel(battleId, reason)).second)
    }

    /** Leaves the game's link battle (what it earned stays); the catch is read first, the bridge's tidy-up is pumped on until the field. */
    private fun finish(blackout: Boolean) {
        val c = engine.caught()
        engine.leave(blackout)
        // A leave does nothing once the bridge says ENDED (the game's own end runs): the bridge still has to go, with what it never paid.
        if (endedSeen) { engine.resetBridge() }
        active = false
        settleFrames = 0
        ui.clear()
        if (!reported) {
            reported = true
            onEnded(CoopLinkEnd(null, c?.getOrNull(0)?.toInt()?.and(0xFF) ?: 0, c?.copyOfRange(1, c.size), left = true))
        }
        if (!engine.settled()) pumping = true else pumping = false
    }

    /** The bridge goes without a leave (an app stop while the end was being waited for). */
    private fun resetNow() {
        engine.resetBridge()
        pumping = false; active = false
    }

    private fun reset() {
        outbox.clear(); ready.clear()
        sentTrace.setLength(0); gotTrace.setLength(0); sentN = 0; gotN = 0; traceLogged = false; stuckLogged = false
        lastSeq = -1; refusals = 0; downAt = null; endedSeen = false; endFrames = 0; settleFrames = 0; flushFrames = 0
        totalSent = 0; totalGiven = 0; totalHeard = 0; lastMoveAt = 0L; stallLogs = 0; marked = 0
        reported = false; active = false; pumping = false; cancelled = false; dropped = 0; retried = 0
    }
}
