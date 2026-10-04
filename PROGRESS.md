# CM-Chat — build progress

Native Android rebuild of CM-Chat (Kotlin + Jetpack Compose). Private
1:1 messenger in the same category as Briar / Cwtch. This file tracks
what's done, what's next, and decisions made so a fresh session can
continue.

## Stack / decisions
- Kotlin 2.1.0, Jetpack Compose (BOM 2024.12.01), AGP 8.7.3, Gradle 8.11.1.
- minSdk 24, targetSdk 34, compileSdk 35. Single `:app` module, package
  `org.cmchat.app`, single-activity + state-based `AppNav`.
- Font: Nunito (OFL) bundled as static regular/semibold/bold TTFs in
  `res/font`; license in `licenses/Nunito-OFL.txt`.
- Theme: Material3 dark, palette in `ui/theme/Color.kt`.
- CI: `.github/workflows/build.yml` — setup-java 21 temurin (lazysodium-java
  used by the host unit tests needs JDK 21+), runs the unit tests then builds both
  `assembleDebug` and `assembleRelease`, uploads `cm-chat-debug-apk`
  (installs on any phone, debug-signed), `cm-chat-apk` (release, unsigned
  for now), and the gradle build log. Green as of the icon commit.
- Release signing: not set up yet (release APK is unsigned). Stable-key
  signing via GitHub secrets is a later step; see "Signing TODO" below.

## Per-ABI APK splits (latest)
Commit `build: per-ABI APK splits (arm64 primary, arm32 fallback), keep PT libs
per split`.
- `app/build.gradle.kts`: `splits { abi { … include arm64-v8a, armeabi-v7a,
  x86_64; isUniversalApk = true } }`. One lean APK per architecture + a universal
  fallback. Verified each split carries its own `libtor.so` AND `libgojni.so`
  (obfs4/snowflake) + libsodium — bridges intact per split.
- Signed release sizes: **arm64-v8a 34.2 MB**, armeabi-v7a 31.7 MB,
  x86_64 37.2 MB, universal 99.7 MB (down from the single 100 MB fat APK).
- CI (`.github/workflows/build.yml`): builds debug + release splits; if no real
  `KEYSTORE_BASE64` secret it generates an EPHEMERAL signing key so the release
  APKs are v2-signed and installable (caveat: fresh key per run ⇒ uninstall to
  move between builds until a stable key is configured). Publishes a GitHub
  Release (`v0.1-build<run>`) with arm64 (recommended) + arm32 + universal; the
  release notes lead with arm64 and the one-line guidance. x86_64 builds for
  emulator testing but is NOT published. Debug artifact now uploads all per-ABI
  debug APKs.

## Left-anchored header · privacy-PIN digi-lock · rotation hot-loop
Commit `ui: left-anchored compact header; privacy PIN digi-lock; fix rotation
hot-loop + log spam`.
- **Header:** Circle header rebuilt left-anchored and compact — logo + engine
  line on one row (knock "+" far right), then "Me:" status + minimise/exit pill,
  then the stay-unlocked tick; top padding 14→8dp and the tall centred stack
  removed, freeing vertical space. `ui/screens/CircleScreen.kt`.
- **Privacy PIN = real digital lock:** replaced the plain field with
  `PrivacyPinDialog` (`SettingsScreen.kt`). Masked numeric entry; SET and CHANGE
  require enter + confirm with a mismatch error; verifying the current PIN
  (UNLOCK/CHANGE/REMOVE) uses the same escalating `LoginThrottle` lockout as
  login; distinct Set / Change / Remove actions; the section still only opens
  after the confirm-lock passes. Added `onRemovePrivacyPin` through AppNav.
- **Rotation hot-loop:** the only looping caller was the debug Self-Test's
  lifecycle-mash (`SelfTest.mashLifecycle` calls `requestNewAddress` 100× with no
  delay); normal use only triggers it from the "Request new address" button.
  Hardened `ServerController.requestNewAddress` to claim the 60s debounce window
  SYNCHRONOUSLY at the gate (so a burst collapses to ONE rotation instead of
  launching a coroutine per call), reset the window on a genuine failure so a
  real retry still works, and collapse debounced logging to one line every 2s
  ("rotation debounced x<n>") instead of one line per call.

## Bridges (obfs4/Snowflake) + QR contact exchange
Commit `feat: pluggable-transport bridges (obfs4/snowflake), QR contact
exchange, honest stealth wording`.
- **Pluggable transports:** added `com.netzarchitekten:IPtProxy:5.5.1` (bundles
  lyrebird/obfs4proxy + snowflake as in-process Go clients). `tor/Bridges.kt`
  runs the transport, then writes `UseBridges 1` + `ClientTransportPlugin <t>
  socks5 127.0.0.1:<port>` + `Bridge` lines into tor-android's user torrc
  (`TorService.getTorrc`) BEFORE Tor binds — so the FIRST connection already
  goes through the bridge; there is no direct-Tor phase to leak.
- **Fail closed:** `TorService.bindGuardian()` calls `Bridges.prepare()` first;
  if an enabled transport (or the torrc write) fails it sets
  `TorStatus.Failed("bridges")` and does NOT bind Tor — never a fallback to
  direct Tor/clearnet. EngineLine shows "Failed (bridges)" with Retry.
- **Honest status:** while connecting with bridges the Circle shows "Connecting
  through bridges… can take longer than normal."
- **Settings → Stealth (Bridges):** `ui/screens/BridgesScreen.kt` — Off / obfs4 /
  Snowflake, a custom-bridge-lines box (custom wins over the built-in obfs4
  fallback set), and plain wording: "Makes your Tor traffic look like normal web
  traffic… Can be slower. It does NOT add message secrecy." Save persists
  `bridgeMode`/`bridgeLines` to the vault and restarts Tor via the existing
  watchdog/teardown (`TorService.retry`).
