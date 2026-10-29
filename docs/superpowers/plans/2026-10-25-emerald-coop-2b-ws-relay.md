# Emerald Co-op, Plan 2b: Our Own WebSocket Relay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the co-op transport (iroh through a Rust crate) with a pure-Kotlin transport over the user's own WebSocket relay, end-to-end encrypted with Noise IK, keeping same-Wi-Fi direct connections as plain TCP, so the app builds with no Rust or cargo-ndk.

**Architecture:** A new `RelayCoopTransport` implements plan 2's existing `CoopTransport` interface and emits the existing `CoopEvent`s, so `CoopLinkImpl`, `CoopPairing`, `CoopSession`'s API and plans 3-5 are untouched. Each connection is a `CoopChannel` (a Noise `IK_25519_AESGCM_SHA256` session) over a pipe: a relay room (an OkHttp WebSocket to `wss://<relay>/v1/rooms/<id>`) or a TCP socket on the LAN (found through Android NSD). The relay, `server/coop-relay/`, is a small Kotlin JVM program on Java-WebSocket that pairs two sockets per room and forwards opaque bytes; it ships as a Docker image behind Caddy.

**Tech Stack:** Kotlin 1.9.24 (the app's), JUnit 4.13.2; Bouncy Castle `org.bouncycastle:bcprov-jdk18on:1.79` (X25519 only); platform `javax.crypto` (SHA-256, HmacSHA256, AES/GCM/NoPadding); OkHttp `com.squareup.okhttp3:okhttp:4.12.0`; Android `NsdManager`; server: `org.java-websocket:Java-WebSocket:1.5.7`, `org.slf4j:slf4j-nop:2.0.13`, JDK 17 `com.sun.net.httpserver`; Docker, `caddy:2`.

**Spec:** `docs/superpowers/specs/2026-10-25-emerald-coop-ws-relay.md` (the rulings this plan implements), an addendum to `docs/superpowers/specs/2026-10-24-emerald-coop-design.md` section 1. Plan 2 (`docs/superpowers/plans/2026-10-24-emerald-coop-2-connection.md`) built everything above the transport.

**Interfaces for plans 3-5:** none change. `CoopLink`, `CoopSession`'s public functions, `CoopPairing`'s public functions, `CoopTransport`, the `CoopEvent` classes, the presence link and `attachCoopLink` stay as they are. Removed (no other branch uses them): `CoopNative`, `NativeCoopTransport`, `CoopEvent.decode`. Changed behaviour only: the ticket string form (`CoopInvite.isTicket` now matches `pdco...`), the RELAY row reads NONE instead of PUBLIC, and pairs made with the iroh build are dropped on load.

## Global Constraints

- Everything stays behind `FeatureFlags.coop` (off). Retail English Emerald only, `EMERALD_COOP_SHA1`; HELLO, pairing, `CoopSaveId`, cheats gate unchanged.
- No code in `companion/coop/` mentions `MgbaCore`, `pkWrite`, `pkCall` or `EmulatorEngine` (`CoopSafetyTest`).
- Identity: `filesDir/coop/secret.key` (32 bytes) is the X25519 private key; `CoopPeerId` = its X25519 public key (32 bytes, 64 lowercase hex). Never logged or sent.
- Noise protocol name exactly `Noise_IK_25519_AESGCM_SHA256`; prologue = ASCII `"PokeDaisy co-op 1"` + one path byte (0 invite room, 1 pair room, 2 TCP) + the room id's 32 ASCII characters (nothing for TCP). Msg 1 payload: u16 BE transport version `1`. Msg 2 payload: one status byte, 0 = go on, else a reason code.
- Reason codes (unchanged): 0 CLOSED, 1 TIMEOUT, 2 DENIED, 3 VERSION, 4 NOT_ALLOWED, 5 OVERFLOW, 6 ERROR, 7 PENDING_EXPIRED, 8 DUPLICATE.
- Session plaintext after the handshake: u8 kind + body; kinds DATA 0 (an app frame, 1..65,536 bytes), PING 1 (empty), CLOSE 2 (one reason byte). PING every 5,000 ms; 20,000 ms with nothing received = TIMEOUT; the handshake must finish within 5,000 ms (TIMEOUT).
- Room ids: 32 lowercase hex chars. Invite room = hex of HMAC-SHA256(key = invite secret (16 bytes), data = ASCII `"pokedaisy coop invite"`)[0..16]. Pair room = hex of HMAC-SHA256(key = X25519(own private, partner public), data = ASCII `"pokedaisy coop pair"` + smaller public key + larger public key)[0..16].
- Ticket: `"pdco"` + lowercase unpadded base32 (RFC 4648 alphabet `a-z2-7`, zero pad bits) of: u8 version 1, 32-byte public key, u8 flags (bit 0 = invite secret present, other bits 0), 16-byte invite secret if flagged, u8 address count 0..4, then per address u8 family (4 or 6) + 4 / 16 address bytes + u16 BE port (1..65,535). Nothing after. Regex `^pdco[a-z2-7]{56,205}$`.
- Relay protocol: `GET /v1/rooms/<room id>` WebSocket, binary messages only; byte 0: client to server `0x01` DATA + bytes; server to client `0x01` DATA (forwarded), `0x10` PEER_JOINED, `0x11` PEER_LEFT. Close codes 4400 bad request, 4408 expired / idle, 4409 room full, 4413 too large, 4429 rate / limit. `GET /healthz` -> `200 ok` on the health port.
- Relay limits (env, defaults): `PORT` 8080, `HEALTH_PORT` 8081, `MAX_ROOMS` 10000, `MAX_SOCKETS_PER_IP` 16, `MAX_NEW_SOCKETS_PER_IP_PER_MIN` 30, `MAX_MESSAGE_BYTES` 70000, `MAX_MESSAGES_PER_SEC` 500, `MAX_BYTES_PER_SEC` 524288, `LONE_TIMEOUT_SEC` 1800, `SILENT_TIMEOUT_SEC` 120, `TRUST_PROXY` 0. Logs counts and close codes only: never payloads, room ids or full IP addresses.
- Relay URL: `BuildConfig.COOP_RELAY_URL` (`coop.relayUrl` / env `COOP_RELAY_URL`, never committed) or `Prefs.coopRelayUrl`, both `https://host[:port]`; the transport speaks `wss://` (and `ws://` for an `http://` URL, tests only). None configured = no relay (LAN / BY IP only).
- Timings (a `CoopTimings` data class, shortened in tests): dial 10,000 ms, direct TCP connect 1,500 ms, handshake 5,000, ping 5,000, idle 20,000, pending 60,000, duplicate window 5,000, relay backoff 1,000 doubling to 30,000 with ±20% jitter (reset after a socket stays open 30,000 ms), LAN address recheck 10,000.
- Pending peers: at most 8 frames of at most 2,048 bytes each, 60 s; over = closed OVERFLOW / PENDING_EXPIRED with a `Disconnected`. Event queue: 1,024 frames + 64 slots for every other event; a frame that doesn't fit closes that peer OVERFLOW. Send backlog 256 frames per peer.
- Windows: Git Bash, `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`, `./gradlew --no-daemon -x licensesAsset ...` (submodules aren't checked out in worktrees), `local.properties` with `sdk.dir=C:/Users/migue/AppData/Local/Android/Sdk` (untracked). No Python. Baseline: 6 JVM tests fail here already (PerformanceGuardsTest, SiteDataExportTest, RomAddedTest.keptAcrossLaunches, SaveFileEditsTest.aTradeWritesBothOrNeither, BestEffortTest x2). Never run `find /` or any search from a drive root; never run an interpreter reading stdin with nothing piped. Never commit `third_party/melonds` changes.
- Commits: plain-sentence subjects with the `Co-op:` prefix, no Claude / Anthropic attribution (the `commit-msg` hook rejects it; never bypass it). Code reads like the surrounding code: KDoc on public types, the repo's comment density.
- New bundled code goes into NOTICE and `licensesAsset`: Bouncy Castle (MIT), OkHttp + Okio (Apache 2.0). The relay is never in the APK.

## Review Focus

- A malicious relay (or anyone on the path) replaying, reordering, truncating or flipping bytes of a session message: the channel closes with ERROR, never delivers a changed frame. Test: Task 5 `tamperedMessageClosesWithError`, `replayedMessageClosesWithError`.
- A third device, or the relay itself, trying to join an invite room and pose as the host: the joiner's handshake fails (wrong static key) and it reports `DialFailed`, never `Connected`. Test: Task 6 `impostorHostNeverConnects`.
- The relay restarting or Wi-Fi dropping mid-game: both sides see `Disconnected`, the relay sockets come back with backoff, the pair reconnects without re-pairing. Test: Task 6 `relaySocketDropReconnects`, Task 8 `serverRestartReconnects`.
- A hostile client flooding the relay (huge messages, a message storm, hundreds of sockets from one IP, joining a full room): closed with 4413 / 4429 / 4409, other rooms unaffected. Tests: Task 3 `oversizeMessageClosed4413`, `messageStormClosed4429`, `perIpSocketLimit`, `thirdPeerClosed4409`.
- Both devices dialing at once over relay and LAN: exactly one connection survives, the same one on both sides. Test: Task 6 `simultaneousDialsSettleOnOne`.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/com/pokedaisy/app/companion/coop/CoopKeys.kt` (create) | X25519 public key / DH (Bouncy Castle), room ids. |
| `.../coop/CoopNoise.kt` (create) | Noise IK handshake state and `CoopCipher` (AES-GCM, counter nonce). |
| `.../coop/CoopTicket.kt` (create) | The ticket codec. |
| `.../coop/CoopFrames.kt` (create) | TCP length framing, session kinds, relay message kinds, reason codes. |
| `.../coop/CoopChannel.kt` (create) | One end-to-end session over a pipe: handshake, policy decision, DATA / PING / CLOSE, timeouts. |
| `.../coop/CoopLan.kt` (create) | `CoopLan` interface, `NoCoopLan`, `NsdCoopLan` (Android NSD). |
| `.../coop/CoopRelaySocket.kt` (create) | `CoopRelayConnector` / `CoopRelaySocket` interfaces, `OkHttpRelayConnector`. |
| `.../coop/RelayCoopTransport.kt` (create) | `CoopTransport` over channels: rooms, TCP listener and dials, policy, pending, duplicates, events. |
| `.../coop/CoopInvite.kt`, `CoopPairing.kt`, `CoopPairs.kt`, `CoopModel.kt`, `CoopIdentity.kt`, `CoopRelay.kt`, `CoopSession.kt`, `CoopTransport.kt`, `CoopEvent.kt` (modify) | New ticket form; drop iroh pairs; doc wording; NONE relay; session builds the new transport. |
| `.../coop/CoopNative.kt` (delete) | |
| `app/src/main/rust/coop_net/**`, `third_party/licenses/coop_net-crates.txt` (delete) | |
| `app/build.gradle.kts` (modify) | Dependencies; drop `cargoNdkBuild`, rust jniLibs; licenses; test source dir for the relay. |
| `.github/workflows/ci.yml`, `release.yml` (modify) | Drop the Rust steps. |
| `server/coop-relay/settings.gradle.kts`, `build.gradle.kts` (create) | Standalone Gradle build (Kotlin JVM 1.9.24, `application`). |
| `server/coop-relay/src/main/kotlin/com/pokedaisy/relay/RelayConfig.kt`, `RelayRooms.kt`, `RelayServer.kt`, `Main.kt` (create) | Limits from env; pure room / limit logic; the WebSocket + health servers; entry point. |
| `server/coop-relay/src/test/kotlin/com/pokedaisy/relay/*Test.kt` (create) | Server tests. |
| `server/coop-relay/Dockerfile`, `docker-compose.yml`, `Caddyfile`, `README.md` (create) | Image, deployment, steps. |
| `app/src/test/kotlin/com/pokedaisy/app/companion/coop/*` | New tests; `FakeRelayConnector.kt`, `FakeCoopLan.kt`; updated ticket fixtures; `CoopRelayIntegrationTest.kt`. |
| `NOTICE`, `third_party/licenses/BouncyCastle-MIT.txt`, `docs/DEVELOPMENT.md`, `docs/manual-drafts/coop.md`, `CLAUDE.md`, the design spec (modify / create) | Licences and docs. |

## Shared interfaces (every task uses these names)

```kotlin
// CoopKeys.kt (Task 1)
object CoopKeys {
    fun publicKey(secret: ByteArray): ByteArray                    // 32 -> 32
    fun dh(secret: ByteArray, peerPublic: ByteArray): ByteArray?   // null when the result is all zero
    fun inviteRoom(inviteSecret: ByteArray): String
    fun pairRoom(ownSecret: ByteArray, own: CoopPeerId, partner: CoopPeerId): String?
}
// CoopNoise.kt (Task 1)
class CoopCipher { fun encrypt(plain: ByteArray): ByteArray; fun decrypt(sealed: ByteArray): ByteArray? }
class CoopNoise {
    companion object {
        const val PROTOCOL = "Noise_IK_25519_AESGCM_SHA256"
        fun initiator(staticSecret: ByteArray, remoteStatic: ByteArray, prologue: ByteArray, ephemeral: ByteArray? = null): CoopNoise
        fun responder(staticSecret: ByteArray, prologue: ByteArray, ephemeral: ByteArray? = null): CoopNoise
    }
    val remoteStatic: ByteArray?          // the responder knows it after reading msg 1
    val isDone: Boolean
    val handshakeHash: ByteArray          // after isDone
    fun writeMessage(payload: ByteArray): ByteArray
    fun readMessage(message: ByteArray): ByteArray?   // null: bad length or authentication failed (state unusable after)
    fun split(): Pair<CoopCipher, CoopCipher>          // (send, receive) for this side
}
// CoopTicket.kt (Task 2)
class CoopTicket(val peer: CoopPeerId, val inviteSecret: ByteArray?, val addresses: List<InetSocketAddress>) {
    fun encode(): String
    companion object { const val PREFIX = "pdco"; fun decode(s: String): CoopTicket?; fun peerOnly(peer: CoopPeerId): String }
}
// CoopFrames.kt (Task 2)
object CoopFrames {
    const val MAX_APP_FRAME = 65_536; const val MAX_TCP_FRAME = 70_000
    const val DATA = 0; const val PING = 1; const val CLOSE = 2
    const val RELAY_DATA = 0x01; const val PEER_JOINED = 0x10; const val PEER_LEFT = 0x11
    const val CLOSED = 0; const val TIMEOUT = 1; const val DENIED = 2; const val VERSION = 3; const val NOT_ALLOWED = 4
    const val OVERFLOW = 5; const val ERROR = 6; const val PENDING_EXPIRED = 7; const val DUPLICATE = 8
    fun writeTcp(out: OutputStream, bytes: ByteArray)
    fun readTcp(input: InputStream): ByteArray        // EOFException at a clean end, IOException for a length over MAX_TCP_FRAME (body never read)
    fun relayData(bytes: ByteArray): ByteArray         // 0x01 + bytes
}
// CoopLan.kt (Task 4)
interface CoopLan {
    interface Listener { fun found(peer: CoopPeerId, userData: String, addresses: List<InetSocketAddress>); fun lost(peer: CoopPeerId) }
    fun start(self: CoopPeerId, port: Int, userData: String?, listener: Listener)
    fun setUserData(userData: String?)
    fun stop()
}
object NoCoopLan : CoopLan
class NsdCoopLan(context: Context) : CoopLan
// CoopChannel.kt (Task 5)
class CoopChannel(
    val initiator: Boolean, ownSecret: ByteArray, remoteStatic: ByteArray?, prologue: ByteArray,
    private val pipe: (ByteArray) -> Boolean, private val clock: () -> Long, private val timings: CoopTimings,
    private val decide: (CoopPeerId) -> Int,      // responder only: 0 go on, else a reason code to refuse with
    private val listener: Listener,
) {
    interface Listener {
        fun opened(channel: CoopChannel, remote: CoopPeerId)
        fun frame(channel: CoopChannel, bytes: ByteArray)
        /** [beforeOpen]: the handshake never finished (the initiator reports DialFailed). Not called for our own close(). */
        fun closed(channel: CoopChannel, code: Int, beforeOpen: Boolean)
    }
    val remote: CoopPeerId?; val isOpen: Boolean; val openedAt: Long
    fun begin(); fun receive(message: ByteArray); fun send(frame: ByteArray): Boolean
    fun close(code: Int); fun tick(); fun pipeClosed()
}
data class CoopTimings(/* the Global Constraints' values, in ms */)
// CoopRelaySocket.kt (Task 6)
interface CoopRelaySocket { fun send(bytes: ByteArray): Boolean; fun close()
    interface Listener { fun onOpen(); fun onMessage(bytes: ByteArray); fun onClosed(code: Int) } }
