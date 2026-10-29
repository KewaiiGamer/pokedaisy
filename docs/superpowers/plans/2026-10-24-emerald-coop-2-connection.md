# Emerald Co-op, Plan 2: Connection and Pairing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two devices running retail English Emerald pair once per save (an invite link or its QR code, SAME WI-FI, or BY IP, then "accept this player?" on both), remember each other's key, refuse everyone else, and reconnect by themselves whenever that save is played, exposing a small `CoopLink` API (state, send, received messages) that plans 3 and 4 build on. Nothing from the network touches game memory.

**Architecture:** A Rust crate, `app/src/main/rust/coop_net`, wraps iroh (QUIC / TLS 1.3, hole punching, relays) behind our own poll-based JNI surface and is built to `libpk_coop_net.so` by cargo-ndk from a Gradle task. It owns transport framing and the transport handshake; everything above it (app messages, HELLO, pairing, reconnect, policy) is plain Kotlin in `companion/coop/`, written against a `CoopTransport` interface so it is unit-tested on the JVM with a fake. A process-wide `CoopSession` runs the endpoint while the top-screen pairing pages or a paired Emerald game hold it. UI is the OPTION look: a CO-OP group on both Settings screens, invite pages on the top screen, link status on the companion.

**Tech Stack:** Rust 1.91+ (this machine: 1.98.1), iroh `=1.4.1`, iroh-mdns-address-lookup `=0.6.1`, iroh-tickets `=1.0.0`, tokio `=1.54.1`, jni `=0.21.1`, cargo-ndk `4.1.2`, cargo-about `0.9.2`; Kotlin + coroutines + JUnit; ZXing core `3.5.3` (QR encoding only); Compose (OPTION look); ui-preview and Paparazzi for renders.

**Spec:** `docs/superpowers/specs/2026-10-24-emerald-coop-design.md` (section 1; sections 6 and 7 for settings and safety). This is plan 2 of 5: 1 battle engine (done), 3 seeing each other, 4 joined battles end to end, 5 the lock and story events.

**Left to later plans:** anything sent over the link besides the link's own control messages (positions: plan 3; battles: plan 4), the NEARBY RANGE and lock rows (plans 4 / 5), LOAD STATE / REWIND / RetroAchievements rules during co-op battles (plan 4).

## Global Constraints

- Game: retail English Emerald only, SHA-1 `f3ae088181bf583e55daf962a92bb46f4f1d07b7` (`EMERALD_COOP_SHA1`), on both devices; the HELLO refuses any other ROM.
- Transport is iroh from Rust through our own JNI (no iroh Kotlin package). Library `libpk_coop_net.so`, ABIs arm64-v8a always, x86_64 when the build asks for emulator ABIs (`-Ppokedaisy.x86_64=true`, which adds x86_64 to `abiFilters` for libpokedaisy too). ALPN `pokedaisy/coop/1`.
- iroh endpoints are built from `Builder::empty()` (no n0 DNS / pkarr lookup), plus the mDNS lookup unless RELAY ONLY. Reconnects dial the partner's last ticket, refreshed in every HELLO.
- Relay: `coop.relayUrl` in untracked `local.properties` or env `COOP_RELAY_URL` (CI secret) into `BuildConfig.COOP_RELAY_URL` (empty = none), overridable in Settings (`Prefs.coopRelayUrl`); unreachable for 10 s after start, the endpoint restarts on iroh's public relays (`RelayMode::Default`). No relay address is ever committed.
- RELAY ONLY = `clear_ip_transports()` on the builder: no direct paths, no mDNS, tickets carry the relay only; SAME WI-FI and BY IP are hidden while it's on.
- Everything received is untrusted: transport frames at most 65,536 bytes, app payloads at most 65,534, unknown message types dropped, names shown only through `Gen3Text.decode` of 8 raw Emerald-charset bytes. No code in `companion/coop/` references `MgbaCore` or writes game RAM.
- One partner per save. A save is `CoopSaveId` (SaveBlock2 trainer ID, secret ID, 8 raw name bytes), read from the save file. Pairings live in `filesDir/coop/pairs.json`; the device key in `filesDir/coop/secret.key` (32 bytes, created once, never logged or sent).
- Settings: a CO-OP group in the same place on both screens (right before `CONTROLS`), NEW tag `NewFeatures.COOP` on `PAIR` on both screens, `SETTING_KEYWORDS` for every new row, a manual page `docs/manual/coop.md`, every new string translated (JA kana only, FR, DE, IT, ES, BR, PT) in `i18n/TrCoop.kt`.
- UI only from the OPTION pieces (`GbaMenu.kt`, `SettingRows.kt`, `PixelShapes.kt`, `PixelIcons`), `soundClickable` on companion buttons; the QR code drawn as square pixels.
- New bundled code (the Rust crates, ZXing) goes into NOTICE and `licensesAsset`.
- Windows: Git Bash, `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`, `./gradlew --no-daemon`, `ANDROID_NDK_HOME=/c/Users/migue/AppData/Local/Android/Sdk/ndk/28.2.13676358`. No Python. Baseline: 6 JVM tests already fail here (PerformanceGuardsTest, SiteDataExportTest, RomAddedTest.keptAcrossLaunches, SaveFileEditsTest.aTradeWritesBothOrNeither, BestEffortTest x2); "all pass" below means no failures beyond those six.
- Commits carry no Claude / Anthropic attribution (the `commit-msg` hook rejects it). Plain-sentence subjects, `Co-op:` prefix like plan 1.

## Review Focus

- A hostile or broken invite (wrong scheme, extra path, 1 MB of text, non-base32 characters, a ticket for an endpoint that never answers): refused before any dial, or a dial that fails into a plain message; never a crash or a hang. Tests in Task 4 (parse) and Task 2 (dial failure event).
- Both devices dialing each other at the same moment on a reconnect: exactly one connection survives, the same one on both sides. Tests in Task 2 (Rust) and Task 7 (Kotlin state).
- The partner reinstalled (new device key) or unpaired: the game refuses them silently, shows `NOT RECOGNISED` instead of reconnecting forever at full speed, and never opens an accept prompt during play. Test in Task 7.
- The network drops and comes back (Wi-Fi to mobile, sleep, relay restart): reconnect with backoff 1 s doubling to 60 s with jitter, reset on success, no busy loop. Tests in Task 6 (backoff) and Task 7 (state).
- A peer flooding frames, oversize frames or many frames before being accepted: disconnected with `OVERFLOW`, memory bounded by the event queue cap. Tests in Task 1 (framing) and Task 2 (pending caps, queue cap).

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/rust/coop_net/Cargo.toml`, `Cargo.lock` (create) | Crate `coop_net`, lib name `pk_coop_net`, `crate-type = ["cdylib", "rlib"]`, exact pins above; release profile `opt-level = "s"`, `lto = true`, `strip = true`, `panic = "abort"`. |
| `app/src/main/rust/coop_net/src/frame.rs` (create) | Length-prefixed framing and the transport hello. Pure, no I/O types beyond `AsyncRead` / `AsyncWrite`. |
| `app/src/main/rust/coop_net/src/events.rs` (create) | The bounded event queue and the event byte layout Kotlin decodes. |
| `app/src/main/rust/coop_net/src/node.rs` (create) | The iroh endpoint: config, relay fallback, accept / dial, policy (allowed keys, pairing open), pending caps, one connection per peer, mDNS listing. |
| `app/src/main/rust/coop_net/src/lib.rs` (create) | JNI exports only: a global `Mutex<Option<Node>>`, a 2-worker tokio runtime, argument checks. |
| `app/src/main/rust/coop_net/tests/loopback.rs` (create) | Two endpoints in one process over 127.0.0.1, relay disabled. |
| `app/src/main/rust/coop_net/about.toml`, `about.hbs` (create) | cargo-about config (accepted licenses) and the plain-text template. |
| `third_party/licenses/coop_net-crates.txt` (create, generated) | Every Rust crate's license text, for `licensesAsset`. |
| `app/build.gradle.kts` (modify) | `COOP_RELAY_URL` BuildConfig, `cargoNdkBuild` task, jniLibs dir, x86_64 opt-in, ZXing dependency, two `licensesAsset` entries. |
| `.github/workflows/ci.yml`, `.github/workflows/release.yml` (modify) | Android Rust targets + cargo-ndk before the APK build; `COOP_RELAY_URL` secret on release. |
| `app/src/main/kotlin/com/pokedaisy/app/companion/coop/CoopNative.kt` (create) | `external fun`s, loads `pk_coop_net` lazily. |
| `.../companion/coop/CoopTransport.kt` (create) | `CoopTransport` interface + `NativeCoopTransport`. |
| `.../companion/coop/CoopEvent.kt` (create) | Native event decoding. |
| `.../companion/coop/CoopWire.kt` (create) | App message framing, message types, `CoopHello` codec. |
| `.../companion/coop/CoopInvite.kt` (create) | Invite link / ticket parse and format. |
| `.../companion/coop/CoopModel.kt` (create) | `CoopPeerId`, `CoopSaveId`, `CoopPartner`, `CoopPair`, `CoopState`, reasons, `CoopMessage`, `CoopLink`. |
| `.../companion/coop/CoopPairs.kt` (create) | `pairs.json` store. |
| `.../companion/coop/CoopIdentity.kt` (create) | The device key file. |
| `.../companion/coop/CoopRelay.kt` (create) | Which relay to use, URL validation, the RELAY row's value. |
| `.../companion/coop/CoopBackoff.kt` (create) | Reconnect delays. |
| `.../companion/coop/CoopIpHello.kt` (create) | BY IP's TCP hello server and client. |
| `.../companion/coop/CoopLinkImpl.kt` (create) | The `CoopLink` over a transport: HELLO checks, reconnect, one partner. |
| `.../companion/coop/CoopPairing.kt` (create) | The pairing state machine the invite pages drive. |
| `.../companion/coop/CoopQr.kt` (create) | ZXing `QRCodeWriter` to a `BooleanArray` grid. |
| `.../companion/coop/CoopSession.kt` (create) | Process-wide owner: holds, endpoint lifetime, poll thread, multicast lock, prefs. |
| `app/src/main/kotlin/com/pokedaisy/app/CoopLinkActivity.kt` (create) | Exported trampoline for `pokedaisy://coop/<ticket>`; starts SettingsActivity on the JOIN page. |
| `app/src/main/AndroidManifest.xml` (modify) | The trampoline + its intent filter, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, the INTERNET comment. |
| `app/src/main/kotlin/com/pokedaisy/app/Prefs.kt` (modify) | `coopRelayUrl`, `coopRelayOnly`. |
| `app/src/main/kotlin/com/pokedaisy/app/SettingsActivity.kt` (modify) | CO-OP group; `Screen.COOP_PAIR`, `COOP_JOIN`, `COOP_PARTNERS`, `COOP_RELAY`. |
| `app/src/main/kotlin/com/pokedaisy/app/companion/ui/CoopUi.kt` (create) | Shared pieces: `CoopQrCode`, `CoopAcceptPrompt`, `coopLinkLabel`, `CoopPartnerRows`. |
| `app/src/main/kotlin/com/pokedaisy/app/companion/CompanionSettings.kt`, `companion/ui/CompanionSettingsScreen.kt` (modify) | The companion's CO-OP group and `Page.COOP_PARTNERS`. |
| `app/src/main/kotlin/com/pokedaisy/app/PokeDaisyActivity.kt` (modify) | `CoopSession.startForGame` / `stopGame`; the `CompanionSettings` coop members. |
| `app/src/main/kotlin/com/pokedaisy/app/companion/NewFeatures.kt`, `companion/ui/SettingRows.kt` (modify) | `COOP` id; keywords. |
| `app/src/main/kotlin/com/pokedaisy/app/companion/i18n/TrCoop.kt` (create), `i18n/Tables.kt` (modify) | Translations. |
| `ui-preview/build.gradle.kts`, `ui-preview/data/build.gradle.kts`, `ui-preview/src/render/Main.kt`, `ui-preview/src/render/DocsShots.kt` (modify) | Include `companion/coop/**`, ZXing; `coop-*` shots. |
| `docs/manual/coop.md` (create), `docs/manual/settings-reference.md`, `docs/manual/synonyms.yml` (modify), `docs/manual/shots/*.png` (generated) | The manual. |
| `NOTICE`, `docs/DEVELOPMENT.md`, `CLAUDE.md` (modify) | Third-party notices; a "Co-op connection" section; the exposed-surfaces line and a short project note. |

