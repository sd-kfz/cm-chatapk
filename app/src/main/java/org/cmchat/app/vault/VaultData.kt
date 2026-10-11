package org.cmchat.app.vault

import kotlinx.serialization.Serializable


@Serializable
data class Face(
    val id: String,
    val name: String,
    val publicKey: String,
    val secretKey: String,
    val onionKey: String? = null,
    val onionAddress: String? = null,
)


@Serializable
data class ContactRec(
    val id: String,
    val name: String,
    val colorArgb: Long,
    val faceId: String,
    val cmId: String? = null,

    val teamHour: String? = null,

    val pending: Boolean = false,

    val lastSeenAt: Long? = null,

    val addrConfirmed: String? = null,

    val theirName: String? = null,

    val nameConfirmed: String? = null,

    val teamHourAt: Long = 0,

    val teamHourSynced: Boolean = true,
)


@Serializable
data class PendingTermination(val cmId: String, val sinceMs: Long)

@Serializable
data class VaultSettings(
    val cerberusMinutes: Int = 90,

    val cerberusArmed: Boolean = true,

    val defaultSelfTimer: String = "off",

    val textSize: Int = 0,

    val privacyPin: String? = null,

    val sessionWindow: Boolean = false,

    val language: String = "",

    val bridgeMode: String = "off",

    val bridgeLines: String = "",

    val coverTraffic: Boolean = false,

    val onboardingSeen: Boolean = false,

    val onboardingPage: Int = 0,



    val buzzFrequency: String = "H1",

    val buzzWhenClosed: Boolean = true,

    val decoyEnabled: Boolean = false,
    val decoyName: String = "Notes to self",
    val decoyAtTop: Boolean = true,

    val toolCalc: Boolean = false,
    val toolNotes: Boolean = false,
    val toolFlash: Boolean = false,


    val serverStopped: Boolean = false,
)


@Serializable
data class VaultData(
    val faces: List<Face> = emptyList(),
    val contacts: List<ContactRec> = emptyList(),
    val settings: VaultSettings = VaultSettings(),

    val terminations: List<PendingTermination> = emptyList(),
)
