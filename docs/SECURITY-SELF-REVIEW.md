# CM-Chat v0.1 — internal security self-review

> **This is an internal review, NOT an independent audit.** It was written by
> the same AI assistant that wrote most of the code, so it shares that author's
> blind spots. Nothing here certifies anything. CM-Chat has **not** had an
> independent security audit; do not rely on it where being discovered would
> put anyone at risk.

Branch `claude/can-you-see-it-0mggq7`, final batch (after build81). Wire
protocol v6.

## How the review was done

- Read the code paths that touch keys, the network, the disk and untrusted
  input: `crypto/`, `transport/` (SecureChannel, MessageService, HeldInbox,
  Outbox, Transport, FramePad), `vault/`, `tor/`, `media/`, `LifecycleController`,
  `guard/`, `diag/`, `notify/`, `MainActivity`.
- Ran the unit and loopback tests on the JVM. The loopback tests drive the real
  `MessageService` over real sockets on 127.0.0.1; only the Tor dial is swapped
  for a local connect.
- Ran a new fuzz/flood self-attack test (`SelfAttackTest`) and spot-checked
  it with mutations: weakening a defence (knock rate limit, per-friend rate
  limit, frame-size cap) makes the matching test fail.
- **Not covered:** Tor itself, the Android OS, real devices and OEM behaviour,
  side channels, keyboard apps, and the supply chain beyond the pinned
  dependency versions.

## What the app tries to resist

| Who | What they can do | What CM-Chat does |
|---|---|---|
| Network watcher (ISP, Wi-Fi owner) | Sees your traffic | Everything goes over Tor to onion services. Optional obfs4/Snowflake bridges hide that it is Tor. Frames are padded to size buckets (536 B … 48 KiB). |
| Stranger who has your CMC-ID | Can send friend requests or junk to your onion | Anonymous requests: 6 at once, then 1 per 20 s; at most 20 waiting. Junk is dropped before anything is stored. Frames are capped at 64 KiB before allocation; files at 100 MB, checked before reading. |
| A friend | Can send you anything over an authenticated channel | Per-friend rate limit (20 at once, 5/s). Every frame type tolerates junk. Received files are never opened by the app. |
| Someone who seizes a **locked** phone | Gets the app's files, maybe a RAM image | Vault encrypted with a key from Argon2id(PIN). Held messages sealed to your identity key. Messages are otherwise in RAM only. See findings 2–5 for what is left. |
| Someone who grabs an **unlocked, open** phone | Sees what's on screen | Mostly out of scope. Panic tools shorten the exposure: Exit, Cerberus idle wipe, Kill Timer, Shredder (reverse PIN), decoy chat. |

## How it works, briefly

- **Identity:** an X25519 key pair. Your CMC-ID = your onion address + your
  identity public key. The secret key and everything else you keep (friends,
  settings, onion key) live in one vault file, encrypted with a key derived
  by Argon2id (ops 2, 64 MiB, per-vault salt). Argon2 runs only when the PIN
  is entered or changed; the derived key stays in RAM while unlocked.
- **Sending:** the first frame is a `crypto_box` from your identity key that
  asks for a one-time prekey. The friend answers with a fresh X25519 prekey
  for this connection only. The message is sealed to that prekey, and the
  prekey is wiped after one use (forward secrecy). An authenticated receipt
  (OK / RETRY / REJECTED / HAVE) comes back. Your phone counts a message as
  delivered only on OK, which means stored or durably held.
- **Friend requests** are anonymous `crypto_box_seal` boxes to the
  recipient's identity key, with a receipt.
- **While locked or closed**, incoming frames are written to `held/`, each one
  sealed to your identity public key. They are replayed and shredded at the
  next unlock.

## Findings

Severity is for the stated threat model. Status: **Fixed** (this batch),
**Open** (needs work or an owner decision), **Accepted** (known and kept on
purpose).