- **ABIs trimmed** to arm64-v8a, armeabi-v7a, x86_64 (dropped 32-bit x86).
- **QR contact exchange:** already present — `MyIdScreen` renders your CMC-ID as
  a QR fully offline; `KnockScreen` scans a CMC-ID with the camera (zxing;
  permission requested only when Scan is tapped; no frames stored) and feeds the
  existing knock/first-contact flow. Added a "Show my QR" button beside "Scan
  their QR" for discoverability.
- **APK size:** debug ~48 MB → ~108 MB, release ~100 MB (the obfs4+snowflake Go
  runtime is ~60 MB across three ABIs). Per-ABI splits could cut an arm64-only
  APK back to ~45 MB if wanted.
- **Caveat:** CI-green and the wiring is correct, but actual bridge CONNECTIVITY
  can't be verified from the build environment (no device, no Tor egress) — must
  be tested on a real phone. Snowflake defaults (broker/STUN) can rot; obfs4 with
  user-supplied bridges is the reliable path.

## Resilience + self-attack batch
Commit `harden: self-attack test harness, crash anti-forensics, tor watchdog +
reliable stop, fast reopen, off-main-thread, parser/memory bounds`.
- **A — Single Message:** one concept only. The chat composer's per-message
  self-destruct selector is now labelled "Single Message:" with a "applies to
  this message only" note; `SelfTimer.displayLabel()` OFF reverted to a neutral
  "Off" (no second "Single Message" label; the general/Settings timer is never
  called that). `chat/ChatModels.kt`, `ui/screens/ChatScreen.kt`.
- **B — Self-attack harness (DEBUG only):** `selftest/SelfTest.kt` +
  `selftest/SelfTestLog.kt` (its OWN log, separate from the Tor log). Gated by
  `BuildConfig.DEBUG` (enabled `buildConfig = true`). Four attacks: parser fuzz
  (malformed/truncated/oversized/empty/random → `Transport.readFrame`), onion
  flood (token-bucket burst + a real loopback socket flood: silent/garbage/
  oversized-header/partial, concurrency cap), lifecycle mashing (100× stop +
  address-rotate + presence toggles, thread-leak check), PIN abuse (throwaway
  vault: 25 wrong PINs, escalating-lockout schedule, reversed-PIN duress). Run +
  view from Diagnostics → "Self-Test" section. Findings: what attacked / what
  broke / severity.
- **C — Crash anti-forensics:** global uncaught handler (`diag/CrashCatcher.kt`)
  zeros decrypted messages + key material (`MessageService.zeroKeys()`,
  `ChatStore.clearAll()`, `ServerController.stop()`) before the process dies;
  crash file is written ONLY in debug and SCRUBBED. `diag/Redact.kt` collapses
  any full onion address / long hex key blob, applied in `Diag.add()` (defence
  in depth) and at the onion-publish log site.
- **D — Tor watchdog + reliable stop:** `TorService` watchdog restarts a
  bootstrap stuck <100% for 60s ONCE, then `TorStatus.Failed` with a manual
  "Retry" on the engine line. Stop/Exit HALTs on a bounded daemon thread (≤1.5s)
  then unbinds, so it never hangs at 95%.
- **E — Fast reopen:** `TorService.start()` short-circuits when Tor is already
  Online (reuse, no second bootstrap); onion publish stays idempotent per key.
- **F — Off the main thread:** Argon2id unlock/create run off-main in
  `LockScreen` (busy state gates the keypad); all vault saves go through
  `vault/VaultIO.kt` (single-thread IO lane, serialized).
- **G — Parser/memory bounds:** `Transport.readFrame` validates length vs the
  hard cap BEFORE allocating and wraps the body read, returning null on any
  truncation instead of throwing; `MessageService.handleIncoming` wraps all
  remote parsing and drops the connection cleanly. One bounded frame per
  connection caps buffered data per peer.

Self-test result on this build: all four attacks pass — every malformed/
oversized/truncated/random frame is dropped with no crash, the flood limits and
concurrency cap hold, lifecycle mashing leaves no stuck state or thread leak,
and the PIN lockout + duress paths behave. The parser try/catch around the body
read (G) is the one defect the fuzzer would have hit before this batch.

## Done
- Project skeleton, theme, Nunito font, glowing CM-Chat logo.
- Screens (visual, fake data): Circle (contacts), Chat (Cerberus/Timer
  bar with hex-eye CerberusMark, bubbles, offline retry bubble, input),
  Settings (text-size slider, rows, wipe button).
- App launcher icon: seed-of-life flower + red ring + plus, transparent
  background. Adaptive icon (`mipmap-anydpi-v26`) for API 26+, legacy
  layer-list fallback (`mipmap-anydpi` -> `drawable/ic_launcher_legacy`)
  for API 24-25.
- Phase 2 security core:
  - `crypto/CryptoManager.kt` — libsodium via lazysodium. Argon2id
    (crypto_pwhash, 16-byte per-vault salt, interactive limits 2 / 64 MiB)
    for PIN->key; crypto_secretbox_easy (XSalsa20-Poly1305, the libsodium
    "secretbox") for vault sealing, framed nonce||ciphertext; crypto_box
    (X25519) keypair per Face. No hand-rolled crypto.
  - `vault/Vault.kt` — one encrypted `vault.dat` + `salt.dat` in
    app-internal storage; VaultData JSON (faces, contacts, settings) via
    kotlinx.serialization. Messages are never persisted. wipe() overwrites
    then deletes (best-effort; flash wear-levelling is not defeated).
  - `vault/VaultManager.kt` — first-run create, unlock, duress. Duress:
    on a failed unlock the reversed input is tried; if it opens the vault
    the real PIN was entered backwards -> wipe + return to first-run,
    silently. No PIN is ever stored. Palindrome PINs rejected so
    reverse != forward.
  - `LockScreen` reworked into first-run (new PIN + confirm + Face name)
    and unlock flows; wrong PIN shows an error and, after 5 tries, an
    escalating countdown lock. Duress silently resets to first-run.
  - Circle now renders contacts from the decrypted vault; the sample
    Circle shows only when the vault has no contacts yet.
  - Unit tests (`app/src/test`, run in CI before the APK build): vault
    round-trip, wrong PIN fails, reverse-PIN detected as duress + wipe,
    palindrome rejected, tampered ciphertext rejected. Run on the host JVM
    via lazysodium-java (same code path as on-device lazysodium-android).