## Shared interfaces (every task uses these names)

Reason codes, shared by Rust (QUIC close codes and events) and Kotlin (`CoopEvent.Disconnected.code`):

| Code | Name | Meaning |
|---|---|---|
| 0 | `CLOSED` | Closed normally (either side, or `close()`). |
| 1 | `TIMEOUT` | Idle timeout (QUIC, 20 s). |
| 2 | `DENIED` | The other side pressed `DENY`, or our `deny()`. |
| 3 | `VERSION` | Transport hello: different magic or transport version. |
| 4 | `NOT_ALLOWED` | A key not in the allowed set while pairing is closed. |
| 5 | `OVERFLOW` | Oversize frame, pending caps exceeded, or our event queue full. |
| 6 | `ERROR` | Anything else (I/O, TLS). |
| 7 | `PENDING_EXPIRED` | Not accepted or denied within 60 s. |
| 8 | `DUPLICATE` | The losing connection of two to the same peer. |

Native event layout (`pollEvent()` returns one of these or null; integers big-endian; `peer` = 32 raw bytes):

| Kind byte | Name | Payload after the kind byte |
|---|---|---|
| 1 | READY | ticket, UTF-8 |
| 2 | RELAY | u8 kind (0 none, 1 custom, 2 public), url UTF-8 (may be empty) |
| 3 | CONNECTED | peer, u8 flags (bit 0 pending, bit 1 we dialed, bit 2 direct path) |
| 4 | FRAME | peer, frame bytes |
| 5 | DISCONNECTED | peer, u8 reason code |
| 6 | DIAL_FAILED | u8 reason code, message UTF-8 |
| 7 | LAN_PEER | peer, user data UTF-8 |
| 8 | LAN_GONE | peer |
| 9 | PATH | peer, u8 (1 direct, 0 relay) |
| 10 | ERROR | message UTF-8 (logged only) |

JNI (`com.pokedaisy.app.companion.coop.CoopNative`, Rust symbols `Java_com_pokedaisy_app_companion_coop_CoopNative_<name>`):

```kotlin
internal object CoopNative {
    external fun init(secretKey: ByteArray, relayUrl: String?, relayOnly: Boolean): Int   // 0 ok; -1 bad key, -2 bad url, -3 bind failed, -4 already running
    external fun endpointId(): ByteArray?          // 32 bytes once running
    external fun ticket(): String?                 // current EndpointTicket string, null until READY
    external fun setPolicy(allowed: Array<ByteArray>, pairingOpen: Boolean, lanUserData: String?)
    external fun connect(ticket: String): Int      // 0 queued, -1 unparsable ticket, -2 not running
    external fun accept(peer: ByteArray)
    external fun deny(peer: ByteArray)
    external fun disconnect(peer: ByteArray)
    external fun send(peer: ByteArray, frame: ByteArray): Boolean   // false: not connected, pending, or frame > 65,536
    external fun pollEvent(): ByteArray?
    external fun close()
}
```

Kotlin model (`CoopModel.kt`), what plans 3 and 4 rely on:

```kotlin
const val COOP_PROTOCOL = 1
const val EMERALD_COOP_SHA1 = "f3ae088181bf583e55daf962a92bb46f4f1d07b7"

@JvmInline value class CoopPeerId(val hex: String)          // 64 lowercase hex chars; CoopPeerId.of(bytes: ByteArray)
data class CoopSaveId(val trainerId: Int, val secretId: Int, val name: ByteArray /* 8 raw Emerald-charset bytes */) {
    val key: String   // "%04x%04x-" + name as 16 hex chars; equals / hashCode by key
}
data class CoopPartner(val peer: CoopPeerId, val name: String /* Gen3Text.decode of save.name */, val trainerId: Int, val female: Boolean, val save: CoopSaveId)
data class CoopPair(val ownSave: CoopSaveId, val ownLabel: String, val partner: CoopPartner, val lastTicket: String?, val pairedAt: Long)

sealed interface CoopState {
    data class Off(val reason: CoopOffReason? = null) : CoopState
    data class Connecting(val partner: CoopPartner) : CoopState
    data class Connected(val partner: CoopPartner, val direct: Boolean) : CoopState
    data class Disconnected(val partner: CoopPartner, val reason: CoopDropReason, val retryAtMs: Long) : CoopState
}
enum class CoopOffReason { NOT_EMERALD, NO_SAVE, NOT_PAIRED, CHEATS_ON, NETWORK_ERROR }
enum class CoopDropReason { CLOSED, NETWORK, NOT_RECOGNISED, VERSION, DIFFERENT_ROM, DIFFERENT_SAVE }

class CoopMessage(val type: Int, val payload: ByteArray)

interface CoopLink {
    val state: StateFlow<CoopState>
    val messages: SharedFlow<CoopMessage>          // only types >= CoopWire.FIRST_APP_TYPE, only from the paired partner
    fun send(type: Int, payload: ByteArray): Boolean   // false unless Connected; throws IllegalArgumentException for a type < 0x0100 or > 0xFFFF, or payload > CoopWire.MAX_PAYLOAD
}
```

Message type ranges (`CoopWire`): `0x0001`-`0x00FF` the link's own (HELLO 1, PAIR_ACCEPT 2, PAIR_DENY 3, BYE 4); `0x0100`-`0x01FF` plan 3 (seeing each other), `0x0200`-`0x02FF` plan 4 (battles), `0x0300`-`0x03FF` plan 5 (the lock). Other types are dropped on receipt.

---

### Task 1: Rust crate, framing and the transport hello

**Files:**
- Create: `app/src/main/rust/coop_net/Cargo.toml`, `Cargo.lock`, `src/lib.rs` (empty but for `pub mod frame;`), `src/frame.rs`
- Modify: `.gitignore` (add `app/src/main/rust/coop_net/target/`)

**Interfaces:**
- Produces (`frame.rs`):
```rust
pub const MAGIC: [u8; 4] = *b"PDCO";
pub const TRANSPORT_VERSION: u16 = 1;
pub const MAX_FRAME: usize = 65_536;
pub enum FrameError { TooLarge(usize), Closed, Io(std::io::Error), BadHello, VersionMismatch { ours: u16, theirs: u16 } }
pub fn encode(payload: &[u8]) -> Result<Vec<u8>, FrameError>                       // u32 BE length + payload
pub async fn read_frame<R: AsyncRead + Unpin>(r: &mut R) -> Result<Vec<u8>, FrameError>   // rejects a length > MAX_FRAME before reading the body
pub async fn write_frame<W: AsyncWrite + Unpin>(w: &mut W, payload: &[u8]) -> Result<(), FrameError>
pub fn hello_bytes() -> [u8; 6]                                                   // MAGIC + TRANSPORT_VERSION BE
pub fn check_hello(b: &[u8]) -> Result<(), FrameError>                            // BadHello / VersionMismatch
```

- [ ] **Step 1: Write the failing tests** in `frame.rs` (`#[cfg(test)] mod tests`, tokio `duplex` streams):

