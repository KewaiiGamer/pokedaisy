# Emerald Co-op: our own WebSocket relay (addendum to the co-op design)

Addendum to `2026-10-24-emerald-coop-design.md`, section 1 (the connection). It replaces iroh and its Rust
crate with a Kotlin transport over the user's own relay. Everything above the transport stays: invites as one
link / one QR (`pokedaisy://coop/<ticket>`, the paste field, `CoopLinkActivity`), ACCEPT THIS PLAYER? on both
sides, one partner per save (`CoopSaveId`), refused with cheats loaded, English Emerald only, all behind
`FeatureFlags.coop`, and nothing from the network reaching game memory (`CoopSafetyTest`).

## What the user decided

- Option A: both devices open an outgoing `wss://` (443) to a relay the user runs, join a room, the relay pairs
  two peers per room and forwards opaque frames. Same Wi-Fi still connects directly (LAN discovery and BY IP),
  falling back to the relay.
- End to end encryption between the phones on top of TLS: the relay sees ciphertext and metadata only, and the
  invite carries what the joiner needs to authenticate the host, so the relay can't sit in the middle.
- Partners never see each other's IP over the relay. The relay is a small always-on VPS service, not Cloud Run.
- No Rust, no cargo-ndk anywhere in the build.

## Rulings

- **Ruling: the seam is `CoopTransport`.** A new pure-Kotlin `RelayCoopTransport` implements the existing
  interface and emits the existing `CoopEvent`s with the native node's semantics (pending peers and their caps,
  reason codes 0..8, one connection per peer, a repeated `Connected` = a new connection, our own `disconnect` /
  `deny` echoing a `Disconnected`). `CoopLink`, `CoopSession`'s public API, `CoopPairing`'s public API, the
  presence link and `attachCoopLink` do not change, so plans 3, 4 and 5 need nothing. Removed: `CoopNative`,
  `NativeCoopTransport` and `CoopEvent.decode` (the native byte layout); no other branch calls them.
- **Ruling: identity is an X25519 static key.** `filesDir/coop/secret.key` (32 random bytes, unchanged) is the
  X25519 private key; `CoopPeerId` is its public key (still 32 bytes / 64 hex). Pairings made with the iroh build
  (Ed25519 ids, iroh tickets) don't carry over: the feature never shipped, so `CoopPairs` drops a pair whose
  `lastTicket` isn't a new ticket and the player pairs again.