Decisions: "secretbox (XChaCha20-Poly1305)" in the brief is implemented
with libsodium's actual secretbox primitive (XSalsa20-Poly1305) — the
standard, correct choice; XChaCha20-Poly1305 is reserved for the message
crypto_box layer in a later phase. Argon2id uses interactive limits so
unlock stays usable on low-RAM phones. R8/minify stays OFF this phase so
JNA/libsodium aren't stripped; turning it on with keep rules is a later
hardening step.

- Phase 3.1 Tor foreground service:
  - Deps: info.guardianproject:tor-android:0.4.9.5 (0.4.9.6+ demand
    compileSdk 36/37 which AGP 8.7.3 rejects, so pinned to 0.4.9.5 which
    has no compileSdk floor) + jtorctl 0.4.5.7 + kotlinx-coroutines 1.9.0.
    A transitive kotlin-stdlib 2.3.0 is force-pinned to 2.1.0 to match the
    compiler.
  - `tor/TorService.kt`: foreground service (min-importance "Active"
    notification) that starts + binds the library TorService, listens for
    its status broadcasts, and polls the control port
    (status/bootstrap-phase) for %. Exposes `TorService.status:
    StateFlow<TorStatus>` = Starting / Connecting(%) / Online / Offline.
  - Manifest: INTERNET, FOREGROUND_SERVICE(+DATA_SYNC), POST_NOTIFICATIONS;
    service declared with foregroundServiceType=dataSync.
  - Circle header shows a status dot + label (grey Offline / orange
    Connecting x% / green Online) and a "first launch can take 1-3 min"
    note while connecting. Tor is started on unlock.
  - NOTE: debug APK ~47 MB because tor-android bundles the tor binary for
    all 4 ABIs; a release ABI split is a later step. Tor reaching ONLINE,
    the notification, and bootstrap-% wiring can only be verified on a real
    device (no emulator in CI); this commit is compile- + unit-test-green.

- Phase 3.3/3.4 cores (testable, done):
  - `crypto/CmId.kt` — CM-ID = "cm1:" + base32([onionLen][onion][32-byte
    identity pubkey]); encode/decode with a self-contained RFC4648 base32.
  - `crypto/CryptoManager` — added crypto_box seal/open (X25519 +
    XChaCha20? no: crypto_box = X25519+XSalsa20-Poly1305) for frames.
  - `transport/Frame.kt` — FrameType enum (KNOCK, KNOCK_ACCEPT, MSG, ACK,
    STATUS, ERASE_CHAT, PING, PONG) + FrameCodec seal/open (nonce||cipher
    of [type][payload]); unopenable frames dropped.
  - `transport/Transport.kt` — length-prefixed read/write, SOCKS5-through-
    Tor connect with UNRESOLVED host (Tor resolves .onion), loopback
    ServerSocket. Socket paths are compile-only until wired on device.
  - Tests now 8/8: + CM-ID round-trip, malformed CM-ID rejected, frame
    seal/open round-trip, tampered frame rejected.

- R1 complete (onion service + My Server), compile + test green:
  - Face gained onionKey/onionAddress (v3 endpoint key), stored in the
    vault SEPARATE from the messaging identity key (Home Node friendly).
  - `tor/ServerController.kt`: publishes a v3 onion (ADD_ONION via jtorctl,
    virtual port 80 -> random loopback ServerSocket) for the active Face;
    reuses the stored key or asks Tor to generate one (NEW:ED25519-V3) and
    returns it to persist. ServerStatus StateFlow Off/Starting/Online/
    Failed; stop (DEL_ONION + close), restart, and self-test (connect to
    own onion through Tor, OK/FAIL + ms).
  - TorService exposes controlConnection() + socksPort().
  - `ui/screens/MyServerScreen.kt`: status steps, onion address, Face,
    live uptime, Start/Stop/Restart + Self-test. Reached from Settings.
  - AppNav now keeps the PIN for the session (needed to save the vault),
    starts the server when Tor is ONLINE, and persists a freshly-generated
    onion key back into the vault.
  - Onion accept loop currently accepts + closes (frame handling is R2).

- R2 (transport + My ID + Knock), compile + test green:
  - ZXing added (core 3.5.3 + journeyapps zxing-android-embedded 4.3.0).
  - CryptoManager: crypto_box_seal / seal_open (anonymous sealed box) for
    KNOCK, since the knocker isn't in the Circle yet so there's no shared
    key; everything else stays crypto_box between known identities.
  - transport/MessageService: onion accept loop -> read length-prefixed
    frame -> sealedOpen (KNOCK) -> KnockPayload -> incomingKnocks flow;
    sendKnock connects SOCKS5-through-Tor and writes a sealed KNOCK;
    accept/decline. ServerController.onIncoming hands sockets to it.
  - Messages.kt: KnockPayload / TextPayload / StatusPayload (JSON).
  - ui/screens/MyIdScreen: CM-ID (from active Face onion + identity pubkey)
    as selectable text + a ZXing QR bitmap + Copy + Share.
  - ui/screens/KnockScreen: paste a CM-ID or scan a QR (zxing ScanContract
    camera), pick a nickname, Send Knock. Circle shows incoming knocks
    with Accept/Decline.
  - Tests 9/9: + knock sealed-box round-trip (only the recipient's key
    opens it).
  - Device-only: camera scanning and real Tor delivery of the KNOCK are
    not exercised in CI. KNOCK_ACCEPT delivery + persisting accepted
    contacts land with R3 chat.