| # | Severity | Finding | Status |
|---|---|---|---|
| 1 | **High** (real use) | **The published APKs are debug builds.** They are debuggable and screenshots are allowed (`FLAG_SECURE` is release-only). On an unlocked phone with USB debugging on, `adb run-as` can copy the app's files, and a debugger can attach to the running app and read keys from memory. Debug builds also keep a scrubbed crash file. | **Open.** Fine for testing. For real use, ship a signed **release** build (not debuggable, `FLAG_SECURE` on, no crash file). |
| 2 | **High** | **Short PINs fall to an offline attack.** The vault key is Argon2id(PIN, salt) only; it is not bound to the phone's hardware keystore. If the vault file is extracted (forensic tools, root, or finding 1), guesses can run off the phone and the in-app wrong-PIN delays don't apply. A 4–6 digit PIN won't last; a long passphrase (several random words, or 12+ random characters) will hold far longer. The app recommends 8+ characters but accepts 4. | **Open** (owner decision). Options: wrap the vault key with a non-exportable Android Keystore key (forces guessing on the phone; lost on factory reset), raise the minimum length, or warn more strongly. |
| 3 | Medium | **Copies of the identity secret held as text can't be zeroed.** Exit zeroes every key held as bytes and drops every reference (tested). But hex-string copies (decrypted vault data, `MessageService`, the channel) are immutable Kotlin `String`s: they stay in the heap until garbage collection reuses the memory. Exit stops Tor and the server but does not end the process. Ending it early is unsafe because the engine service is "sticky", so Android would restart it. | **Open.** Fix: keep the identity secret as a `ByteArray` end to end, then end the process once the engine has fully stopped. Real-world risk needs a RAM capture right after Exit (root, an exploit, or finding 1). |
| 4 | Medium | **Messages that arrive while locked or closed touch flash.** They are sealed to your identity key, which is inside the vault, and shredded after unlock. On flash storage, shredding is best effort: wear-levelling can keep old blocks, but those blocks only ever held ciphertext. Requested for reliability (A1). Cerberus, if armed, ends the closed-app listener and shreds what it held. | **Accepted** (by design). |
| 5 | Low–Medium | **Other traces on disk (plaintext):** (a) the `cover` file while cover mode is on, holding which calculator key opens the app; (b) Tor's working folder during a session: `torrc` with any custom bridge lines, and the cached consensus. It is wiped at Exit, teardown and Shredder, but stays after a crash or force-stop until the next start. (c) The debug-only crash file. (d) The app itself: package `org.cmchat.app`, launcher icon. | **Accepted.** Listed so nobody assumes "nothing on disk". |
| 6 | Medium | **The keyboard could learn what you type.** Text typed in CM-Chat could be learned by the system keyboard and kept outside the app. | **Fixed.** Every text field now asks for incognito input (`IME_FLAG_NO_PERSONALIZED_LEARNING`). **Needs a device test** (e.g. Gboard shows its incognito icon). Some third-party keyboards ignore the flag. |
| 7 | Low | **Friend-request names could hide behind invisible or right-to-left override characters**, so a name could display as something else. | **Fixed.** Names from other phones lose control and format characters and are capped at 24 characters. Tested. |
| 8 | Low | **Anyone with your CMC-ID can trigger the "update both apps" banner**, by sending an anonymous request with a different version byte. Annoyance only. | **Accepted.** The banner helps when two real versions differ. |
| 9 | Low | **Key-compromise impersonation.** Authentication uses static identity keys, so whoever steals your identity secret can pretend to be any of your friends *to you*, and be you to them. Past messages stay protected by the one-time prekeys. | **Accepted** (inherent to the design). |
| 10 | Low–Medium | **Flooding.** Anyone with your CMC-ID can keep the 8 connection slots busy (15 s read timeout each) or send junk. Caps keep RAM and disk bounded and the app working (tested), but a determined flood can delay real messages; they are retried, not lost. Tor's onion-service proof-of-work defence is not enabled. | **Partial.** Recommend turning on Tor PoW defences when the bundled Tor supports them through `ADD_ONION`. |
| 11 | Info | **The decoy burn signal is best effort.** It only reaches confirmed friends who are online. Nothing can un-send what a friend already saw or screenshotted. | By design (F1). |
| 12 | Info | **Files.** The app never opens a received file. "Save" hands the bytes to Android's file picker; after that they are outside CM-Chat's protection. Documents go as-is (the user is warned). Photo/video metadata removal is fail-closed for the formats parsed; HEIC/AVIF re-encoding needs a device test. | By design. |
| 13 | Info | **Clipboard.** Copying your CMC-ID, the fingerprint or a log puts it on the system clipboard, where keyboards and (on older Android) other apps can read it. | Documented. |
| 14 | Info | **Notifications** are generic ("Activity" / "Notification") and private on the lock screen, but their timing still shows activity. A Buzz, a friend request and a friend's decoy alert notify even while Invisible (Decision A). | By design. |
| 15 | Info | **Traffic analysis.** Tor hides addresses, not timing. Someone watching both ends (or a global observer) can correlate. Cover traffic is optional and basic. | Documented. |
| 16 | Info | **Supply chain.** Dependencies are pinned Maven versions; builds are not reproducible. The debug signing key is either a CI secret or ephemeral. | Documented. |
| 17 | Info | **Translations** (13 languages) were written by the AI assistant. Safety-critical wording (PIN, Shredder, decoy, files) should be checked by native speakers. | Documented. |

