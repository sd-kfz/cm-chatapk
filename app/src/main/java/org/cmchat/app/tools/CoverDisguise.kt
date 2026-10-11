package org.cmchat.app.tools

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Launcher-label disguise. Two <activity-alias> launchers point at the same
 * MainActivity with the same flower icon: LauncherNormal is labelled "CM-Chat",
 * LauncherCover is labelled the Cyrillic "Сalculator". Exactly one is enabled;
 * this flips them to follow the calculator cover toggle. Only the text label
 * changes — the icon is always the flower.
 */
object CoverDisguise {
    private const val NORMAL = "org.cmchat.app.LauncherNormal"
    private const val COVER = "org.cmchat.app.LauncherCover"

    /** Make the enabled launcher match [coverOn] (cover => the "Сalculator" label). */
    fun apply(ctx: Context, coverOn: Boolean) {
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        val want = ComponentName(pkg, if (coverOn) COVER else NORMAL)
        val other = ComponentName(pkg, if (coverOn) NORMAL else COVER)
        // Already in the right state? Do nothing (avoids a launcher refresh every start).
        if (isEnabled(pm, want, enabledByDefault = !coverOn) &&
            !isEnabled(pm, other, enabledByDefault = coverOn)) return
        runCatching {
            // Enable the wanted alias FIRST, so there is never zero launcher entry.
            pm.setComponentEnabledSetting(want,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(other,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    /** Sync the launcher label to the stored cover setting (safe to call on start). */
    fun sync(ctx: Context) = apply(ctx, CoverMode.isOn(ctx))

    private fun isEnabled(pm: PackageManager, c: ComponentName, enabledByDefault: Boolean): Boolean =
        when (pm.getComponentEnabledSetting(c)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> enabledByDefault   // DEFAULT = whatever android:enabled says in the manifest
        }
}