- R3 (chat), compile + test green:
  - chat/ChatModels + ChatStore: RAM-only per-contact threads (never
    written to disk), message state SENDING/SENT/DELIVERED/OFFLINE,
    self-timer (off/30s/5m/1h counted from SEEN), last-seen buckets
    (<=60 "recently", <=180 "a while ago", else nothing), peer status,
    Team Hour, purgeExpired, clearAll.
  - MessageService: sendText/retry/sendErase/sendStatus + KNOCK_ACCEPT;
    handleIncoming now also boxOpens MSG (-> store + ACK), ACK (->
    DELIVERED), STATUS (-> peer status), ERASE_CHAT (-> erase) from known
    contacts. Accepted knocks are persisted to the vault as contacts
    (ContactRec gained cmId); Circle lists real contacts and chats key on
    the contact CM-ID.
  - ChatScreen rebound to ChatStore: real bubbles with delivery labels,
    dashed red Offline-Retry bubble with a once/30s countdown, self-timer
    chips, Erase (both sides), Team Hour line, peer status + last seen.
  - Tests 12/12: + last-seen bucketing, self-timer expiry, timer labels.
  - Device-only: real Tor delivery/ACK, and Team Hour cross-device sync
    (currently local; rides a later frame). Status is sent to contacts,
    never your own is shown.

- R4 (guardians / hardening / tools / metadata), compile + test green:
  - R4a guardians: GuardLogic (pure, tested) + GuardController — Cerberus
    idle wipe (touch/onResume resets; reopening from recents counts),
    Kill Timer, first-one-wins; on expiry silent RAM wipe (chats, tools),
    stop server + Tor, kill process; vault stays. Chat eye = real armed.
  - R4b Wipe Everything Now: confirm -> delete vault+salt+caches, clear
    RAM, fire ACTION_DELETE (uninstall) intent. Reverse-PIN unchanged.
  - R4c hardening: FLAG_SECURE, allowBackup=false + data-extraction/backup
    rules excluding all, R8 minify + resource shrink for release with
    keep/strip rules (Log stripped; JNA/lazysodium/tor/zxing/serialization
    kept). Release APK builds under R8 (~39 MB).
  - R4d tools dock (off by default, per-tool Settings toggle, all offline):
    Calculator (arithmetic), Notes (RAM-only, wiped on close/wipe),
    Converter (length/volume/mass). Calculator + Converter unit tested.
  - R4e metadata scrub: MetadataScrubber.stripJpeg removes APP1 (Exif/GPS/
    XMP) keeping APP0/JFIF + image data (pure, unit tested); AppSettings
    metadataScrub (default ON) + shareLastSeen (default ON) toggles. Added
    FILE_OFFER/CHUNK/DONE frame types.
  - Tests 19/19.
  - Deferred/device-only: real 100 MB file transfer over Tor (frame types
    reserved, UI not built — RAM/app-cache-chunk choice to be made on
    device); Team Hour cross-device sync; last-seen/status/metadata-scrub
    wiring into the live send path; FLAG_SECURE blanking, uninstall intent,
    minified-release runtime, and process kill are all device-only.
  - R5 (Bouncy Castle swap) intentionally NOT done — would risk the green
    crypto; revisit only with device testing.

- Diagnostics + onion ADD_ONION robustness + Cyrillic label:
  - diag/Diag: RAM-only ring buffer (~200), levels D/I/W/E, StateFlow;
    mirrors to Logcat in debug (R8 strips it in release); dropped
    undecryptable frames logged as COUNT only (never content/peer).
    Cleared on app close and every wipe path (Cerberus/Kill/Wipe Now).
    Settings -> Diagnostics screen (newest-first, Copy-all, Clear).
  - diag/CrashCatcher: DEBUG-PHASE aid — a default uncaught-exception
    handler writes ONE crash file to app-internal storage; shown on the
    Diagnostics screen next launch then deleted; wiped by every wipe path.
    Flag CrashCatcher.ENABLED (currently true) — MUST be set false / removed
    before any real-safety release (it is the only on-disk exception trace).
  - Onion: ADD_ONION runs off the main thread (ServerController IO scope,
    so no ANR); logs the command + full parsed reply keys. **Fix:** jtorctl
    returns the reply under keys `onionAddress` (base32 host, no scheme) and
    `onionPrivKey` ("ED25519-V3:..."), NOT "ServiceID" — that key mismatch
    was the whole publish bug. Now reads those keys; onion = addr + ".onion".
    Stores the priv key verbatim and reuses it next run (recreate falls back
    to the stored address if the reply omits it). A missing onionAddress /
    5xx is surfaced as a clear Failed(...) error. Priv-key blob never logged.
  - Errors wired into Diag at Tor status, onion publish/self-test, knock
    send, and dropped frames. Launcher label -> Cyrillic "СM-Chat"
    (sorts to the bottom); app_name stays Latin CM-Chat.
  - Debug + release (R8) both build; tests 19/19.

## Signing (still needs the repo owner)
The workflow is now wired for stable-key signing (see "Signing TODO" below
for the exact click-by-click steps). Until the four keystore secrets are
added in GitHub, release APKs build **unsigned** (`app-release-unsigned.apk`)
and nothing secret is stored in the repo. Test with the debug APK meanwhile.

## Privacy PIN length + session window
- Privacy & Safety gate now uses a SEPARATE user-chosen 4-8 digit PIN (digits
  only), stored in the encrypted vault (VaultSettings.privacyPin). First visit
  sets it (4-8 enforced); later visits enter it. It is distinct from the main
  login passcode.
- Session window (item 7, default OFF): "Stay unlocked for 6h" — after unlock,
  returning to the app within 6h skips the re-lock (LifecycleController checks
  AppSettings.sessionStillValid). In-process only: a cold start always re-asks,
  because the derived key is never persisted to disk (kept this way on purpose —
  caching keys for 6h on disk would be a real downgrade; say the word if you want
  that trade-off instead). Choice persisted in VaultSettings.sessionWindow.

## Self-timer appearance (reverted to selector)
- Per-message timer is a horizontal SELECTOR again (chips, not click-to-cycle),
  labelled "Once:"; it applies only to the next message then resets to OFF.
- General timer (Settings) still applies to ALL messages in ALL chats; a one-off
  per-message pick overrides it just for that message (so global 60m + one-off 5m
  coexist, as MessageService.sendText already resolves).

## Flashlight in place + Notes limits
- Flashlight: tapping the dock circle toggles the torch IN PLACE (no screen); the
  circle fills while on and stays on as you use the app; off on wipe/exit.