```rust
#[tokio::test] async fn round_trip() { /* write_frame(b"abc") then read_frame == b"abc"; also an empty payload and one of exactly MAX_FRAME bytes */ }
#[tokio::test] async fn oversize_length_rejected_before_body() { /* raw header 0x00010001 (65,537), no body: read_frame -> TooLarge(65537) without waiting */ }
#[test] fn encode_refuses_oversize() { assert!(matches!(encode(&vec![0; MAX_FRAME + 1]), Err(FrameError::TooLarge(_)))); }
#[tokio::test] async fn split_reads() { /* header and body arrive one byte at a time: still one frame */ }
#[tokio::test] async fn eof_mid_frame_is_closed() { /* header says 10, 4 bytes then EOF -> Closed */ }
#[test] fn hello_checks() { /* hello_bytes() ok; b"PDCX\0\x01" -> BadHello; b"PDCO\0\x02" -> VersionMismatch { ours: 1, theirs: 2 }; 3 bytes -> BadHello */ }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `cargo test --manifest-path app/src/main/rust/coop_net/Cargo.toml` → Expected: compile errors (functions not defined).

- [ ] **Step 3: Write `Cargo.toml` with the exact pins** (`iroh = { version = "=1.4.1", default-features = false, features = ["tls-ring", "portmapper"] }`, `iroh-mdns-address-lookup = "=0.6.1"`, `iroh-tickets = "=1.0.0"`, `tokio = { version = "=1.54.1", features = ["rt-multi-thread", "net", "time", "sync", "macros", "io-util"] }`, `jni = { version = "=0.21.1", default-features = false }`, `rust-version = "1.91"`) **and implement `frame.rs`.** Commit `Cargo.lock`.

- [ ] **Step 4: Run them to verify they pass**

Run: `cargo test --manifest-path app/src/main/rust/coop_net/Cargo.toml frame` → Expected: 6 passed. (If ring fails to build on the Windows MSVC host, run the same command in a `rust:1.91` Docker container mounting the worktree; note which in docs/DEVELOPMENT.md in Task 11.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/rust/coop_net .gitignore
git commit -m "Co-op: Rust crate for the link, framing and the transport hello"
```

### Task 2: The iroh node: endpoint, policy, events, loopback test

**Files:**
- Create: `app/src/main/rust/coop_net/src/events.rs`, `src/node.rs`, `tests/loopback.rs`
- Modify: `src/lib.rs` (`pub mod events; pub mod node;`)

**Interfaces:**
- Consumes: Task 1's `frame`.
- Produces:
```rust
// events.rs
pub const QUEUE_CAP: usize = 1024;
pub enum Event { Ready(String), Relay { kind: u8, url: String }, Connected { peer: [u8; 32], pending: bool, outgoing: bool, direct: bool },
                 Frame { peer: [u8; 32], bytes: Vec<u8> }, Disconnected { peer: [u8; 32], reason: u8 }, DialFailed { reason: u8, message: String },
                 LanPeer { peer: [u8; 32], user_data: String }, LanGone([u8; 32]), Path { peer: [u8; 32], direct: bool }, Error(String) }
impl Event { pub fn to_bytes(&self) -> Vec<u8> }                // the layout table above
pub struct EventQueue { /* Mutex<VecDeque<Event>> */ }
impl EventQueue { pub fn push(&self, e: Event) -> bool; pub fn pop(&self) -> Option<Event>; }   // push false when full

// node.rs
pub struct NodeConfig { pub secret_key: [u8; 32], pub relay_url: Option<String>, pub relay_only: bool, pub lan: bool,
                        pub bind_loopback: bool /* tests: 127.0.0.1:0 */, pub relay_fallback_after: Duration /* 10 s; tests shorter */,
                        pub fallback_mode: FallbackRelay /* Public (RelayMode::Default) | None (tests) */ }
pub struct Node { /* Endpoint, connections: HashMap<[u8;32], Conn>, policy, queue: Arc<EventQueue> */ }
impl Node {
    pub async fn start(cfg: NodeConfig, queue: Arc<EventQueue>) -> Result<Node, StartError>;
    pub fn id(&self) -> [u8; 32];
    pub fn ticket(&self) -> Option<String>;
    pub fn set_policy(&self, allowed: HashSet<[u8; 32]>, pairing_open: bool, lan_user_data: Option<String>);
    pub fn connect(&self, ticket: &str) -> Result<(), ConnectError>;   // dial runs on the runtime; result as events
    pub fn accept(&self, peer: [u8; 32]); pub fn deny(&self, peer: [u8; 32]); pub fn disconnect(&self, peer: [u8; 32]);
    pub fn send(&self, peer: [u8; 32], frame: &[u8]) -> bool;
    pub async fn close(self);
}
```

Rules `node.rs` implements:
- Builder: `Builder::empty()`, `secret_key`, `alpns(vec![b"pokedaisy/coop/1".to_vec()])`, `relay_mode(RelayMode::Custom(<relay_url>))` when a URL is given else `RelayMode::Default`; `relay_only` adds `clear_ip_transports()`; `lan && !relay_only` adds the mDNS address lookup with `user_data_for_address_lookup` updated from `set_policy` (user data only while `pairing_open`). No relay home after `relay_fallback_after` with a custom URL: restart the endpoint on `fallback_mode`, push `Relay { kind: 2, .. }`; otherwise `Relay { kind: 1, .. }` once a home relay is up.
- Each connection: the dialer opens one bidirectional stream, both sides write `hello_bytes()` first and `check_hello` the other's (failure: close with `VERSION` / `ERROR`, push `Disconnected`).
- Incoming from a key in `allowed`: `Connected { pending: false }`. Not allowed and pairing open: `Connected { pending: true }`; until `accept`, at most 8 frames of at most 2,048 bytes each are forwarded, more closes with `OVERFLOW`; after 60 s, `PENDING_EXPIRED`. Not allowed and pairing closed: close with `NOT_ALLOWED`, no `Connected` event. Outgoing dials are never pending.
- Two live connections to one peer: keep the one dialed by the smaller endpoint id (bytewise), close the other with `DUPLICATE` and push no `Disconnected` for it (the peer stays connected).
- Queue full when pushing a `Frame`: close that peer with `OVERFLOW` (never drop silently).
- `Path` events on relay / direct changes (from the connection's path watcher).

- [ ] **Step 1: Write the failing loopback tests** in `tests/loopback.rs` (each test: two `Node`s with `bind_loopback: true`, `relay_url: None`, `fallback_mode: None`, `lan: false`; a helper `wait_for(queue, pred, 5 s)`):

```rust
#[tokio::test] async fn allowed_peer_connects_and_exchanges_frames() { /* B allows A; A.connect(B.ticket()); both get Connected{pending:false}; A.send(b"hi") -> B gets Frame{bytes:b"hi"} and back */ }
#[tokio::test] async fn unknown_peer_refused_when_pairing_closed() { /* B allows nobody, pairing closed; A dials -> A gets Disconnected{reason:4}; B gets no Connected */ }
#[tokio::test] async fn pending_until_accept_then_full() { /* B pairing open; Connected{pending:true}; a 2,049-byte frame from A closes with 5; reconnect, B.accept(A) -> a 60,000-byte frame passes */ }
#[tokio::test] async fn ninth_pending_frame_overflows() { /* 9 small frames before accept -> Disconnected{reason:5} on both */ }
#[tokio::test] async fn deny_closes_with_denied() { /* B.deny(A) -> A gets Disconnected{reason:2} */ }
#[tokio::test] async fn transport_version_mismatch() { /* a test-only cfg hook makes A send PDCO v2 -> both Disconnected{reason:3} */ }
#[tokio::test] async fn simultaneous_dials_leave_one_connection() { /* A and B allow each other and dial at once; after 2 s each has exactly one live connection to the other, a frame each way arrives once */ }
#[tokio::test] async fn dial_to_dead_ticket_fails() { /* ticket of a closed node -> DialFailed within 15 s */ }
#[tokio::test] async fn unreachable_custom_relay_falls_back() { /* relay_url "https://127.0.0.1:9", relay_fallback_after 1 s -> Relay{kind:2} event */ }
#[test] fn event_layout() { /* Event::Connected{..pending:true,outgoing:false,direct:true}.to_bytes() == [3, peer.., 0b101]; DialFailed{reason:6,message:"x"} == [6,6,b'x'] */ }
#[test] fn full_queue_refuses() { /* QUEUE_CAP pushes ok, next push false */ }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `cargo test --manifest-path app/src/main/rust/coop_net/Cargo.toml` → Expected: compile errors (`Node` not defined).

- [ ] **Step 3: Implement `events.rs` and `node.rs`** to the rules above. iroh API names come from docs.rs for iroh 1.4.1 (`iroh::endpoint::Builder`, `RelayMode`, `Endpoint::connect` / `accept`, `iroh_tickets::endpoint::EndpointTicket`); confirm the mDNS lookup's builder and user-data subscription on docs.rs for iroh-mdns-address-lookup 0.6.1.

- [ ] **Step 4: Run them to verify they pass**

Run: `cargo test --manifest-path app/src/main/rust/coop_net/Cargo.toml` → Expected: all pass (Task 1's too). Run the suite three times; any flaky test gets a longer wait, never a retry loop.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/rust/coop_net
git commit -m "Co-op: iroh node with pairing policy, pending caps and one connection per peer"
```

### Task 3: JNI exports, the Gradle cargo task, relay BuildConfig, CI and licenses

**Files:**
- Modify: `app/src/main/rust/coop_net/src/lib.rs`
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/coop/CoopNative.kt`
- Modify: `app/build.gradle.kts` (secret helper ~line 41, defaultConfig ~line 67, ndk ~line 76, `licensesAsset` ~line 156, dependencies), `.github/workflows/ci.yml` (before "assembleDebug" ~line 55), `.github/workflows/release.yml` (sdkmanager step ~line 113, env ~line 144)
- Create: `app/src/main/rust/coop_net/about.toml`, `about.hbs`, `third_party/licenses/coop_net-crates.txt` (ZXing reuses `third_party/licenses/Apache-2.0.txt`)
- Modify: `NOTICE`

**Interfaces:**
- Consumes: Task 2's `Node`, `EventQueue`.
- Produces: the JNI surface in "Shared interfaces"; `BuildConfig.COOP_RELAY_URL: String`; Gradle task `cargoNdkBuild`; property `pokedaisy.x86_64`.

- [ ] **Step 1: Write the failing check**: `./gradlew --no-daemon :app:assembleDebug` then `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep pk_coop_net` → Expected now: no match.

- [ ] **Step 2: Implement `lib.rs`'s JNI exports**: one `static NODE: Mutex<Option<(Runtime, Node, Arc<EventQueue>)>>`; every export catches panics (`std::panic::catch_unwind`) and returns its error value; byte arrays checked for length 32 before use; `pollEvent` returns `null` when empty; `close` drops the runtime after `Node::close`. Add a Rust unit test that calls the non-JNI inner functions (`init_inner`, `poll_inner`) for: a 31-byte key returns -1, `connect_inner("not a ticket")` returns -1, a second `init_inner` returns -4.

- [ ] **Step 3: Gradle.** Rename `screenScraperSecret` to `buildSecret` (same body, both call sites updated); `buildConfigField("String", "COOP_RELAY_URL", "\"${buildSecret("coop.relayUrl", "COOP_RELAY_URL")}\"")`. `val nativeAbis = listOf("arm64-v8a") + if (featureFlag("x86_64")) listOf("x86_64") else emptyList()` feeds `ndk.abiFilters`. Register `cargoNdkBuild` (Exec): `cargo ndk <-t abi per nativeAbis> --platform 26 -o build/rustJniLibs build --release --manifest-path src/main/rust/coop_net/Cargo.toml`, environment `ANDROID_NDK_HOME = android.ndkDirectory`, inputs `src/main/rust/coop_net/{src,Cargo.toml,Cargo.lock}`, output `build/rustJniLibs`; before running, if `cargo ndk --version` fails, throw `GradleException("Co-op needs Rust: rustup target add aarch64-linux-android x86_64-linux-android && cargo install cargo-ndk --version 4.1.2 --locked")`. `sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("rustJniLibs"))`; `tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach { dependsOn(cargoNdkBuild) }` (not `preBuild`: JVM tests need no Rust). Add `implementation("com.google.zxing:core:3.5.3")`.

- [ ] **Step 4: `CoopNative.kt`**: the object from "Shared interfaces" with `init { System.loadLibrary("pk_coop_net") }`.

- [ ] **Step 5: CI.** In both workflows, before the APK build: `rustup target add aarch64-linux-android` and `cargo install cargo-ndk --version 4.1.2 --locked` (no new actions, so nothing new to pin), `ANDROID_NDK_HOME` from the sdkmanager path. Release env adds `COOP_RELAY_URL: ${{ secrets.COOP_RELAY_URL }}` with a `::notice::` (not a warning) when empty: those builds use the public relays.

- [ ] **Step 6: Licenses.** `about.toml` accepts `MIT`, `Apache-2.0`, `Apache-2.0 WITH LLVM-exception`, `BSD-2-Clause`, `BSD-3-Clause`, `ISC`, `Unicode-3.0`, `Zlib`, `CDLA-Permissive-2.0`, `0BSD`, `MPL-2.0`; anything else fails generation and must be added deliberately. Generate: `cargo install cargo-about --version 0.9.2 --locked && cargo about generate --manifest-path app/src/main/rust/coop_net/Cargo.toml app/src/main/rust/coop_net/about.hbs > third_party/licenses/coop_net-crates.txt`. `licensesAsset` gains `"iroh and its Rust crates (co-op) - MIT / Apache 2.0 and others" to "third_party/licenses/coop_net-crates.txt"`, and ZXing joins the Apache line's name (`"..., ZXing - Apache License 2.0"`). NOTICE gains two bullets: iroh (n0-computer, MIT or Apache-2.0, https://github.com/n0-computer/iroh, built into libpk_coop_net.so with the crates listed in third_party/licenses/coop_net-crates.txt) and ZXing core (Apache-2.0, https://github.com/zxing/zxing, QR encoding for co-op invites).

- [ ] **Step 7: Verify**

Run: `cargo test --manifest-path app/src/main/rust/coop_net/Cargo.toml` → all pass. Run: `ANDROID_NDK_HOME=/c/Users/migue/AppData/Local/Android/Sdk/ndk/28.2.13676358 ./gradlew --no-daemon :app:assembleDebug` then `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep pk_coop_net` → Expected: `lib/arm64-v8a/libpk_coop_net.so`. With `-Ppokedaisy.x86_64=true` → both ABIs listed. Note the arm64 `.so` size in the commit message.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/rust/coop_net app/src/main/kotlin/com/pokedaisy/app/companion/coop/CoopNative.kt app/build.gradle.kts .github/workflows third_party/licenses NOTICE
git commit -m "Co-op: JNI surface, cargo-ndk build, relay from the build's secrets, Rust licenses"
```

