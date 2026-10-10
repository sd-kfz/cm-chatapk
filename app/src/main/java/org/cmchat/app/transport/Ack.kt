package org.cmchat.app.transport

/**
 * The recipient's receipt for one frame (wire byte, inside an authenticated
 * reply — see [SecureChannel]). A sender counts a frame DELIVERED only on [OK]:
 * the recipient has stored it, or durably held it. No receipt, or [RETRY], means
 * "keep it and try again later" — so nothing is ever silently dropped.
 */
enum class Ack(val code: Int) {
    /** Stored (or held safely on disk): delivered. */
    OK(0),
    /** Not stored right now (e.g. locked and this can't be held, or full): retry later. */
    RETRY(1),
    /** Refused for good (e.g. a file over the size cap): don't retry. */
    REJECTED(2),
    /** (File offers only) Already stored — its earlier final receipt got lost: delivered. */
    HAVE(3);

    companion object {
        fun of(code: Int): Ack? = entries.firstOrNull { it.code == code }
    }
}