## What the tests verify

| Property | Where |
|---|---|
| Frame length checked before allocating (2 GB claims refused) | `SelfAttackTest.frame_reader_*` (mutation-checked) |
| Random, sealed-junk and friend-boxed-junk frames never open a session | `SelfAttackTest.first_frame_dispatch_*` |
| File pieces and receipts reject junk; held records survive forged files | `SelfAttackTest` |
| Photo/video cleaner never crashes on 4,000 mutated files and never grows the output | `SelfAttackTest.media_cleaner_*` |
| 400-connection garbage flood: nothing stored, real message still arrives | `SelfAttackTest.garbage_connection_flood_*` |
| Stranger request flood rate-limited; extras told to retry, not silently dropped | `SelfAttackTest.stranger_knock_flood_*` (mutation-checked) |
| Friend spam rate-limited; every message confirmed "stored" really is stored | `SelfAttackTest.a_spamming_friend_*` (mutation-checked) |
| Replayed connection bytes never store a message twice | `SelfAttackTest.replayed_*` |
| Junk inside every frame type doesn't break the app | `SelfAttackTest.junk_inside_every_frame_type_*` |
| Forward secrecy, replay guard, receipts, held inbox, decoy, nicknames, Team Clock, files | `ForwardSecrecyTest`, `ReplayGuardTest`, `SpineLoopbackTest`, `HeldInboxTest` |
| Vault crypto, Argon2id parameters unchanged, crash-safe saves | `VaultTest` |
| Hostile text is only ever data (no injection anywhere) | `OpaqueInputTest` |
| Every language has every string with the same placeholders | `TranslationsTest` |

**Needs a real phone** (no JVM test can show these): Tor bootstrap and onion
reachability, notifications and their icon, `FLAG_SECURE` on a release build,
keyboard incognito, cover mode, QR camera, file picker / Save, HEIC
re-encoding, soft reconnect after "No service", battery optimisation and OEM
process killing. See `docs/TWO-PHONE-TEST.md`.

## Recommended next steps, most important first

1. Use a **release** build for anything real (finding 1).
2. Use a **long passphrase**, and decide on Keystore-wrapping the vault key
   (finding 2).
3. Keep the identity secret as bytes only, and end the process after Exit once
   the engine has stopped (finding 3).
4. Get an **independent** security audit before any high-risk use.
5. Turn on Tor onion-service PoW defences when available (finding 10).
