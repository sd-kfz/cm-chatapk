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
    var pin by remember { mutableStateOf<String?>(null) }

    val torStatus by TorService.status.collectAsState()
    var showWipeConfirm by remember { mutableStateOf(false) }
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

    // Team Clock changes a friend made while the app was locked (no passcode in
    // RAM to save the vault): applied right after the next unlock.
    val pendingTeamClock = remember { mutableStateMapOf<String, String>() }
    var showReviewSettings by remember { mutableStateOf(false) }

    // Surface a crash from a previous run (debug-phase aid), then delete it.
    LaunchedEffect(Unit) {
        org.cmchat.app.diag.CrashCatcher.consume(context)?.let {
            org.cmchat.app.diag.Diag.e("crash", "previous run crashed:\n$it")
        }
    }

    // Re-lock on background / Exit: wipe the vault-unlock material from RAM.
    LaunchedEffect(Unit) {
        org.cmchat.app.LifecycleController.lockRequests.collect {
            if (nav != Nav.Lock) {
                pin = null
                data = null
                nav = Nav.Lock
            }
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
                    pin = null; data = null; nav = Nav.Lock
                    scope.launch {
                        // 1) stop the engine so nothing is still writing files…
                        org.cmchat.app.transport.CoverTraffic.stop()
                        ServerController.stop()
                        org.cmchat.app.tor.BuzzListenerService.stop(context)
                        TorService.stop(context)
                        // 2) …wipe RAM…
                        ChatStore.clearAll()
                        org.cmchat.app.tools.ToolsState.clear()
                        org.cmchat.app.buzz.BuzzPolicy.clear()
                        org.cmchat.app.notify.Notifier.clearAll(context)
                        MessageService.zeroKeys()
                        org.cmchat.app.diag.Diag.clear()
                        // 3) …shred EVERY file the app owns (vault, salt, prefs,
                        //    caches, Tor's working dir, crash file)…
                        withContext(Dispatchers.IO) {
                            kotlinx.coroutines.delay(800)   // let Tor finish shutting down
                            org.cmchat.app.diag.CrashCatcher.delete(context)
                            org.cmchat.app.vault.Shredder.shredAll(context)
                        }
                        // 4) …and only then ask Android to uninstall the app. An app
                        //    can't remove itself silently; this opens the system prompt.
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
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
            contactNames = d.contacts.mapNotNull { c -> c.cmId?.let { it to c.name } }.toMap(),
        )
        // A contact rotated their onion: update their stored cmId in the vault.
        MessageService.onContactAddressUpdated = upd@{ oldCmId, newCmId ->
            val p = pin ?: return@upd
            val cur = data ?: return@upd
            val updated = cur.copy(
                contacts = cur.contacts.map { if (it.cmId == oldCmId) it.copy(cmId = newCmId) else it }
            )
            org.cmchat.app.vault.VaultIO.save(manager, p, updated)
            data = updated
        }
        // A friend set / turned off this chat's Team Clock: persist it per friend.
        MessageService.onTeamClockChanged = tc@{ cmId, value ->
            val p = pin; val cur = data
            if (p == null || cur == null) { pendingTeamClock[cmId] = value ?: ""; return@tc }
            val updated = cur.copy(contacts = cur.contacts.map { if (it.cmId == cmId) it.copy(teamHour = value) else it })
            org.cmchat.app.vault.VaultIO.save(manager, p, updated)
            data = updated
        }
        // Apply Team Clock changes that arrived while the app was locked.
        if (pendingTeamClock.isNotEmpty()) {
            val p = pin
            if (p != null) {
                val updated = d.copy(contacts = d.contacts.map { c ->
                    val v = c.cmId?.let { pendingTeamClock[it] }
                    if (v != null) c.copy(teamHour = v.ifEmpty { null }) else c
                })
                pendingTeamClock.clear()
                org.cmchat.app.vault.VaultIO.save(manager, p, updated)
                data = updated
                return@LaunchedEffect
            }
        }
        // Persist an accepted knock as a contact in the vault.
        MessageService.onContactAccepted = accepted@{ req ->
            val p = pin ?: return@accepted
            val cur = data ?: return@accepted
            if (cur.contacts.none { it.cmId == req.cmId }) {
                val contact = org.cmchat.app.vault.ContactRec(
                    id = manager.crypto.randomHex(8),
                    name = req.displayName,
                    colorArgb = 0xFF6FB8D9,
                    faceId = face.id,
                    cmId = req.cmId,
                )
                val updated = cur.copy(contacts = cur.contacts + contact)
                org.cmchat.app.vault.VaultIO.save(manager, p, updated)
                data = updated
            }
        }
    }

    // Once Tor is ONLINE, publish the active Tag's onion service. The server
    // stays up even while Invisible — messages still arrive but are held as
    // "missed" (no receipts, so Invisible is indistinguishable to a sender).
    LaunchedEffect(torStatus, data) {
        val d = data ?: return@LaunchedEffect
        val p = pin ?: return@LaunchedEffect
        val face = d.faces.firstOrNull() ?: return@LaunchedEffect
        if (torStatus is TorStatus.Online) {
            ServerController.start(face.name, face.onionKey, face.onionAddress) { pub ->
                val keyChanged = pub.newPrivateKey != null && face.onionKey == null
                val addrChanged = face.onionAddress != pub.onion
                if (keyChanged || addrChanged) {
                    val updated = d.copy(
                        faces = d.faces.map {
                            if (it.id == face.id)
                                it.copy(
                                    onionKey = pub.newPrivateKey ?: it.onionKey,
                                    onionAddress = pub.onion,
                                )
                            else it
                        }
                    )
                    org.cmchat.app.vault.VaultIO.save(manager, p, updated)
                    data = updated
                }
            }
        }
    }

    when (val n = nav) {
        Nav.Lock -> LockScreen(manager) { enteredPin, unlocked, firstRun ->
            pin = enteredPin
            data = unlocked
            // Always start INVISIBLE on login.
            org.cmchat.app.settings.AppSettings.invisibleMode.value = true
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
            // Brand-new users get the one-time onboarding wizard first.
            if (firstRun) nav = Nav.Onboarding else nav = Nav.Friends
        }
        Nav.Friends -> {
            val threads by ChatStore.threads.collectAsState()
            val real = data?.contacts?.takeIf { it.isNotEmpty() }
                ?.map {
                    val t = it.cmId?.let { id -> threads[id] }
                    Contact(it.name, Color(it.colorArgb),
                        unread = t?.unread ?: false,
                        cmId = it.cmId,
                        lastSeenMs = t?.peerLastSeen,
                        missed = t?.messages?.any { m -> m.missed } == true,
                        buzzed = t?.buzzed ?: false)
                }
                ?: emptyList()   // real empty state (no fake sample contacts)
            // Decoy chat: a fake contact; tapping it = this phone may be compromised.
            val decoyOn by org.cmchat.app.settings.AppSettings.decoyEnabled.collectAsState()
            val decoyName by org.cmchat.app.settings.AppSettings.decoyName.collectAsState()
            val decoyTop by org.cmchat.app.settings.AppSettings.decoyAtTop.collectAsState()
            val contacts = if (decoyOn) {
                val decoy = Contact(decoyName, CmGreen, unread = false, cmId = DECOY_CM_ID)
                if (decoyTop) listOf(decoy) + real else real + decoy
            } else real
            FriendsScreen(
                contacts = contacts,
                onOpenChat = {
                    if (it.cmId == DECOY_CM_ID) {
                        // DECOY TRIPPED. (1) Instantly wipe MY side from RAM and send
                        // every friend a red "Decoy chat tripped." alert — their copy
                        // is NOT deleted. (2) Rotate to a new onion address (saved with
                        // the unlock material captured here; the signed address update
                        // goes to friends). (3) Silently log out to the lock screen.
                        val p = pin; val cur = data
                        MessageService.tripDecoy()
                        if (p != null && cur != null) {
                            ServerController.requestNewAddress { pub ->
                                val me = cur.faces.firstOrNull() ?: return@requestNewAddress
                                val updated = cur.copy(faces = cur.faces.map { f ->
                                    if (f.id == me.id) f.copy(onionKey = pub.newPrivateKey ?: f.onionKey,
                                        onionAddress = pub.onion) else f
                                })
                                // Saved to disk only — the decrypted vault is NOT put
                                // back into RAM, since we're locked now.
                                org.cmchat.app.vault.VaultIO.save(manager, p, updated)
                                myCmId(updated)?.let { id -> MessageService.sendAddressUpdate(id) }
                            }
                        }
                        pin = null; data = null; nav = Nav.Lock
                    } else nav = Nav.Chat(it.name, it.cmId)
                },
                onOpenSettings = { nav = Nav.Settings },
                onAddFriend = { nav = Nav.Knock },
                onOpenTool = { nav = Nav.Tool(it) },
                onMinimise = { findActivity(context)?.moveTaskToBack(true) },
                onExit = {
                    org.cmchat.app.LifecycleController.exit(context)
                    findActivity(context)?.finish()
                },
            )
        }
        is Nav.Chat -> ChatScreen(
            contactName = n.name,
            chatCmId = n.cmId,
            onBack = { nav = Nav.Friends },
            teamHour = data?.contacts?.firstOrNull { it.cmId == n.cmId }?.teamHour,
            onSetTeamHour = { value ->
                val p = pin; val cur = data
                if (p != null && cur != null && n.cmId != null) {
                    val updated = cur.copy(
                        contacts = cur.contacts.map {
                            if (it.cmId == n.cmId) it.copy(teamHour = value.ifEmpty { null }) else it
                        }
                    )
                    org.cmchat.app.vault.VaultIO.save(manager, p, updated)
                    data = updated
                }
            },
            onRename = { newName ->
                val p = pin; val cur = data
                if (p != null && cur != null && n.cmId != null) {
                    val updated = cur.copy(
                        contacts = cur.contacts.map {
                            if (it.cmId == n.cmId) it.copy(name = newName) else it
                        }
                    )
                    org.cmchat.app.vault.VaultIO.save(manager, p, updated)
                    data = updated
                    nav = Nav.Chat(newName, n.cmId)
                }
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
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            onExit = { org.cmchat.app.LifecycleController.exit(context) },
            onAbout = { nav = Nav.About },
            onHelp = { nav = Nav.Help },
            onLanguage = { nav = Nav.Language },
            onRamDiag = { nav = Nav.RamDiag },
            privacyPinSet = data?.settings?.privacyPin != null,
            verifyPrivacyPin = { entered -> entered == data?.settings?.privacyPin },
            onCreatePrivacyPin = { newPin ->
                val p = pin; val cur = data
                if (p != null && cur != null) {
                    val updated = cur.copy(settings = cur.settings.copy(privacyPin = newPin))
                    org.cmchat.app.vault.VaultIO.save(manager, p, updated)
                    data = updated
                }
            },
            onCerberusChange = { armed, minutes ->
                org.cmchat.app.guard.GuardController.setCerberusMinutes(minutes)
                org.cmchat.app.guard.GuardController.setCerberusArmed(armed)
                val p = pin; val cur = data
                if (p != null && cur != null) {
                    val updated = cur.copy(settings = cur.settings.copy(cerberusArmed = armed, cerberusMinutes = minutes))
                    org.cmchat.app.vault.VaultIO.save(manager, p, updated)
                    data = updated
                }
            },
            textSize = data?.settings?.textSize ?: 0,
            onTextSize = { step ->
                org.cmchat.app.settings.AppSettings.textSize.value = step
                val p = pin; val cur = data
                if (p != null && cur != null) {
                    val updated = cur.copy(settings = cur.settings.copy(textSize = step))
                    org.cmchat.app.vault.VaultIO.save(manager, p, updated)
                    data = updated
                }
            },
            onSessionWindow = { enabled ->
                val p = pin; val cur = data
                if (p != null && cur != null) {
                    val updated = cur.copy(settings = cur.settings.copy(sessionWindow = enabled))
                    org.cmchat.app.vault.VaultIO.save(manager, p, updated)
                    data = updated
                }
            },
            onOpenIntegrity = { nav = Nav.Integrity },
            // Argon2id runs on a background dispatcher; the result is handed back on
            // the main thread. On a successful change we swap the in-RAM pin so the
            // session keeps saving under the new passcode.
            verifyVaultPin = { entered, cb ->
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { manager.verify(entered) }
                    cb(ok)
                }
            },
            onChangeVaultPin = { old, new, cb ->
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { manager.changePin(old, new) }
                    if (ok) pin = new
                    cb(ok)
                }
            },
        )
        Nav.Diagnostics -> org.cmchat.app.ui.screens.DiagnosticsScreen(onBack = { nav = Nav.Settings })
        Nav.Onboarding -> org.cmchat.app.ui.screens.OnboardingScreen(onDone = {
            val p = pin; val cur = data
            if (p != null && cur != null) {
                val updated = cur.copy(settings = cur.settings.copy(onboardingSeen = true))
                org.cmchat.app.vault.VaultIO.save(manager, p, updated)
                data = updated
            }
            nav = Nav.Friends
            showReviewSettings = true
        })
        Nav.Help -> org.cmchat.app.ui.screens.HelpScreen(onBack = { nav = Nav.Settings })
        Nav.Connection -> org.cmchat.app.ui.screens.ConnectionScreen(
            contacts = data?.contacts?.mapNotNull { c -> c.cmId?.let { id -> c.name to id } } ?: emptyList(),
            onLinkTest = { cmId -> MessageService.linkTest(cmId) },
            onBack = { nav = Nav.Settings },
        )
        Nav.About -> org.cmchat.app.ui.screens.AboutScreen(onBack = { nav = Nav.Settings })
        Nav.RamDiag -> org.cmchat.app.ui.screens.RamDiagnosticsScreen(onBack = { nav = Nav.Settings })
        Nav.Language -> org.cmchat.app.ui.screens.LanguageScreen(
            onBack = { nav = Nav.Settings },
            onPick = { tag ->
                org.cmchat.app.settings.Languages.selected.value = tag
                val p = pin; val cur = data
                if (p != null && cur != null) {
                    val updated = cur.copy(settings = cur.settings.copy(language = tag))
                    org.cmchat.app.vault.VaultIO.save(manager, p, updated)
                    data = updated
                }
            },
        )
        Nav.MyId -> MyIdScreen(cmId = myCmId(data), onBack = { nav = Nav.Settings })
        Nav.Integrity -> org.cmchat.app.ui.screens.IntegrityScreen(onBack = { nav = Nav.Settings })
        Nav.Bridges -> org.cmchat.app.ui.screens.BridgesScreen(
            currentMode = data?.settings?.bridgeMode ?: "off",
            currentLines = data?.settings?.bridgeLines ?: "",
            onSave = { modeWire, lines ->
                org.cmchat.app.tor.Bridges.configure(modeWire, lines)
                val p = pin; val cur = data
                if (p != null && cur != null) {
                    val updated = cur.copy(
                        settings = cur.settings.copy(bridgeMode = modeWire, bridgeLines = lines)
                    )
                    org.cmchat.app.vault.VaultIO.save(manager, p, updated)
                    data = updated
                }
                // Reset the retry cap and restart Tor so the new config applies.
                TorService.retry(context)
                nav = Nav.Settings
            },
            onToggleCover = { on ->
                org.cmchat.app.transport.CoverTraffic.setEnabled(on)
                val p = pin; val cur = data
                if (p != null && cur != null) {
                    val updated = cur.copy(settings = cur.settings.copy(coverTraffic = on))
                    org.cmchat.app.vault.VaultIO.save(manager, p, updated)
                    data = updated
                }
            },
            onBack = { nav = Nav.Settings },
        )
        Nav.Knock -> KnockScreen(
            myCmId = myCmId(data),
            onSend = { cmId, _ ->
                MessageService.sendKnock(cmId) {}
                nav = Nav.Friends
            },
            onBack = { nav = Nav.Friends },
            onShowMyQr = { nav = Nav.MyId },
        )
        Nav.MyServer -> {
            val face = data?.faces?.firstOrNull()
            MyServerScreen(
                onStart = {
                    if (face != null) ServerController.start(face.name, face.onionKey, face.onionAddress) {}
                },
                onStop = { ServerController.stop() },
                onRestart = {
                    if (face != null) ServerController.restart(face.name, face.onionKey, face.onionAddress) {}
                },
                onRequestNewAddress = {
                    val p = pin; val cur = data
                    if (p != null && cur != null && face != null) {
                        ServerController.requestNewAddress { pub ->
                            // Persist the new onion key/address, recompute my
                            // CMC-ID, and tell contacts (signed address-update).
                            val updated = cur.copy(
                                faces = cur.faces.map {
                                    if (it.id == face.id)
                                        it.copy(onionKey = pub.newPrivateKey ?: it.onionKey,
                                                onionAddress = pub.onion)
                                    else it
                                }
                            )
                            org.cmchat.app.vault.VaultIO.save(manager, p, updated)
                            data = updated
                            myCmId(updated)?.let { MessageService.sendAddressUpdate(it) }
                        }
                    }
                },
                onBack = { nav = Nav.Settings },
            )
        }
    }
}
