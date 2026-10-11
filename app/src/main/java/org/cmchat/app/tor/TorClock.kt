package org.cmchat.app.tor

import org.cmchat.app.diag.ConnDiag


object TorClock {


    fun skewMinutes(): Long? = runCatching {
        val raw = TorService.controlConnection()?.getInfo("consensus/valid-after") ?: return null
        parseUtc(raw)?.let { (System.currentTimeMillis() - it) / 60_000L }
    }.getOrNull()

    internal fun parseUtc(raw: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
            isLenient = false
        }.parse(raw.trim().removeSurrounding("\""))?.time
    }.getOrNull()

    internal fun verdict(minutes: Long?): String = when {
        minutes == null -> "Clock check: not available"
        minutes < -5 -> "Clock check: this phone looks ${-minutes} min BEHIND the Tor network — turn on automatic date & time"
        minutes > 180 -> "Clock check: this phone looks ${minutes} min AHEAD of the Tor network — turn on automatic date & time"
        else -> "Clock check: OK"
    }

    fun log() = ConnDiag.sys(verdict(skewMinutes()))
}
