package org.cmchat.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cmchat.app.chat.ChatStore
import org.cmchat.app.crypto.CmId
import org.cmchat.app.tor.ServerController
import org.cmchat.app.tor.TorService
import org.cmchat.app.tor.TorStatus
import org.cmchat.app.transport.MessageService
import org.cmchat.app.ui.screens.ChatScreen
import org.cmchat.app.ui.screens.FriendsScreen
import org.cmchat.app.ui.screens.Contact
import org.cmchat.app.ui.screens.KnockScreen
import org.cmchat.app.ui.screens.LockScreen
import org.cmchat.app.ui.screens.MyIdScreen
import org.cmchat.app.ui.screens.MyServerScreen
import org.cmchat.app.ui.screens.SettingsScreen
import org.cmchat.app.ui.theme.CmGreen
import org.cmchat.app.vault.SecurityFactory
import org.cmchat.app.vault.VaultData

/** Sentinel id for the decoy chat row (never a real contact). */
private const val DECOY_CM_ID = "__decoy__"

/**
 * Unwrap the Activity from a Compose LocalContext. LocalContext.current is often
 * a ContextWrapper (ContextThemeWrapper), so a direct `as? Activity` cast fails
 * and silently no-ops — which is why Minimise/Exit didn't work. Walk the base
 * context chain to find the real Activity.
 */
private tailrec fun findActivity(c: android.content.Context?): android.app.Activity? = when (c) {
    is android.app.Activity -> c
    is android.content.ContextWrapper -> findActivity(c.baseContext)
    else -> null
}

private sealed class Nav {
    object Lock : Nav()
    object Friends : Nav()
    data class Chat(val name: String, val cmId: String?) : Nav()
    object Settings : Nav()
    object MyServer : Nav()
    object MyId : Nav()
    object Bridges : Nav()
    object Connection : Nav()
    object Onboarding : Nav()
    object Help : Nav()
    object Knock : Nav()
    object Diagnostics : Nav()
    object About : Nav()
    object Language : Nav()
    object RamDiag : Nav()
    object Integrity : Nav()
    data class Tool(val which: String) : Nav()
}

/**
 * Open Android's own "Uninstall this app?" prompt — the last step of Wipe
 * Everything. Needs REQUEST_DELETE_PACKAGES in the manifest: since Android 9
 * the prompt silently refuses without it. Started from the Activity when we
 * have one; falls back to the app-info page (with its Uninstall button) if no
 * uninstaller answers.
 */
private fun requestUninstall(context: android.content.Context) {
    val uri = Uri.fromParts("package", context.packageName, null)
    val activity = findActivity(context)
    @Suppress("DEPRECATION")
    val attempts = listOf(
        Intent(Intent.ACTION_DELETE, uri),
        Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri),
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri),
    )
    for (i in attempts) {
        val ok = runCatching {
            if (activity != null) activity.startActivity(i)
            else context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (ok) return
    }
}

/**
 * The name shown for a friend: MY private label for them if I gave one;
 * otherwise "New Friend" until they've accepted, then THEIR own nickname.
 */
private fun shownName(c: org.cmchat.app.vault.ContactRec): String =
    c.name.ifBlank { if (c.pending) "New Friend" else c.theirName?.takeIf { it.isNotBlank() } ?: "New Friend" }

private fun myCmId(data: VaultData?): String? {
    val face = data?.faces?.firstOrNull() ?: return null
    val onion = face.onionAddress ?: return null
    return CmId.encode(onion, face.publicKey)
}

/**
 * Root: applies the app-wide text size (Settings → Text size) to EVERY screen
 * and dialog by scaling the font scale in LocalDensity, then runs the nav.
 */
@Composable
fun AppNav() {
    val step by org.cmchat.app.settings.AppSettings.textSize.collectAsState()
    val base = androidx.compose.ui.platform.LocalDensity.current
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
            base.density, base.fontScale * org.cmchat.app.settings.AppSettings.textScale(step)),
    ) { AppNavContent() }
}