### Task 4: Kotlin wire: events, app messages, HELLO, invites

**Files:**
- Create: `.../companion/coop/CoopModel.kt`, `CoopEvent.kt`, `CoopWire.kt`, `CoopInvite.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/coop/CoopWireTest.kt`, `CoopInviteTest.kt`, `CoopEventTest.kt`

**Interfaces:**
- Produces: the model in "Shared interfaces", plus
```kotlin
sealed interface CoopEvent {
    data class Ready(val ticket: String) : CoopEvent
    data class Relay(val kind: Int, val url: String) : CoopEvent              // 0 none, 1 custom, 2 public
    data class Connected(val peer: CoopPeerId, val pending: Boolean, val outgoing: Boolean, val direct: Boolean) : CoopEvent
    class Frame(val peer: CoopPeerId, val bytes: ByteArray) : CoopEvent
    data class Disconnected(val peer: CoopPeerId, val code: Int) : CoopEvent
    data class DialFailed(val code: Int, val message: String) : CoopEvent
    data class LanPeer(val peer: CoopPeerId, val userData: String) : CoopEvent
    data class LanGone(val peer: CoopPeerId) : CoopEvent
    data class Path(val peer: CoopPeerId, val direct: Boolean) : CoopEvent
    data class Error(val message: String) : CoopEvent
    companion object { fun decode(b: ByteArray): CoopEvent? }                  // null on any malformed layout
}
object CoopWire {
    const val MAX_FRAME = 65_536; const val MAX_PAYLOAD = 65_534; const val FIRST_APP_TYPE = 0x0100
    const val HELLO = 1; const val PAIR_ACCEPT = 2; const val PAIR_DENY = 3; const val BYE = 4
    fun encode(type: Int, payload: ByteArray): ByteArray                     // u16 BE type + payload; throws on type outside 1..0xFFFF or payload > MAX_PAYLOAD
    fun decode(frame: ByteArray): CoopMessage?                               // null: shorter than 2 bytes, longer than MAX_FRAME, type 0
}
class CoopHello(val protocol: Int, val appVersion: String, val romSha1: String, val save: CoopSaveId, val female: Boolean, val ticket: String?) {
    fun encode(): ByteArray
    companion object { fun decode(b: ByteArray): CoopHello? }               // null on any field out of range
}
object CoopInvite {
    const val SCHEME_PREFIX = "pokedaisy://coop/"
    fun link(ticket: String): String
    fun parse(text: String): String?                                         // the ticket, or null
}
```

HELLO layout: u16 protocol, u8 appVersion length (1..32, ASCII `[0-9A-Za-z.+-]`), appVersion, 20 bytes ROM SHA-1, u16 trainerId, u16 secretId, 8 bytes name, u8 female (0 / 1), u16 ticket length (0..2,048), ticket (ASCII). Nothing after it. `CoopInvite.parse`: trim; the input at most 4,096 chars; strip `SCHEME_PREFIX` when present (case-insensitive scheme), else take it as a bare ticket; the ticket must match `^endpoint[a-z2-7]{16,2040}$` (iroh's EndpointTicket string form; confirm the prefix and alphabet against `EndpointTicket::to_string()` in Task 2's tests and adjust the regex there, not here).

- [ ] **Step 1: Write the failing tests**

```kotlin
// CoopWireTest
@Test fun roundTrip() { val m = CoopWire.decode(CoopWire.encode(0x0123, byteArrayOf(1, 2)))!!; assertEquals(0x0123, m.type); assertArrayEquals(byteArrayOf(1, 2), m.payload) }
@Test fun refusesOversizeAndBadTypes() { assertThrows(IllegalArgumentException::class.java) { CoopWire.encode(5, ByteArray(65_535)) }; assertThrows(IllegalArgumentException::class.java) { CoopWire.encode(0x10000, ByteArray(0)) }; assertNull(CoopWire.decode(byteArrayOf(0))); assertNull(CoopWire.decode(byteArrayOf(0, 0))) }
@Test fun helloRoundTrip() { /* protocol 1, "1.3.1", EMERALD sha1, save (0x1234, 0xABCD, name bytes BB C6 C9 C8 FF 00 00 00), female, ticket "endpointabc..." -> decode == fields */ }
@Test fun helloRejects() { /* truncated by 1 byte; trailing byte; appVersion length 33; appVersion "1.3.1\n"; ticket length 2,049; female = 2 -> all null */ }
// CoopInviteTest
@Test fun linkAndBareTicketParse() { val t = "endpoint" + "a".repeat(40); assertEquals(t, CoopInvite.parse(CoopInvite.link(t))); assertEquals(t, CoopInvite.parse("  $t\n")); assertEquals(t, CoopInvite.parse("POKEDAISY://coop/$t")) }
@Test fun hostileInvitesRefused() { listOf("https://coop/endpointaaaaaaaaaaaaaaaaaaaa", "pokedaisy://coop/endpoint" + "a".repeat(40) + "/x", "pokedaisy://coop/endpointAAAA" + "a".repeat(30), "endpoint" + "1".repeat(40), "a".repeat(1_000_000), "", "pokedaisy://coop/").forEach { assertNull(it.take(40), CoopInvite.parse(it)) } }
// CoopEventTest
@Test fun decodesEveryKind() { /* one byte array per row of the layout table -> the matching class and fields */ }
@Test fun malformedIsNull() { /* kind 3 with a 31-byte peer; kind 99; empty array -> null */ }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests '*CoopWireTest' --tests '*CoopInviteTest' --tests '*CoopEventTest'` → Expected: FAIL, unresolved references.

- [ ] **Step 3: Implement** the four files to the interfaces and layouts above (`CoopSaveId` equality by `key`).

