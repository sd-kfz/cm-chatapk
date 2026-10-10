package org.cmchat.app.vault

import kotlinx.serialization.Serializable

/**
 * An identity ("Face"). Two SEPARATE keys are kept:
 *  - publicKey/secretKey: the X25519 messaging identity (crypto_box).
 *  - onionKey/onionAddress: the v3 onion-service endpoint key (Tor
 *    ADD_ONION "ED25519-V3:..."). Kept distinct from the messaging identity
 *    so a future Home Node can move the endpoint without changing identity.
 * The onion key lives only in the vault; it is handed to Tor at runtime and
 * never written to Tor's data dir except while running.
 */
@Serializable
data class Face(
    val id: String,
    val name: String,
    val publicKey: String,
    val secretKey: String,
    val onionKey: String? = null,
    val onionAddress: String? = null,
)

/** A friend (contact). `faceId` is a legacy field: the app has ONE identity. */
@Serializable
data class ContactRec(
    val id: String,
    val name: String,
    val colorArgb: Long,
    val faceId: String,
    val cmId: String? = null,
    /** Per-contact Team clock, persisted (encrypted) so it survives logout. */
    val teamHour: String? = null,
    /** I added (knocked) them; they haven't accepted yet. Cleared by their
     * acceptance or any authenticated frame from them. */
    val pending: Boolean = false,
    /** When they were last active, rounded DOWN to the hour, kept at most 24 h —
     * so "last seen recently" survives restarts and chat erases. Never exact. */
    val lastSeenAt: Long? = null,
    /** The address (CMC-ID) of MINE their phone confirmed. While it isn't my
     * current one, my address keeps being re-sent to them (across restarts). */
    val addrConfirmed: String? = null,
    /** Their OWN nickname (from their acceptance / their change). [name] — my
     * private label for them — wins when it's set. */
    val theirName: String? = null,
    /** My nickname their phone confirmed (re-sent until it's my current one). */
    val nameConfirmed: String? = null,
    /** When [teamHour] was set (whoever set it): the newest setting wins. */
    val teamHourAt: Long = 0,
    /** MY latest Team Clock change has reached them (if not, it's re-sent). */
    val teamHourSynced: Boolean = true,
)

/** A TERMINATE I sent that hasn't reached the ex-friend's phone yet. */
@Serializable
data class PendingTermination(val cmId: String, val sinceMs: Long)

@Serializable
data class VaultSettings(
    val cerberusMinutes: Int = 90,
    /** Cerberus idle auto-wipe armed (default ON, as before). */
    val cerberusArmed: Boolean = true,
    /** General timer for all messages (a [org.cmchat.app.chat.SelfTimer] label). Was
     * never read before, so its old "30s" default was never in effect: Off. */
    val defaultSelfTimer: String = "off",
    /** App-wide text size step, -6..+6 (0 = default). Applied to every screen. */
    val textSize: Int = 0,
    /** Separate 4-8 digit PIN gating the Privacy & Safety section (null = unset). */
    val privacyPin: String? = null,
    /** If true, a successful unlock stays valid for 6h (no re-ask on return). */
    val sessionWindow: Boolean = false,
    /** Selected UI language (BCP-47 tag); English until a locale is translated. */
    val language: String = "en",
    /** Bridge mode: "off" | "obfs4" | "snowflake" (hide that Tor is in use). */
    val bridgeMode: String = "off",
    /** User-pasted bridge lines, one per line (preferred over the built-ins). */
    val bridgeLines: String = "",
    /** Cover traffic (decoy frames) to hide when you're really messaging. */
    val coverTraffic: Boolean = false,
    /** First-run onboarding wizard has been finished. Until then it re-opens at unlock. */
    val onboardingSeen: Boolean = false,
    /** The onboarding page reached, so minimising (or a restart) resumes there. */
    val onboardingPage: Int = 0,

    // ---- settings that used to live only in RAM (they reset on every restart) ----
    /** "Accept Buzz" frequency ([org.cmchat.app.buzz.BuzzFrequency] name). */
    val buzzFrequency: String = "H1",
    /** Retired: the Buzz listener is always on after close now. Kept so older
     * vaults still read; never used. */
    val buzzWhenClosed: Boolean = true,
    /** Decoy chat on/off, its name, and whether it sits at the top. */
    val decoyEnabled: Boolean = false,
    val decoyName: String = "Notes to self",
    val decoyAtTop: Boolean = true,
    /** Which tools sit on the main page. */
    val toolCalc: Boolean = false,
    val toolNotes: Boolean = false,
    val toolFlash: Boolean = false,

    /** I stopped My Server: it stays down (no automatic re-publish) until I tap Start. */
    val serverStopped: Boolean = false,
)

/** Everything persisted in the encrypted vault. Messages are NOT here. */
@Serializable
data class VaultData(
    val faces: List<Face> = emptyList(),
    val contacts: List<ContactRec> = emptyList(),
    val settings: VaultSettings = VaultSettings(),
    /** Terminations still to deliver (retried on every start, given up after 7 days). */
    val terminations: List<PendingTermination> = emptyList(),
)
