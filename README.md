# CM-Chat (v0.1)

A private, peer-to-peer 1:1 messenger for Android. Same category as Briar and
Cwtch: no server, no cloud, no accounts. All networking goes through **Tor**;
each phone runs its own **onion service** and *is* its own server. Messages,
files and statuses live in **RAM only** — the single thing written to disk is
the encrypted vault (your identities, Circle and settings).

> ⚠️ **v0.1 is unaudited test software.** It has not had a security review and
> is meant for trying things out with friends, **not** for real-life safety or
> high-risk use. Don't rely on it to protect anyone yet.

## Install

Grab the **debug APK** from GitHub Actions (installs on any phone, no signing
setup): open the latest green run under the repo's **Actions** tab and download
the `cm-chat-debug-apk` artifact, unzip, and install `app-debug.apk`
(you'll need "install unknown apps" enabled for your file manager/browser).

In the app drawer it shows as **СM-Chat** (a discreet label); the app itself is
CM-Chat.

## Test with a friend

Both people:

1. Install the same APK and open it.
2. Create a **6-digit PIN** (not a palindrome) and a **Face** (display name).
3. Wait for the Tor indicator on the Circle screen to say **Online**. The
   first launch can take **1–3 minutes** while Tor bootstraps and your onion
   service publishes. (Settings → My Server shows the detailed steps and your
   onion address; Settings → Diagnostics shows a live log if something stalls.)
4. Share your **CMC-ID**: Settings → **My CMC-ID** shows it as text and a QR,
   with Copy/Share.
5. One person taps **+ Knock**, pastes or scans the other's CMC-ID, picks a
   nickname, and sends. The other sees the incoming knock and taps **Accept**.
6. You're now in each other's Circle — tap a contact and chat.

**Both must be online at the same time.** There is no server holding messages
for you (no mailbox), so if your friend's phone is off, your message stays in
your own chat as an "Offline. Retry?" bubble until they're back.

## Two-phone dry run (full feature check)

Phone A and Phone B, both installed, PIN set, Face created, green dot showing:

1. **A:** Settings → My CMC-ID → show the QR. **B:** + Knock → scan A's QR → name
   it → Send. **A:** Accept the knock.
2. **Text both ways** — check messages show sent → delivered.
3. **Offline path:** Settings → My Server → **Stop** on A, then send from B.
   B's message shows the red **"Offline. Retry?"** bubble (once/30s retry).
4. **A:** My Server → **Start/Restart**, then B taps retry — it delivers.
5. **Erase** from one side — the chat clears on **both**.
6. **Self-timer:** set 30s on a message; it disappears on both sides ~30s
   after it's seen.
7. **Status:** change your status word; your friend sees it (you never see your
   own).
8. **Reverse-PIN wipe on B:** enter B's PIN backwards — B silently wipes back
   to first-run; **A keeps working**.

## Guardians & panic

- **Cerberus:** if the app is untouched for 90 or 180 minutes (Settings), it
  silently wipes RAM state, stops Tor, and closes; the vault stays, so the next
  open just needs your PIN. Reopening the app resets the timer.
- **Kill Timer:** a manual countdown (up to 24h) that does the same. Whichever
  of Cerberus/Kill fires first wins.
- **Wipe Everything Now:** Settings → confirm → deletes all app data, then asks
  Android to uninstall (one tap).
- **Reverse-PIN:** entering your PIN backwards is a silent data wipe back to
  first-run (no uninstall prompt).

## Privacy properties (design intent)

- No server/relay/cloud, no analytics, no crash reporting, no Google Play
  Services/Firebase, no per-install or advertising IDs.
- All traffic over Tor; the app opens no direct internet socket.
- `allowBackup=false`, backups/data-transfer excluded, `FLAG_SECURE` on every
  window (no screenshots, blank in recents). Release logs stripped by R8.
- Only the encrypted vault touches disk (Argon2id-derived key, libsodium
  secretbox). Messages/files/statuses are RAM-only.

## Build it yourself

Needs the Android SDK + JDK 17/21. From the repo root:

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. CI (GitHub Actions)
runs the unit tests and builds debug + release on every push to `main`.

See `PROGRESS.md` for build status, decisions, and what still needs on-device
testing or a maintainer action (e.g. release-signing secrets).