- [ ] **Step 4: Run them to verify they pass** → same command, Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pokedaisy/app/companion/coop app/src/test/kotlin/com/pokedaisy/app/companion/coop
git commit -m "Co-op: app messages, HELLO and invite links, every field checked on the way in"
```

### Task 5: Save identity, the pairings store, the device key

**Files:**
- Create: `.../companion/coop/CoopPairs.kt`, `CoopIdentity.kt`; add `CoopSaveId.read(saveFile: File): CoopSaveId?` to `CoopModel.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/coop/CoopPairsTest.kt`, `CoopSaveIdTest.kt`

**Interfaces:**
- Consumes: `Gen3SaveFile.parse(bytes, Gen3SaveFormat.forGameCode("BPEE")!!)`, `Gen3SaveData.trainerId` / `secretId`, the SaveBlock2 name bytes (offset 0, 8 bytes: add `val trainerNameBytes: ByteArray` to `Gen3SaveData`), `SyntheticSave` (tests).
- Produces:
```kotlin
// CoopModel.kt
companion object (of CoopSaveId) { fun read(saveFile: File): CoopSaveId?; fun fromHello(h: CoopHello): CoopSaveId }   // null: missing / unreadable / not Emerald's layout / trainer name empty
// CoopPairs.kt
class CoopPairs(private val file: File) {                 // filesDir/coop/pairs.json
    fun all(): List<CoopPair>
    fun forSave(save: CoopSaveId): CoopPair?
    fun add(pair: CoopPair): Boolean                     // false (nothing written) when that save already has a partner
    fun updateTicket(save: CoopSaveId, ticket: String)
    fun remove(save: CoopSaveId)
    fun allowedPeers(): Set<CoopPeerId>
}
// CoopIdentity.kt
object CoopIdentity { fun secretKey(dir: File): ByteArray }   // dir/secret.key: 32 bytes from SecureRandom on first call, read after; a file of the wrong size is replaced
```

`pairs.json` is a JSON array written through `org.json` to a temp file then renamed (atomic); an unreadable file reads as empty and is kept aside as `pairs.json.bad` (never silently overwritten while unread). Fields: `ownSave` (key), `ownLabel`, `peer`, `partnerName` (hex of the 8 raw bytes, decoded for display at load), `partnerTrainerId`, `partnerFemale`, `partnerSave` (key), `lastTicket`, `pairedAt`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// CoopSaveIdTest (SyntheticSave builds an Emerald save with trainer ID 0x1234, secret 0xABCD, name "MAY")
@Test fun readsIdFromSave() { val id = CoopSaveId.read(syntheticFile())!!; assertEquals(0x1234, id.trainerId); assertEquals(0xABCD, id.secretId); assertEquals("1234abcd-", id.key.take(9)) }
@Test fun sameIdAfterMovingTheFile() { /* copy to another dir and name: equal keys */ }
@Test fun emptyOrForeignSaveIsNull() { /* zero-filled 128 KB file; a FireRed-layout synthetic save -> null */ }
// CoopPairsTest (TemporaryFolder)
@Test fun addFindRemove() { /* add -> forSave finds it, allowedPeers has the peer; remove -> gone, file still valid JSON */ }
@Test fun onePartnerPerSave() { /* second add for the same ownSave returns false, first kept */ }
@Test fun survivesReload() { /* new CoopPairs(sameFile).all() == written, name decodes to "MAY" */ }
@Test fun corruptFileIsKeptAside() { /* write "{oops" -> all() empty, pairs.json.bad exists with "{oops" */ }
@Test fun secretKeyIsStable() { /* two calls equal, 32 bytes; a 5-byte file is replaced by 32 bytes */ }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests '*CoopPairsTest' --tests '*CoopSaveIdTest'` → Expected: FAIL, unresolved references.

- [ ] **Step 3: Implement** as specified.

- [ ] **Step 4: Run them to verify they pass** → Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pokedaisy/app/companion/coop app/src/main/kotlin/com/pokedaisy/app/companion/data/save/Gen3SaveData.kt app/src/test/kotlin/com/pokedaisy/app/companion/coop
git commit -m "Co-op: a save's identity, pairings per save, the device key"
```

### Task 6: Relay choice, reconnect backoff, the BY IP hello

**Files:**
- Create: `.../companion/coop/CoopRelay.kt`, `CoopBackoff.kt`, `CoopIpHello.kt`
- Modify: `app/src/main/kotlin/com/pokedaisy/app/Prefs.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/coop/CoopRelayTest.kt`, `CoopBackoffTest.kt`, `CoopIpHelloTest.kt`

**Interfaces:**
- Produces:
```kotlin
// Prefs
var coopRelayUrl: String?     // "coop_relay_url"; null = the build's
var coopRelayOnly: Boolean    // "coop_relay_only", false
// CoopRelay.kt
object CoopRelay {
    fun normalize(input: String): String?                 // "https://host[:port][/]" -> "https://host[:port]"; http, paths, queries, user info, spaces, > 253-char hosts -> null
    fun effective(override: String?, built: String): String?   // override ?: built.ifEmpty { null }; null = public relays
    fun rowValue(override: String?, built: String): String     // tk("BUILT-IN") / the override's host / tk("PUBLIC")
}
// CoopBackoff.kt
class CoopBackoff(private val random: java.util.Random = java.util.Random()) {
    fun next(): Long        // 1,000, 2,000, 4,000 ... capped at 60,000 ms, each +/-20% jitter
    fun reset()
}
// CoopIpHello.kt
const val COOP_IP_PORT = 47321
class CoopIpHelloServer(private val link: () -> String?) {   // the invite link to hand out
    fun start(): Int                                          // the bound port: 47321, else an ephemeral one
    fun stop()
}
object CoopIpHelloClient { fun fetch(host: String, port: Int, timeoutMs: Int = 5_000): String? }   // the ticket via CoopInvite.parse, or null
fun parseHostPort(text: String): Pair<String, Int>?          // "192.168.1.20:47321", "[fe80::1]:47321"; a port outside 1..65535 or a missing port -> null
```

Hello protocol: the client sends the 7 bytes `PDCO?1\n`; the server reads at most 16 bytes within 3 s, answers `<invite link>\n` only to exactly that line, then closes; anything else is closed with no answer. One connection at a time, at most 2 answers a second. The client reads at most 4,096 bytes and returns `CoopInvite.parse` of it.

- [ ] **Step 1: Write the failing tests**

```kotlin
// CoopRelayTest
@Test fun normalizes() { assertEquals("https://relay.example.net", CoopRelay.normalize(" https://relay.example.net/ ")); assertEquals("https://r.example:8443", CoopRelay.normalize("https://r.example:8443")); listOf("http://r.example", "https://r.example/path", "https://u@r.example", "r.example", "https://", "https://a b").forEach { assertNull(it, CoopRelay.normalize(it)) } }
@Test fun effectiveOrder() { assertEquals("https://mine", CoopRelay.effective("https://mine", "https://built")); assertEquals("https://built", CoopRelay.effective(null, "https://built")); assertNull(CoopRelay.effective(null, "")) }
// CoopBackoffTest
@Test fun doublesToCapWithJitter() { val b = CoopBackoff(java.util.Random(1)); val d = List(10) { b.next() }; assertTrue(d[0] in 800..1_200); assertTrue(d[1] in 1_600..2_400); assertTrue(d.last() in 48_000..72_000) }
@Test fun resetStartsOver() { /* next() x5, reset(), next() in 800..1_200 */ }
// CoopIpHelloTest (loopback)
@Test fun handsOutTheTicket() { val t = "endpoint" + "b".repeat(40); val s = CoopIpHelloServer { CoopInvite.link(t) }; val port = s.start(); assertEquals(t, CoopIpHelloClient.fetch("127.0.0.1", port)); s.stop() }
@Test fun wrongGreetingGetsNothing() { /* raw socket sends "GET / HTTP/1.1\r\n" -> reads EOF with 0 bytes */ }
@Test fun silentClientTimesOut() { /* raw socket sends nothing: closed by the server within 4 s */ }
@Test fun parsesHostPort() { assertEquals("192.168.1.20" to 47321, parseHostPort("192.168.1.20:47321")); assertNull(parseHostPort("192.168.1.20")); assertNull(parseHostPort("host:70000")) }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests '*CoopRelayTest' --tests '*CoopBackoffTest' --tests '*CoopIpHelloTest'` → Expected: FAIL, unresolved references.

- [ ] **Step 3: Implement** (`java.net.ServerSocket` on a daemon thread named `pokedaisy-coop-ip`; Prefs fields in the file's style).

- [ ] **Step 4: Run them to verify they pass** → Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pokedaisy/app/companion/coop app/src/main/kotlin/com/pokedaisy/app/Prefs.kt app/src/test/kotlin/com/pokedaisy/app/companion/coop
git commit -m "Co-op: relay choice, reconnect backoff, and the BY IP hello"
```

### Task 7: CoopLink and the pairing state machine

**Files:**
- Create: `.../companion/coop/CoopTransport.kt`, `CoopLinkImpl.kt`, `CoopPairing.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/coop/FakeCoopTransport.kt`, `CoopLinkImplTest.kt`, `CoopPairingTest.kt`, `CoopSafetyTest.kt`

**Interfaces:**
- Consumes: Tasks 4-6.
- Produces:
```kotlin
interface CoopTransport {
    fun start(secretKey: ByteArray, relayUrl: String?, relayOnly: Boolean): Int          // CoopNative.init's codes
    fun endpointId(): CoopPeerId?
    fun ticket(): String?
    fun setPolicy(allowed: Set<CoopPeerId>, pairingOpen: Boolean, lanUserData: String?)
    fun connect(ticket: String): Int
    fun accept(peer: CoopPeerId); fun deny(peer: CoopPeerId); fun disconnect(peer: CoopPeerId)
    fun send(peer: CoopPeerId, frame: ByteArray): Boolean
    fun poll(): CoopEvent?
    fun close()
}
class NativeCoopTransport : CoopTransport      // forwards to CoopNative, decodes with CoopEvent.decode (a null decode is logged and skipped)

class CoopLinkImpl(
    private val transport: CoopTransport, private val pairs: CoopPairs, private val ownHello: () -> CoopHello?,
    private val clock: () -> Long, private val backoff: CoopBackoff,
) : CoopLink {
    fun startFor(save: CoopSaveId)       // looks up the pair, setPolicy({partner}, false, null), dials lastTicket; Off(NOT_PAIRED) when none
    fun stop()                           // BYE(0) if connected, disconnect, state Off()
    fun onEvent(e: CoopEvent)            // called by CoopSession's poll loop
    fun tick()                           // called every 250 ms: due retries
}

class CoopPairing(private val transport: CoopTransport, private val pairs: CoopPairs, private val ownHello: () -> CoopHello?, private val clock: () -> Long) {
    sealed interface Step {
        data class Inviting(val ticket: String?, val lan: List<LanEntry>) : Step
        data class Connecting(val viaLan: Boolean) : Step
        data class Confirm(val partner: CoopPartner) : Step              // the ACCEPT THIS PLAYER? prompt
        data class WaitingForPartner(val partner: CoopPartner) : Step    // we accepted; they haven't yet
        data class Paired(val pair: CoopPair) : Step
        data class Failed(val reason: CoopPairFail) : Step
    }
    data class LanEntry(val peer: CoopPeerId, val label: String)         // label from user data "pokedaisy-coop/1;<name hex>;<tid>", decoded through Gen3Text; other user data ignored
    enum class CoopPairFail { VERSION, DIFFERENT_ROM, SAVE_ALREADY_PAIRED, DENIED, NETWORK, TIMEOUT, BAD_INVITE }
    val step: StateFlow<Step>
    fun open(save: CoopSaveId)            // setPolicy(allowed = all paired peers, pairingOpen = true, lanUserData = ours)
    fun join(ticket: String)
    fun joinLan(peer: CoopPeerId)
    fun accept(); fun deny()
    fun close()                           // setPolicy(pairingOpen = false); any unpaired connection closed
    fun onEvent(e: CoopEvent)
}
```