- **Ruling: Noise `IK_25519_AESGCM_SHA256` for the end-to-end channel**, built from library primitives (no
  hand-rolled primitive): X25519 from Bouncy Castle (`org.bouncycastle:bcprov-jdk18on:1.79`, MIT; Android's
  platform has no X25519 below API 33 and our minSdk is 26), SHA-256 / HMAC-SHA256 / AES-GCM from the platform's
  `javax.crypto`. The handshake is checked against the published Noise test vectors for that exact protocol name.
  IK fits: the initiator always knows the responder's static key (from the ticket, or the pair). Prologue =
  `"PokeDaisy co-op 1"` + a path byte + the room id (or the TCP path's fixed tag), binding a handshake to its room
  and so to the invite. The relay sees the initiator's static key only encrypted (IK sends it under `es`). Tink and
  a Noise library were the alternatives: Tink has no Noise and is larger; the JVM Noise libraries are unmaintained.
  Release builds don't minify, so Bouncy Castle adds its full jar to the APK (a few MB); minifying is a separate
  change.
- **Ruling: roles.** Invite room: the joiner is the initiator. Pair room: the device with the smaller public key
  (byte order) is the initiator. Direct TCP: the dialer is the initiator.
- **Ruling: rooms.** A room id is 32 lowercase hex characters (128 bits), never the invite secret or a key:
  invite room = first 16 bytes of HMAC-SHA256(key = the ticket's 16-byte invite secret, "pokedaisy coop invite");
  pair room = first 16 bytes of HMAC-SHA256(key = X25519(own private, partner public), "pokedaisy coop pair" +
  the two public keys, smaller first). Only the two partners can compute their pair room, so nobody else can
  occupy it, and the relay can't tie a room to a key. The host sits in its invite room only while pairing is open;
  each device sits in the pair room of every peer `setPolicy` allows (one socket each, a handful at most).
- **Ruling: the ticket.** `"pdco"` + lowercase unpadded base32 of: version 1, the host's 32-byte public key, a flag
  byte (bit 0: invite secret present), the 16-byte invite secret if present, then up to 4 LAN addresses (family 4 /
  6, the address, u16 port) when RELAY ONLY is off. `CoopInvite`'s regex becomes `^pdco[a-z2-7]{56,205}$` (version, key, flags, secret, an address count, then the addresses); its API
  doesn't change. The invite secret is random per transport start (a stale QR stops working when the host's app
  restarts, as iroh's did when its addresses changed). `peerOnlyTicket` (SAME WI-FI) is a ticket with the key alone,
  dialed over the LAN only. The relay URL is never in the ticket: both sides use their own build's / setting's.
- **Ruling: the handshake's last word.** Message 2's payload is one status byte: 0 = go on (also for a joiner the
  host holds as pending: the joiner can't tell), or a reason code (NOT_ALLOWED, VERSION, DENIED) after which the
  responder closes; the initiator turns it into `DialFailed(code)`. Message 1's payload is the transport version
  (u16, 1). After the handshake every message is AES-GCM ciphertext of `u8 kind` + body: DATA (an app frame, at
  most 65,536 bytes), PING (every 5 s), CLOSE (a reason code). 20 s without anything = `TIMEOUT`.
- **Ruling: paths and duplicates.** On `connect`, a direct TCP dial to the ticket's and LAN discovery's addresses
  (1.5 s each) runs alongside the relay; whichever handshake finishes first is the connection. One connection per
  peer: a new one replaces the old, unless the old is under 5 s old, when both sides keep the one that is direct,
  then the one whose initiator has the smaller key (the native rule, so both settle the same way). The loser is
  closed as DUPLICATE and reports nothing. No path migration: `Connected.direct` is the path for the connection's
  life, and `Path` events are never sent.
- **Ruling: reconnect.** Relay sockets reconnect by themselves (1 s doubling to 30 s, ±20% jitter, reset after a
  join that lasts 30 s); a session over a socket that drops ends in `Disconnected(ERROR)`, which `CoopLinkImpl`
  already turns into a backoff redial. The relay tells the other peer at once when one leaves (PEER_LEFT), so the
  partner sees the link down within a second, not after the 20 s timeout.
- **Ruling: NOT RECOGNISED over the relay.** A partner who unpaired or reinstalled never comes back to the pair
  room, and over the relay that looks the same as a partner who isn't playing: the link keeps redialing at the
  backoff's 60 s cap and shows the partner as not connected. NOT RECOGNISED still comes from the LAN and BY IP paths,
  where the host answers NOT_ALLOWED. The price of rooms only the two partners can compute; a per-device "knock"
  room would let anyone who knows a key block it.
- **Ruling: no public relay.** The relay is the player's override, else the build's `COOP_RELAY_URL`, else none:
  SAME WI-FI and BY IP still work, invites carry LAN addresses only, and the RELAY row reads NONE (it read PUBLIC).
  `Relay(1, url)` once a relay socket is up, `Relay(0, "")` with none. The URL stays `https://host[:port]` in
  settings and the build secret; the transport speaks `wss://` to the same host.
- **Ruling: LAN discovery is Android's NSD** (`NsdManager`, DNS-SD over mDNS, service `_pdcoop._tcp`, TXT `k` = the
  public key in base32 and `u` = the pairing label), behind a small `CoopLan` interface so the JVM tests use a fake.
  Advertised and browsed only while RELAY ONLY is off, as before; the multicast lock stays in `CoopSession`.
- **Ruling: the server is Kotlin on the JVM with Java-WebSocket** (`org.java-websocket:Java-WebSocket`, MIT), in
  `server/coop-relay/`, a standalone Gradle build (its own settings file, the repo's wrapper) with a Dockerfile and a
  Caddy front for TLS. Why: the app's language (one language to maintain), no framework, and the integration test
  compiles the relay's sources straight into the app's JVM unit tests and runs it in-process against two real
  clients. Go would give a smaller image; Node would add a runtime we only use for the website. The server is
  never in the APK, so it adds nothing to NOTICE.
- **Ruling: the client WebSocket is OkHttp** (`com.squareup.okhttp3:okhttp:4.12.0` + Okio, Apache 2.0): Android's
  usual, handles TLS, proxies and pings. Both go into NOTICE and the LICENSES page with Bouncy Castle.
- **Ruling: the relay protocol.** `GET /v1/rooms/<room id>` upgraded to a WebSocket; binary messages only, byte 0 the
  kind: client to server `0x01 DATA` + bytes (forwarded unchanged to the other peer as `0x01`), server to client
  `0x10 PEER_JOINED` (sent to both when the room reaches two) and `0x11 PEER_LEFT`. A third peer is closed with
  4409. `GET /healthz` answers `ok`. Limits (all settable by environment): 2 peers a room, 10,000 rooms, 16 sockets
  per IP, 30 new sockets per IP per minute, messages at most 70,000 bytes, 500 messages and 512 KB per second per
  socket (over it: closed 4429), a lone peer waits at most 30 minutes, a pair silent for 2 minutes is closed. It
  logs counts and close codes, never payloads, room ids or full IPs. Behind Caddy it takes the client IP from
  `X-Forwarded-For` only when `TRUST_PROXY=1`.
- **Ruling: deployment** is a small always-on VPS (Hetzner CX22 or a Fly.io machine) running `docker compose` with
  the relay and Caddy (automatic Let's Encrypt). Nothing is provisioned or deployed by these plans; the README
  gives the steps.

## What goes

The Rust crate `app/src/main/rust/coop_net`, `cargoNdkBuild` and its jniLibs wiring, the Rust steps in
`ci.yml` / `release.yml`, `third_party/licenses/coop_net-crates.txt` and its NOTICE / LICENSES entries, iroh in
docs and CLAUDE.md's "Co-op link" paragraph (rewritten for the relay).
