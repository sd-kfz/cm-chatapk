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
import org.cmchat.app.R
import org.cmchat.app.i18n.Tr


private const val DECOY_CM_ID = "__decoy__"


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


private fun shownName(c: org.cmchat.app.vault.ContactRec): String =
    c.name.ifBlank { if (c.pending) Tr.s(R.string.new_friend) else c.theirName?.takeIf { it.isNotBlank() } ?: Tr.s(R.string.new_friend) }

private fun myCmId(data: VaultData?): String? {
    val face = data?.faces?.firstOrNull() ?: return null
    val onion = face.onionAddress ?: return null
    return CmId.encode(onion, face.publicKey)
}


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

    var coverOn by remember { mutableStateOf(org.cmchat.app.tools.CoverMode.isOn(context)) }
    var coverPassed by remember { mutableStateOf(false) }

    val shredEpoch by org.cmchat.app.vault.Shredder.epoch.collectAsState()
    var showWipeConfirm by remember { mutableStateOf(false) }


    var confirmExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun guardedExit(doExit: () -> Unit) {
        if (org.cmchat.app.tools.ToolsState.hasContent()) confirmExit = doExit else doExit()
    }




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



    fun flushEdits() {
        if (org.cmchat.app.vault.PendingVaultEdits.isEmpty()) return
        saveVault {
            org.cmchat.app.vault.PendingVaultEdits.drainInto(it, MessageService::currentId, System.currentTimeMillis())
        }
    }

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

                if (runCatching { manager.flush() }.isSuccess) org.cmchat.app.vault.OwnOnionStash.clear(dir)
            }
            org.cmchat.app.diag.ConnDiag.sys("My server: the address made while locked is now saved")
        }
        return updated
    }


    LaunchedEffect(Unit) {
        org.cmchat.app.diag.CrashCatcher.consume(context)?.let {
            org.cmchat.app.diag.Diag.e("crash", "previous run crashed:\n$it")
        }
    }



    LaunchedEffect(Unit) {

        if (data == null) { MessageService.closeVault(); manager.lock() }
        org.cmchat.app.LifecycleController.lockRequests.collect {
            MessageService.closeVault()
            manager.lock()
            if (nav != Nav.Lock) {
                data = null
                nav = Nav.Lock
            }
        }
    }




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
            title = { Text(Tr.s(R.string.nav_welcome)) },
            text = { Text(Tr.s(R.string.nav_review_settings)) },
            confirmButton = {
                TextButton(onClick = { showReviewSettings = false; nav = Nav.Settings }) {
                    Text(Tr.s(R.string.nav_take_me_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showReviewSettings = false }) { Text(Tr.s(R.string.nav_later)) }
            },
        )
    }

    confirmExit?.let { doExit ->
        AlertDialog(
            onDismissRequest = { confirmExit = null },
            title = { Text(Tr.s(R.string.nav_exit_notes_q)) },
            text = { Text(Tr.s(R.string.nav_exit_notes_text)) },
            confirmButton = { TextButton(onClick = { confirmExit = null; doExit() }) { Text(Tr.s(R.string.nav_exit)) } },
            dismissButton = { TextButton(onClick = { confirmExit = null }) { Text(Tr.s(R.string.cancel)) } },
        )
    }

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text(Tr.s(R.string.nav_wipe_q)) },
            text = { Text(Tr.s(R.string.nav_wipe_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showWipeConfirm = false

                    MessageService.closeVault(); manager.lock(); data = null; nav = Nav.Lock
                    scope.launch {
                        try {

                            runCatching { org.cmchat.app.transport.CoverTraffic.stop() }
                            runCatching { ServerController.stop() }
                            runCatching { TorService.stop(context) }

                            runCatching { ChatStore.clearAll() }
                            runCatching { org.cmchat.app.tools.ToolsState.clear() }
                            runCatching { org.cmchat.app.buzz.BuzzPolicy.clear() }
                            runCatching { org.cmchat.app.notify.Notifier.clearAll(context) }
                            runCatching { MessageService.zeroKeys() }
                            runCatching { org.cmchat.app.diag.Diag.clear() }


                            withContext(Dispatchers.IO) {
                                kotlinx.coroutines.delay(800)
                                runCatching { org.cmchat.app.diag.CrashCatcher.delete(context) }
                                runCatching { org.cmchat.app.vault.Shredder.shredAll(context) }
                            }
                        } finally {



                            requestUninstall(context)
                        }
                    }
                }) { Text(Tr.s(R.string.chat_wipe)) }
            },
            dismissButton = { TextButton(onClick = { showWipeConfirm = false }) { Text(Tr.s(R.string.cancel)) } },
        )
    }


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

        MessageService.onFriendConfirmed = { cmId -> edits.confirmed(cmId); flushEdits() }

        MessageService.onContactAddressUpdated = { oldCmId, newCmId ->
            edits.relinked(oldCmId, newCmId); flushEdits()

            (nav as? Nav.Chat)?.takeIf { it.cmId == oldCmId }?.let { nav = it.copy(cmId = newCmId) }
        }

        MessageService.onTeamClockChanged = { cmId, value, at -> edits.teamClockSet(cmId, value, at); flushEdits() }

        MessageService.onTeamClockSynced = { cmId, at -> edits.teamClockSynced(cmId, at); flushEdits() }

        MessageService.onFriendName = { cmId, name -> edits.theirName(cmId, name); flushEdits() }
        MessageService.onNameConfirmed = { cmId, name -> edits.nameConfirmed(cmId, name); flushEdits() }

        MessageService.onFriendTerminated = { cmId ->
            edits.removedByFriend(cmId); flushEdits()
            (nav as? Nav.Chat)?.takeIf { it.cmId == cmId }?.let { nav = Nav.Friends }
        }

        MessageService.onTerminationDelivered = { cmId -> edits.terminationDelivered(cmId); flushEdits() }

        MessageService.onPeerSeen = { cmId, at -> edits.seen(cmId, at); flushEdits() }

        MessageService.onAddressConfirmed = { cmId, mine -> edits.addressConfirmed(cmId, mine); flushEdits() }

        MessageService.flushVault = { manager.flush() }

        flushEdits()

        MessageService.onContactAccepted = { req ->
            saveVault { cur ->
                if (cur.contacts.any { it.cmId == req.cmId }) {

                    cur.copy(contacts = cur.contacts.map { if (it.cmId == req.cmId) it.copy(pending = false) else it })
                } else {

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


        MessageService.openVault()
    }






    LaunchedEffect(data) {
        val d = data ?: return@LaunchedEffect
        val face = d.faces.firstOrNull() ?: return@LaunchedEffect
        TorService.status.collect { st ->

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


        Nav.Lock -> if (coverOn && !coverPassed) {
            org.cmchat.app.ui.screens.CalculatorCover(onOpen = { coverPassed = true })
        } else key(shredEpoch) { LockScreen(manager) { opened ->
            coverPassed = false


            val unlocked = withStashedOnion(opened)


            org.cmchat.app.settings.AppSettings.restoreFrom(unlocked.settings)
            ServerController.stoppedByUser.value = unlocked.settings.serverStopped
            data = unlocked




            org.cmchat.app.settings.AppSettings.sessionWindowEnabled.value = unlocked.settings.sessionWindow
            org.cmchat.app.settings.Languages.select(unlocked.settings.language)
            org.cmchat.app.settings.AppSettings.lastUnlockMs = System.currentTimeMillis()

            org.cmchat.app.tor.Bridges.configure(unlocked.settings.bridgeMode, unlocked.settings.bridgeLines)
            org.cmchat.app.transport.CoverTraffic.setEnabled(unlocked.settings.coverTraffic)
            org.cmchat.app.settings.AppSettings.textSize.value = unlocked.settings.textSize
            TorService.start(context)
            org.cmchat.app.guard.GuardController.init(context)
            org.cmchat.app.guard.GuardController.setCerberusMinutes(unlocked.settings.cerberusMinutes)
            org.cmchat.app.guard.GuardController.setCerberusArmed(
                unlocked.settings.cerberusArmed && !org.cmchat.app.settings.AppSettings.stayReachable.value)

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

                        lastSeenMs = listOfNotNull(t?.peerLastSeen, it.lastSeenAt).maxOrNull(),
                        missed = t?.messages?.any { m -> m.missed || m.closedMiss } == true,
                        buzzed = t?.buzzed ?: false,
                        pending = it.pending)
                }
                ?: emptyList()

            val decoyOn by org.cmchat.app.settings.AppSettings.decoyEnabled.collectAsState()
            val decoyName by org.cmchat.app.settings.AppSettings.decoyName.collectAsState()
            val decoyTop by org.cmchat.app.settings.AppSettings.decoyAtTop.collectAsState()
            val contacts = if (decoyOn) {

                val decoy = Contact(org.cmchat.app.i18n.Tr.decoyName(decoyName), Color(0xFF6FB8D9), unread = false, cmId = DECOY_CM_ID)
                if (decoyTop) listOf(decoy) + real else real + decoy
            } else real
            FriendsScreen(
                contacts = contacts,
                onOpenChat = {
                    if (it.cmId == DECOY_CM_ID) {






                        val cur = data


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


                                pub.newPrivateKey?.let { k ->
                                    org.cmchat.app.vault.OwnOnionStash.put(context.filesDir, manager.crypto,
                                        me.publicKey, k, pub.onion)
                                }


                                late.save(updated)
                                myCmId(updated)?.let { id -> MessageService.sendAddressUpdate(id) }
                            }
                        }
                        MessageService.closeVault(); manager.lock(); data = null; nav = Nav.Lock



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


            onSetTeamHour = { value, at ->
                if (n.cmId != null) saveVault { cur ->
                    cur.copy(contacts = cur.contacts.map {
                        if (it.cmId == n.cmId) it.copy(teamHour = value.ifEmpty { null }, teamHourAt = at,
                            teamHourSynced = false) else it
                    })
                }
            },

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
            languageLabel = org.cmchat.app.settings.Languages.displayName(data?.settings?.language ?: ""),
            onRamDiag = { nav = Nav.RamDiag },
            privacyPinSet = data?.settings?.privacyPin != null,


            privacyPinNumeric = data?.settings?.privacyPin?.all { it.isDigit() } != false,
            verifyPrivacyPin = { entered -> entered == data?.settings?.privacyPin },
            onCreatePrivacyPin = { newPin ->
                saveVault { cur -> cur.copy(settings = cur.settings.copy(privacyPin = newPin)) }
            },
            onRemovePrivacyPin = {
                saveVault { cur -> cur.copy(settings = cur.settings.copy(privacyPin = null)) }
            },
            coverOn = coverOn,
            onCoverMode = { on ->
                org.cmchat.app.tools.CoverMode.setOn(context, on)
                org.cmchat.app.tools.CoverDisguise.apply(context, on)
                coverOn = on
            },
            myNickname = data?.faces?.firstOrNull()?.name ?: "",

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
                org.cmchat.app.settings.Languages.select(tag)
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
                        Tr.s(R.string.nav_not_ready)
                    MessageService.KnockResult.INVALID -> Tr.s(R.string.add_not_cmc_id)
                    MessageService.KnockResult.SELF -> Tr.s(R.string.add_own_id)
                    MessageService.KnockResult.TOO_SOON -> Tr.s(R.string.nav_already_sent)
                }
            },
            onBack = { nav = Nav.Friends },
            onShowMyQr = { nav = Nav.MyId },
        )
        Nav.MyServer -> {
            val face = data?.faces?.firstOrNull()
            MyServerScreen(

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
