package org.cmchat.app.tools

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager


object CoverDisguise {
    private const val NORMAL = "org.cmchat.app.LauncherNormal"
    private const val COVER = "org.cmchat.app.LauncherCover"


    fun apply(ctx: Context, coverOn: Boolean) {
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        val want = ComponentName(pkg, if (coverOn) COVER else NORMAL)
        val other = ComponentName(pkg, if (coverOn) NORMAL else COVER)

        if (isEnabled(pm, want, enabledByDefault = !coverOn) &&
            !isEnabled(pm, other, enabledByDefault = coverOn)) return
        runCatching {

            pm.setComponentEnabledSetting(want,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(other,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }


    fun sync(ctx: Context) = apply(ctx, CoverMode.isOn(ctx))

    private fun isEnabled(pm: PackageManager, c: ComponentName, enabledByDefault: Boolean): Boolean =
        when (pm.getComponentEnabledSetting(c)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> enabledByDefault
        }
}
