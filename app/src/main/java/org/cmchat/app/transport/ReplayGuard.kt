package org.cmchat.app.transport


class ReplayGuard(private val window: Int = 64, private val maxKeys: Int = 512) {

    private class W {
        var highest = -1L
        var mask = 0L
    }

    private val map = HashMap<String, W>()


    @Synchronized
    fun check(key: String, seq: Long): Boolean {
        if (seq < 0) return false
        if (map.size > maxKeys && !map.containsKey(key)) map.clear()
        val w = map.getOrPut(key) { W() }
        return when {
            w.highest < 0L -> { w.highest = seq; w.mask = 1L; true }
            seq > w.highest -> {
                val shift = seq - w.highest
                w.mask = if (shift >= 64L) 1L else ((w.mask shl shift.toInt()) or 1L)
                w.highest = seq
                true
            }
            else -> {
                val diff = w.highest - seq
                if (diff >= window) false
                else {
                    val bit = 1L shl diff.toInt()
                    if (w.mask and bit != 0L) false
                    else { w.mask = w.mask or bit; true }
                }
            }
        }
    }

    @Synchronized
    fun clear() = map.clear()
}