- Notes: checklist capped at 10 items ("Max 10 checks"); scratchpad capped at
  10,000 chars.

## Lock screen: alphanumeric in place
- The "Aa" toggle now shows the passcode field ON the same lock screen (no new
  window) with the system keyboard auto-focused; Done submits; "123" returns to
  the number pad. Input capped at 128 chars, newlines stripped.
- Security: audited — the passcode is only ever passed to Argon2 key derivation
  (cryptoPwHash) as bytes. No Runtime.exec/ProcessBuilder/Class.forName/
  reflection/ScriptEngine anywhere; Calculator.eval is a pure arithmetic parser
  used only by the calculator tool, never the passcode.

## UI batch (languages, notif privacy, logo, Me, shredder, engine line, etc.)
- Languages: added Finnish (fi) + Norwegian (no) to the list (Languages.kt, 14
  locales). A Language picker screen persists the choice in the vault
  (VaultSettings.language); English is complete, others scaffolded (strings still
  English until each locale is filled in).
- Notification privacy: removed the "show sender name" option entirely. Only two
  generic types remain — "Activity" (buzz) and "Notification" (message); neither
  ever reveals who sent anything.
- Logo glow: stronger, softer bloom (higher floor + bigger peak + wider blur),
  clipped to the logo's own bounds so it never spills past the edges.
- Logo state by Tor: the Circle-page logo is desaturated grey with no glow when
  the engine isn't Online, and only coloured + glowing when Online. The
  login/naming logo is unaffected (always coloured).
- "Me:" label: own status now reads "Me: Invisible" / "Me: Online", distinct
  from contacts' statuses.
- Engine status line: moved under the logo, centred, as "Engine: Online /
  Starting / Connecting n% / Offline".
- Minimise/Exit pill: a small centred segmented pill between the status line and
  the "Me:" row — left "–" minimises (Tor keeps running, moveTaskToBack); right
  red "⏻" Exits (full teardown + finish). 1px #2b3340 border, dim icons, red exit.
- Stay-unlocked tick: a small symmetric checkbox on the Circle page toggles the
  6h session window (persisted), mirroring the Settings toggle.
- Shredder PIN expanded: the reverse-PIN now erases ALL recoverable on-disk data
  (Shredder.shredAll: filesDir, caches, code cache, no-backup, external files/
  cache, databases, shared_prefs — overwrite then delete) plus RAM, then resets
  to first-run. Only the app binary remains (needs user uninstall).
- Per-setting hints: one-line italic plain-language explanations (<=60 chars)
  under the settings that need them.
- RAM diagnostics: its OWN screen + log (RamDiag), separate from the Tor/Diag
  log; samples used heap over time labelled by active features, with "Enable all
  features" and a peak readout. (Android can't attribute heap per-feature exactly
  — it's an indicator under the active feature set, noted in-app.)

