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

/** A contact in the Circle, belonging to one Face. */
@Serializable
data class ContactRec(
    val id: String,
    val name: String,
    val colorArgb: Long,
    val faceId: String,
    val cmId: String? = null,
    /** Per-contact Team clock, persisted (encrypted) so it survives logout. */
    val teamHour: String? = null,
)

@Serializable
data class VaultSettings(
    val cerberusMinutes: Int = 90,
    val defaultSelfTimer: String = "30s",
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
    /** First-run onboarding wizard has been shown. */
    val onboardingSeen: Boolean = false,
)

/** Everything persisted in the encrypted vault. Messages are NOT here. */
@Serializable
data class VaultData(
    val faces: List<Face> = emptyList(),
    val contacts: List<ContactRec> = emptyList(),
    val settings: VaultSettings = VaultSettings(),
)
