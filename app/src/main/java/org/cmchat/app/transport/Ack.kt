package org.cmchat.app.transport


enum class Ack(val code: Int) {

    OK(0),

    RETRY(1),

    REJECTED(2),

    HAVE(3);

    companion object {
        fun of(code: Int): Ack? = entries.firstOrNull { it.code == code }
    }
}