Rules:
- Both sides send HELLO first on every connection. A received HELLO is checked in this order: `protocol == COOP_PROTOCOL` else BYE(VERSION) + `VERSION`; `romSha1 == EMERALD_COOP_SHA1` and equal to ours else `DIFFERENT_ROM`; in `CoopLinkImpl`, the HELLO's save must equal the pair's `partner.save` else `DIFFERENT_SAVE`. No HELLO within 10 s closes the connection.
- `CoopLinkImpl` ignores every message before a valid HELLO, every link-range type but BYE after it, and every type above `0x03FF`; app types go to `messages` (a `MutableSharedFlow` with `extraBufferCapacity = 256` fed by `tryEmit`; a failed `tryEmit` disconnects with a log line, like Rust's full queue: never drop a message silently). A HELLO's ticket updates `CoopPairs.updateTicket`.
- Reconnect: on `Disconnected` / `DialFailed`, state `Disconnected(partner, reason, retryAtMs = clock() + backoff.next())`; `tick()` dials when due; success resets the backoff; an incoming connection from the partner during the wait is taken at once. Rust code 4 (`NOT_ALLOWED`) three times in a row becomes `NOT_RECOGNISED` and the retry delay goes straight to 60 s.
- `CoopPairing`: any HELLO-valid connection while open goes to `Confirm` on both sides (the joiner confirms the host too). `accept()` sends PAIR_ACCEPT and goes to `WaitingForPartner`, then `Paired` once the other's PAIR_ACCEPT has arrived too; `CoopPairs.add` then runs, and `transport.accept(peer)` lifts the pending caps. `SAVE_ALREADY_PAIRED` is checked in `open()`, before anything is shown, on each side for its own save (so a joiner opening a link for an already paired save fails locally). `deny()` or a PAIR_DENY ends in `Failed(DENIED)` on both. `Confirm` with no answer in 60 s: `Failed(TIMEOUT)`.

- [ ] **Step 1: Write the failing tests** (`FakeCoopTransport`: a pair of fakes wired to each other in memory, recording `setPolicy` / `accept` / `deny` calls, with `deliver()` to pump events; a fake clock)

```kotlin
// CoopLinkImplTest
@Test fun connectsAndDeliversAppMessages() { /* paired A/B, startFor on both -> both Connected; A.send(0x0101, [7]) -> B.messages emits type 0x0101 [7] */ }
@Test fun linkTypesAndUnknownTypesNeverReachMessages() { /* B receives types 2, 0x00FF, 0x0400 -> messages empty, still Connected */ }
@Test fun sendRefusesLinkRangeAndOffState() { /* send(4, ..) throws; send before Connected returns false */ }
@Test fun differentRomRefused() { /* B's HELLO carries another SHA-1 -> A Disconnected(DIFFERENT_ROM), no messages */ }
@Test fun differentSaveRefused() { /* B's HELLO save != pair.partner.save -> DIFFERENT_SAVE */ }
@Test fun versionRefused() { /* protocol 2 -> VERSION; A sent BYE */ }
@Test fun reconnectBacksOffAndResets() { /* drop 3 times: retryAtMs gaps ~1 s, 2 s, 4 s; connect; drop -> ~1 s again */ }
@Test fun partnerWithNewKeyIsNotRecognised() { /* B restarts with a new key: A's dials end NOT_ALLOWED x3 -> Disconnected(NOT_RECOGNISED), next retry 60 s; A never shows a prompt (no setPolicy with pairingOpen = true) */ }
@Test fun ticketRefreshedFromHello() { /* B's HELLO ticket "endpointnew..." -> pairs.forSave(...).lastTicket updated */ }
@Test fun simultaneousConnectsOneState() { /* both dial; fake delivers CONNECTED twice then DISCONNECTED(code 8) for the loser on neither side -> exactly one Connected emission each, no Disconnected */ }
// CoopPairingTest
@Test fun bothAcceptPairs() { /* A open, B join(A ticket) -> both Confirm; A.accept -> A WaitingForPartner; B.accept -> both Paired; each store has the other; transport.accept called for the peer */ }
@Test fun denyOnEitherSide() { /* B.deny -> both Failed(DENIED), no store writes */ }
@Test fun alreadyPairedSaveFailsBeforeShowingAnything() { /* store has a pair for A's save -> open() -> Failed(SAVE_ALREADY_PAIRED), setPolicy never called with pairingOpen = true */ }
@Test fun confirmTimesOut() { /* no answer for 60 s on the fake clock -> Failed(TIMEOUT), connection closed */ }
@Test fun lanEntriesFromUserData() { /* LanPeer with "pokedaisy-coop/1;<MAY hex>;4660" -> LanEntry label "MAY"; LanPeer with "something-else" -> ignored; LanGone removes */ }
@Test fun closeClosesThePolicy() { /* close() -> last setPolicy has pairingOpen = false, lanUserData null */ }
// CoopSafetyTest (decision 6)
@Test fun coopCodeNeverTouchesGameMemory() { /* every .kt under src/main/kotlin/.../companion/coop: no "MgbaCore", "pkWrite", "pkCall", "EmulatorEngine" */ }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests '*CoopLinkImplTest' --tests '*CoopPairingTest' --tests '*CoopSafetyTest'` → Expected: FAIL, unresolved references.

- [ ] **Step 3: Implement** to the rules above (no threads inside: `CoopSession` drives `onEvent` / `tick`).

- [ ] **Step 4: Run them to verify they pass**, then the whole suite: `./gradlew --no-daemon :app:testDebugUnitTest` → Expected: only the six baseline failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pokedaisy/app/companion/coop app/src/test/kotlin/com/pokedaisy/app/companion/coop
git commit -m "Co-op: the link to a paired partner, and pairing with a prompt on both sides"
```

### Task 8: CoopSession, the link trampoline, QR, the game hooks

**Files:**
- Create: `.../companion/coop/CoopSession.kt`, `CoopQr.kt`, `app/src/main/kotlin/com/pokedaisy/app/CoopLinkActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/pokedaisy/app/PokeDaisyActivity.kt` (`startGame` ~line 1919, the `engine.stop` paths ~1855 / 2036 / 2087), `app/src/main/kotlin/com/pokedaisy/app/companion/CompanionSettings.kt`
- Test: `app/src/test/kotlin/com/pokedaisy/app/companion/coop/CoopQrTest.kt`, `CoopSessionGateTest.kt`

**Interfaces:**
- Consumes: Tasks 4-7.
- Produces:
```kotlin
object CoopSession {
    val link: CoopLink                                    // the one CoopLinkImpl; Off() while nothing holds the endpoint
    val relay: StateFlow<Int>                             // 0 none, 1 custom, 2 public (from CoopEvent.Relay)
    fun pairing(context: Context): CoopPairing            // takes a hold for the invite pages; release with releasePairing()
    fun releasePairing()
    fun startForGame(context: Context, prefs: Prefs, romSha1: String?, saveFile: File, cheatsLoaded: Boolean)
    fun stopGame()
    fun restart(context: Context, prefs: Prefs)            // RELAY / RELAY ONLY changed: close and init again if held
    fun unpair(context: Context, save: CoopSaveId)         // BYE if connected to them, CoopPairs.remove, policy refreshed
    internal fun previewState(state: CoopState)           // ui-preview / Paparazzi only, like RetroAchievements.previewSignedIn
    fun gate(romSha1: String?, save: CoopSaveId?, paired: Boolean, cheatsLoaded: Boolean): CoopOffReason?   // pure: NOT_EMERALD, NO_SAVE, NOT_PAIRED, CHEATS_ON, else null
}
object CoopQr { fun encode(text: String): Array<BooleanArray> }   // ZXing QRCodeWriter, ErrorCorrectionLevel.M, margin 0
// CompanionSettings (defaults, overridden by PokeDaisyActivity)
val coopState: CoopState get() = CoopState.Off()
val coopPairs: List<CoopPair> get() = emptyList()
val coopRelayValue: String get() = ""
val coopRelayOnly: Boolean get() = false
fun setCoopRelayOnly(on: Boolean) {}
fun coopUnpair(save: CoopSaveId) {}
```

Behaviour: the endpoint runs while at least one hold exists (pairing pages, a game); a poll thread `pokedaisy-coop` calls `transport.poll()` until empty, then sleeps 16 ms, and calls `link.tick()` every 250 ms; holds released: `transport.close()`, thread ends, state `Off()`. While LAN is on (not RELAY ONLY) and Wi-Fi is up it holds a `WifiManager.MulticastLock` (`"pokedaisy-coop"`, released with the endpoint). `startForGame`: `gate(...)` non-null sets `Off(reason)` and starts nothing; else hold + `link.startFor(save)`. The ROM SHA-1 passed in is the library's (`RomIdentity.sha1`, else `RetailDumps.matchStream` for a trimmed retail dump). `ownHello` builds from `BuildConfig.VERSION_NAME`, the SHA-1, the save's id and gender, and `transport.ticket()`.

`CoopLinkActivity`: translucent, `noHistory`, `excludeFromRecents`, exported with one filter (`VIEW`, `DEFAULT` + `BROWSABLE`, `scheme="pokedaisy" host="coop"`); `onCreate` runs `CoopInvite.parse(intent.dataString)`: null finishes with a toast `tr("That invite link isn't valid")`; else starts the (still unexported) `SettingsActivity` with `EXTRA_COOP_TICKET` = the parsed ticket and finishes. It never reads extras, files or other URIs.

Manifest: the activity above; `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`; the INTERNET comment gains "co-op: QUIC to the paired partner, the co-op relay, and mDNS on the local network while CO-OP is in use".

- [ ] **Step 1: Write the failing tests**

```kotlin
// CoopQrTest
@Test fun encodesAnInviteLink() { val g = CoopQr.encode(CoopInvite.link("endpoint" + "c".repeat(200))); assertTrue(g.size in 41..73); assertTrue(g.all { it.size == g.size }); assertTrue(g[0][0] && g[0][6] && g[6][0]) /* finder pattern corners */ }
@Test fun decodesBack() { /* com.google.zxing.qrcode.QRCodeReader over a BinaryBitmap built from the grid (scaled x4, quiet zone 4) -> the same link */ }
// CoopSessionGateTest
@Test fun gateOrder() { assertEquals(CoopOffReason.NOT_EMERALD, CoopSession.gate("00", id, true, false)); assertEquals(CoopOffReason.NO_SAVE, CoopSession.gate(EMERALD_COOP_SHA1, null, false, false)); assertEquals(CoopOffReason.NOT_PAIRED, CoopSession.gate(EMERALD_COOP_SHA1, id, false, false)); assertEquals(CoopOffReason.CHEATS_ON, CoopSession.gate(EMERALD_COOP_SHA1, id, true, true)); assertNull(CoopSession.gate(EMERALD_COOP_SHA1, id, true, false)) }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests '*CoopQrTest' --tests '*CoopSessionGateTest'` → Expected: FAIL, unresolved references.

- [ ] **Step 3: Implement** `CoopQr`, `CoopSession`, `CoopLinkActivity`, the manifest, and the game hooks: `startGame` calls `CoopSession.startForGame(this, prefs, <sha1 off the UI thread, cached like GameTitles>, SavesLocation.saveFor(this, prefs, rom), cheatsLoaded = prefs.cheatsEnabled && <this game's enabled cheats> > 0)`; every `engine.stop` / `stopWithSuspend` path calls `CoopSession.stopGame()`; the activity's `CompanionSettings` implementation overrides the coop members (relay-only flips `Prefs.coopRelayOnly` then `CoopSession.restart`).

- [ ] **Step 4: Run them to verify they pass**, then `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` → Expected: only the baseline failures; the APK builds.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pokedaisy/app/companion/coop app/src/main/kotlin/com/pokedaisy/app/CoopLinkActivity.kt app/src/main/AndroidManifest.xml app/src/main/kotlin/com/pokedaisy/app/PokeDaisyActivity.kt app/src/main/kotlin/com/pokedaisy/app/companion/CompanionSettings.kt app/src/test/kotlin/com/pokedaisy/app/companion/coop
git commit -m "Co-op: the session runs the link for a paired Emerald save; invite links and QR codes"
```

### Task 9: Top-screen Settings: the CO-OP group and the invite pages

**Files:**
- Create: `app/src/main/kotlin/com/pokedaisy/app/companion/ui/CoopUi.kt`, `app/src/main/kotlin/com/pokedaisy/app/companion/i18n/TrCoop.kt`
- Modify: `app/src/main/kotlin/com/pokedaisy/app/SettingsActivity.kt` (`Screen` enum line 128, titles ~273, dispatch ~301, `HomeScreen` rows before `groupTitle(tk("CONTROLS"))` ~686, `goBack` ~481, `onCreate` / `onNewIntent` for `EXTRA_COOP_TICKET`), `companion/NewFeatures.kt`, `companion/ui/SettingRows.kt` (`SETTING_KEYWORDS`), `companion/i18n/Tables.kt`
- Modify: `ui-preview/build.gradle.kts` (`appIncludes` + ZXing dependency), `ui-preview/data/build.gradle.kts` (include `companion/coop/**`), `ui-preview/src/render/Main.kt` (shots)

**Interfaces:**
- Consumes: `CoopSession`, `CoopPairing`, `CoopQr`, `CoopRelay`, `CoopPairs`, `CoopIpHelloServer` / `Client`, `parseHostPort`.
- Produces:
```kotlin
// CoopUi.kt
@Composable fun CoopQrCode(link: String, m: GbaTextMetrics, modifier: Modifier = Modifier)   // white quiet zone 4 modules, black squares in whole screen pixels, OptionColors-independent (always black on white, also in DARK MODE)
@Composable fun CoopAcceptPrompt(partner: CoopPartner, onAccept: () -> Unit, onDeny: () -> Unit) // OptionConfirm: "ACCEPT THIS PLAYER?", "{0}  ID {1}" (name, 5-digit ID), ACCEPT / DENY
fun coopLinkLabel(state: CoopState): String       // tk: OFF / CONNECTING / CONNECTED / RECONNECTING / NOT PAIRED / NOT RECOGNISED / CHEATS ON / NOT EMERALD
fun coopPartnerRows(pairs: List<CoopPair>, onUnpair: (CoopPair) -> Unit): List<SettingRow>      // one row per pair: label = partner name, value = own save label, tap = confirm UNPAIR
// NewFeatures
const val COOP = "coop"   // "SETTINGS > CO-OP: play Emerald with a friend (companion.coop)"; appended to ALL
```

Top-screen rows (group `CO-OP`, between `ONLINE` and `CONTROLS`):
- `PAIR`, value none, `opensPage`, `labelBadge = NewFeatures.badge(NewFeatures.COOP)`, subtitle "Link one of your Emerald saves with a friend's" → `Screen.COOP_PAIR`.
- `PARTNERS`, value the count or `NONE`, `opensPage` → `Screen.COOP_PARTNERS` (`coopPartnerRows`; UNPAIR asks "Unpair {0}? You'll need a new invite to play together again.").
- `RELAY`, value `CoopRelay.rowValue`, `opensPage` → `Screen.COOP_RELAY`: a text field (the Cheats page's field), `SAVE` (refuses with "That isn't an https:// address" when `normalize` is null), `USE BUILT-IN` (clears the override); then `CoopSession.restart`.
- `RELAY ONLY`, ON / OFF, subtitle "Hide your IP address from your partner" → flips `Prefs.coopRelayOnly`, `CoopSession.restart`.

`Screen.COOP_PAIR` (holds `CoopSession.pairing`, released on leaving): first a save list (`PICK A SAVE`): every library ROM whose library SHA-1 is `EMERALD_COOP_SHA1`, each row the save's trainer name and ID (from `CoopSaveId.read`) or greyed with "Save in the game once first" / "Paired with {0}"; none: `NO EMERALD SAVES` and "Co-op needs English Pokémon Emerald." Picking a save runs `open(save)` and shows the invite: the QR (`CoopQrCode`), `COPY LINK` (clipboard), `SHARE LINK` (`ACTION_SEND` text "Play Emerald with me in PokeDaisy: {link}"), `JOIN WITH A LINK` (a text field + `CONNECT` → `join(CoopInvite.parse(text))`, `BAD_INVITE` on null), then, unless RELAY ONLY, `SAME WI-FI` (the `LanEntry` list, `SEARCHING…` while empty; a tap = `joinLan`) and `BY IP` (`YOUR ADDRESS  192.168.1.20:47321` from the Wi-Fi interface's IPv4 + `CoopIpHelloServer.start()`, and a field `PARTNER'S ADDRESS` + `CONNECT` → `parseHostPort` → `CoopIpHelloClient.fetch` off the UI thread → `join`). `Confirm` shows `CoopAcceptPrompt`; `WaitingForPartner` shows "WAITING FOR YOUR PARTNER…"; `Paired` shows `PAIRED WITH {0}` and `DONE`; `Failed` shows its line:
VERSION "Your partner's PokeDaisy is a different version. Update both to the same one.", DIFFERENT_ROM "Your partner plays a different Emerald ROM.", SAVE_ALREADY_PAIRED "This save is already paired. Unpair it in PARTNERS first.", DENIED "The other player said no.", NETWORK "Couldn't reach your partner. Check the internet and try again.", TIMEOUT "No answer. Try again.", BAD_INVITE "That invite link isn't valid", each with `TRY AGAIN`.
`Screen.COOP_JOIN` (from `EXTRA_COOP_TICKET`): the save list, then `join(ticket)` straight away.

- [ ] **Step 1: Add the ui-preview shots that will fail** (Main.kt, top-screen size like `settings-home`): `settings-coop` (HOME scrolled to CO-OP), `coop-pick-save`, `coop-invite` (fake ticket, LAN list with two entries, BY IP shown), `coop-invite-relayonly`, `coop-confirm`, `coop-waiting`, `coop-paired`, `coop-failed-rom`, `coop-partners`, `coop-relay`. Previews use a `FakeCoopTransport`-like shim in `ui-preview/src/shims/CoopShim.kt` that feeds `CoopPairing` its steps directly; no network.

- [ ] **Step 2: Run to make sure it fails**

Run: `./gradlew --no-daemon -p ui-preview render -Ponly=coop` → Expected: compile errors (`Screen.COOP_PAIR`, `CoopUi` missing).

- [ ] **Step 3: Implement** the rows, pages and `CoopUi.kt`; `SETTING_KEYWORDS` gains `"PAIR" to "co-op coop multiplayer multi player two player online friend partner link invite qr code join netplay play together"`, `"PARTNERS" to "co-op coop paired friends unpair forget remove partner"`, `"RELAY" to "co-op coop server relay iroh address url self hosted"`, `"RELAY ONLY" to "co-op coop privacy hide ip address relay only"`; `TrCoop.kt` holds every new `tk` / `tr` key and subtitle above in all seven languages (JA kana only, the Emerald games' own words: POKéMON, PC), registered in `Tables.kt`.

- [ ] **Step 4: Render and look**

Run: `./gradlew --no-daemon -p ui-preview render -Ponly=coop,settings-coop` and Read every `ui-preview/build/shots/coop-*.png` and `settings-coop.png`: OPTION look, the QR crisp (whole pixels, white quiet zone), nothing cut off; again with `-Pdark=true -Pout=build/shots-dark` (QR stays black on white) and `-Plang=JA` / `-Plang=DE` (longest labels fit). Then `./gradlew --no-daemon :app:testDebugUnitTest --tests '*TranslationsTest'` → PASS, and `./gradlew --no-daemon -p ui-preview stress` → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pokedaisy/app/SettingsActivity.kt app/src/main/kotlin/com/pokedaisy/app/companion/ui/CoopUi.kt app/src/main/kotlin/com/pokedaisy/app/companion/ui/SettingRows.kt app/src/main/kotlin/com/pokedaisy/app/companion/NewFeatures.kt app/src/main/kotlin/com/pokedaisy/app/companion/i18n ui-preview
git commit -m "Co-op: the CO-OP settings and invite pages on the top screen"
```

### Task 10: The companion's CO-OP group and link status

**Files:**
- Modify: `app/src/main/kotlin/com/pokedaisy/app/companion/ui/CompanionSettingsScreen.kt` (`Page` enum line 51, `homeRows` before `groupTitle(tk("CONTROLS"))` ~574, page dispatch), `app/src/main/kotlin/com/pokedaisy/app/companion/i18n/TrCoop.kt`, `companion/ui/SettingRows.kt`
- Modify: `app/src/test/kotlin/com/pokedaisy/app/companion/ui/CompanionScreenshotTest.kt`, `ui-preview/src/render/Main.kt`

**Interfaces:**
- Consumes: `CompanionSettings.coopState`, `coopPairs`, `coopRelayValue`, `coopRelayOnly`, `setCoopRelayOnly`, `coopUnpair` (Task 8); `coopLinkLabel`, `coopPartnerRows` (Task 9).
- Produces: `Page.COOP_PARTNERS(tk("PARTNERS"))`; `SETTING_KEYWORDS` gains `"LINK" to "co-op coop connection connected status partner online"`.

Companion rows (group `CO-OP`, between `COMPANION` and `CONTROLS`, i.e. right before CONTROLS like the top screen):
- `LINK` (companion only, like SCENE SPEEDS), value `coopLinkLabel(state)`, subtitle the partner's name when there is one, else the reason line ("Pair this save on the top screen: Settings > CO-OP" for NOT PAIRED; "Turn cheats off to play together" for CHEATS ON; "Co-op needs English Pokémon Emerald" for NOT EMERALD); not tappable. `Disconnected` reads `RECONNECTING` with subtitle `tr("{0}: trying again in {1} s", name, seconds)` (recomputed once a second while SETTINGS shows).
- `PAIR`, disabled, `labelBadge = NewFeatures.badge(NewFeatures.COOP)`, subtitle "On the top screen: Settings > CO-OP".
- `PARTNERS`, count / `NONE`, `opensPage` → `Page.COOP_PARTNERS` (`coopPartnerRows`, UNPAIR through `OptionConfirm`, then `s.coopUnpair`).
- `RELAY`, value `coopRelayValue`, disabled, subtitle "Change it in Settings on the top screen".
- `RELAY ONLY`, ON / OFF → `s.setCoopRelayOnly`.

- [ ] **Step 1: Add the failing renders**: Paparazzi `companionSettingsCoop` in `CompanionScreenshotTest` (SETTINGS scrolled to CO-OP with `CoopSession.previewState(Connected(MAY, direct = true))`) and ui-preview shots `companion-coop-connected`, `companion-coop-reconnecting`, `companion-coop-notpaired`, `companion-coop-partners`.

- [ ] **Step 2: Run to make sure they fail**

Run: `./gradlew --no-daemon -p ui-preview render -Ponly=companion-coop` → Expected: no CO-OP rows (the shots show no `LINK`), or compile errors for `Page.COOP_PARTNERS`.

- [ ] **Step 3: Implement** the rows and page; translations for the new lines in `TrCoop.kt`.

- [ ] **Step 4: Render and look**

Run: `./gradlew --no-daemon -p ui-preview render -Ponly=companion-coop`, then `-PcompanionW=640 -PcompanionH=480 -PcompanionDensity=1.5` (RG DS) and `-Pdark=true -Pout=build/shots-dark`; Read each PNG (labels fit, the red values aligned, the greyed rows grey). Run: `./gradlew --no-daemon :app:recordPaparazziDebug --tests '*CompanionScreenshotTest'` and Read `app/src/test/snapshots/images/*companionSettingsCoop*.png`. Then `./gradlew --no-daemon :app:testDebugUnitTest --tests '*TranslationsTest'` → PASS; `./gradlew --no-daemon -p ui-preview stress` → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/pokedaisy/app/companion/ui/CompanionSettingsScreen.kt app/src/main/kotlin/com/pokedaisy/app/companion/ui/SettingRows.kt app/src/main/kotlin/com/pokedaisy/app/companion/i18n/TrCoop.kt app/src/test/kotlin/com/pokedaisy/app/companion/ui/CompanionScreenshotTest.kt ui-preview/src/render/Main.kt
git commit -m "Co-op: link status, partners and relay rows on the companion"
```

### Task 11: The manual, docs and a two-device check

**Files:**
- Create: `docs/manual/coop.md`, `docs/manual/shots/coop-*.png` (rendered)
- Modify: `docs/manual/settings-reference.md` (both screens' tables), `docs/manual/synonyms.yml`, `ui-preview/src/render/DocsShots.kt`, `docs/DEVELOPMENT.md`, `CLAUDE.md`

**Interfaces:**
- Consumes: every label above; the shots from Tasks 9-10.

- [ ] **Step 1: Write `docs/manual/coop.md`** (front matter `title: Play together`, `section: player`, `order:` one after the section's last page, `keywords: [co-op, coop, multiplayer, two player, netplay, online, link, friend, invite, qr]`), following docs/manual/README.md's rules (second person, no dashes, no real Pokémon / move / item names, labels in backticks): what co-op is for and that this release only pairs and links (playing together comes with later updates: say it plainly, no promises of dates); pairing with a link or QR code, `SAME WI-FI`, `BY IP`; `ACCEPT THIS PLAYER?` on both devices; one partner per save, `PARTNERS` and `UNPAIR`; `RELAY` and `RELAY ONLY` (what each hides and what it costs: no `SAME WI-FI` or `BY IP`); the companion's `LINK` values; a troubleshooting section for each `Failed` line. Every new `SETTING_KEYWORDS` label (`PAIR`, `PARTNERS`, `RELAY`, `RELAY ONLY`, `LINK`) appears in backticks. Screenshots: add `Shot`s to `DocsShots.kt` for `coop-invite`, `coop-confirm`, `companion-coop-connected` (the invented save's trainer, never a real name).

- [ ] **Step 2: Run the docs check to make sure it fails first, then passes**

Before Step 1's page exists: `cd website && node --test test/docs.test.js test/docsearch.test.js` → Expected: FAIL naming the undocumented labels. After: `./gradlew --no-daemon -p ui-preview docsShots -Ponly=coop,companion-coop` then the same node command → PASS; `cd website && npm run build` → builds (Node 22.12+).

- [ ] **Step 3: Docs for maintainers.** `docs/DEVELOPMENT.md` gets "Co-op connection": the crate and its pins, building (`cargo-ndk`, the x86_64 flag, `ANDROID_NDK_HOME`), running `cargo test`, the reason codes and event table, relay config (`coop.relayUrl`, `COOP_RELAY_URL`, the fallback), regenerating `coop_net-crates.txt`, the message ranges reserved for plans 3-5. `CLAUDE.md`: the "Exposed surfaces" line gains "`CoopLinkActivity` is exported for `pokedaisy://coop/<ticket>` only: it passes a `CoopInvite.parse`d ticket to the unexported Settings, nothing else"; a short **Co-op link** paragraph (iroh through our JNI, poll-based, nothing from the network into game memory, `CoopSafetyTest`, pairs per save, the relay never committed).

- [ ] **Step 4: Two-device check** (the player's own Emerald ROM and two saves; two devices, or one device and the Windows emulator with a `-Ppokedaisy.x86_64=true` build): pair by link, by QR (the system camera opens the link), on the same Wi-Fi, by IP; deny once; RELAY ONLY on one side (the companion still connects; `adb logcat -s pokedaisy/coop` shows no direct path); turn Wi-Fi off and on mid-session (RECONNECTING, then CONNECTED); unpair on one side (the other reads NOT RECOGNISED after three refusals). Write what was seen into the commit message. Anything that fails goes back to its task.

- [ ] **Step 5: Commit**

```bash
git add docs/manual docs/DEVELOPMENT.md CLAUDE.md ui-preview/src/render/DocsShots.kt
git commit -m "Co-op: the manual page and the connection's developer notes"
```

---

## Self-review

- **Spec coverage (section 1):** invite link + QR (Tasks 4, 8, 9), SAME WI-FI (Tasks 2, 7, 9), BY IP (Tasks 6, 9), accept prompt and key memory, others refused (Tasks 2, 7), iroh with direct / relay (Task 2), RELAY ONLY (Tasks 2, 9, 10), relay baked from a secret with public fallback (Tasks 2, 3, 6), pairing per save surviving a moved file and unpairing (Tasks 5, 9, 10), framing and versioning (Tasks 1, 4, 7), NOTICE / LICENSES (Task 3). Section 6: untrusted input (Tasks 1, 2, 4, 7), refuse with cheats loaded (Task 8's gate); the save is already backed up on every game start (`SaveBackups`), and this plan writes no save. Section 7: CO-OP group on both screens, NEW tag, keywords, manual (Tasks 9-11); NEARBY RANGE and the lock rows are plans 4-5.
- **Type consistency:** `CoopPeerId`, `CoopSaveId.key`, `CoopState` variants, `CoopDropReason` / `CoopOffReason` and `CoopPairing.Step` / `CoopPairFail` are defined once (Shared interfaces, Task 7) and used with the same names in Tasks 8-10; the reason codes table is the only source for Rust and Kotlin.
- **Review Focus:** each line has its test in the named task.