interface CoopRelayConnector { fun open(relayUrl: String, room: String, listener: CoopRelaySocket.Listener): CoopRelaySocket }
class OkHttpRelayConnector(client: OkHttpClient = /* pingInterval 20 s, connect 10 s */) : CoopRelayConnector
// RelayCoopTransport.kt (Task 6)
class RelayCoopTransport(
    private val connector: CoopRelayConnector = OkHttpRelayConnector(), private val lan: CoopLan = NoCoopLan,
    private val timings: CoopTimings = CoopTimings(), private val clock: () -> Long = System::currentTimeMillis,
    private val bindLoopback: Boolean = false,   // tests: the TCP listener and ticket addresses on 127.0.0.1
) : CoopTransport
// server (Task 3)
class RelayServer(config: RelayConfig, tap: ((ByteArray) -> Unit)? = null) { fun start(): Unit; val port: Int; val healthPort: Int; fun stop() }
data class RelayConfig(/* the Global Constraints' env values */) { companion object { fun fromEnv(env: Map<String, String> = System.getenv()): RelayConfig } }
```

## Task order and parallel waves

- Wave 1 (parallel, separate worktrees): Task 1, Task 2, Task 3.
- Wave 2 (parallel): Task 4, Task 5 (needs 1, 2).
- Wave 3: Task 6 (needs 1, 2, 4, 5).
- Wave 4 (parallel): Task 7 (needs 6), Task 8 (needs 3, 6). Both edit `app/build.gradle.kts` in different places; the controller merges.

---

### Task 1: Keys, room ids and the Noise IK handshake

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/coop/CoopKeys.kt`, `.../coop/CoopNoise.kt`
- Create: `app/src/test/resources/coop/noise-ik-25519-aesgcm-sha256.json`, `app/src/test/resources/coop/README.md`
- Modify: `app/build.gradle.kts` (dependencies only: `implementation("org.bouncycastle:bcprov-jdk18on:1.79")`)
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/coop/CoopKeysTest.kt`, `CoopNoiseTest.kt`

**Interfaces:** Produces `CoopKeys`, `CoopNoise`, `CoopCipher` (Shared interfaces).

- [ ] **Step 1: Fetch one test vector.** From the Noise test vectors (cacophony: `https://raw.githubusercontent.com/haskell-cryptography/cacophony/master/vectors/cacophony.txt`, else snow's `tests/vectors/snow.txt`) copy the one object whose `protocol_name` is `Noise_IK_25519_AESGCM_SHA256` (no PSK) into the JSON resource as it is; the README says where it came from, its licence and the date. Use WebFetch; if neither is reachable, stop and report BLOCKED.
- [ ] **Step 2: Write the failing tests.**
  - `CoopKeysTest`: `publicKeyMatchesRfc7748` (RFC 7748 section 6.1: Alice's private `77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a` -> `8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a`), `dhAgreesBothWays` (and equals RFC 7748's shared `4a5d9d5b4ce0115a...742` with Bob's key `5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb`), `dhRefusesLowOrderPoint` (all-zero public -> null), `inviteRoomIs32Hex`, `pairRoomSameFromBothSides` and `pairRoomDiffersPerPair`, `pairRoomNeverContainsKeyHex`.
  - `CoopNoiseTest`: `matchesPublishedVector` (load the JSON with `org.json` (test dependency already there); drive initiator and responder with the vector's static / ephemeral keys and prologue; every message's bytes equal `ciphertext`, every read gives `payload`, `handshakeHash` equals `handshake_hash`, then the transport messages through `split()`); `roundTripWithRandomKeys`; `wrongResponderKeyFailsAtResponder` (initiator built with another public key: responder's `readMessage` returns null); `tamperedHandshakeFails`; `cipherRejectsTamperAndReplay` (a flipped byte -> null; the same ciphertext decrypted twice -> second is null).
- [ ] **Step 3: Run** `./gradlew --no-daemon -x licensesAsset :app:testDebugUnitTest --tests '*CoopKeysTest' --tests '*CoopNoiseTest'`. Expected: compile FAIL.
- [ ] **Step 4: Implement.** `CoopKeys` with `org.bouncycastle.math.ec.rfc7748.X25519` (`generatePublicKey`, `calculateAgreement` returning false = all zero), rooms per the Global Constraints with `javax.crypto.Mac("HmacSHA256")`. `CoopNoise` follows the Noise spec revision 34 section 5 (CipherState / SymmetricState / HandshakeState) for the IK pattern only: `-> e, es, s, ss` / `<- e, ee, se`; HASHLEN 32, `HKDF` as the spec's HMAC-SHA256 construction, AESGCM nonce = 4 zero bytes + 8-byte big-endian counter, AD = h. Ephemerals from `SecureRandom` unless passed (tests). `CoopCipher.decrypt` returns null on failure and counts the nonce only on success; it refuses after 2^63 messages. Nothing logs key material.
- [ ] **Step 5: Run the tests again.** Expected: PASS.
- [ ] **Step 6: Commit** `Co-op: X25519 keys, room ids and a Noise IK handshake checked against the published vector`.

### Task 2: Tickets, framing and the new invite form

**Files:**
- Create: `.../coop/CoopTicket.kt`, `.../coop/CoopFrames.kt`
- Modify: `.../coop/CoopInvite.kt` (regex, KDoc), `.../coop/CoopPairing.kt` (`peerOnlyTicket` -> `CoopTicket.peerOnly`, drop `ALPHABET`), `.../coop/CoopPairs.kt` (drop pairs whose non-null `lastTicket` isn't `CoopInvite.isTicket`), `.../coop/CoopModel.kt` and `.../coop/CoopIdentity.kt` (KDoc: X25519 instead of iroh)
- Test: `CoopTicketTest.kt`, `CoopFramesTest.kt` (create); `CoopInviteTest.kt`, `CoopWireTest.kt`, `CoopPairingTest.kt`, `CoopPairsTest.kt`, `CoopLinkImplTest.kt`, `FakeCoopTransport.kt` (modify: any `endpoint...` ticket becomes a real `CoopTicket(...).encode()`)

**Interfaces:** Produces `CoopTicket`, `CoopFrames` (Shared interfaces). `CoopInvite.isTicket / parse / link` keep their signatures.

- [ ] **Step 1: Write the failing tests.**
  - `CoopTicketTest`: `roundTripsKeySecretAndAddresses` (IPv4 + IPv6, ports 1 and 65535), `peerOnlyHasNoSecretOrAddresses`, `encodedMatchesInviteRegex` (min and max sizes: 56 and 205 chars after the prefix), `refusesBadInput` (wrong prefix, uppercase, `1`/`8`/`9`, a wrong version, flag bit 1 set, address count 5, family 5, port 0, a byte past the end, non-zero pad bits, a truncated secret) -> null, `addressesAreNeverLookedUp` (decode builds `InetAddress.getByAddress`, no host name).
  - `CoopFramesTest`: `tcpRoundTrip`, `tcpRefusesOversizeLengthWithoutReadingBody` (a length of 70,001 then no body: IOException, nothing more read), `tcpCleanEndIsEof`, `tcpTruncatedBodyIsIoException`.
  - `CoopInviteTest`: the old `endpoint...` tickets are now refused; a `pdco` link and a bare ticket parse; 1 MB input refused.
  - `CoopPairsTest`: `dropsPairsFromTheIrohBuild` (a `pairs.json` whose `lastTicket` is `endpoint...` loads without that pair; one with `null` or a `pdco` ticket stays).
- [ ] **Step 2: Run** `./gradlew --no-daemon -x licensesAsset :app:testDebugUnitTest --tests '*Coop*'`. Expected: FAIL.
- [ ] **Step 3: Implement** `CoopTicket` and `CoopFrames` per the Global Constraints (`DataInputStream.readFully` for the body; `writeTcp` refuses a body over `MAX_TCP_FRAME` with `IllegalArgumentException`), the invite regex `^pdco[a-z2-7]{56,205}$`, `CoopPairing.peerOnlyTicket(peer) = CoopTicket.peerOnly(peer)` (keep the internal name: tests call it), and the `CoopPairs` load filter. Update every test fixture that used an iroh ticket.
- [ ] **Step 4: Run** the same command. Expected: every `Coop*` test passes except `CoopEventTest` (unchanged here; Task 7 deletes it with the native layout).
- [ ] **Step 5: Commit** `Co-op: a ticket of our own (key, invite secret, LAN addresses) and TCP framing`.

### Task 3: The relay server

**Files:**
- Create: `server/coop-relay/settings.gradle.kts` (`rootProject.name = "coop-relay"`, plugin repositories `gradlePluginPortal()` + `mavenCentral()`, `dependencyResolutionManagement` with `mavenCentral()`), `server/coop-relay/build.gradle.kts` (`kotlin("jvm") version "1.9.24"`, `application` with `mainClass = "com.pokedaisy.relay.MainKt"`, JVM toolchain-free `jvmTarget = "17"`, deps from Tech Stack, `testImplementation("junit:junit:4.13.2")`)
- Create: `server/coop-relay/src/main/kotlin/com/pokedaisy/relay/RelayConfig.kt`, `RelayRooms.kt`, `RelayServer.kt`, `Main.kt`
- Create: `server/coop-relay/Dockerfile`, `docker-compose.yml`, `Caddyfile`, `README.md`, `.dockerignore`
- Test: `server/coop-relay/src/test/kotlin/com/pokedaisy/relay/RelayRoomsTest.kt`, `RelayServerTest.kt`

**Interfaces:** Produces `RelayServer`, `RelayConfig` (Shared interfaces). The main sources use only the JDK, Kotlin stdlib and Java-WebSocket, and compile with Kotlin 1.9.24: Task 8 compiles them into the app's unit tests.

- [ ] **Step 1: Write the failing tests.**
  - `RelayRoomsTest` (no sockets; `RelayRooms` takes a clock): `secondJoinPairsAndThirdIsRefused`, `leaveTellsTheOther`, `maxRoomsRefusesNewRooms`, `perIpSocketLimit`, `perIpNewSocketRate` (31st in a minute refused, allowed again a minute later), `tokenBucketsMessagesAndBytes`, `loneAndSilentExpire`, `roomIdMustBe32LowerHex`, `ipIsMaskedForLogs` (IPv4 to /24, IPv6 to /48).
  - `RelayServerTest` (a real `RelayServer` on port 0 with Java-WebSocket clients): `forwardsDataUnchanged` (both get PEER_JOINED, then 0x01 + bytes arrives as sent), `thirdPeerClosed4409`, `peerLeftIsSentAtOnce`, `oversizeMessageClosed4413`, `messageStormClosed4429`, `textMessageClosed4400`, `unknownKindClosed4400`, `badPathRefused` (`/v1/rooms/XYZ` and `/` never open), `perIpSocketLimit` (with `MAX_SOCKETS_PER_IP` 2), `forwardedForOnlyWithTrustProxy`, `healthzAnswersOk` (`HttpURLConnection` on `healthPort`), `tapSeesForwardedBytes`.
- [ ] **Step 2: Run** `./gradlew --no-daemon -p server/coop-relay test`. Expected: FAIL (compile).
- [ ] **Step 3: Implement.** `RelayServer` extends `org.java_websocket.server.WebSocketServer` (path checked in `onWebsocketHandshakeReceivedAsServer`, throwing `InvalidDataException(4400)` for a bad path, a full IP or the new-socket rate), with `setConnectionLostTimeout(60)` and a single scheduled sweep every 5 s for `LONE_TIMEOUT_SEC` / `SILENT_TIMEOUT_SEC` (4408). `RelayRooms` is the synchronized pure state (rooms, per-IP counts, token buckets refilled per second). The client IP is the socket's, or the last `X-Forwarded-For` entry when `TRUST_PROXY=1`. Health: `com.sun.net.httpserver.HttpServer` on `HEALTH_PORT`, `GET /healthz` -> `ok`. A log line every 60 s: rooms, sockets, messages, bytes, close codes. `tap` is called with each forwarded DATA body (tests only; null in `Main`). `Main` reads `RelayConfig.fromEnv()`, starts, adds a shutdown hook.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Docker and deployment files.** `Dockerfile`: stage 1 `gradle:8.10.2-jdk17` runs `gradle --no-daemon installDist` in `/src`; stage 2 `eclipse-temurin:17-jre-alpine`, a non-root user, `EXPOSE 8080 8081`, `ENTRYPOINT ["/app/bin/coop-relay"]`, `JAVA_OPTS="-Xmx96m"`. `docker-compose.yml`: `relay` (built here, `restart: unless-stopped`, `TRUST_PROXY=1`, no published ports) and `caddy` (`caddy:2`, ports 80 / 443, `RELAY_HOST` env, volumes for data / config). `Caddyfile`: `{$RELAY_HOST} { handle /healthz { reverse_proxy relay:8081 } handle /v1/* { reverse_proxy relay:8080 } handle { respond 404 } }`. `README.md`: what it is (no payloads logged, the limits table), a VPS (Hetzner CX22 or Fly.io; not Cloud Run: it bills an instance per open WebSocket), DNS A / AAAA record, `git clone`, `cd server/coop-relay`, `RELAY_HOST=relay.example.com docker compose up -d --build`, check `curl https://relay.example.com/healthz`, then set `coop.relayUrl=https://relay.example.com` in `local.properties` / the `COOP_RELAY_URL` Actions secret; updating (`git pull && docker compose up -d --build`); firewall (22, 80, 443 only). Build the image once locally: `docker build -t coop-relay server/coop-relay` (Docker Desktop is available); if Docker isn't running, say so in the report.
- [ ] **Step 6: Commit** `Co-op: the relay server (rooms of two, limits, health) with its Docker and Caddy setup`.

### Task 4: LAN discovery over Android NSD

**Files:**
- Create: `.../coop/CoopLan.kt`
- Create: `app/src/test/kotlin/com/pokedaisy/app/companion/coop/FakeCoopLan.kt` (a shared in-memory "network": several `FakeCoopLan`s that see each other's `start` / `setUserData` / `stop`)
- Test: `CoopLanTest.kt` (`FakeCoopLan` behaves as `CoopLan` says; `NsdCoopLan.txtFor` / `parseTxt` round-trip: `k` = 64 hex, `u` = user data up to 200 UTF-8 bytes, longer or bad `k` -> not found)

**Interfaces:** Produces `CoopLan`, `NoCoopLan`, `NsdCoopLan`, `FakeCoopLan` (Shared interfaces).

- [ ] **Step 1: Write the failing tests** above. **Step 2: Run** `--tests '*CoopLanTest'`: FAIL.
- [ ] **Step 3: Implement** `NsdCoopLan`: service type `_pdcoop._tcp`, name `pdco-` + the first 12 hex of the key, TXT `k` / `u` (internal `txtFor(peer, userData)` / `parseTxt(attributes)` are pure and tested); register on `start`, re-register on `setUserData`; browse and resolve one service at a time (a queue: `NsdManager.resolveService` fails with `FAILURE_ALREADY_ACTIVE` before API 34); `found` with the resolved host + port; `lost` from `onServiceLost` by the name -> key map; skip our own key; every callback guarded (a throwing `NsdManager` logs through `coopLog` and leaves LAN off). `NoCoopLan` does nothing.
- [ ] **Step 4: Run** the tests: PASS. **Step 5: Commit** `Co-op: same-Wi-Fi discovery through Android's NSD`.

### Task 5: The end-to-end channel

**Files:**
- Create: `.../coop/CoopChannel.kt` (with `CoopTimings`)
- Test: `CoopChannelTest.kt` (two channels wired back to back through in-memory pipes and a manual clock)

**Interfaces:** Consumes `CoopNoise`, `CoopCipher`, `CoopKeys`, `CoopFrames`. Produces `CoopChannel`, `CoopTimings` (Shared interfaces).

- [ ] **Step 1: Write the failing tests:** `opensBothSidesAndCarriesFrames`, `responderLearnsInitiatorKey`, `refusalReachesInitiatorBeforeOpen` (decide returns NOT_ALLOWED: initiator `closed(code 4, beforeOpen = true)`, responder never `opened`), `versionMismatchRefusedWithVersion` (msg 1 payload 2), `wrongResponderKeyNeverOpens`, `tamperedMessageClosesWithError`, `replayedMessageClosesWithError`, `closeSendsReasonToPeer` (peer gets `closed(DENIED, false)`, the closer gets no callback), `pingKeepsAliveAndSilenceTimesOut` (no traffic for 20 s on the manual clock -> TIMEOUT), `handshakeTimesOut`, `oversizeFrameRefusedOnSend`, `pipeClosedReportsError`, `prologueMismatchNeverOpens` (different room ids).
- [ ] **Step 2: Run** `--tests '*CoopChannelTest'`: FAIL.
- [ ] **Step 3: Implement** per the Global Constraints. Synchronized; never calls the listener while holding a lock it needs again (collect callbacks, run after). `decide` runs after msg 1 is read; a refusal still writes msg 2 (status = the code) and then closes. `send` = false unless open; a frame of 0 or over 65,536 bytes throws `IllegalArgumentException`.
- [ ] **Step 4: Run**: PASS. **Step 5: Commit** `Co-op: an end-to-end channel (Noise IK, ping, close reasons, timeouts)`.

### Task 6: The relay transport

**Files:**
- Create: `.../coop/CoopRelaySocket.kt`, `.../coop/RelayCoopTransport.kt`
- Modify: `app/build.gradle.kts` (dependencies: `implementation("com.squareup.okhttp3:okhttp:4.12.0")`)
- Create: `app/src/test/kotlin/com/pokedaisy/app/companion/coop/FakeRelayConnector.kt` (an in-memory relay with the server's room semantics: PEER_JOINED to both at two, PEER_LEFT, a third refused 4409, `dropAll()` closes every socket with 1006, `down` refuses new sockets)
- Test: `RelayCoopTransportTest.kt`

**Interfaces:** Consumes Tasks 1, 2, 4, 5 and `CoopTransport` / `CoopEvent` as they are. Produces `RelayCoopTransport`, `CoopRelayConnector`, `CoopRelaySocket`, `OkHttpRelayConnector` (Shared interfaces).

Behaviour (the spec's rulings, in transport terms):
- `start`: 0 ok, -1 key not 32 bytes, -2 a relay URL that isn't `http(s)://`, -3 the TCP listener can't bind (only without RELAY ONLY), -4 running. Picks a new random 16-byte invite secret; pushes `Ready(ticket)` (secret + up to 4 site-local IPv4 / IPv6 addresses of up interfaces and the listener's port, none with RELAY ONLY; 127.0.0.1 only with `bindLoopback`), again whenever the address list changes (rechecked every 10 s); pushes `Relay(0, "")` without a relay, `Relay(1, url)` when the first relay socket opens. Starts `lan` without RELAY ONLY. One worker thread `pokedaisy-coop-net` runs ticks (250 ms) and dials; the TCP listener has its own accept thread and one reader thread per socket. `endpointId()` = `CoopKeys.publicKey`.
- `setPolicy(allowed, pairingOpen, lanUserData)`: a relay socket on the pair room of each allowed peer (closed for peers no longer allowed; their open channels stay); the invite room while `pairingOpen`; `lan.setUserData`.
- `connect(ticket)`: -1 unparsable, -2 not running, else 0 and a dial with a 10 s deadline: an allowed peer -> its pair room (already joined, or joined now); otherwise the ticket's invite room as the initiator (no secret and no relay: LAN only); plus direct TCP to the ticket's and `lan`'s addresses for that key (1.5 s each, none with RELAY ONLY). No channel by the deadline -> `DialFailed(TIMEOUT)`; a refusal -> `DialFailed(code)`.
- Roles and decisions: invite room joiner and TCP dialer initiate; pair room: smaller key initiates when PEER_JOINED arrives. Responder `decide`: pair room -> remote must be that room's partner else NOT_ALLOWED; invite room / TCP -> allowed: 0 (not pending); pairing open: 0 (pending); else NOT_ALLOWED.
- `Connected(peer, pending, outgoing = initiator, direct = TCP)` when a channel opens; one channel per peer by the spec's duplicate rule (the loser closed DUPLICATE, no event); every channel that pushed `Connected` ends in exactly one `Disconnected` unless replaced. Frames -> `Frame`. A relay socket closing under an open channel -> `Disconnected(ERROR)`; PEER_LEFT under an open channel -> `Disconnected(ERROR)` at once; relay sockets for rooms still wanted reconnect with the backoff.
- `accept` lifts the pending caps; `deny` sends CLOSE(DENIED) and pushes `Disconnected(peer, DENIED)`; `disconnect` sends CLOSE(CLOSED), pushes `Disconnected(peer, CLOSED)`, cancels dials to it; `send` false while pending / unknown / backlog full / over 65,536 bytes; `close()` closes everything, `lan.stop()`, drops queued events.
- `LanPeer(peer, userData)` / `LanGone(peer)` from `lan` (never for our own key). `Path` is never sent. Nothing logged but codes and counts.

- [ ] **Step 1: Write the failing tests** (two or three transports, `FakeRelayConnector`, `FakeCoopLan`, short `CoopTimings`, `bindLoopback = true`; poll with a 5 s deadline helper): `invitePairingPendingThenAccepted` (host `setPolicy(∅, true, …)`, joiner `connect(hostTicket)`: host `Connected(pending)`, joiner `Connected(pending = false, outgoing)`; host `send` false until `accept`, then frames both ways), `pendingCapsOverflow`, `pendingExpires`, `closedPairingRefusesNotAllowed` (joiner gets `DialFailed(4)`), `impostorHostNeverConnects` (a third transport sits in the invite room first: the joiner's handshake fails, `DialFailed`, never `Connected`), `pairRoomConnectsFromPolicyAlone` (both allow each other, neither dials: both `Connected`), `relaySocketDropReconnects` (`dropAll()`: both `Disconnected(6)`, then `Connected` again with nothing called), `peerLeftReportsAtOnce` (one side `close()`: the other `Disconnected` within 1 s), `disconnectAndDenyEcho`, `directTcpPreferred` (both on the fake LAN, RELAY ONLY off: `Connected.direct = true`), `relayOnlyNeverListensOrAdvertises` (ticket has no addresses, `FakeCoopLan` saw no `start`), `simultaneousDialsSettleOnOne` (both `connect` at once over TCP and relay: after 2 s each side has one channel and their handshake hashes match, exposed through an `internal fun channelHashFor(peer)`), `dialTimesOutWithoutAnyPath`, `badTicketAndNotRunningCodes`, `queueCapClosesFloodingPeer`, `noRelayPushesRelayKindZero`, `readyAgainWhenAddressesChange` (inject the address source: `internal var addresses: () -> List<InetAddress>`).
- [ ] **Step 2: Run** `--tests '*RelayCoopTransportTest'`: FAIL.
- [ ] **Step 3: Implement** `CoopRelaySocket.kt` (`OkHttpRelayConnector` maps `https` -> `wss`, `http` -> `ws`, path `/v1/rooms/<room>`, binary messages, `onClosed` with the close code or 1006 on failure) and `RelayCoopTransport.kt`.
- [ ] **Step 4: Run** the new test and every `Coop*` test: PASS (all but `CoopEventTest`'s native layout cases).
- [ ] **Step 5: Commit** `Co-op: the transport over our relay and the LAN (rooms, pending peers, one connection per partner)`.

### Task 7: Wire it in, take Rust out, docs and licences

**Files:**
- Modify: `.../coop/CoopSession.kt` (`private val transport: CoopTransport = RelayCoopTransport()` becomes lazily built with `NsdCoopLan(app)` on first `pairsOf(app)`; nothing else changes), `.../coop/CoopRelay.kt` (`effective` null = no relay; `rowValue` -> `tk("NONE")` with neither; KDoc), `.../coop/CoopTransport.kt` (remove `NativeCoopTransport`, keep `coopLog`, KDoc), `.../coop/CoopEvent.kt` (remove `decode` and the native-layout KDoc; `Relay.kind` 0 none / 1 custom; `Path` documented as unused)
- Delete: `.../coop/CoopNative.kt`, `app/src/test/.../coop/CoopEventTest.kt`, `app/src/main/rust/coop_net/` (all), `third_party/licenses/coop_net-crates.txt`
- Modify: `app/build.gradle.kts` (remove `cargoNdkBuild`, the `rustJniLibs` jniLibs dir and the merge-task hook; the `COOP_RELAY_URL` comment: no relay = LAN only; licences list: drop the iroh line, add `"Bouncy Castle (co-op) - MIT" to "third_party/licenses/BouncyCastle-MIT.txt"` and name OkHttp / Okio in the Apache 2.0 line), `.github/workflows/ci.yml` and `release.yml` (remove the "Rust for Android (co-op)" steps; keep the `COOP_RELAY_URL` secret), `NOTICE` (iroh entry out; Bouncy Castle and OkHttp / Okio in), `CLAUDE.md` (the "Co-op link" paragraph rewritten: Kotlin transport over our relay `server/coop-relay` + Noise IK + NSD; no Rust; relay URL a build secret; native calls note gone), `docs/DEVELOPMENT.md` ("Co-op connection": the transport, rooms, ticket, Noise, reason codes, relay protocol and limits, `server/coop-relay/README.md` for deploying; no crate / cargo text), `docs/manual-drafts/coop.md` (relay wording: no public relays; without a relay only same Wi-Fi and BY IP), `docs/superpowers/specs/2026-10-24-emerald-coop-design.md` (one line at section 1's top: superseded for the transport by the 2026-10-25 addendum), `app/src/main/kotlin/com/pokedaisy/app/companion/ui/SettingRows.kt` (the RELAY keywords: drop "iroh")
- Create: `third_party/licenses/BouncyCastle-MIT.txt` (Bouncy Castle's licence text)
- Test: `CoopRelayTest.kt` (modify: `effective(null, "") == null`, row NONE), `CoopSafetyTest` unchanged

**Interfaces:** Consumes `RelayCoopTransport`, `NsdCoopLan`, `OkHttpRelayConnector`.

- [ ] **Step 1: Update `CoopRelayTest`; run `--tests '*Coop*'`: the NONE case FAILS.**
- [ ] **Step 2: Make the code changes and deletions above.** Check every caller of `CoopSession.relay` / `CoopEvent.Relay.kind == 2` and of "PUBLIC" in the co-op UI (`CoopUi.kt`, `SettingsActivity.kt`, `CompanionSettingsScreen.kt`, ui-preview's `Main.kt`): a relay kind of 2 no longer exists, and any player-facing "public relay" text says there's no relay instead (new strings get their seven translations in `i18n/TrCoop.kt`). `git grep -n -i "iroh\|cargo\|coop_net\|pk_coop_net\|rustJniLibs"` must find nothing outside `docs/superpowers/` history (plans / specs dated before 2026-10-25) and this plan.
- [ ] **Step 3: Run** `./gradlew --no-daemon -x licensesAsset :app:testDebugUnitTest --tests '*Coop*'` and `:app:compileDebugKotlin`: PASS. Then `cd ui-preview && gradle render -Ponly=coop` (the cached Gradle, `~/.gradle/wrapper/dists/gradle-8.10.2-all/*/gradle-8.10.2/bin/gradle`) if it builds on this machine; read the RELAY row's PNG.
- [ ] **Step 4: Commit** `Co-op: the relay transport replaces iroh; Rust and cargo-ndk leave the build`.

### Task 8: Integration test against the real relay

**Files:**
- Modify: `app/build.gradle.kts` (`sourceSets["test"].java.srcDirs("src/test/kotlin", "../server/coop-relay/src/main/kotlin")`; `testImplementation("org.java-websocket:Java-WebSocket:1.5.7")`, `testImplementation("org.slf4j:slf4j-nop:2.0.13")`)
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/coop/CoopRelayIntegrationTest.kt`

**Interfaces:** Consumes `RelayServer` / `RelayConfig` (Task 3), `RelayCoopTransport` + `OkHttpRelayConnector` (Task 6). Both transports use RELAY ONLY so every byte goes through the relay; relay URL `http://127.0.0.1:<port>`.

- [ ] **Step 1: Write the tests:** `pairsThroughTheRelay` (host opens pairing, joiner connects with the host's ticket, host accepts, a 60,000-byte frame and 200 small ones arrive in order both ways), `relaySeesOnlyCiphertext` (the server's `tap`: a marker string sent as an app frame never appears in any forwarded body), `pairRoomReconnectsAfterServerRestart` (`serverRestartReconnects`: both allow each other and connect; `stop()` the server: both `Disconnected` within 3 s; a new `RelayServer` on the same port: both `Connected` again within 10 s with short backoff timings, no new pairing), `partnerLeavingIsSeenAtOnce` (one transport `close()`: the other `Disconnected` within 2 s), `relayRejectsAThirdDevice` (a third transport that knows the invite room can't get between them: `DialFailed`).
- [ ] **Step 2: Run** `./gradlew --no-daemon -x licensesAsset :app:testDebugUnitTest --tests '*CoopRelayIntegrationTest'`. Expected: PASS (they exercise finished code; a failure is a bug in Task 3 or 6 to report, not to paper over).
- [ ] **Step 3: Commit** `Co-op: an integration test of two devices through the real relay`.

## Final checks (controller, after Task 8)

- `./gradlew --no-daemon :app:assembleDebug` with no `cargo` reachable (submodules checked out for the native build; PATH without `~/.cargo/bin`).
- `./gradlew --no-daemon -x licensesAsset :app:testDebugUnitTest`: no failures beyond the six known ones. `./gradlew --no-daemon -p server/coop-relay test`: PASS.
- A whole-branch review.