## Stability + security hardening
- Onion rotation loop / "address collision" fixed: ServerController now guards
  publish with a Mutex (single-flight — never two publishes at once), a fast
  "already online for this key -> no-op" check (kills the re-publish storm when
  the start effect re-fires), DEL_ONION of the previous AND the stored address
  before every ADD_ONION (re-add can't collide), and tracks activeKey. Rotation
  (requestNewAddress) is debounced to at most once / 60s and skips if a publish
  is in flight.
- Tor teardown: TorService.teardown() stops the onion (DEL_ONION), HALTs tor via
  the control port (kills a stuck 95% bootstrap), unbinds the Guardian service
  (its onDestroy stops the tor thread), and clears state; a single-instance guard
  tears any old half-dead instance down before a new start, and the static
  instance is only nulled if it is still us. Stop/Exit now actually stop Tor.
- Onion DoS protection: accept loop enforces a global accept-rate token bucket
  and a max-concurrent-connection cap (drop before any work/alloc), sets a 15s
  read timeout so a silent peer can't hold a slot, and hands each connection off
  synchronously with proper close/decrement. The frame is authenticated (opened)
  BEFORE any dispatch. Post-auth, a per-contact token bucket (RateLimiter, unit-
  tested) drops a contact that floods us. Pending knocks capped at 20 + de-duped.
  (Peers are indistinguishable pre-auth over Tor, so pre-auth limiting is global;
  per-peer limiting applies once a frame authenticates to a known contact.)
- Input hardening: max frame size cut 8 MiB -> 64 KiB, checked BEFORE allocating
  the buffer; all remote bytes are only decrypted/parsed, never executed; flood
  OOM bounded by the frame cap + concurrent cap + knock cap + rate limits.
- Control port: we never configure it; Guardian binds it to 127.0.0.1 with
  cookie auth (never 0.0.0.0). Verified no ControlPort/SETCONF/0.0.0.0 in code.
- Exported components: MainActivity stays exported (launcher); our services are
  exported=false; Guardian's org.torproject.jni.TorService is force-merged to
  exported=false (we only bind it in-process).
- Memory: the Argon2 key and the decrypted vault plaintext are zeroed (fill(0))
  immediately after use on both load and save paths. (JVM Strings are immutable
  so the decoded object graph can't be wiped, but raw key/plaintext buffers are.)

## Lock keyboard (custom) + Single Message + text limits
- Lock screen: the simple digit pad stays default with an "ABC" key that switches
  to CM-Chat's OWN dark QWERTY (not the grey system keyboard) — only the key
  arrangement follows the reference. Row 1 = symbols (| @ # $ % ^ & * « »); rows
  2-4 qwerty; [Shift] one-shot / caps-lock (highlighted state); [123] back to the
  number pad; [⌫] backspace. The passcode accumulates across both screens (mixes
  digits+letters+symbols), masked as dots, capped 128, submitted via Enter once
  letters are used (pure 6-digit keeps instant-submit). isValidNewPin now accepts
  any 6..128 non-palindrome passcode. SECURITY: passcode is opaque bytes passed
  only to Argon2id — never executed/eval'd/used as a filename/shell/SQL (noted in
  code).
- Self-timer "off" renamed to "Single Message" in the UI (chat selector + Settings
  general timer) via SelfTimer.displayLabel(); wire value stays "off", behaviour
  unchanged (OFF = follow the global/general expiry; any other = one-off timer).
- Chat body limits: max 10,000 chars (was 100,000), min 1 (send disabled while
  blank), live "9,214 / 10,000" counter once past ~9,000.

## CRITICAL crash fix — ForegroundServiceDidNotStartInTime (definitive)
- Repro: launch on Android 14 (Ulefone Armor 22). On unlock we started Tor; the
  service died with ForegroundServiceDidNotStartInTimeException at the point we
  called startForegroundService() for Guardian's org.torproject.jni.TorService,
  plus random ANRs.
- Root cause (verified by decompiling tor-android 0.4.9.5): Guardian's TorService
  .onCreate does NOT call startForeground() at all — it only broadcasts status and
  starts the tor thread. Starting it via startForegroundService() therefore makes
  the OS wait 5s for a startForeground() that never comes -> crash. We were running
  TWO foreground services and the Guardian one could never satisfy the timer.
- Fix: run ONE foreground service (ours). We now ONLY bindService() the Guardian
  service (BIND_AUTO_CREATE), which runs its onCreate -> tor thread on its own
  worker; we never startForegroundService() it. Our onCreate calls startForeground
  as the literal first action (channel created first); on failure it falls back to
  an untyped call and finally stopSelf (never lingers to be watchdog-killed).
  FGS launches are wrapped so an API 12+ background-start rejection can't crash.
  All Tor/bootstrap/ADD_ONION work stays on Dispatchers.IO (no main-thread ANR).
- API 34 path: typed startForeground (FOREGROUND_SERVICE_TYPE_DATA_SYNC) on API
  29+, permission declared. Compile-verified; on-device confirmation pending.

## Security adds (status)
- Onion hidden-service key: encrypted in the vault (libsodium secretbox) and
  handed to Tor over the control port (ADD_ONION). It is NEVER written to Tor's
  data dir — it lives in Tor's memory only while running and is removed on
  DEL_ONION/stop. Exceeds the "written to Tor dir only while running" ask.
- RAM-only logs: Diag is a RAM ring buffer; onion addresses are only ever logged
  there, never to disk. (The debug-phase CrashCatcher writes one crash file,
  deleted next launch; it stays gated by its ENABLED flag for real releases.)
- Lock + wipe keys from RAM on background: done (onStop -> lockRequests clears
  the PIN + decrypted vault from the UI layer) unless stay-reachable is on.
- Onion-only network guard + fail-closed: done (Transport.isOnionHost; sends
  require Tor Online).

## Deferred (owner decisions)
- Bridges (obfs4 + Snowflake): needs PT binaries (IPtProxy); no mock shipped.
- Languages (full i18n): externalizing every string + runtime picker + 11 locale
  scaffolds is a large mechanical refactor; deferred to a dedicated pass so the
  picker isn't half-wired. English strings remain inline and complete.

## Screens / UI
- Logo glow is now a letter-by-letter SWEEP (a bright point crosses the wordmark):
  6.5s cycle on the Circle page, 3.25s (2x) on the lock + first-run naming screen.
- Circle header: orange "+" only (done earlier), bigger logo, smaller status text.
- Chat bar guardians display-only (done earlier). First-run "review Settings"
  prompt + About/Version safety welcome (done in the guardians/settings commit).
- PIN keypad taps are already immediate (direct clickable, auto-submit at 6).

## Team Hour (persisted)
- ContactRec.teamHour (encrypted in the vault, per contact) survives logout.
  Editable in the chat ("Set Team clock" / tap to edit), seeded into the thread
  on open, saved back to the vault on change.

## Guardians / safety + Settings groups
- Settings reorganised into groups: Tags · Chats · [PIN-gated] Privacy & Safety ·
  Server · Tools · System.
- Privacy & Safety is PIN-gated: a side-effect-free VaultManager.verify unlocks
  the group (Cerberus, Kill Timer, Stay-reachable, Shredder PIN, Decoy, status
  default, Wipe Everything).
- "Panic PIN" -> "Shredder PIN" (mechanism = reverse-PIN silent wipe, unchanged).
- Decoy chat: toggle + renamable name + position (top/bottom). Shows as a fake
  contact in the Circle; tapping it = silent instant Exit + RAM wipe, no confirm.
- First run after creating the Tag: a prompt "Please take your time to review the
  Settings page before you start." with "Ok, take me to Settings." / "I'll do it
  later." (later -> main screen).
- About / Version: the serious numbered CMC safety welcome (AboutScreen).
- Cerberus/Kill/Wipe/reverse-PIN mechanisms unchanged. Last-seen already matches
  the spec (recently within 24h of activity, else nothing).

## Tools dock (flashlight + calc fix)
- Flashlight tool: CameraManager.setTorchMode (no camera permission). Dock circle
  glyph ☀; a tap screen toggles the torch; forced off on wipe/exit. Settings
  toggle added. Dock = Calculator + Notes + Flashlight, transparent circles,
  centred by count.
- Calculator display fixed: entry capped to 15 significant digits; any result
  with >=15 integer digits or non-finite shows "Error"; big/decimal numbers
  formatted via BigDecimal(15 sig figs, trailing zeros trimmed). Unit-tested
  (overflow -> Error).
- Notes checklist (Add check / green-tick strike-through) already in place.

## Presence / lifecycle
- Start INVISIBLE every login (invisibleMode=true on unlock). Online/Invisible
  toggle in the Circle header; going Online calls ChatStore.markMissedSeen (self-
  timers start then).
- NO online indicator on friends: removed the peer status dot/word in chat; only
  "last seen recently" remains.
- Invisible behaviour redefined: the server stays UP (no longer stops). Incoming
  messages are held as "missed" — orange unread dot on the contact; opening the
  chat shows "Change status to Online to receive messages" (content hidden,
  sender learns nothing — no receipts exist). Going Online reveals them as italic
  red "Missed Message"; the dot clears when viewed Online.
- "Stay reachable in background" (default OFF): keeps the full server up after
  close until Exit, and forces Cerberus + Kill Timer OFF.
- Exit (Settings): stop server, clear RAM, log out (re-lock).
- Lock on background: onStop re-locks + wipes the vault-unlock material (PIN +
  decrypted vault) from the UI layer unless stay-reachable is on; the service
  keeps running so minimised = still online + Cerberus counting; swipe-away =
  offline + clear RAM (unless stay-reachable). First-run explainer already shown.

## Identity / contacts
- "Faces" -> "Tag" in the UI (first-run naming, Settings row, My Server label).
  CMC-ID prefix cmc1: unchanged.
- Self-add blocked: Knock shows "That's your own ID" if you enter your own CMC-ID.
- Accepted knock autopopulates the nickname from the requester's display name
  (unchanged); you can now rename a contact by tapping their name in the chat
  header (persisted to the vault).
- Address rotation (manual): My Server -> "Request new address" publishes a NEW
  onion while keeping the OLD one registered ~24h (overlap so none drop), then
  sends a signed ADDR_UPDATE (crypto_box-authenticated by the identity key) to
  all contacts; receivers verify the identity pubkey matches and auto-relink to
  the new onion, persisting it. Device-only to exercise end-to-end.

## Login security
- Escalating failed-attempt delay (LoginThrottle, unit-tested): 2,4,8,20,40,60,
  80,120,150,200,250,300s for attempts 1-12, then a 30-minute lockout; the
  counter resets to zero after the lockout elapses. Lock message formats mm/ss.
  No password recovery (unchanged).

## Network hardening
- Onion-only guard + fail-closed: done in the urgent Tor fixes (connectThroughTor
  refuses non-onion; sends require Tor Online).
- Bridges (obfs4 + Snowflake): DEFERRED by owner decision — needs PT binaries
  (IPtProxy); no mock shipped. Revisit after the two-phone test.

## Messaging
- Delivery/read receipts DROPPED entirely: removed ACK frame send + handling and
  MsgState.DELIVERED; no sent/delivered/read label is shown. SENDING/SENT/OFFLINE
  are local-only, used just for the offline/retry affordance (not a receipt).
- Messages RAM-only (unchanged). Input is multiline — Enter = newline, send only
  via the button; capped at 100,000 chars.
- Per-message self-timer: a cycling chip, defaults OFF and resets to OFF after
  each send; a small RED duration shows under the message (no countdown) and it
  vanishes when it expires.
- General timer (all messages): SelfTimer now OFF/30s/5m/10m/30m/60m/120m/6h/
  12h/24h; AppSettings.generalTimer (Settings-only, default OFF) applies to every
  message unless a per-message timer overrides it; shown as a small red line
  under the contact name.

## URGENT Tor fixes (two-phone blockers)
- Onion collision: publish ONCE per session. ServerController.start is
  idempotent (skips if Online/Starting); stop() flips state synchronously so
  restart() re-publishes. No second ADD_ONION of the same service.
- "SOCKS: Host unreachable": a fresh v3 descriptor needs ~30-90s to upload.
  Transport.connectThroughTorRetry retries with backoff (2->15s) up to 90s with
  progress; self-test and outbound sends use it instead of hard-failing.
- Onion-only guard (fail closed): connectThroughTor refuses any non-v3-onion
  host; sends throw if Tor isn't Online (never touch clearnet). isOnionHost
  unit-tested.
- Foreground crash: TorService (and BuzzListenerService) call startForeground
  IMMEDIATELY, API-branched, typed (FOREGROUND_SERVICE_TYPE_DATA_SYNC on API
  29+); TorService re-asserts it in onStartCommand.
- Device-only: actual descriptor upload / reachability needs a phone.

## Tools dock rework
- Removed the Converter (dock = Calculator + Notes for now). Converter.kt and
  its toggle/tests deleted.
- Dock tools are now transparent CIRCLES with a symbol glyph + tiny caption
  (the old black square was a bug — fixed), centred and evenly spaced by count.
- Calculator: Google-calculator look (clean rounded Material-dark keys), CT-200N
  key set + arrangement — MRC / M- / M+, √, %, C/CE, ÷ × − + =, . and 0–9 (no
  "OFF"). New CalcEngine (pure, immediate-execution pocket-calc semantics +
  memory), unit-tested. Dock glyph ▦.
- Notes: still RAM-only scratchpad, plus an "+ Add check" button that adds a
  to-do line with an empty checkbox; tapping the checkbox green-ticks it
  (strike-through, item stays). Dock glyph ☑. Checklist cleared on wipe.
- 25 unit tests pass (added CalcEngine coverage).

## Circle header + chat guardians
- Circle header (item 8): removed the "Knock" word and its pill; now just a
  suggestive tappable orange circular "+" where Knock was.
- Chat guardians (item 9): the Cerberus / Kill Timer bar is DISPLAY ONLY now —
  removed the tap-to-toggle. Both show live state (Cerberus armed + 90m; Kill
  Timer counts down or "Timer off") and are changed only in Settings.

## Lock screen: alphanumeric option
- "Aa" button in the bottom-left keypad cell (under 7, left of 0). Tapping it
  switches the number pad to a full keyboard (OutlinedTextField, password-
  masked) with an explicit Enter button and a "123" link back to the numeric pad.
- isValidNewPin now accepts a 6-digit numeric PIN OR a 6+ char alphanumeric
  passcode with at least one letter; palindromes still rejected (keeps the
  reversed-input duress check unambiguous). Argon2id hashes the raw bytes, so
  any length/charset derives a valid key. Tests cover both. 21 tests pass.
- NOTE for the owner: the batch said "default stays a 4-digit numeric PIN", but
  the app has shipped a 6-digit default throughout (confirm UI + duress logic
  assume 6). Dropping to 4 digits weakens the passcode in a privacy tool, so I
  kept the 6-digit numeric default and ADDED the alphanumeric option rather than
  silently weakening it. Say the word if you really want 4-digit.

## App lifecycle
- Swiped from recents (TorService.onTaskRemoved -> LifecycleController): CLOSED
  — clear ALL RAM (messages/notes/statuses/buzz), go offline (or keep the buzz-
  listener per the toggle). Vault stays; next open needs the PIN. (Wired in the
  BUZZ commit.)
- Minimised (still in recents): no onTaskRemoved fires, so the app stays ONLINE
  and keeps RAM; Cerberus keeps counting because minimising never calls touch()
  (touch is only on Activity onResume). Returning to the foreground resets
  Cerberus (onResume -> GuardController.touch) and ends buzz-only mode.
- First run: one plain teaching sentence on the Face screen — "Open = present;
  minimised = present but on Cerberus's timer; swiped away = closed and offline."

## Last-seen (simplified)
- LastSeen.bucket now: within 24h -> "last seen recently"; after 24h -> nothing.
  Dropped the 60/180-min tiers ("a while ago"). Global "Share my last-seen"
  on/off unchanged. Tests updated; 19/19.

## Invisible mode
- Settings toggle (AppSettings.invisibleMode, default OFF). When ON, the onion
  server is stopped and the accept loop refuses every incoming connection, so
  any probe (message, retry, buzz) sees us as OFFLINE. Outbound (SOCKS through
  Tor) is unaffected — you can still start conversations. Turning it off
  re-publishes the onion. Closes the "retry reveals a hidden-online user" leak.
- Note: our transport is one-shot per connection, so a reply "within an open
  session" isn't a separate path — invisible simply drops all inbound.

## BUZZ + scout listener
- New FrameType.BUZZ (12). Fire-and-forget: no ack, no retry, no read state,
  no content — so it can't act as a presence detector. Send has a 5-min
  per-contact cooldown (BuzzPolicy.SEND_COOLDOWN_MS).
- Receiver "Accept Buzz" frequency (Settings, taps to cycle): 1h / 12h / 24h /
  Once only. "Once only" = after one buzz from a person, no more accepted until
  you send them a message (BuzzPolicy.onMessagedContact clears it).
- Receive with the chat open: screen shake (Animatable offset) + vibration.
- Notifications are generic like original CM-Chat: a BUZZ = "Activity", a new
  MESSAGE = "Notification" (only when that chat isn't on screen). No sender
  name / no content by default; a setting ("Show sender name on alerts") can
  switch on the nickname. Cleared on every wipe path + on app close.
- Buzz UI: ⚡ Buzz chip in the chat (shows cooldown), long-press a contact in
  the Circle.
- Scout listener (buzz-through-when-closed): BuzzListenerService, a minimal
  foreground service that survives swipe-away with its own minimal "Listening"
  notification. On swipe (TorService.onTaskRemoved -> LifecycleController):
  if "Let a Buzz reach me when closed" is ON (default) and not Invisible, Tor +
  onion stay up, RAM is cleared, MessageService enters buzzOnlyMode (only a
  BUZZ -> "Activity"); else full close (stop server + Tor, clear RAM). Force-
  stopping in Android Settings kills even the listener (fully dark). Returning
  to foreground (onResume) ends buzz-only mode and stops the listener.
- Device-only: the actual swipe-survival, Tor reachability, shake/vibration,
  and notifications need a phone (CI verifies compile + crypto only).

## Rename CM-ID -> CMC-ID
- ID prefix `cm1:` -> `cmc1:` (CmId.PREFIX); all user-facing labels now say
  "CMC-ID" (My CMC-ID screen + Settings row, Knock hint/error, QR desc).
  No live users / no back-compat. Tests updated; 19/19.

## Next
- On device (two phones): PIN + Face, wait for Tor "Online", My ID/QR,
  Knock/Accept, chat both ways, offline retry, erase, self-timer, status,
  reverse-PIN wipe, My Server self-test. Then decide the file-transfer
  RAM-vs-cache approach and finish R4 files + live presence/scrub wiring.
- Later: chat over Tor, Cerberus/Kill timer, file transfer, hardening
  review (FLAG_SECURE, R8 log stripping, data-extraction rules).

## Signing TODO (needs the repo owner to click in GitHub)
The build + CI are wired: `app/build.gradle.kts` reads a keystore from the
`CMCHAT_KEYSTORE` env var (gated on the file existing), and the workflow
decodes it from a secret and passes the passwords in. So all that's left is
adding four secrets. **The keystore is never committed** — it lives only as
a GitHub secret. Do this once:

1. **Make a keystore** (on your own computer, needs a JDK/`keytool`):
   ```
   keytool -genkeypair -v -keystore cmchat.keystore \
     -alias cmchat -keyalg RSA -keysize 2048 -validity 10000
   ```
   It asks for a keystore password and a key password (you can use the same
   one) and a name/org (anything). Keep `cmchat.keystore` and the passwords
   somewhere safe and private — losing them means future versions can't
   install over old ones. **Do not add the keystore to git.**

2. **Base64-encode it** into a text blob for the secret:
   - macOS/Linux: `base64 -i cmchat.keystore | tr -d '\n' > cmchat.b64`
   - Windows PowerShell:
     `[Convert]::ToBase64String([IO.File]::ReadAllBytes("cmchat.keystore")) > cmchat.b64`
   Open `cmchat.b64` and copy all of it.

3. **Add the four secrets** on GitHub: repo → **Settings** → (left menu)
   **Secrets and variables** → **Actions** → **New repository secret**.
   Add each of these (name exactly, value = yours), clicking "Add secret"
   after each:
   - `KEYSTORE_BASE64` — the whole base64 blob from step 2
   - `KEYSTORE_PASSWORD` — the keystore password from step 1
   - `KEY_ALIAS` — `cmchat` (or whatever `-alias` you used)
   - `KEY_PASSWORD` — the key password from step 1

4. **Re-run the build**: Actions tab → latest run → "Re-run all jobs" (or
   just push any commit). The release artifact `cm-chat-apk` will now be
   `app-release.apk` (signed). Delete `cmchat.b64` afterwards.

If the secrets are absent the build still succeeds; the release is just
unsigned. Never paste the keystore or passwords into code, commits, or issues.

## Known issues
- Text input and the settings slider are visual-only until later phases.
- Release APK unsigned until signing secrets are added.
