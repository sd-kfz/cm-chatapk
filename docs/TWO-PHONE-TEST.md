# CM-Chat — two-phone test checklist

For the final batch (everything after build81). Use two phones, **A** and
**B**. Make B the Motorola if you can (it had the "can't receive" problem and
the small screen). Tick each line. If something fails, write down what you
saw and the time, then copy Settings → Diagnostics → Connection test → *Copy
log* from **both** phones.

**Before you start**

- [ ] Install `CM-Chat-arm64-debug.apk` from the newest release on both phones
      (the arm32 file is only for old 32-bit phones).
- [ ] Allow notifications. Settings → Privacy & Safety → *Keep engine running
      in background* → allow.
- [ ] If you can, put the phones on different networks (one Wi-Fi, one mobile
      data).
- [ ] Debug builds can be screenshotted. That is expected; release builds
      can't.

---

## 1. First start, PIN, language (H, L)

| ✓ | Do | Expect |
|---|---|---|
| [ ] | Fresh install on a phone whose language is e.g. German | Lock screen and onboarding appear in German without picking anything |
| [ ] | Create a PIN; tap the eye on the lock screen | Characters show/hide; long PINs wrap onto new lines, no "+N" counter |
| [ ] | Settings → Language → pick another language | The whole app switches **immediately** (no restart): Settings, Friends, Chat, Help |
| [ ] | Exit, reopen, unlock | The app is in the language you picked |
| [ ] | Try 3–4 languages, look at the main screens | No English left on normal screens (log contents stay English on purpose) |
| [ ] | Type in a chat with Gboard | The keyboard shows its incognito icon (it doesn't learn your words) |

## 2. Add a friend (B1, B2, B4, I1)

| ✓ | Do | Expect |
|---|---|---|
| [ ] | B: Settings → My identity → *Share*, then *Copy* | Share sheet opens; "CMC-ID copied" toast |
| [ ] | A: + → Add friend → *Scan their QR* on B's screen | "✓ Scanned"; nickname field is optional |
| [ ] | A: leave the nickname empty, tap Add friend | A shows **"New Friend"** with *Pending* |
| [ ] | B is set to **Invisible**, or B's app is swiped away | B still gets a notification and the "Friend request from …" card after unlocking |
| [ ] | B accepts | A's row changes from "New Friend" to **B's own nickname** |
| [ ] | A: rename B in the chat header | A's own label wins over B's nickname |
| [ ] | A: add a third CMC-ID that never answers, tap the pending row | Choice of *Cancel request* / *Remove* / *Keep waiting*; both remove it |

## 3. Messages and delivery (A1–A4) — the Motorola test

| ✓ | Do | Expect |
|---|---|---|
| [ ] | Chat both ways | Messages arrive in seconds; each shows its time (4:05 PM style) |
| [ ] | B: swipe CM-Chat away (Buzz listener on). A sends 3 messages | A's messages are **not** lost |
| [ ] | B: open and unlock, go Online | The 3 messages show, marked **"Missed Message"** |
| [ ] | B: minimise (locked), A sends | Same: held, shown after unlock |
| [ ] | B: airplane mode for ~20 s, then off | Engine comes back by itself and keeps its address; A's next message arrives without B "republishing" |
| [ ] | A: My Server → *Request new address* while B is offline; later B comes online | B gets A's new address and can still reach A |
| [ ] | A: lock the app during the address change | The new address is still there after unlock (it isn't lost) |

## 4. Invisible, Buzz, notifications (A5, C3, C4, D1, D2)

| ✓ | Do | Expect |
|---|---|---|
| [ ] | B Invisible; A sends a message | No message notification on B; it waits as "Missed Message" |
| [ ] | A Buzzes B (long-press, or ⚡ Buzz) | B gets a status-bar notification with the **flower** icon |
| [ ] | A just after sending a Buzz | Button is grey "⚡ Buzz", **no countdown**, re-enables later |
| [ ] | Look at the status bar on both phones | One quiet engine notification with the flower icon; **no** "Active"/Bluetooth notification |
| [ ] | Settings → Chats → Accept Buzz → *Once only* | A second Buzz from the same friend is not accepted until you message them |

## 5. Chat screen (B3, C1, C2, L6)

| ✓ | Do | Expect |
|---|---|---|
| [ ] | Look at the bar above the message box | A fixed 🔥 on the left; only the chips scroll |
| [ ] | Top pills | Timer pill shows 🔥, Kill pill shows a power symbol |
| [ ] | Open the keyboard on the small phone | The timer/Cerberus pill strip stays visible |
| [ ] | Send a *Single Message (view once)*; B reads it and leaves | Gone on B |
| [ ] | Red X → *Wipe conversation* → confirm | Chat erased on both phones |
| [ ] | Red X → *Delete friend* → confirm | The friend disappears from the list entirely |

## 6. Files (E1–E5)

| ✓ | Do | Expect |
|---|---|---|
| [ ] | Paperclip (left of the box) → a camera photo with location on | Note says location/camera data removed; B receives it |
| [ ] | B: *Save*, then check the saved photo in an EXIF viewer | **No GPS**, no camera model; picture not sideways |
| [ ] | Send a short phone video | "Video: location and camera data removed" or a clear refusal |
| [ ] | Send a PDF | Warning that documents go as they are |
| [ ] | Try a file over 100 MB | Refused on the sender with the 100 MB message; nothing sent |
| [ ] | B locked while A sends a file | A keeps retrying; the file arrives after B unlocks |
| [ ] | B receives an `.apk` or `.html` | Warning shown; nothing opens by itself |

## 7. Decoy (F1)

| ✓ | Do | Expect |
|---|---|---|
| [ ] | A: enable the decoy chat; B online; A taps the decoy row | A's chats wiped, app locked, A moved to a new address |
| [ ] | On B | B gets the decoy alert (even if Invisible); the chat with A is wiped and only "Decoy chat triggered — chat erased." remains, gone after leaving it |
| [ ] | Repeat with B **offline** | Nothing reaches B (best effort); A's log says "no confirmed friends …" or "sent to N" |

## 8. Settings, PINs, safety (G1–G7)

| ✓ | Do | Expect |
|---|---|---|
| [ ] | Open Settings | Language → groups (Privacy & Safety) → System → Tools; switches say **Yes/No** |
| [ ] | Set a Privacy PIN | **Number pad** only |
| [ ] | Lock and reopen Privacy & Safety | Diagnostics, Connection test, RAM diagnostics are behind the PIN |
| [ ] | *Remove Privacy PIN* | The group opens without a PIN afterwards |
| [ ] | No "Let a Buzz reach me when closed" setting | Gone (always on); only *Accept Buzz* |
| [ ] | Words on screen | "nickname" (not "face"), "+Add" / "Add friend" (not "Knock") |
| [ ] | Cerberus 15 min; leave the app untouched | App wipes RAM and closes; vault and friends are still there after unlock |
| [ ] | Kill Timer 2 minutes ahead | Same at that time |
| [ ] | Exit (red power) | Engine notification disappears; reopening needs the PIN |
| [ ] | **Test install only:** type the PIN backwards | Everything erased; "Error. Please restart the app." under "Welcome back" |

## 9. Team Clock (J1, J2)

| ✓ | Do | Expect |
|---|---|---|
| [ ] | A: Set Team Clock | Alarm-style picker, **no UTC/timezone** text; B shows the same time |
| [ ] | Restart **both** phones, unlock | The clock is still set on both |
| [ ] | B locked; A changes the clock twice | After unlock B shows the **newest** one |

## 10. Calculator (K1, K2, K3)

| ✓ | Do | Expect |
|---|---|---|
| [ ] | Calculator tool on the Motorola | Every button fits, nothing cut off |
| [ ] | Settings → Open as a calculator → Yes; reopen the app | A working calculator; small italic "tap 10 times" |
| [ ] | Tap one key 10 times in a row | The real app (lock screen) opens |
| [ ] | Tap "reset" 20 times, then a different key 10 times | The new key now opens the app |
| [ ] | Twin calculator app (if included in this release) | Looks like a calculator; the secret sequence opens CM-Chat |

## 11. Look and smoothness (L, M)

| ✓ | Do | Expect |
|---|---|---|
| [ ] | Friends screen | Bigger wordmark; "● Online / ● Invisible" with a coloured dot; all rows (including "Notes to self") the same height |
| [ ] | Scroll Settings fast; type in a chat with a disappearing timer running | Smooth; no stutter every second |
| [ ] | Lock screen logo | Glow animates; stops when the app is in the background |

---

**When done:** note phone models and Android versions, which lines failed,
and attach both Connection logs.