@Composable
private fun AppNavContent() {
    val context = LocalContext.current
    val manager = remember { SecurityFactory.create(context.filesDir) }
    val scope = rememberCoroutineScope()

    var nav by remember { mutableStateOf<Nav>(Nav.Lock) }
    var data by remember { mutableStateOf<VaultData?>(null) }
    // Cover mode: the app opens to a working calculator (the same key 10× gets in).
    var coverOn by remember { mutableStateOf(org.cmchat.app.tools.CoverMode.isOn(context)) }
    var coverPassed by remember { mutableStateOf(false) }

    val shredEpoch by org.cmchat.app.vault.Shredder.epoch.collectAsState()
    var showWipeConfirm by remember { mutableStateOf(false) }
    // Exit wipes RAM — Notes included. If there are notes, confirm first so one
    // accidental tap can't erase them. Holds the exit action to run on "Exit".
    var confirmExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun guardedExit(doExit: () -> Unit) {
        if (org.cmchat.app.tools.ToolsState.hasContent()) confirmExit = doExit else doExit()
    }
    // Android 13+ only shows notifications (Buzz "Activity", new-message
    // "Notification") if the app asks for POST_NOTIFICATIONS at runtime — the
    // system never asks for a targetSdk-33+ app. Ask ONCE per run, after unlock;
    // never nag (Android itself stops showing it after two denials).
    var askedNotif by remember { mutableStateOf(false) }
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(nav) {
        if (nav == Nav.Friends && !askedNotif && android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askedNotif = true
            runCatching { notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    var showReviewSettings by remember { mutableStateOf(false) }

    // Vault saves come from the UI AND from network threads (a friend accepted,
    // moved, terminated…): serialize them so one can never overwrite another.
    // Written in the background with the session's cached vault key (Argon2id
    // ran once, at unlock). Returns false when locked — the caller's change waits.
    val saveLock = remember { Any() }
    fun saveVault(transform: (VaultData) -> VaultData): Boolean {
        synchronized(saveLock) {
            val cur = data ?: return false
            val updated = transform(cur)
            if (updated != cur) {
                if (!org.cmchat.app.vault.VaultIO.save(manager, updated)) return false
                data = updated
            }
        }
        return true
    }
    // What friends changed (acceptance, new address, Team Clock, terminate,
    // last seen) is buffered in PendingVaultEdits and written in one save — at
    // once if unlocked, else right after the next unlock.
    fun flushEdits() {
        if (org.cmchat.app.vault.PendingVaultEdits.isEmpty()) return
        saveVault {
            org.cmchat.app.vault.PendingVaultEdits.drainInto(it, MessageService::currentId, System.currentTimeMillis())
        }
    }
    // Removing friends (the chat's X menu / cancelling a pending add).
    fun removeContact(d: VaultData, cmId: String?, name: String): VaultData =
        d.copy(contacts = d.contacts.filterNot { if (cmId != null) it.cmId == cmId else it.cmId == null && shownName(it) == name })
    fun deleteFriend(cmId: String?, name: String) {
        cmId?.let { MessageService.deleteFriend(it) }
        saveVault { removeContact(it, cmId, name) }
    }
    fun cancelPendingAdd(cmId: String?, name: String) {
        cmId?.let { MessageService.cancelPending(it) }
        saveVault { removeContact(it, cmId, name) }
    }
    // My onion key/address changed (first publish, rotation). Saved in the vault
    // when it's unlocked; otherwise sealed aside for the next unlock — a key that
    // is lost means a NEW address next start, and friends could never reach me.
    fun keepMyOnion(face: org.cmchat.app.vault.Face, pub: org.cmchat.app.tor.OnionPublish) {
        val key = pub.newPrivateKey ?: face.onionKey ?: return
        val saved = saveVault { cur ->
            cur.copy(faces = cur.faces.map {
                if (it.id == face.id) it.copy(onionKey = key, onionAddress = pub.onion) else it
            })
        }
        if (!saved) {
            val ok = org.cmchat.app.vault.OwnOnionStash.put(context.filesDir, manager.crypto, face.publicKey, key, pub.onion)
            org.cmchat.app.diag.ConnDiag.sys(if (ok) "My server: new address kept sealed until you unlock"
                else "My server: new address could NOT be kept — it may change after a restart")
        }
    }
    // At unlock: a key that was made while locked goes into the vault now.
    fun withStashedOnion(d: VaultData): VaultData {
        val face = d.faces.firstOrNull() ?: return d
        val dir = context.filesDir
        val st = org.cmchat.app.vault.OwnOnionStash.read(dir, manager.crypto, face.publicKey, face.secretKey) ?: return d
        if (face.onionKey == st.key && face.onionAddress == st.onion) {
            org.cmchat.app.vault.OwnOnionStash.clear(dir); return d
        }
        val updated = d.copy(faces = d.faces.map {
            if (it.id == face.id) it.copy(onionKey = st.key, onionAddress = st.onion) else it
        })
        if (org.cmchat.app.vault.VaultIO.save(manager, updated)) {
            scope.launch(Dispatchers.IO) {
                // Shredded only once the vault on disk has it.
                if (runCatching { manager.flush() }.isSuccess) org.cmchat.app.vault.OwnOnionStash.clear(dir)
            }
            org.cmchat.app.diag.ConnDiag.sys("My server: the address made while locked is now saved")
        }
        return updated
    }

    // Surface a crash from a previous run (debug-phase aid), then delete it.
    LaunchedEffect(Unit) {
        org.cmchat.app.diag.CrashCatcher.consume(context)?.let {
            org.cmchat.app.diag.Diag.e("crash", "previous run crashed:\n$it")
        }
    }

    // Re-lock on background / Exit: the vault key leaves RAM (right after any
    // queued save is written) and the decrypted vault leaves the UI.
    LaunchedEffect(Unit) {
        // A recreated screen starts locked: never leave an earlier unlock's key behind.
        if (data == null) { MessageService.closeVault(); manager.lock() }
        org.cmchat.app.LifecycleController.lockRequests.collect {
            MessageService.closeVault()   // what arrives from now on is held until unlock
            manager.lock()
            if (nav != Nav.Lock) {
                data = null
                nav = Nav.Lock
            }
        }
    }

    // Settings the app reads live (general timer, Buzz, decoy, tools) are saved
    // the moment they change, and were restored at unlock: none of them may
    // silently reset on a restart.
    val loggedIn = data != null
    LaunchedEffect(loggedIn) {
        if (!loggedIn) return@LaunchedEffect
        org.cmchat.app.settings.AppSettings.savedChoices.collect {
            saveVault { cur -> cur.copy(settings = org.cmchat.app.settings.AppSettings.applyTo(cur.settings)) }
        }
    }

    if (showReviewSettings) {
        AlertDialog(
            onDismissRequest = { showReviewSettings = false },
            title = { Text("Welcome") },
            text = { Text("Please take your time to review the Settings page before you start.") },
            confirmButton = {
                TextButton(onClick = { showReviewSettings = false; nav = Nav.Settings }) {
                    Text("Ok, take me to Settings.")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReviewSettings = false }) { Text("I'll do it later.") }
            },
        )
    }

    confirmExit?.let { doExit ->
        AlertDialog(
            onDismissRequest = { confirmExit = null },
            title = { Text("Exit and erase your Notes?") },
            text = { Text("Exit clears everything from memory — your Notes scratchpad and checklist will be gone.") },
            confirmButton = { TextButton(onClick = { confirmExit = null; doExit() }) { Text("Exit") } },
            dismissButton = { TextButton(onClick = { confirmExit = null }) { Text("Cancel") } },
        )
    }

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text("Wipe everything?") },
            text = { Text("Shreds ALL app data on this phone (vault, keys, friends, settings, Tor " +
                "cache) and then opens Android's uninstall prompt to remove the app itself.") },
            confirmButton = {
                TextButton(onClick = {
                    showWipeConfirm = false
                    // Log out first so nothing decrypted stays on screen or in RAM.
                    MessageService.closeVault(); manager.lock(); data = null; nav = Nav.Lock
                    scope.launch {
                        try {
                            // 1) stop the engine so nothing is still writing files…
                            runCatching { org.cmchat.app.transport.CoverTraffic.stop() }
                            runCatching { ServerController.stop() }
                            runCatching { TorService.stop(context) }
                            // 2) …wipe RAM…
                            runCatching { ChatStore.clearAll() }
                            runCatching { org.cmchat.app.tools.ToolsState.clear() }
                            runCatching { org.cmchat.app.buzz.BuzzPolicy.clear() }
                            runCatching { org.cmchat.app.notify.Notifier.clearAll(context) }
                            runCatching { MessageService.zeroKeys() }
                            runCatching { org.cmchat.app.diag.Diag.clear() }
                            // 3) …shred EVERY file the app owns (vault, salt, prefs,
                            //    caches, Tor's working dir, crash file)…
                            withContext(Dispatchers.IO) {
                                kotlinx.coroutines.delay(800)   // let Tor finish shutting down
                                runCatching { org.cmchat.app.diag.CrashCatcher.delete(context) }
                                runCatching { org.cmchat.app.vault.Shredder.shredAll(context) }
                            }
                        } finally {
                            // 4) …and ALWAYS end by asking Android to uninstall the app
                            //    (even if a step above failed). An app can't remove
                            //    itself silently; this opens the system prompt.
                            requestUninstall(context)
                        }
                    }
                }) { Text("Wipe") }
            },
            dismissButton = { TextButton(onClick = { showWipeConfirm = false }) { Text("Cancel") } },
        )
    }

    // Keep the message service configured with the active Face + contacts.
    LaunchedEffect(data) {
        val d = data ?: return@LaunchedEffect
        val face = d.faces.firstOrNull() ?: return@LaunchedEffect
        MessageService.configure(
            crypto = manager.crypto,
            myDisplayName = face.name,
            myIdentityPubHex = face.publicKey,
            myIdentitySecHex = face.secretKey,
            myCmId = myCmId(d),
            knownContactCmIds = d.contacts.mapNotNull { it.cmId },
            contactNames = d.contacts.mapNotNull { c -> c.cmId?.let { it to shownName(c) } }.toMap(),
            pendingCmIds = d.contacts.filter { it.pending }.mapNotNull { it.cmId },
            pendingTerminations = d.terminations.map { it.cmId },
            confirmedAddresses = d.contacts.mapNotNull { c -> c.cmId?.let { id -> c.addrConfirmed?.let { id to it } } }.toMap(),
            confirmedNames = d.contacts.mapNotNull { c -> c.cmId?.let { id -> c.nameConfirmed?.let { id to it } } }.toMap(),
            teamClockTimes = d.contacts.mapNotNull { c -> c.cmId?.let { it to c.teamHourAt } }.toMap(),
            unsyncedTeamClocks = d.contacts.filter { !it.teamHourSynced && it.teamHourAt > 0 }
                .mapNotNull { c -> c.cmId?.let { it to ((c.teamHour ?: "") to c.teamHourAt) } }.toMap(),
        )
        val edits = org.cmchat.app.vault.PendingVaultEdits
        // A friend I added proved they accepted me → no longer pending.
        MessageService.onFriendConfirmed = { cmId -> edits.confirmed(cmId); flushEdits() }
        // A contact rotated their onion: update their stored cmId in the vault.
        MessageService.onContactAddressUpdated = { oldCmId, newCmId ->
            edits.relinked(oldCmId, newCmId); flushEdits()
            // Their chat is open right now: follow them to the new address.
            (nav as? Nav.Chat)?.takeIf { it.cmId == oldCmId }?.let { nav = it.copy(cmId = newCmId) }
        }
        // A friend set / turned off this chat's Team Clock: persist it per friend.
        MessageService.onTeamClockChanged = { cmId, value, at -> edits.teamClockSet(cmId, value, at); flushEdits() }
        // MY Team Clock change reached them: no need to send it again after a restart.
        MessageService.onTeamClockSynced = { cmId, at -> edits.teamClockSynced(cmId, at); flushEdits() }
        // A friend's OWN nickname (their acceptance, or they changed it).
        MessageService.onFriendName = { cmId, name -> edits.theirName(cmId, name); flushEdits() }
        MessageService.onNameConfirmed = { cmId, name -> edits.nameConfirmed(cmId, name); flushEdits() }
        // A friend TERMINATED: they removed me, so they go from my list too.
        MessageService.onFriendTerminated = { cmId ->
            edits.removedByFriend(cmId); flushEdits()
            (nav as? Nav.Chat)?.takeIf { it.cmId == cmId }?.let { nav = Nav.Friends }
        }
        // My TERMINATE reached them: stop retrying it.
        MessageService.onTerminationDelivered = { cmId -> edits.terminationDelivered(cmId); flushEdits() }
        // A friend was active: keep a coarse "last seen" (survives restarts).
        MessageService.onPeerSeen = { cmId, at -> edits.seen(cmId, at); flushEdits() }
        // A friend's phone confirmed my current address: stop re-sending it.
        MessageService.onAddressConfirmed = { cmId, mine -> edits.addressConfirmed(cmId, mine); flushEdits() }
        // Held frames are shredded only after what they changed is on disk.
        MessageService.flushVault = { manager.flush() }
        // Anything friends changed while the app was locked lands now.
        flushEdits()
        // Persist an accepted knock as a contact in the vault.
        MessageService.onContactAccepted = { req ->
            saveVault { cur ->
                if (cur.contacts.any { it.cmId == req.cmId }) {
                    // We had knocked them too: accepting their knock settles it.
                    cur.copy(contacts = cur.contacts.map { if (it.cmId == req.cmId) it.copy(pending = false) else it })
                } else {
                    // No private label yet: their OWN nickname (from the request) shows.
                    cur.copy(contacts = cur.contacts + org.cmchat.app.vault.ContactRec(
                        id = manager.crypto.randomHex(8),
                        name = "",
                        theirName = req.displayName,
                        colorArgb = 0xFF6FB8D9,
                        faceId = face.id,
                        cmId = req.cmId,
                    ))
                }
            }
        }
        // Everything is wired: deliver what was held while locked (in order), then
        // new frames go straight into the chats.
        MessageService.openVault()
    }

    // Once Tor is ONLINE, publish my onion service. The server stays up even
    // while Invisible — messages still arrive but are held as "missed" (the
    // receipt says only "stored", so Invisible is indistinguishable to a sender).
    // Tor's status is collected HERE, not read by the whole screen: a progress
    // tick (5 %, 10 %, …) no longer redraws whatever page is open.
    LaunchedEffect(data) {
        val d = data ?: return@LaunchedEffect
        val face = d.faces.firstOrNull() ?: return@LaunchedEffect
        TorService.status.collect { st ->
            // (Stopped on My Server = stays down: ServerController.start refuses.)
            if (st is TorStatus.Online) {
                ServerController.start(face.name, face.onionKey, face.onionAddress) { pub ->
                    val keyChanged = pub.newPrivateKey != null && pub.newPrivateKey != face.onionKey
                    val addrChanged = face.onionAddress != pub.onion
                    if (keyChanged || addrChanged) keepMyOnion(face, pub)
                }
            }
        }
    }

    when (val n = nav) {
        // A fresh lock screen after the Shredder's error is cleared (the app was
        // closed and reopened) — it then starts clean, never stuck.
        Nav.Lock -> if (coverOn && !coverPassed) {
            org.cmchat.app.ui.screens.CalculatorCover(onOpen = { coverPassed = true })
        } else key(shredEpoch) { LockScreen(manager) { opened ->
            coverPassed = false   // the next lock shows the calculator again
            // A new address made while the app was locked goes into the vault
            // FIRST — before the server starts with an older key.
            val unlocked = withStashedOnion(opened)
            // Saved choices back into the live settings BEFORE anything reads them
            // (and before the settings mirror above starts saving).
            org.cmchat.app.settings.AppSettings.restoreFrom(unlocked.settings)
            ServerController.stoppedByUser.value = unlocked.settings.serverStopped
            data = unlocked
            // Presence is NOT set here: Invisible is the state at every STARTUP
            // (fresh process, after Exit / close), so a re-unlock after minimising
            // keeps Online if you were Online.
            // Session window (item 7): load the saved choice and stamp this unlock.
            org.cmchat.app.settings.AppSettings.sessionWindowEnabled.value = unlocked.settings.sessionWindow
            org.cmchat.app.settings.Languages.selected.value = unlocked.settings.language
            org.cmchat.app.settings.AppSettings.lastUnlockMs = System.currentTimeMillis()
            // Load bridge config BEFORE starting Tor so it's in the torrc at launch.
            org.cmchat.app.tor.Bridges.configure(unlocked.settings.bridgeMode, unlocked.settings.bridgeLines)
            org.cmchat.app.transport.CoverTraffic.setEnabled(unlocked.settings.coverTraffic)
            org.cmchat.app.settings.AppSettings.textSize.value = unlocked.settings.textSize
            TorService.start(context)
            org.cmchat.app.guard.GuardController.init(context)
            org.cmchat.app.guard.GuardController.setCerberusMinutes(unlocked.settings.cerberusMinutes)
            org.cmchat.app.guard.GuardController.setCerberusArmed(
                unlocked.settings.cerberusArmed && !org.cmchat.app.settings.AppSettings.stayReachable.value)
            // Onboarding until it's finished: a minimise (re-lock) resumes it.
            nav = if (!unlocked.settings.onboardingSeen) Nav.Onboarding else Nav.Friends
        } }
        Nav.Friends -> {
            val threads by ChatStore.threads.collectAsState()
            val real = data?.contacts?.takeIf { it.isNotEmpty() }
                ?.map {
                    val t = it.cmId?.let { id -> threads[id] }
                    Contact(shownName(it), Color(it.colorArgb),
                        unread = t?.unread ?: false,
                        cmId = it.cmId,
                        // RAM (this run) or the vault's coarse copy (survives restarts).
                        lastSeenMs = listOfNotNull(t?.peerLastSeen, it.lastSeenAt).maxOrNull(),
                        missed = t?.messages?.any { m -> m.missed || m.closedMiss } == true,
                        buzzed = t?.buzzed ?: false,
                        pending = it.pending)
                }
                ?: emptyList()   // real empty state (no fake sample contacts)
            // Decoy chat: a fake contact; tapping it = this phone may be compromised.
            val decoyOn by org.cmchat.app.settings.AppSettings.decoyEnabled.collectAsState()
            val decoyName by org.cmchat.app.settings.AppSettings.decoyName.collectAsState()
            val decoyTop by org.cmchat.app.settings.AppSettings.decoyAtTop.collectAsState()
            val contacts = if (decoyOn) {
                // Looks like every other friend (same colour, same row) — no tell.
                val decoy = Contact(decoyName, Color(0xFF6FB8D9), unread = false, cmId = DECOY_CM_ID)
                if (decoyTop) listOf(decoy) + real else real + decoy
            } else real
            FriendsScreen(
                contacts = contacts,
                onOpenChat = {
                    if (it.cmId == DECOY_CM_ID) {
                        // DECOY TRIPPED. (1) Instantly wipe MY side from RAM and send
                        // every friend a "Decoy chat triggered — chat erased." alert —
                        // their copy is erased once they leave it. (2) Rotate to a new
                        // onion address (saved with the unlock material captured here;
                        // the signed address update goes to friends). (3) Silently log
                        // out to the lock screen and leave the app.
                        val cur = data
                        // Everything locks NOW; the vault key is kept for exactly ONE
                        // late save (the new address), then wiped (or after 90 s).
                        val late = if (ServerController.stoppedByUser.value) null
                            else org.cmchat.app.vault.VaultIO.holdForLateSave(manager)
                        MessageService.tripDecoy()
                        if (late != null && cur != null) {
                            ServerController.requestNewAddress(urgent = true) { pub ->
                                val me = cur.faces.firstOrNull() ?: return@requestNewAddress late.release()
                                val updated = cur.copy(faces = cur.faces.map { f ->
                                    if (f.id == me.id) f.copy(onionKey = pub.newPrivateKey ?: f.onionKey,
                                        onionAddress = pub.onion) else f
                                })
                                // The late save below can time out on a slow Tor: the
                                // sealed copy makes sure the next unlock still gets it.
                                pub.newPrivateKey?.let { k ->
                                    org.cmchat.app.vault.OwnOnionStash.put(context.filesDir, manager.crypto,
                                        me.publicKey, k, pub.onion)
                                }
                                // Saved to disk only — the decrypted vault is NOT put
                                // back into RAM, since we're locked now.
                                late.save(updated)
                                myCmId(updated)?.let { id -> MessageService.sendAddressUpdate(id) }
                            }
                        }
                        MessageService.closeVault(); manager.lock(); data = null; nav = Nav.Lock
                        // …and leave the app (Home screen). The engine keeps running
                        // briefly so the alerts + new address can go out silently;
                        // reopening needs the PIN.
                        findActivity(context)?.moveTaskToBack(true)
                    } else nav = Nav.Chat(it.name, it.cmId)
                },
                onOpenSettings = { nav = Nav.Settings },
                onAddFriend = { nav = Nav.Knock },
                onCancelPending = { c -> cancelPendingAdd(c.cmId, c.name) },
                onRemovePending = { c -> deleteFriend(c.cmId, c.name) },
                onOpenTool = { nav = Nav.Tool(it) },
                onMinimise = { findActivity(context)?.moveTaskToBack(true) },
                onExit = {
                    guardedExit {
                        org.cmchat.app.LifecycleController.exit(context)
                        findActivity(context)?.finish()
                    }
                },
            )
        }
        is Nav.Chat -> ChatScreen(
            contactName = n.name,
            chatCmId = n.cmId,
            onBack = { nav = Nav.Friends },
            teamHour = data?.contacts?.firstOrNull { it.cmId == n.cmId }?.teamHour,
            lastSeenSaved = data?.contacts?.firstOrNull { it.cmId == n.cmId }?.lastSeenAt,
            onDeleteFriend = { deleteFriend(n.cmId, n.name); nav = Nav.Friends },
            // MY change: stored with when I set it, and kept "unsynced" until it
            // reaches them — after a restart it is sent again (newest wins).
            onSetTeamHour = { value, at ->
                if (n.cmId != null) saveVault { cur ->
                    cur.copy(contacts = cur.contacts.map {
                        if (it.cmId == n.cmId) it.copy(teamHour = value.ifEmpty { null }, teamHourAt = at,
                            teamHourSynced = false) else it
                    })
                }
            },
            // My private label for them (empty = show their own nickname again).
            onRename = { newName ->
                var shown = newName
                if (n.cmId != null && saveVault { cur ->
                        cur.copy(contacts = cur.contacts.map {
                            if (it.cmId == n.cmId) it.copy(name = newName).also { c -> shown = shownName(c) } else it
                        })
                    }) nav = Nav.Chat(shown, n.cmId)
            },
        )
        is Nav.Tool -> org.cmchat.app.ui.screens.ToolsScreen(n.which) { nav = Nav.Friends }
        Nav.Settings -> SettingsScreen(
            onBack = { nav = Nav.Friends },
            onOpenMyServer = { nav = Nav.MyServer },
            onOpenMyId = { nav = Nav.MyId },
            onOpenBridges = { nav = Nav.Bridges },
            onWipeEverything = { showWipeConfirm = true },
            onOpenDiagnostics = { nav = Nav.Diagnostics },
            onOpenConnection = { nav = Nav.Connection },
            onIgnoreBattery = {
                org.cmchat.app.LifecycleController.expectOwnLaunch()
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            onExit = { guardedExit { org.cmchat.app.LifecycleController.exit(context) } },
            onAbout = { nav = Nav.About },
            onHelp = { nav = Nav.Help },
            onLanguage = { nav = Nav.Language },
            languageLabel = org.cmchat.app.settings.Languages.displayName(data?.settings?.language ?: "en"),
            onRamDiag = { nav = Nav.RamDiag },
            privacyPinSet = data?.settings?.privacyPin != null,
            // An all-digit Privacy PIN is typed on the number pad (any older
            // non-digit one still gets the full keyboard, so no one is locked out).
            privacyPinNumeric = data?.settings?.privacyPin?.all { it.isDigit() } != false,
            verifyPrivacyPin = { entered -> entered == data?.settings?.privacyPin },
            onCreatePrivacyPin = { newPin ->
                saveVault { cur -> cur.copy(settings = cur.settings.copy(privacyPin = newPin)) }
            },
            onRemovePrivacyPin = {
                saveVault { cur -> cur.copy(settings = cur.settings.copy(privacyPin = null)) }
            },
            coverOn = coverOn,
            onCoverMode = { on -> org.cmchat.app.tools.CoverMode.setOn(context, on); coverOn = on },
            myNickname = data?.faces?.firstOrNull()?.name ?: "",
            // My own nickname: saved, and sent to every friend until each one has it.
            onRenameMe = { newName ->
                if (saveVault { cur ->
                        cur.copy(faces = cur.faces.mapIndexed { i, f -> if (i == 0) f.copy(name = newName) else f })
                    }) MessageService.setMyName(newName)
            },
            onCerberusChange = { armed, minutes ->
                org.cmchat.app.guard.GuardController.setCerberusMinutes(minutes)
                org.cmchat.app.guard.GuardController.setCerberusArmed(armed)
                saveVault { cur -> cur.copy(settings = cur.settings.copy(cerberusArmed = armed, cerberusMinutes = minutes)) }
            },
            textSize = data?.settings?.textSize ?: 0,
            onTextSize = { step ->
                org.cmchat.app.settings.AppSettings.textSize.value = step
                saveVault { cur -> cur.copy(settings = cur.settings.copy(textSize = step)) }
            },
            onSessionWindow = { enabled ->
                saveVault { cur -> cur.copy(settings = cur.settings.copy(sessionWindow = enabled)) }
            },
            onOpenIntegrity = { nav = Nav.Integrity },
            // Argon2id runs on a background dispatcher (both PINs were just typed);
            // the result is handed back on the main thread. After a change the
            // session keeps saving under the new key (VaultManager swaps it).
            verifyVaultPin = { entered, cb ->
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { manager.verify(entered) }
                    cb(ok)
                }
            },
            onChangeVaultPin = { old, new, cb ->
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { manager.changePin(old, new) }
                    cb(ok)
                }
            },
        )
        Nav.Diagnostics -> org.cmchat.app.ui.screens.DiagnosticsScreen(onBack = { nav = Nav.Settings })
        Nav.Onboarding -> org.cmchat.app.ui.screens.OnboardingScreen(
            startPage = data?.settings?.onboardingPage ?: 0,
            // Each page is saved, so minimising (or a restart) resumes right here.
            onPage = { p -> saveVault { cur -> cur.copy(settings = cur.settings.copy(onboardingPage = p)) } },
            onDone = {
                saveVault { cur -> cur.copy(settings = cur.settings.copy(onboardingSeen = true)) }
                nav = Nav.Friends
                showReviewSettings = true
            },
        )
        Nav.Help -> org.cmchat.app.ui.screens.HelpScreen(onBack = { nav = Nav.Settings })
        Nav.Connection -> org.cmchat.app.ui.screens.ConnectionScreen(
            contacts = data?.contacts?.mapNotNull { c -> c.cmId?.let { id -> shownName(c) to id } } ?: emptyList(),
            onLinkTest = { cmId -> MessageService.linkTest(cmId) },
            onBack = { nav = Nav.Settings },
        )
        Nav.About -> org.cmchat.app.ui.screens.AboutScreen(onBack = { nav = Nav.Settings })
        Nav.RamDiag -> org.cmchat.app.ui.screens.RamDiagnosticsScreen(onBack = { nav = Nav.Settings })
        Nav.Language -> org.cmchat.app.ui.screens.LanguageScreen(
            onBack = { nav = Nav.Settings },
            onPick = { tag ->
                org.cmchat.app.settings.Languages.selected.value = tag
                saveVault { cur -> cur.copy(settings = cur.settings.copy(language = tag)) }
            },
        )
        Nav.MyId -> MyIdScreen(cmId = myCmId(data), onBack = { nav = Nav.Settings })
        Nav.Integrity -> org.cmchat.app.ui.screens.IntegrityScreen(onBack = { nav = Nav.Settings })
        Nav.Bridges -> org.cmchat.app.ui.screens.BridgesScreen(
            currentMode = data?.settings?.bridgeMode ?: "off",
            currentLines = data?.settings?.bridgeLines ?: "",
            onSave = { modeWire, lines ->
                org.cmchat.app.tor.Bridges.configure(modeWire, lines)
                saveVault { cur -> cur.copy(settings = cur.settings.copy(bridgeMode = modeWire, bridgeLines = lines)) }
                // Reset the retry cap and restart Tor so the new config applies.
                TorService.retry(context)
                nav = Nav.Settings
            },
            onToggleCover = { on ->
                org.cmchat.app.transport.CoverTraffic.setEnabled(on)
                saveVault { cur -> cur.copy(settings = cur.settings.copy(coverTraffic = on)) }
            },
            onBack = { nav = Nav.Settings },
        )
        Nav.Knock -> KnockScreen(
            myCmId = myCmId(data),
            onSend = { cmId, nickname ->
                when (MessageService.sendKnock(cmId)) {
                    MessageService.KnockResult.QUEUED -> {
                        // Add them NOW as a pending friend (shown on Friends right
                        // away); their acceptance flips it. Saved in the vault.
                        saveVault { cur ->
                            if (cur.contacts.any { it.cmId == cmId }) cur
                            else cur.copy(contacts = cur.contacts + org.cmchat.app.vault.ContactRec(
                                id = manager.crypto.randomHex(8), name = nickname, colorArgb = 0xFF35C6F2,
                                faceId = cur.faces.firstOrNull()?.id ?: "", cmId = cmId, pending = true,
                            ))
                        }
                        nav = Nav.Friends
                        null
                    }
                    MessageService.KnockResult.NOT_READY ->
                        "Not ready yet — wait until the Engine is Online once, then try again."
                    MessageService.KnockResult.INVALID -> "That doesn't look like a CMC-ID"
                    MessageService.KnockResult.SELF -> "That's your own ID 🙂"
                    MessageService.KnockResult.TOO_SOON -> "Already sent — it keeps trying in the background."
                }
            },
            onBack = { nav = Nav.Friends },
            onShowMyQr = { nav = Nav.MyId },
        )
        Nav.MyServer -> {
            val face = data?.faces?.firstOrNull()
            MyServerScreen(
                // Stop STAYS stopped (saved): only this Start brings it back.
                onStart = {
                    if (face != null) {
                        ServerController.userStart(face.name, face.onionKey, face.onionAddress) {}
                        saveVault { cur -> cur.copy(settings = cur.settings.copy(serverStopped = false)) }
                    }
                },
                onStop = {
                    ServerController.userStop()
                    saveVault { cur -> cur.copy(settings = cur.settings.copy(serverStopped = true)) }
                },
                onRestart = {
                    if (face != null) ServerController.restart(face.name, face.onionKey, face.onionAddress) {}
                },
                onRequestNewAddress = {
                    if (data != null && face != null) {
                        ServerController.requestNewAddress { pub ->
                            // Persist the new onion key/address (sealed aside if the
                            // app locked meanwhile) and tell every friend (signed
                            // address-update, re-sent until each one confirms) —
                            // whether or not the vault could be written right now.
                            keepMyOnion(face, pub)
                            MessageService.sendAddressUpdate(CmId.encode(pub.onion, face.publicKey))
                        }
                    }
                },
                onBack = { nav = Nav.Settings },
            )
        }
    }
}
